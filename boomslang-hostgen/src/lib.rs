use askama::Template;
use heck::{ToLowerCamelCase, ToUpperCamelCase};
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
}

impl Build {
    pub fn new(extension: ExtensionSpec) -> Self {
        Self {
            manifest: extension.into_manifest(),
            rust_guest: false,
            abi_json: false,
            abi_json_to: None,
            java_host: None,
        }
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
    for function in &manifest.functions {
        if function.name.trim().is_empty() {
            return Err("function name is required".into());
        }
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
        }
    }
    Ok(())
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
        needs_memory: java_needs_memory(f),
    }
}

/// Whether the generated host function body touches WASM linear memory: true when it reads any
/// string/bytes argument, or writes a string/bytes result back into a caller-provided buffer.
/// Functions with only scalar params and no buffer return (e.g. `add`) must NOT declare the
/// `Memory` local, otherwise error-prone's UnusedLocalVariable check fails downstream.
fn java_needs_memory(f: &Function) -> bool {
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
mod tests {
    use super::*;

    fn stock_manifest() -> Manifest {
        ExtensionSpec::new("boomslang_host")
            .wasm_module("boomslang")
            .prewarm([
                "_boomslang_host",
                "boomslang_host",
                "boomslang_host.asyncio",
            ])
            .function("call", |f| {
                f.param("name", Type::String)
                    .param("args", Type::String)
                    .returns(Type::String)
            })
            .function("log", |f| {
                f.param("level", Type::Int).param("message", Type::String)
            })
            .into_manifest()
    }

    fn async_manifest() -> Manifest {
        ExtensionSpec::new("demo_async")
            .wasm_module("demo")
            .function("lookup", |f| {
                f.r#async()
                    .param("request", Type::String)
                    .param("count", Type::Int)
                    .returns(Type::String)
            })
            .function("echo", |f| {
                f.param("request", Type::String).returns(Type::String)
            })
            .into_manifest()
    }

    #[test]
    fn it_generates_stock_java_equivalent_to_checked_in_class() {
        let expected =
            include_str!("../../core/src/main/java/com/hubspot/boomslang/generated/BoomslangHostHostFunctions.java");

        assert_eq!(
            generate_java_code(&stock_manifest(), "com.hubspot.boomslang.generated"),
            expected
        );
    }

    #[test]
    fn it_generates_stock_rust_guest_from_dsl() {
        let expected = r#"use pyo3::prelude::*;

#[link(wasm_import_module = "boomslang")]
unsafe extern "C" {
    fn call(
        name_ptr: *const u8,
        name_len: i32,
        args_ptr: *const u8,
        args_len: i32,
        result_ptr: *mut u8,
        result_max_len: i32,
    ) -> i32;
    fn log(
        level: i32,
        message_ptr: *const u8,
        message_len: i32,
    ) -> ();
}

const MAX_RESULT: i32 = 1024 * 1024;

#[pyfunction]
#[pyo3(name = "call")]
fn py_call(name: &str, args: &str) -> PyResult<String> {
    unsafe {
        let name_bytes = name.as_bytes();
        let args_bytes = args.as_bytes();
        let mut result_buf = vec![0u8; MAX_RESULT as usize];
        let ret = call(
            name_bytes.as_ptr(),
            name_bytes.len() as i32,
            args_bytes.as_ptr(),
            args_bytes.len() as i32,
            result_buf.as_mut_ptr(),
            MAX_RESULT,
        );
        if ret < 0 {
            return Err(PyErr::new::<pyo3::exceptions::PyRuntimeError, _>("host call failed"));
        }
        Ok(String::from_utf8_lossy(&result_buf[..ret as usize]).into_owned())
    }
}

#[pyfunction]
#[pyo3(name = "log")]
fn py_log(level: i32, message: &str) -> PyResult<()> {
    unsafe {
        let message_bytes = message.as_bytes();
        log(
            level,
            message_bytes.as_ptr(),
            message_bytes.len() as i32,
        );
        Ok(())
    }
}

unsafe extern "C" {
    #[allow(non_snake_case)]
    fn PyInit__boomslang_host() -> *mut pyo3::ffi::PyObject;
}

pub fn register() {
    unsafe {
        pyo3::ffi::PyImport_AppendInittab(
            b"_boomslang_host\0".as_ptr() as *const i8,
            Some(PyInit__boomslang_host),
        );
    }
}

pub fn prewarm(py: Python) {
    let modules = ["_boomslang_host", "boomslang_host", "boomslang_host.asyncio"];
    for name in modules {
        match py.import(name) {
            Ok(_) => eprintln!("[prewarm] OK: {}", name),
            Err(e) => eprintln!("[prewarm] FAILED: {} - {:?}", name, e),
        }
    }
}

#[pymodule]
fn _boomslang_host(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(py_call, m)?)?;
    m.add_function(wrap_pyfunction!(py_log, m)?)?;
    Ok(())
}
"#;

        assert_eq!(generate_rust_code(&stock_manifest()), expected);
    }

    #[test]
    fn it_serializes_stock_abi_json() {
        let abi = serde_json::to_string_pretty(&stock_manifest()).unwrap();

        assert!(abi.contains(r#""abi_version": 1"#));
        assert!(abi.contains(r#""name": "boomslang_host""#));
        assert!(abi.contains(r#""type": "string""#));
    }

    #[test]
    fn it_generates_async_java_handlers_with_typed_args() {
        let code = generate_java_code(&async_manifest(), "com.example");

        assert!(code.contains("import com.hubspot.boomslang.AsyncHostRegistry;"));
        assert!(code.contains("CompletionStage<String> handle(String request, int count);"));
        assert!(code.contains("public Builder withAsyncRegistry(AsyncHostRegistry asyncRegistry)"));
        assert!(code.contains("functions.add(createLookupFunction());"));
        assert!(code.contains("int param1 = Math.toIntExact(wasmArgs[2]);"));
        assert!(code.contains("CompletionStage<String> stage = lookup.handle(param0, param1);"));
        assert!(code.contains("return new long[] { asyncRegistry.start(stage) };"));
        assert!(code.contains("return new long[] { asyncRegistry.startFailed(e) };"));
        assert!(code.contains("functions.add(createEchoFunction());"));
    }

    #[test]
    fn it_generates_async_rust_wrapper_using_boomslang_asyncio() {
        let code = generate_rust_code(&async_manifest());

        assert!(code.contains(
            "fn py_lookup(py: Python, request: &str, count: i32) -> PyResult<Py<PyAny>>"
        ));
        assert!(code.contains("py.import(\"boomslang_host.asyncio\")?"));
        assert!(code.contains("let token = lookup("));
        assert!(code.contains("if token < 0"));
        assert!(code.contains("request_bytes.as_ptr()"));
        assert!(code.contains("count,"));
        assert!(code.contains("from_host_token.call1((token,))?"));
        assert!(code.contains("fn echo("));
        assert!(code.contains("fn lookup(\n"));
    }
}
