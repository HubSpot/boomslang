use askama::Template;
use heck::{ToLowerCamelCase, ToSnakeCase, ToUpperCamelCase};
use serde::{Deserialize, Serialize};
use std::env;
use std::error::Error;
use std::fs;
use std::path::{Path, PathBuf};

const ABI_VERSION: u32 = 1;

fn default_abi_version() -> u32 {
    ABI_VERSION
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Manifest {
    #[serde(default = "default_abi_version")]
    pub abi_version: u32,
    pub extension: Extension,
    #[serde(default)]
    pub functions: Vec<Function>,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Extension {
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wasm_module: Option<String>,
    #[serde(default)]
    pub prewarm: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Function {
    pub name: String,
    #[serde(default)]
    pub params: Vec<Param>,
    #[serde(default)]
    pub returns: Option<Type>,
    #[serde(default)]
    pub r#async: bool,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Param {
    pub name: String,
    #[serde(rename = "type")]
    pub ty: Type,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Type {
    String,
    Int,
    Float,
    Bytes,
}

#[derive(Clone, Debug)]
pub struct ExtensionSpec {
    manifest: Manifest,
}

impl ExtensionSpec {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            manifest: Manifest {
                abi_version: ABI_VERSION,
                extension: Extension {
                    name: name.into(),
                    wasm_module: None,
                    prewarm: Vec::new(),
                },
                functions: Vec::new(),
            },
        }
    }

    pub fn wasm_module(mut self, wasm_module: impl Into<String>) -> Self {
        self.manifest.extension.wasm_module = Some(wasm_module.into());
        self
    }

    pub fn prewarm<I, S>(mut self, modules: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.manifest.extension.prewarm = modules.into_iter().map(Into::into).collect();
        self
    }

    pub fn function<F>(mut self, name: impl Into<String>, configure: F) -> Self
    where
        F: FnOnce(FunctionSpec) -> FunctionSpec,
    {
        self.manifest
            .functions
            .push(configure(FunctionSpec::new(name)).build());
        self
    }

    pub fn manifest(&self) -> &Manifest {
        &self.manifest
    }

    pub fn into_manifest(self) -> Manifest {
        self.manifest
    }
}

#[derive(Clone, Debug)]
pub struct FunctionSpec {
    function: Function,
}

impl FunctionSpec {
    fn new(name: impl Into<String>) -> Self {
        Self {
            function: Function {
                name: name.into(),
                params: Vec::new(),
                returns: None,
                r#async: false,
            },
        }
    }

    pub fn param(mut self, name: impl Into<String>, ty: Type) -> Self {
        self.function.params.push(Param {
            name: name.into(),
            ty,
        });
        self
    }

    pub fn returns(mut self, ty: Type) -> Self {
        self.function.returns = Some(ty);
        self
    }

    pub fn r#async(mut self) -> Self {
        self.function.r#async = true;
        self
    }

    fn build(self) -> Function {
        self.function
    }
}

pub struct Build {
    manifest: Manifest,
    rust_guest: bool,
    abi_json: bool,
    abi_json_to: Option<PathBuf>,
    java_host: Option<(PathBuf, String)>,
    rust_host: Option<PathBuf>,
}

impl Build {
    pub fn new(extension: ExtensionSpec) -> Self {
        Self {
            manifest: extension.into_manifest(),
            rust_guest: false,
            abi_json: false,
            abi_json_to: None,
            java_host: None,
            rust_host: None,
        }
    }

    pub fn emit(self) -> Self {
        self.emit_rust_guest().emit_abi_json()
    }

    pub fn emit_defaults(self) -> Self {
        self.emit()
    }

    pub fn emit_rust_guest(mut self) -> Self {
        self.rust_guest = true;
        self
    }

    pub fn emit_abi_json(mut self) -> Self {
        self.abi_json = true;
        self
    }

    pub fn emit_abi_json_to(mut self, path: impl Into<PathBuf>) -> Self {
        self.abi_json_to = Some(path.into());
        self
    }

    pub fn emit_java_host(
        mut self,
        out_dir: impl Into<PathBuf>,
        package: impl Into<String>,
    ) -> Self {
        self.java_host = Some((out_dir.into(), package.into()));
        self
    }

    pub fn emit_rust_host(mut self, out_dir: impl Into<PathBuf>) -> Self {
        self.rust_host = Some(out_dir.into());
        self
    }

    pub fn generate(self) -> Result<(), Box<dyn Error>> {
        validate_manifest(&self.manifest)?;

        if self.rust_guest {
            let out_dir = out_dir()?;
            write_rust_guest(&self.manifest, &out_dir)?;
        }

        if self.abi_json {
            let out_dir = out_dir()?;
            write_abi_json(
                &self.manifest,
                &out_dir.join(format!("{}.abi.json", self.manifest.extension.name)),
            )?;
        }

        if let Some(path) = &self.abi_json_to {
            write_abi_json(&self.manifest, path)?;
        }

        if let Some((out_dir, package)) = &self.java_host {
            write_java_host(&self.manifest, out_dir, package)?;
        }

        if let Some(out_dir) = &self.rust_host {
            write_rust_host(&self.manifest, out_dir)?;
        }

        Ok(())
    }
}

fn out_dir() -> Result<PathBuf, Box<dyn Error>> {
    Ok(PathBuf::from(env::var("OUT_DIR")?))
}

fn write_rust_guest(manifest: &Manifest, out_dir: &Path) -> Result<(), Box<dyn Error>> {
    let filename = format!("ext_{}.rs", manifest.extension.name);
    fs::write(out_dir.join(filename), generate_rust_code(manifest))?;
    Ok(())
}

fn write_abi_json(manifest: &Manifest, path: &Path) -> Result<(), Box<dyn Error>> {
    if let Some(parent) = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
    {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, serde_json::to_string_pretty(manifest)? + "\n")?;
    Ok(())
}

fn write_java_host(
    manifest: &Manifest,
    out_dir: &Path,
    package: &str,
) -> Result<(), Box<dyn Error>> {
    let package_dir = out_dir.join(package.replace('.', "/"));
    fs::create_dir_all(&package_dir)?;
    let code = generate_java_code(&manifest, package);
    let classname = format!(
        "{}HostFunctions",
        manifest.extension.name.to_upper_camel_case()
    );
    fs::write(package_dir.join(format!("{}.java", classname)), code)?;
    Ok(())
}

fn write_rust_host(manifest: &Manifest, out_dir: &Path) -> Result<(), Box<dyn Error>> {
    fs::create_dir_all(out_dir)?;
    let filename = format!("host_{}.rs", manifest.extension.name);
    fs::write(out_dir.join(filename), generate_rust_host_code(manifest)?)?;
    Ok(())
}

pub fn read_abi(path: &Path) -> Result<Manifest, Box<dyn Error>> {
    let content = fs::read_to_string(path)?;
    let manifest: Manifest = serde_json::from_str(&content)?;
    validate_manifest(&manifest)?;
    Ok(manifest)
}

/// Generate Java host function bindings from an ABI JSON file.
pub fn generate_java(abi_path: &str, out_dir: &str, package: &str) -> Result<(), Box<dyn Error>> {
    let manifest = read_abi(Path::new(abi_path))?;
    write_java_host(&manifest, Path::new(out_dir), package)
}

/// Generate Rust Wasmtime host function bindings from an ABI JSON file.
pub fn generate_rust_host(abi_path: &str, out_dir: &str) -> Result<(), Box<dyn Error>> {
    let manifest = read_abi(Path::new(abi_path))?;
    write_rust_host(&manifest, Path::new(out_dir))
}

fn validate_manifest(manifest: &Manifest) -> Result<(), Box<dyn Error>> {
    if manifest.abi_version != ABI_VERSION {
        return Err(format!(
            "unsupported ABI version {}; expected {}",
            manifest.abi_version, ABI_VERSION
        )
        .into());
    }
    if manifest.extension.name.trim().is_empty() {
        return Err("extension name is required".into());
    }
    validate_identifier("extension name", &manifest.extension.name)?;
    for function in &manifest.functions {
        if function.name.trim().is_empty() {
            return Err("function name is required".into());
        }
        validate_identifier("function name", &function.name)?;
        if is_reserved_async_control_name(&function.name) {
            return Err(format!("function name '{}' is reserved", function.name).into());
        }
        if function.r#async && function.returns != Some(Type::String) {
            return Err(format!(
                "async function '{}' must currently return string",
                function.name
            )
            .into());
        }
        for param in &function.params {
            if param.name.trim().is_empty() {
                return Err(format!("parameter name is required for {}", function.name).into());
            }
            validate_identifier(
                &format!("parameter name for function '{}'", function.name),
                &param.name,
            )?;
        }
    }
    Ok(())
}

fn validate_identifier(kind: &str, name: &str) -> Result<(), Box<dyn Error>> {
    if !is_valid_identifier(name) {
        return Err(format!(
            "{} '{}' must be an ASCII Rust/Java identifier: start with a letter or underscore, continue with letters, digits, or underscores, and avoid reserved keywords",
            kind, name
        )
        .into());
    }
    Ok(())
}

fn is_valid_identifier(name: &str) -> bool {
    let mut chars = name.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    if !(first == '_' || first.is_ascii_alphabetic()) {
        return false;
    }
    if !chars.all(|ch| ch == '_' || ch.is_ascii_alphanumeric()) {
        return false;
    }
    !is_reserved_identifier(name)
}

fn is_reserved_identifier(name: &str) -> bool {
    const RESERVED: &[&str] = &[
        "abstract",
        "as",
        "assert",
        "async",
        "await",
        "become",
        "boolean",
        "box",
        "break",
        "byte",
        "case",
        "catch",
        "char",
        "class",
        "const",
        "continue",
        "crate",
        "default",
        "do",
        "double",
        "dyn",
        "else",
        "enum",
        "extends",
        "extern",
        "false",
        "final",
        "finally",
        "float",
        "fn",
        "for",
        "gen",
        "goto",
        "if",
        "impl",
        "implements",
        "import",
        "in",
        "instanceof",
        "int",
        "interface",
        "let",
        "long",
        "loop",
        "macro",
        "match",
        "mod",
        "move",
        "mut",
        "native",
        "new",
        "null",
        "override",
        "package",
        "priv",
        "private",
        "protected",
        "pub",
        "public",
        "ref",
        "return",
        "self",
        "Self",
        "short",
        "static",
        "strictfp",
        "struct",
        "super",
        "switch",
        "synchronized",
        "this",
        "throw",
        "throws",
        "trait",
        "transient",
        "true",
        "try",
        "type",
        "typeof",
        "union",
        "unsafe",
        "unsized",
        "use",
        "virtual",
        "void",
        "volatile",
        "where",
        "while",
        "yield",
    ];
    RESERVED.contains(&name)
}

fn is_reserved_async_control_name(name: &str) -> bool {
    matches!(
        name,
        "__async_protocol__"
            | "__async_start__"
            | "__async_poll__"
            | "__async_result__"
            | "__async_cancel__"
    )
}

// ── Rust codegen ──────────────────────────────────────────────

pub fn generate_rust_code(m: &Manifest) -> String {
    let mod_name = &m.extension.name;
    let wasm_mod = m.extension.wasm_module.as_deref().unwrap_or(mod_name);

    let mut out = String::new();
    out.push_str("use pyo3::prelude::*;\n\n");

    // WASM import declarations
    if !m.functions.is_empty() {
        out.push_str(&format!("#[link(wasm_import_module = \"{}\")]\n", wasm_mod));
        out.push_str("unsafe extern \"C\" {\n");
        for f in &m.functions {
            out.push_str(&format!("    fn {}(\n", wasm_import_name(f)));
            for p in &f.params {
                for (wn, wt) in wasm_params(&p.name, p.ty) {
                    out.push_str(&format!("        {}: {},\n", wn, wt));
                }
            }
            if !f.r#async && (f.returns == Some(Type::String) || f.returns == Some(Type::Bytes)) {
                out.push_str("        result_ptr: *mut u8,\n");
                out.push_str("        result_max_len: i32,\n");
            }
            let ret = wasm_return_type(f);
            out.push_str(&format!("    ) -> {};\n", ret));
        }
        out.push_str("}\n\n");
    }

    out.push_str("const MAX_RESULT: i32 = 1024 * 1024;\n\n");

    // PyO3 wrapper functions
    for f in &m.functions {
        out.push_str(&generate_rust_pyo3_wrapper(f));
        out.push_str("\n");
    }

    // Forward-declare the PyInit function generated by #[pymodule]
    let py_mod_name = format!("_{}", mod_name);
    out.push_str(&format!(
        "unsafe extern \"C\" {{\n    #[allow(non_snake_case)]\n    fn PyInit_{}() -> *mut pyo3::ffi::PyObject;\n}}\n\n",
        py_mod_name
    ));

    // Module registration
    out.push_str(&format!(
        "pub fn register() {{\n    unsafe {{\n        pyo3::ffi::PyImport_AppendInittab(\n            b\"{}\\0\".as_ptr() as *const i8,\n            Some(PyInit_{}),\n        );\n    }}\n}}\n\n",
        py_mod_name, py_mod_name
    ));

    out.push_str(&format!(
        "pub fn prewarm(py: Python) {{\n    let modules = {:?};\n    for name in modules {{\n        match py.import(name) {{\n            Ok(_) => eprintln!(\"[prewarm] OK: {{}}\", name),\n            Err(e) => eprintln!(\"[prewarm] FAILED: {{}} - {{:?}}\", name, e),\n        }}\n    }}\n}}\n\n",
        m.extension.prewarm
    ));

    // #[pymodule]
    out.push_str(&format!(
        "#[pymodule]\nfn {}(m: &Bound<'_, PyModule>) -> PyResult<()> {{\n",
        py_mod_name
    ));
    for f in &m.functions {
        out.push_str(&format!(
            "    m.add_function(wrap_pyfunction!(py_{}, m)?)?;\n",
            f.name
        ));
    }
    out.push_str("    Ok(())\n}\n");

    out
}

fn generate_rust_pyo3_wrapper(f: &Function) -> String {
    if f.r#async {
        return generate_rust_async_pyo3_wrapper(f);
    }

    let mut out = String::new();

    out.push_str(&format!("#[pyfunction]\n#[pyo3(name = \"{}\")]\n", f.name));
    let ret_type = match f.returns {
        Some(Type::String) => "PyResult<String>",
        Some(Type::Int) => "PyResult<i32>",
        Some(Type::Float) => "PyResult<f64>",
        Some(Type::Bytes) => "PyResult<Vec<u8>>",
        None => "PyResult<()>",
    };
    out.push_str(&format!("fn py_{}(", f.name));
    let py_params: Vec<String> = f
        .params
        .iter()
        .map(|p| format!("{}: {}", &p.name, rust_py_type(p.ty)))
        .collect();
    out.push_str(&py_params.join(", "));
    out.push_str(&format!(") -> {} {{\n", ret_type));
    out.push_str("    unsafe {\n");

    for p in &f.params {
        if p.ty == Type::String {
            out.push_str(&format!(
                "        let {name}_bytes = {name}.as_bytes();\n",
                name = p.name
            ));
        } else if p.ty == Type::Bytes {
            out.push_str(&format!(
                "        let {name}_bytes = {name};\n",
                name = p.name
            ));
        }
    }

    if f.returns == Some(Type::String) || f.returns == Some(Type::Bytes) {
        out.push_str("        let mut result_buf = vec![0u8; MAX_RESULT as usize];\n");
    }

    if f.returns.is_some() {
        out.push_str(&format!("        let ret = {}(\n", wasm_import_name(f)));
    } else {
        out.push_str(&format!("        {}(\n", wasm_import_name(f)));
    }
    for p in &f.params {
        match p.ty {
            Type::String | Type::Bytes => {
                out.push_str(&format!(
                    "            {name}_bytes.as_ptr(),\n            {name}_bytes.len() as i32,\n",
                    name = p.name
                ));
            }
            Type::Int | Type::Float => {
                out.push_str(&format!("            {},\n", p.name));
            }
        }
    }
    if f.returns == Some(Type::String) || f.returns == Some(Type::Bytes) {
        out.push_str("            result_buf.as_mut_ptr(),\n");
        out.push_str("            MAX_RESULT,\n");
    }
    out.push_str("        );\n");

    match f.returns {
        Some(Type::String) => {
            out.push_str("        if ret < 0 {\n");
            out.push_str("            return Err(PyErr::new::<pyo3::exceptions::PyRuntimeError, _>(\"host call failed\"));\n");
            out.push_str("        }\n");
            out.push_str(
                "        Ok(String::from_utf8_lossy(&result_buf[..ret as usize]).into_owned())\n",
            );
        }
        Some(Type::Bytes) => {
            out.push_str("        if ret < 0 {\n");
            out.push_str("            return Err(PyErr::new::<pyo3::exceptions::PyRuntimeError, _>(\"host call failed\"));\n");
            out.push_str("        }\n");
            out.push_str("        Ok(result_buf[..ret as usize].to_vec())\n");
        }
        Some(Type::Int) => out.push_str("        Ok(ret)\n"),
        None => out.push_str("        Ok(())\n"),
        Some(Type::Float) => out.push_str("        Ok(ret)\n"),
    }

    out.push_str("    }\n}\n");
    out
}

fn generate_rust_async_pyo3_wrapper(f: &Function) -> String {
    validate_async_function(f);
    let mut out = String::new();
    out.push_str(&format!("#[pyfunction]\n#[pyo3(name = \"{}\")]\n", f.name));
    out.push_str(&format!("fn py_{}(py: Python", f.name));
    for p in &f.params {
        out.push_str(&format!(", {}: {}", p.name, rust_py_type(p.ty)));
    }
    out.push_str(") -> PyResult<Py<PyAny>> {\n");
    out.push_str("    unsafe {\n");
    for p in &f.params {
        if p.ty == Type::String {
            out.push_str(&format!(
                "        let {name}_bytes = {name}.as_bytes();\n",
                name = p.name
            ));
        } else if p.ty == Type::Bytes {
            out.push_str(&format!(
                "        let {name}_bytes = {name};\n",
                name = p.name
            ));
        }
    }
    out.push_str(&format!("        let token = {}(\n", wasm_import_name(f)));
    for p in &f.params {
        match p.ty {
            Type::String | Type::Bytes => {
                out.push_str(&format!(
                    "            {name}_bytes.as_ptr(),\n            {name}_bytes.len() as i32,\n",
                    name = p.name
                ));
            }
            Type::Int | Type::Float => {
                out.push_str(&format!("            {},\n", p.name));
            }
        }
    }
    out.push_str("        );\n");
    // Defense in depth: tokens are always positive, so a negative return means the host could not
    // even register the call. Fail loudly here rather than handing a bogus token to the event loop
    // (boomslang_host.asyncio also rejects non-positive tokens).
    out.push_str("        if token < 0 {\n");
    out.push_str("            return Err(PyErr::new::<pyo3::exceptions::PyRuntimeError, _>(\"async host call failed\"));\n");
    out.push_str("        }\n");
    out.push_str("        let asyncio = py.import(\"boomslang_host.asyncio\")?;\n");
    out.push_str("        let from_host_token = asyncio.getattr(\"from_host_token\")?;\n");
    out.push_str("        let future = from_host_token.call1((token,))?;\n");
    out.push_str("        Ok(future.unbind())\n");
    out.push_str("    }\n}\n");
    out
}

fn validate_async_function(f: &Function) {
    if f.returns != Some(Type::String) {
        panic!("Async function '{}' must currently return string", f.name);
    }
}

fn wasm_import_name(f: &Function) -> String {
    f.name.clone()
}

fn wasm_params(name: &str, ty: Type) -> Vec<(String, &'static str)> {
    match ty {
        Type::String | Type::Bytes => vec![
            (format!("{}_ptr", name), "*const u8"),
            (format!("{}_len", name), "i32"),
        ],
        Type::Int => vec![(name.to_string(), "i32")],
        Type::Float => vec![(name.to_string(), "f64")],
    }
}

fn wasm_return_type(f: &Function) -> &'static str {
    if f.r#async {
        validate_async_function(f);
        return "i64";
    }
    match f.returns {
        Some(Type::String) | Some(Type::Bytes) | Some(Type::Int) => "i32",
        Some(Type::Float) => "f64",
        None => "()",
    }
}

fn rust_py_type(ty: Type) -> &'static str {
    match ty {
        Type::String => "&str",
        Type::Int => "i32",
        Type::Float => "f64",
        Type::Bytes => "&[u8]",
    }
}

// ── Rust Wasmtime host codegen ──────────────────────────────────────────────

pub fn generate_rust_host_code(m: &Manifest) -> Result<String, Box<dyn Error>> {
    validate_manifest(m)?;

    let ext_name = &m.extension.name;
    let struct_name = rust_host_struct_name(ext_name);
    let builder_name = format!("{}Builder", struct_name);
    let wasm_module = m.extension.wasm_module.as_deref().unwrap_or(ext_name);
    let mut out = String::new();

    out.push_str("// Generated by boomslang-hostgen. Do not edit by hand.\n");
    out.push_str("use anyhow::{bail, Context, Result};\n");
    out.push_str("use std::collections::{HashMap, HashSet, VecDeque};\n");
    out.push_str("use std::sync::atomic::{AtomicI64, Ordering};\n");
    out.push_str("use std::sync::{Arc, Condvar, Mutex, MutexGuard};\n");
    out.push_str("use std::thread;\n");
    out.push_str("use std::time::Duration;\n");
    out.push_str("use wasmtime::{Caller, FuncType, Linker, Memory, Val, ValType};\n\n");

    out.push_str("#[derive(Clone, Default)]\n");
    out.push_str(&format!("pub struct {} {{\n", struct_name));
    for f in &m.functions {
        out.push_str(&format!(
            "    {}: Option<{}>,\n",
            rust_host_field_name(f),
            rust_host_arc_handler_type(f)
        ));
    }
    out.push_str("}\n\n");

    out.push_str("#[derive(Default)]\n");
    out.push_str(&format!("pub struct {} {{\n", builder_name));
    for f in &m.functions {
        out.push_str(&format!(
            "    {}: Option<{}>,\n",
            rust_host_field_name(f),
            rust_host_arc_handler_type(f)
        ));
    }
    out.push_str("}\n\n");

    out.push_str(&format!("impl {} {{\n", struct_name));
    out.push_str(&format!(
        "    pub const EXTENSION_NAME: &'static str = {};\n",
        rust_string_literal(ext_name)
    ));
    out.push_str(&format!(
        "    pub const MODULE: &'static str = {};\n\n",
        rust_string_literal(wasm_module)
    ));
    out.push_str(&format!(
        "    pub fn builder() -> {} {{\n        {}::default()\n    }}\n\n",
        builder_name, builder_name
    ));
    out.push_str("    pub fn register<T: Send + 'static>(&self, linker: &mut Linker<T>) -> Result<Vec<&'static str>> {\n");
    for f in &m.functions {
        out.push_str(&format!(
            "        self.{}(linker)?;\n",
            rust_host_register_method(f)
        ));
    }
    out.push_str("        Ok(vec![");
    for (idx, f) in m.functions.iter().enumerate() {
        if idx > 0 {
            out.push_str(", ");
        }
        out.push_str(&rust_string_literal(&format!(
            "{}::{}",
            wasm_module, f.name
        )));
    }
    out.push_str("])\n    }\n\n");
    for f in &m.functions {
        out.push_str(&generate_rust_host_register_function(f));
        out.push('\n');
    }
    out.push_str("}\n\n");

    out.push_str(&format!("impl {} {{\n", builder_name));
    for f in &m.functions {
        out.push_str(&format!(
            "    pub fn {}<F>(mut self, handler: F) -> Self\n    where\n        F: {} + Send + Sync + 'static,\n    {{\n        self.{} = Some(Arc::new(handler));\n        self\n    }}\n\n",
            rust_host_with_method(f),
            rust_host_fn_bound(f),
            rust_host_field_name(f)
        ));
    }
    out.push_str(&format!("    pub fn build(self) -> {} {{\n", struct_name));
    out.push_str(&format!("        {} {{\n", struct_name));
    for f in &m.functions {
        out.push_str(&format!(
            "            {}: self.{},\n",
            rust_host_field_name(f),
            rust_host_field_name(f)
        ));
    }
    out.push_str("        }\n    }\n}\n\n");
    out.push_str(
        r#"#[derive(Clone, Default)]
pub struct AsyncHostRegistry {
    inner: Arc<AsyncHostRegistryInner>,
}

#[derive(Default)]
struct AsyncHostRegistryInner {
    next_token: AtomicI64,
    state: Mutex<AsyncHostRegistryState>,
    ready: Condvar,
}

type AsyncTokenHandler = Arc<dyn Fn(String, String) -> Result<i64> + Send + Sync>;

#[derive(Default)]
struct AsyncHostRegistryState {
    handlers: HashMap<String, AsyncTokenHandler>,
    pending: HashSet<i64>,
    completed: VecDeque<AsyncCompletion>,
    ready: HashMap<i64, AsyncCompletion>,
    canceled: HashSet<i64>,
}

#[derive(Clone)]
struct AsyncCompletion {
    token: i64,
    ok: bool,
    value: Vec<u8>,
}

#[allow(dead_code)]
impl AsyncHostRegistry {
    pub const PROTOCOL_VERSION: i32 = 1;
    pub const PROTOCOL: &'static str = "__async_protocol__";
    pub const START: &'static str = "__async_start__";
    pub const POLL: &'static str = "__async_poll__";
    pub const RESULT: &'static str = "__async_result__";
    pub const CANCEL: &'static str = "__async_cancel__";

    pub fn is_control_call(name: &str) -> bool {
        matches!(
            name,
            Self::PROTOCOL | Self::START | Self::POLL | Self::RESULT | Self::CANCEL
        )
    }

    pub fn handle_call_or<F>(&self, name: String, args: String, fallback: F) -> Result<String>
    where
        F: FnOnce(String, String) -> Result<String>,
    {
        if let Some(result) = self.handle_control_call(&name, &args)? {
            return Ok(result);
        }
        fallback(name, args)
    }

    pub fn handle_control_call(&self, name: &str, args: &str) -> Result<Option<String>> {
        match name {
            Self::PROTOCOL => Ok(Some(Self::PROTOCOL_VERSION.to_string())),
            Self::START => Ok(Some(self.start_named(args)?)),
            Self::POLL => {
                let timeout_ms = args.trim().parse::<i64>().context("invalid async poll timeout")?;
                Ok(Some(self.poll(timeout_ms)?))
            }
            Self::RESULT => {
                let token = args.trim().parse::<i64>().context("invalid async token")?;
                Ok(Some(self.result(token)?))
            }
            Self::CANCEL => {
                let token = args.trim().parse::<i64>().context("invalid async token")?;
                self.cancel(token)?;
                Ok(Some(String::new()))
            }
            _ => Ok(None),
        }
    }

    pub fn register_token_handler<F>(&self, name: impl Into<String>, handler: F) -> Result<()>
    where
        F: Fn(String, String) -> Result<i64> + Send + Sync + 'static,
    {
        self.lock_state()?.handlers.insert(name.into(), Arc::new(handler));
        Ok(())
    }

    pub fn register_blocking_handler<F>(&self, name: impl Into<String>, handler: F) -> Result<()>
    where
        F: Fn(String, String) -> Result<String> + Send + Sync + 'static,
    {
        let handler = Arc::new(handler);
        let registry = self.clone();
        self.register_token_handler(name, move |name, args| {
            let handler = Arc::clone(&handler);
            registry.start_blocking(move || handler(name, args))
        })
    }

    pub fn start_completed(&self, value: impl Into<String>) -> Result<i64> {
        self.complete_new(true, value.into().into_bytes())
    }

    pub fn start_failed(&self, error: impl ToString) -> Result<i64> {
        self.complete_new(false, error.to_string().into_bytes())
    }

    pub fn start_blocking<F>(&self, work: F) -> Result<i64>
    where
        F: FnOnce() -> Result<String> + Send + 'static,
    {
        let token = self.allocate_pending_token()?;
        let registry = self.clone();
        thread::spawn(move || {
            let (ok, value) = match work() {
                Ok(value) => (true, value.into_bytes()),
                Err(error) => (false, format!("{error:#}").into_bytes()),
            };
            if let Err(error) = registry.complete_existing(token, ok, value) {
                eprintln!("async host completion failed for token {token}: {error:#}");
            }
        });
        Ok(token)
    }

    pub fn cancel(&self, token: i64) -> Result<()> {
        let mut state = self.lock_state()?;
        if state.pending.remove(&token) {
            state.canceled.insert(token);
        }
        state.completed.retain(|completion| completion.token != token);
        state.ready.remove(&token);
        Ok(())
    }

    fn start_named(&self, args: &str) -> Result<String> {
        let (name, payload) = args.split_once('\n').unwrap_or((args, ""));
        let handler = self.lock_state()?.handlers.get(name).cloned();
        let token = match handler {
            Some(handler) => match handler(name.to_string(), payload.to_string()) {
                Ok(token) => token,
                Err(error) => self.start_failed(format!("{error:#}"))?,
            },
            None => self.start_failed(format!("No async handler registered for: {name}"))?,
        };
        Ok(token.to_string())
    }

    fn poll(&self, timeout_ms: i64) -> Result<String> {
        let mut state = self.lock_state()?;
        if timeout_ms < 0 {
            while state.completed.is_empty() {
                state = self
                    .inner
                    .ready
                    .wait(state)
                    .map_err(|_| anyhow::anyhow!("async registry lock poisoned"))?;
            }
        } else if timeout_ms > 0 && state.completed.is_empty() {
            let timeout = Duration::from_millis(timeout_ms as u64);
            let (guard, _) = self
                .inner
                .ready
                .wait_timeout_while(state, timeout, |state| state.completed.is_empty())
                .map_err(|_| anyhow::anyhow!("async registry lock poisoned"))?;
            state = guard;
        }

        let mut headers = String::new();
        while let Some(completion) = state.completed.pop_front() {
            headers.push_str(&format!(
                "{}\t{}\t{}\n",
                completion.token,
                if completion.ok { 1 } else { 0 },
                completion.value.len()
            ));
            state.ready.insert(completion.token, completion);
        }
        Ok(headers)
    }

    fn result(&self, token: i64) -> Result<String> {
        let completion = self.lock_state()?.ready.remove(&token);
        Ok(completion
            .map(|completion| base64_encode(&completion.value))
            .unwrap_or_default())
    }

    fn complete_new(&self, ok: bool, value: Vec<u8>) -> Result<i64> {
        let token = self.next_token();
        self.queue_completion(AsyncCompletion { token, ok, value })?;
        Ok(token)
    }

    fn allocate_pending_token(&self) -> Result<i64> {
        let token = self.next_token();
        self.lock_state()?.pending.insert(token);
        Ok(token)
    }

    fn complete_existing(&self, token: i64, ok: bool, value: Vec<u8>) -> Result<()> {
        let mut state = self.lock_state()?;
        let was_pending = state.pending.remove(&token);
        let was_canceled = state.canceled.remove(&token);
        if !was_pending || was_canceled {
            return Ok(());
        }
        state.completed.push_back(AsyncCompletion { token, ok, value });
        drop(state);
        self.inner.ready.notify_all();
        Ok(())
    }

    fn queue_completion(&self, completion: AsyncCompletion) -> Result<()> {
        self.lock_state()?.completed.push_back(completion);
        self.inner.ready.notify_all();
        Ok(())
    }

    fn next_token(&self) -> i64 {
        self.inner.next_token.fetch_add(1, Ordering::Relaxed) + 1
    }

    fn lock_state(&self) -> Result<MutexGuard<'_, AsyncHostRegistryState>> {
        self.inner
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("async registry lock poisoned"))
    }
}

#[allow(dead_code)]
fn base64_encode(bytes: &[u8]) -> String {
    const TABLE: &[u8; 64] =
        b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(((bytes.len() + 2) / 3) * 4);
    let mut index = 0;
    while index < bytes.len() {
        let b0 = bytes[index];
        let b1 = if index + 1 < bytes.len() {
            bytes[index + 1]
        } else {
            0
        };
        let b2 = if index + 2 < bytes.len() {
            bytes[index + 2]
        } else {
            0
        };
        out.push(TABLE[(b0 >> 2) as usize] as char);
        out.push(TABLE[(((b0 & 0b0000_0011) << 4) | (b1 >> 4)) as usize] as char);
        if index + 1 < bytes.len() {
            out.push(TABLE[(((b1 & 0b0000_1111) << 2) | (b2 >> 6)) as usize] as char);
        } else {
            out.push('=');
        }
        if index + 2 < bytes.len() {
            out.push(TABLE[(b2 & 0b0011_1111) as usize] as char);
        } else {
            out.push('=');
        }
        index += 3;
    }
    out
}

"#,
    );
    out.push_str(
        r#"#[allow(dead_code)]
fn caller_memory<T>(caller: &mut Caller<'_, T>) -> Result<Memory> {
    caller
        .get_export("memory")
        .and_then(|export| export.into_memory())
        .context("host import needs the guest to export memory")
}

#[allow(dead_code)]
fn read_string<T>(caller: &Caller<'_, T>, memory: Memory, ptr: i32, len: i32) -> Result<String> {
    String::from_utf8(read_bytes(caller, memory, ptr, len)?)
        .context("host string argument is not valid UTF-8")
}

#[allow(dead_code)]
fn read_bytes<T>(caller: &Caller<'_, T>, memory: Memory, ptr: i32, len: i32) -> Result<Vec<u8>> {
    let ptr = usize::try_from(ptr).context("negative memory pointer")?;
    let len = usize::try_from(len).context("negative memory length")?;
    let end = ptr
        .checked_add(len)
        .context("memory range overflow while reading host argument")?;
    let data = memory.data(caller);
    let bytes = data
        .get(ptr..end)
        .with_context(|| format!("memory read out of bounds: {ptr}..{end}"))?;
    Ok(bytes.to_vec())
}

#[allow(dead_code)]
fn write_buffer_result<T>(
    caller: &mut Caller<'_, T>,
    memory: Memory,
    ptr: i32,
    max_len: i32,
    bytes: &[u8],
    results: &mut [Val],
) -> Result<()> {
    if max_len < 0 {
        bail!("negative result buffer length");
    }
    if bytes.len() > max_len as usize {
        results[0] = Val::I32(-2);
        return Ok(());
    }
    let ptr = usize::try_from(ptr).context("negative result buffer pointer")?;
    memory.write(caller, ptr, bytes)?;
    results[0] = Val::I32(bytes.len() as i32);
    Ok(())
}

#[allow(dead_code)]
fn expect_i32(params: &[Val], index: usize, name: &str) -> Result<i32> {
    match params.get(index) {
        Some(Val::I32(value)) => Ok(*value),
        other => bail!("expected i32 for {name} at arg {index}, got {other:?}"),
    }
}

#[allow(dead_code)]
fn expect_f64(params: &[Val], index: usize, name: &str) -> Result<f64> {
    match params.get(index) {
        Some(Val::F64(bits)) => Ok(f64::from_bits(*bits)),
        other => bail!("expected f64 for {name} at arg {index}, got {other:?}"),
    }
}
"#,
    );

    Ok(out)
}

fn generate_rust_host_register_function(f: &Function) -> String {
    let mut out = String::new();
    let field = rust_host_field_name(f);
    let import_name = &f.name;
    let register_method = rust_host_register_method(f);
    let params = rust_host_valtype_vec(rust_host_wasmtime_params(f));
    let returns = rust_host_valtype_vec(rust_host_wasmtime_returns(f));
    let results_name = if f.r#async || f.returns.is_some() {
        "results"
    } else {
        "_results"
    };

    out.push_str(&format!(
        "    fn {}<T: Send + 'static>(&self, linker: &mut Linker<T>) -> Result<()> {{\n",
        register_method
    ));
    out.push_str(&format!("        let handler = self.{}.clone();\n", field));
    out.push_str(&format!(
        "        let ty = FuncType::new(linker.engine(), {}, {});\n",
        params, returns
    ));
    out.push_str(&format!(
        "        linker.func_new(Self::MODULE, {}, ty, move |mut caller: Caller<'_, T>, params: &[Val], {}: &mut [Val]| {{\n",
        rust_string_literal(import_name),
        results_name
    ));
    out.push_str("            let result = (|| -> Result<()> {\n");
    out.push_str("                let Some(handler) = handler.as_ref() else {\n");
    out.push_str(&format!(
        "                    bail!(\"No handler registered for host function {{}}::{}\", Self::MODULE);\n",
        import_name
    ));
    out.push_str("                };\n");

    if needs_memory(f) {
        out.push_str("                let memory = caller_memory(&mut caller)?;\n");
    }
    out.push_str(&rust_host_param_reads(f));
    out.push_str(&rust_host_return_handling(f));
    out.push_str("                Ok(())\n");
    out.push_str("            })();\n");
    out.push_str(&rust_host_error_handling(f));
    out.push_str("        })?;\n");
    out.push_str("        Ok(())\n");
    out.push_str("    }\n");

    out
}

fn rust_host_param_reads(f: &Function) -> String {
    let mut out = String::new();
    let mut arg_idx = 0;
    for (param_idx, p) in f.params.iter().enumerate() {
        let rust_name = format!("param{}", param_idx);
        match p.ty {
            Type::String => {
                out.push_str(&format!(
                    "                let {name} = read_string(&caller, memory, expect_i32(params, {ptr_idx}, {ptr_name})?, expect_i32(params, {len_idx}, {len_name})?)?;\n",
                    name = rust_name,
                    ptr_idx = arg_idx,
                    ptr_name = rust_string_literal(&format!("{}_ptr", p.name)),
                    len_idx = arg_idx + 1,
                    len_name = rust_string_literal(&format!("{}_len", p.name))
                ));
                arg_idx += 2;
            }
            Type::Bytes => {
                out.push_str(&format!(
                    "                let {name} = read_bytes(&caller, memory, expect_i32(params, {ptr_idx}, {ptr_name})?, expect_i32(params, {len_idx}, {len_name})?)?;\n",
                    name = rust_name,
                    ptr_idx = arg_idx,
                    ptr_name = rust_string_literal(&format!("{}_ptr", p.name)),
                    len_idx = arg_idx + 1,
                    len_name = rust_string_literal(&format!("{}_len", p.name))
                ));
                arg_idx += 2;
            }
            Type::Int => {
                out.push_str(&format!(
                    "                let {} = expect_i32(params, {}, {})?;\n",
                    rust_name,
                    arg_idx,
                    rust_string_literal(&p.name)
                ));
                arg_idx += 1;
            }
            Type::Float => {
                out.push_str(&format!(
                    "                let {} = expect_f64(params, {}, {})?;\n",
                    rust_name,
                    arg_idx,
                    rust_string_literal(&p.name)
                ));
                arg_idx += 1;
            }
        }
    }

    if !f.r#async && is_buffer_return(f.returns) {
        out.push_str(&format!(
            "                let result_ptr = expect_i32(params, {}, \"result_ptr\")?;\n",
            arg_idx
        ));
        out.push_str(&format!(
            "                let result_max_len = expect_i32(params, {}, \"result_max_len\")?;\n",
            arg_idx + 1
        ));
    }

    out
}

fn rust_host_return_handling(f: &Function) -> String {
    let call_args = (0..f.params.len())
        .map(|idx| format!("param{}", idx))
        .collect::<Vec<_>>()
        .join(", ");
    let call = format!("handler({})", call_args);

    if f.r#async {
        validate_async_function(f);
        return format!(
            "                let token = {}?;\n                results[0] = Val::I64(token);\n",
            call
        );
    }

    match f.returns {
        Some(Type::String) => format!(
            "                let result = {}?;\n                write_buffer_result(&mut caller, memory, result_ptr, result_max_len, result.as_bytes(), results)?;\n",
            call
        ),
        Some(Type::Bytes) => format!(
            "                let result = {}?;\n                write_buffer_result(&mut caller, memory, result_ptr, result_max_len, &result, results)?;\n",
            call
        ),
        Some(Type::Int) => format!(
            "                let result = {}?;\n                results[0] = Val::I32(result);\n",
            call
        ),
        Some(Type::Float) => format!(
            "                let result = {}?;\n                results[0] = Val::F64(result.to_bits());\n",
            call
        ),
        None => format!("                {}?;\n", call),
    }
}

fn rust_host_error_handling(f: &Function) -> String {
    if f.r#async {
        format!(
            "            if let Err(error) = result {{\n                eprintln!(\"async host function {{}}::{} failed: {{error:#}}\", Self::MODULE);\n                results[0] = Val::I64(-1);\n            }}\n            Ok(())\n",
            f.name
        )
    } else if is_buffer_return(f.returns) {
        format!(
            "            if let Err(error) = result {{\n                eprintln!(\"host function {{}}::{} failed: {{error:#}}\", Self::MODULE);\n                results[0] = Val::I32(-1);\n            }}\n            Ok(())\n",
            f.name
        )
    } else {
        "            result.map_err(wasmtime::Error::msg)\n".to_string()
    }
}

fn rust_host_struct_name(ext_name: &str) -> String {
    format!("{}HostFunctions", ext_name.to_upper_camel_case())
}

fn rust_host_field_name(f: &Function) -> String {
    format!("{}_handler", f.name.to_snake_case())
}

fn rust_host_with_method(f: &Function) -> String {
    format!("with_{}", f.name.to_snake_case())
}

fn rust_host_register_method(f: &Function) -> String {
    format!("register_{}", f.name.to_snake_case())
}

fn rust_host_arc_handler_type(f: &Function) -> String {
    format!("Arc<dyn {} + Send + Sync>", rust_host_fn_bound(f))
}

fn rust_host_fn_bound(f: &Function) -> String {
    let params = f
        .params
        .iter()
        .map(|p| rust_host_value_type(p.ty))
        .collect::<Vec<_>>()
        .join(", ");
    format!("Fn({}) -> Result<{}>", params, rust_host_return_type(f))
}

fn rust_host_value_type(ty: Type) -> &'static str {
    match ty {
        Type::String => "String",
        Type::Int => "i32",
        Type::Float => "f64",
        Type::Bytes => "Vec<u8>",
    }
}

fn rust_host_return_type(f: &Function) -> &'static str {
    if f.r#async {
        validate_async_function(f);
        return "i64";
    }
    match f.returns {
        Some(Type::String) => "String",
        Some(Type::Int) => "i32",
        Some(Type::Float) => "f64",
        Some(Type::Bytes) => "Vec<u8>",
        None => "()",
    }
}

fn rust_host_wasmtime_params(f: &Function) -> Vec<&'static str> {
    let mut params = Vec::new();
    for p in &f.params {
        match p.ty {
            Type::String | Type::Bytes => {
                params.push("ValType::I32");
                params.push("ValType::I32");
            }
            Type::Int => params.push("ValType::I32"),
            Type::Float => params.push("ValType::F64"),
        }
    }
    if !f.r#async && is_buffer_return(f.returns) {
        params.push("ValType::I32");
        params.push("ValType::I32");
    }
    params
}

fn rust_host_wasmtime_returns(f: &Function) -> Vec<&'static str> {
    if f.r#async {
        validate_async_function(f);
        return vec!["ValType::I64"];
    }
    match f.returns {
        Some(Type::String) | Some(Type::Bytes) | Some(Type::Int) => vec!["ValType::I32"],
        Some(Type::Float) => vec!["ValType::F64"],
        None => vec![],
    }
}

fn rust_host_valtype_vec(types: Vec<&'static str>) -> String {
    if types.is_empty() {
        "vec![]".to_string()
    } else {
        format!("vec![{}]", types.join(", "))
    }
}

fn rust_string_literal(value: &str) -> String {
    format!("{:?}", value)
}

// ── Java codegen ──────────────────────────────────────────────

#[derive(Template)]
#[template(path = "java_host_functions.java", escape = "none")]
struct JavaHostFunctionsTemplate {
    package: String,
    class_name: String,
    extension_name: String,
    wasm_module: String,
    has_async: bool,
    functions: Vec<JavaFunctionTemplate>,
}

struct JavaFunctionTemplate {
    name: String,
    upper_name: String,
    field: String,
    handler_type: String,
    with_method: String,
    return_type: String,
    interface_params: String,
    wasm_params: String,
    wasm_returns: String,
    param_reads: String,
    return_handling: String,
    error_handling: &'static str,
    #[allow(dead_code)]
    is_async: bool,
    needs_memory: bool,
}

pub fn generate_java_code(m: &Manifest, package: &str) -> String {
    let ext_name = &m.extension.name;
    let template = JavaHostFunctionsTemplate {
        package: package.to_string(),
        class_name: format!("{}HostFunctions", ext_name.to_upper_camel_case()),
        extension_name: ext_name.to_string(),
        wasm_module: m
            .extension
            .wasm_module
            .as_deref()
            .unwrap_or(ext_name)
            .to_string(),
        has_async: m.functions.iter().any(|f| f.r#async),
        functions: m.functions.iter().map(java_function_template).collect(),
    };

    template
        .render()
        .map(normalize_java_code)
        .expect("failed to render Java host functions template")
}

fn normalize_java_code(code: String) -> String {
    let mut out = Vec::new();
    let mut previous_blank = false;

    for line in code.lines() {
        let line = line.trim_end();
        if line.is_empty() {
            if !previous_blank {
                out.push(String::new());
            }
            previous_blank = true;
            continue;
        }

        out.push(line.to_string());
        previous_blank = false;
    }

    let mut idx = 1;
    while idx < out.len() {
        if out[idx - 1].is_empty() && out[idx].trim() == "}" {
            out.remove(idx - 1);
        } else {
            idx += 1;
        }
    }

    out.join("\n") + "\n"
}

fn java_function_template(f: &Function) -> JavaFunctionTemplate {
    let field = f.name.to_lower_camel_case();
    JavaFunctionTemplate {
        name: f.name.clone(),
        upper_name: f.name.to_upper_camel_case(),
        field: field.clone(),
        handler_type: format!("{}Handler", f.name.to_upper_camel_case()),
        with_method: format!("with{}", f.name.to_upper_camel_case()),
        return_type: java_handler_return_type(f),
        interface_params: java_interface_params(f),
        wasm_params: java_value_type_list(java_wasm_params(f)),
        wasm_returns: java_value_type_list(java_wasm_returns(f)),
        param_reads: java_param_reads(f),
        return_handling: java_return_handling(f, &field),
        error_handling: java_error_handling(f),
        is_async: f.r#async,
        needs_memory: needs_memory(f),
    }
}

/// Whether the generated host function body touches WASM linear memory: true when it reads any
/// string/bytes argument, or writes a string/bytes result back into a caller-provided buffer.
/// Functions with only scalar params and no buffer return (e.g. `add`) must NOT declare the
/// `Memory` local, otherwise error-prone's UnusedLocalVariable check fails downstream.
fn needs_memory(f: &Function) -> bool {
    let reads_buffer = f
        .params
        .iter()
        .any(|p| p.ty == Type::String || p.ty == Type::Bytes);
    let writes_buffer = !f.r#async && is_buffer_return(f.returns);
    reads_buffer || writes_buffer
}

fn java_interface_params(f: &Function) -> String {
    f.params
        .iter()
        .map(|p| format!("{} {}", java_type(p.ty), p.name.to_lower_camel_case()))
        .collect::<Vec<_>>()
        .join(", ")
}

fn java_wasm_params(f: &Function) -> Vec<&'static str> {
    let mut params = Vec::new();
    for p in &f.params {
        match p.ty {
            Type::String | Type::Bytes => {
                params.push("ValueType.I32");
                params.push("ValueType.I32");
            }
            Type::Int => params.push("ValueType.I32"),
            Type::Float => params.push("ValueType.F64"),
        }
    }
    if !f.r#async && is_buffer_return(f.returns) {
        params.push("ValueType.I32");
        params.push("ValueType.I32");
    }
    params
}

fn java_value_type_list(types: Vec<&'static str>) -> String {
    if types.is_empty() {
        return "List.of()".to_string();
    }
    if types.len() <= 3 {
        return format!("List.of({})", types.join(", "));
    }
    format!(
        "List.of(\n          {}\n        )",
        types.join(",\n          ")
    )
}

fn java_wasm_returns(f: &Function) -> Vec<&'static str> {
    if f.r#async {
        validate_async_function(f);
        return vec!["ValueType.I64"];
    }
    match f.returns {
        Some(Type::String) | Some(Type::Bytes) | Some(Type::Int) => vec!["ValueType.I32"],
        Some(Type::Float) => vec!["ValueType.F64"],
        None => vec![],
    }
}

fn java_param_reads(f: &Function) -> String {
    let mut out = String::new();
    let mut arg_idx = 0;
    for (param_idx, p) in f.params.iter().enumerate() {
        let java_name = format!("param{}", param_idx);
        match p.ty {
            Type::String => {
                out.push_str(&format!(
                    "          int {name}Ptr = Math.toIntExact(wasmArgs[{i}]);\n          int {name}Len = Math.toIntExact(wasmArgs[{j}]);\n          String {name} = memory.readString({name}Ptr, {name}Len, StandardCharsets.UTF_8);\n",
                    name = java_name,
                    i = arg_idx,
                    j = arg_idx + 1
                ));
                arg_idx += 2;
            }
            Type::Bytes => {
                out.push_str(&format!(
                    "          int {name}Ptr = Math.toIntExact(wasmArgs[{i}]);\n          int {name}Len = Math.toIntExact(wasmArgs[{j}]);\n          byte[] {name} = memory.readBytes({name}Ptr, {name}Len);\n",
                    name = java_name,
                    i = arg_idx,
                    j = arg_idx + 1
                ));
                arg_idx += 2;
            }
            Type::Int => {
                out.push_str(&format!(
                    "          int {} = Math.toIntExact(wasmArgs[{}]);\n",
                    java_name, arg_idx
                ));
                arg_idx += 1;
            }
            Type::Float => {
                out.push_str(&format!(
                    "          double {} = Double.longBitsToDouble(wasmArgs[{}]);\n",
                    java_name, arg_idx
                ));
                arg_idx += 1;
            }
        }
    }

    if !f.r#async && is_buffer_return(f.returns) {
        out.push_str(&format!(
            "          int resultPtr = Math.toIntExact(wasmArgs[{}]);\n          int resultMaxLen = Math.toIntExact(wasmArgs[{}]);\n",
            arg_idx,
            arg_idx + 1
        ));
    }

    out
}

fn java_return_handling(f: &Function, field: &str) -> String {
    let call_expr = java_handler_call(f, field);
    if f.r#async {
        validate_async_function(f);
        return format!(
            "            if (asyncRegistry == null) {{\n              throw new IllegalStateException(\n                \"AsyncHostRegistry is required for async host function \" + MODULE + \"::{name}\"\n              );\n            }}\n            CompletionStage<String> stage = {call_expr};\n            if (stage == null) {{\n              throw new IllegalStateException(\n                \"Host function returned null: \" + MODULE + \"::{name}\"\n              );\n            }}\n            return new long[] {{ asyncRegistry.start(stage) }};",
            call_expr = call_expr,
            name = f.name
        );
    }
    match f.returns {
        Some(Type::String) => format!(
            "            String result = {call_expr};\n            if (result == null) {{\n              throw new IllegalStateException(\n                \"Host function returned null: \" + MODULE + \"::{name}\"\n              );\n            }}\n            byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);\n            if (resultBytes.length > resultMaxLen) {{\n              return new long[] {{ -2 }};\n            }}\n            memory.write(resultPtr, resultBytes);\n            return new long[] {{ resultBytes.length }};",
            call_expr = call_expr,
            name = f.name
        ),
        Some(Type::Bytes) => format!(
            "            byte[] resultBytes = {call_expr};\n            if (resultBytes == null) {{\n              throw new IllegalStateException(\n                \"Host function returned null: \" + MODULE + \"::{name}\"\n              );\n            }}\n            if (resultBytes.length > resultMaxLen) {{\n              return new long[] {{ -2 }};\n            }}\n            memory.write(resultPtr, resultBytes);\n            return new long[] {{ resultBytes.length }};",
            call_expr = call_expr,
            name = f.name
        ),
        Some(Type::Int) => format!(
            "            int result = {};\n            return new long[] {{ result }};",
            call_expr
        ),
        Some(Type::Float) => format!(
            "            double result = {};\n            return new long[] {{ Double.doubleToRawLongBits(result) }};",
            call_expr
        ),
        None => format!(
            "            {};\n            return null;",
            call_expr
        ),
    }
}

fn java_handler_call(f: &Function, field: &str) -> String {
    let call_args = f
        .params
        .iter()
        .enumerate()
        .map(|(idx, _)| format!("param{}", idx))
        .collect::<Vec<_>>()
        .join(", ");
    format!("{}.handle({})", field, call_args)
}

fn java_error_handling(f: &Function) -> &'static str {
    if f.r#async {
        // Deliver the failure through the normal completion path so the awaiting coroutine raises,
        // instead of returning a sentinel token the event loop would wait on forever. Fall back to
        // -1 only when there is no registry to record the failure (the client rejects token <= 0).
        "            if (asyncRegistry == null) {\n              return new long[] { -1 };\n            }\n            return new long[] { asyncRegistry.startFailed(e) };"
    } else if is_buffer_return(f.returns) {
        "            return new long[] { -1 };"
    } else {
        "            throw e;"
    }
}

fn is_buffer_return(ty: Option<Type>) -> bool {
    matches!(ty, Some(Type::String) | Some(Type::Bytes))
}

fn java_type(ty: Type) -> &'static str {
    match ty {
        Type::String => "String",
        Type::Int => "int",
        Type::Float => "double",
        Type::Bytes => "byte[]",
    }
}

fn java_handler_return_type(f: &Function) -> String {
    if f.r#async {
        validate_async_function(f);
        "CompletionStage<String>".to_string()
    } else {
        java_return_type(f.returns).to_string()
    }
}

fn java_return_type(ty: Option<Type>) -> &'static str {
    match ty {
        Some(Type::String) => "String",
        Some(Type::Int) => "int",
        Some(Type::Float) => "double",
        Some(Type::Bytes) => "byte[]",
        None => "void",
    }
}

#[cfg(test)]
mod tests;
