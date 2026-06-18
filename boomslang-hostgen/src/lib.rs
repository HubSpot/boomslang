//! Code generator for typed boomslang host extensions.
//!
//! An extension declares host functions that Python code (running on CPython
//! compiled to WASM) can call into the embedding Java or Rust host. The
//! declaration lives in the extension crate's `build.rs`, written with the
//! builder DSL in this crate: [`ExtensionSpec`] describes the extension and
//! its [functions](FunctionSpec), and [`Build`] selects which artifacts to
//! emit before [`Build::generate`] writes them out. The primary artifacts are
//! a Rust guest module (PyO3 wrappers plus WASM imports, written to
//! `OUT_DIR`) and an ABI JSON contract; the ABI JSON in turn drives host
//! adapter generation — either via the `boomslang-hostgen` CLI or the
//! [`generate_java`] / [`generate_rust_host`] functions — so hosts can be
//! regenerated without rebuilding the guest.
//!
//! # Examples
//!
//! A typical `build.rs`:
//!
//! ```ignore
//! use boomslang_hostgen::{Build, ExtensionSpec, Type};
//!
//! fn main() -> Result<(), Box<dyn std::error::Error>> {
//!     let ext = ExtensionSpec::new("myext")
//!         .wasm_module("myext")
//!         .prewarm(["myext_support"])
//!         .function("do_thing", |f| {
//!             f.param("input", Type::String).returns(Type::String)
//!         });
//!
//!     Build::new(ext).emit().generate()
//! }
//! ```
//!
//! The extension crate then consumes the generated guest module with
//! `include!(concat!(env!("OUT_DIR"), "/ext_myext.rs"));` and calls the
//! included `register()` function to add the Python module to CPython's
//! init table.

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

/// The full extension contract: ABI version, extension metadata, and the
/// list of host functions.
///
/// This is the (de)serialized form of the ABI JSON file. Build it with
/// [`ExtensionSpec`] rather than constructing it by hand, or load one from
/// disk with [`read_abi`]. Manifests are validated before any code is
/// generated: the [`abi_version`](Manifest::abi_version) must exactly match
/// the version this crate supports (currently `1`), every name must be a
/// plain ASCII identifier that is not a Rust/Java keyword, function names
/// must not collide with the reserved `__async_*` control names, and async
/// functions must return [`Type::String`].
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Manifest {
    /// ABI contract version. Generation fails unless this exactly matches
    /// the version supported by this crate (currently `1`).
    #[serde(default = "default_abi_version")]
    pub abi_version: u32,
    /// Extension-level metadata (name, WASM import module, prewarm list).
    pub extension: Extension,
    /// Host functions exposed to Python.
    #[serde(default)]
    pub functions: Vec<Function>,
}

/// Extension-level metadata within a [`Manifest`].
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Extension {
    /// Extension name; must be a valid ASCII identifier. Used to derive the
    /// Python module name (`_<name>`), generated file names, and host class
    /// names.
    pub name: String,
    /// WASM import module the host functions are linked under. Defaults to
    /// [`name`](Extension::name) when absent.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wasm_module: Option<String>,
    /// Python modules to import eagerly during Wizer pre-initialization.
    #[serde(default)]
    pub prewarm: Vec<String>,
}

/// A single host function exposed to Python.
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Function {
    /// Function name; must be a valid ASCII identifier and must not be one
    /// of the reserved `__async_*` control names.
    pub name: String,
    /// Typed parameters, in call order.
    #[serde(default)]
    pub params: Vec<Param>,
    /// Return type, or `None` for a void function.
    #[serde(default)]
    pub returns: Option<Type>,
    /// Whether the function is asynchronous. Async functions must currently
    /// return [`Type::String`]; at the WASM level they return an `i64` token
    /// that the guest resolves to an awaitable via `boomslang_host.asyncio`.
    #[serde(default)]
    pub r#async: bool,
}

/// A typed parameter of a [`Function`].
#[derive(Clone, Debug, Deserialize, PartialEq, Serialize)]
pub struct Param {
    /// Parameter name; must be a valid ASCII identifier.
    pub name: String,
    /// Parameter type (serialized as `"type"` in ABI JSON).
    #[serde(rename = "type")]
    pub ty: Type,
}

/// Value types supported across the guest/host boundary.
///
/// Each variant has a fixed lowering to the WASM ABI. Buffer-typed
/// ([`String`](Type::String)/[`Bytes`](Type::Bytes)) parameters are passed as
/// an `i32` pointer plus `i32` length into guest linear memory. Buffer-typed
/// returns use a caller-provided result buffer: the guest passes a result
/// pointer and maximum length, and the host writes the payload and returns
/// the written length as `i32` (negative values signal failure). Async
/// functions instead return an `i64` token (see
/// [`Function::r#async`](Function#structfield.async)).
#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Type {
    /// UTF-8 string. Lowered to `i32` pointer + `i32` length as a parameter;
    /// as a return value, written into the caller's result buffer with the
    /// written length returned as `i32`.
    String,
    /// 32-bit signed integer, lowered to `i32`.
    Int,
    /// 64-bit float, lowered to `f64`.
    Float,
    /// Raw byte buffer. Lowered to `i32` pointer + `i32` length as a
    /// parameter; as a return value, written into the caller's result buffer
    /// with the written length returned as `i32`.
    Bytes,
}

/// Builder for an extension [`Manifest`], used from `build.rs`.
///
/// Start with [`ExtensionSpec::new`], chain configuration calls, declare
/// functions with [`function`](ExtensionSpec::function), and hand the result
/// to [`Build::new`]. See the [crate-level example](crate).
#[derive(Clone, Debug)]
pub struct ExtensionSpec {
    manifest: Manifest,
}

impl ExtensionSpec {
    /// Creates a spec for an extension with the given name at the current
    /// ABI version. The name must be a valid ASCII identifier (validated at
    /// [`Build::generate`] time).
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

    /// Sets the WASM import module the host functions are linked under.
    /// Defaults to the extension name when not set.
    pub fn wasm_module(mut self, wasm_module: impl Into<String>) -> Self {
        self.manifest.extension.wasm_module = Some(wasm_module.into());
        self
    }

    /// Sets the Python modules to import eagerly during Wizer
    /// pre-initialization, replacing any previously configured list.
    pub fn prewarm<I, S>(mut self, modules: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.manifest.extension.prewarm = modules.into_iter().map(Into::into).collect();
        self
    }

    /// Declares a host function. The closure configures the function's
    /// [params](FunctionSpec::param), [return type](FunctionSpec::returns),
    /// and [asyncness](FunctionSpec#method.async).
    pub fn function<F>(mut self, name: impl Into<String>, configure: F) -> Self
    where
        F: FnOnce(FunctionSpec) -> FunctionSpec,
    {
        self.manifest
            .functions
            .push(configure(FunctionSpec::new(name)).build());
        self
    }

    /// Returns the manifest built so far.
    pub fn manifest(&self) -> &Manifest {
        &self.manifest
    }

    /// Consumes the spec, returning the built manifest.
    pub fn into_manifest(self) -> Manifest {
        self.manifest
    }
}

/// Builder for a single [`Function`], used inside
/// [`ExtensionSpec::function`].
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

    /// Appends a typed parameter. The name must be a valid ASCII identifier
    /// (validated at [`Build::generate`] time).
    pub fn param(mut self, name: impl Into<String>, ty: Type) -> Self {
        self.function.params.push(Param {
            name: name.into(),
            ty,
        });
        self
    }

    /// Sets the return type. Omit for a void function.
    pub fn returns(mut self, ty: Type) -> Self {
        self.function.returns = Some(ty);
        self
    }

    /// Marks the function asynchronous. Async functions must also declare
    /// `returns(Type::String)`; validation fails otherwise. See
    /// [`Function::r#async`](Function#structfield.async) for the runtime
    /// token protocol.
    pub fn r#async(mut self) -> Self {
        self.function.r#async = true;
        self
    }

    fn build(self) -> Function {
        self.function
    }
}

/// Selects which artifacts to generate from an [`ExtensionSpec`].
///
/// Each `emit_*` method enables an output; nothing is written until
/// [`generate`](Build::generate) is called. Most `build.rs` files only need
/// `Build::new(ext).emit().generate()`.
pub struct Build {
    manifest: Manifest,
    rust_guest: bool,
    abi_json: bool,
    abi_json_to: Option<PathBuf>,
    java_host: Option<(PathBuf, String)>,
    rust_host: Option<PathBuf>,
}

impl Build {
    /// Creates a build for the given extension with no outputs enabled.
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

    /// Enables the default outputs: shorthand for
    /// [`emit_rust_guest`](Build::emit_rust_guest) followed by
    /// [`emit_abi_json`](Build::emit_abi_json).
    pub fn emit(self) -> Self {
        self.emit_rust_guest().emit_abi_json()
    }

    /// Alias for [`emit`](Build::emit).
    pub fn emit_defaults(self) -> Self {
        self.emit()
    }

    /// Enables the Rust guest module, written to
    /// `$OUT_DIR/ext_<name>.rs` for consumption via `include!`.
    pub fn emit_rust_guest(mut self) -> Self {
        self.rust_guest = true;
        self
    }

    /// Enables the ABI JSON contract, written to
    /// `$OUT_DIR/<name>.abi.json`. Use
    /// [`emit_abi_json_to`](Build::emit_abi_json_to) when a stable,
    /// build-independent path is needed.
    pub fn emit_abi_json(mut self) -> Self {
        self.abi_json = true;
        self
    }

    /// Additionally writes the ABI JSON to the given path (parent
    /// directories are created). Useful for checking the contract into the
    /// repo or feeding it to the CLI from a stable location.
    pub fn emit_abi_json_to(mut self, path: impl Into<PathBuf>) -> Self {
        self.abi_json_to = Some(path.into());
        self
    }

    /// Enables the Java (Chicory) host adapter, written as
    /// `<Name>HostFunctions.java` under `out_dir` in the given package's
    /// directory layout. Note this writes directly into the given Java
    /// source tree from `build.rs`; running the `boomslang-hostgen` CLI
    /// against the emitted ABI JSON after the build is generally preferable.
    pub fn emit_java_host(
        mut self,
        out_dir: impl Into<PathBuf>,
        package: impl Into<String>,
    ) -> Self {
        self.java_host = Some((out_dir.into(), package.into()));
        self
    }

    /// Enables the Rust (Wasmtime) host adapter, written as
    /// `host_<name>.rs` under `out_dir`. As with
    /// [`emit_java_host`](Build::emit_java_host), generating hosts via the
    /// CLI after the build is generally preferable.
    pub fn emit_rust_host(mut self, out_dir: impl Into<PathBuf>) -> Self {
        self.rust_host = Some(out_dir.into());
        self
    }

    /// Validates the manifest and writes all enabled outputs.
    ///
    /// Validation enforces the exact ABI version (currently `1`), ASCII
    /// identifier rules for all names, the reserved `__async_*` function
    /// names, and that async functions return [`Type::String`].
    /// Guest and default ABI JSON outputs require the `OUT_DIR` environment
    /// variable, which Cargo sets when running `build.rs`.
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

/// Reads and validates an ABI JSON file (see [`Manifest`] for the
/// validation rules, including the exact ABI version check).
pub fn read_abi(path: &Path) -> Result<Manifest, Box<dyn Error>> {
    let content = fs::read_to_string(path)?;
    let manifest: Manifest = serde_json::from_str(&content)?;
    validate_manifest(&manifest)?;
    Ok(manifest)
}

/// Generates Java (Chicory) host function bindings from an ABI JSON file.
///
/// Reads and validates the manifest at `abi_path`, then writes
/// `<Name>HostFunctions.java` under the Java source root `out_dir`, in the
/// directory layout implied by the dot-separated `package` name. This is
/// the library entry point behind the CLI's `--java-out`/`--java-package`
/// flags.
pub fn generate_java(abi_path: &str, out_dir: &str, package: &str) -> Result<(), Box<dyn Error>> {
    let manifest = read_abi(Path::new(abi_path))?;
    write_java_host(&manifest, Path::new(out_dir), package)
}

/// Generates Rust (Wasmtime) host function bindings from an ABI JSON file.
///
/// Reads and validates the manifest at `abi_path`, then writes
/// `host_<name>.rs` into `out_dir` (created if needed). This is the library
/// entry point behind the CLI's `--rust-host-out` flag.
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

/// Renders the Rust guest module source for a manifest: WASM import
/// declarations, PyO3 wrapper functions, and the `register()`/`prewarm()`
/// entry points. [`Build::emit_rust_guest`] writes this to
/// `$OUT_DIR/ext_<name>.rs`; unlike [`Build::generate`], this function does
/// not validate the manifest.
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

/// Renders the Rust (Wasmtime) host adapter source for a manifest: a
/// `<Name>HostFunctions` struct with a builder for wiring typed handler
/// closures into a Wasmtime linker. Validates the manifest first.
pub fn generate_rust_host_code(m: &Manifest) -> Result<String, Box<dyn Error>> {
    validate_manifest(m)?;

    let ext_name = &m.extension.name;
    let struct_name = rust_host_struct_name(ext_name);
    let builder_name = format!("{}Builder", struct_name);
    let template = RustHostTemplate {
        struct_name,
        builder_name,
        extension_name: rust_string_literal(ext_name),
        wasm_module: rust_string_literal(m.extension.wasm_module.as_deref().unwrap_or(ext_name)),
        imports: rust_host_imports(m),
        functions: m
            .functions
            .iter()
            .map(rust_host_function_template)
            .collect(),
    };

    Ok(template.render().map(normalize_template_code)?)
}

#[derive(Template)]
#[template(path = "rust_host.rs", escape = "none")]
struct RustHostTemplate {
    struct_name: String,
    builder_name: String,
    extension_name: String,
    wasm_module: String,
    imports: String,
    functions: Vec<RustHostFunctionTemplate>,
}

struct RustHostFunctionTemplate {
    name: String,
    import_name: String,
    field: String,
    arc_handler_type: String,
    fn_bound: String,
    with_method: String,
    register_method: String,
    wasm_params: String,
    wasm_returns: String,
    results_name: &'static str,
    param_reads: String,
    return_handling: String,
    error_handling: String,
    needs_memory: bool,
}

fn rust_host_function_template(f: &Function) -> RustHostFunctionTemplate {
    RustHostFunctionTemplate {
        name: f.name.clone(),
        import_name: rust_string_literal(&f.name),
        field: rust_host_field_name(f),
        arc_handler_type: rust_host_arc_handler_type(f),
        fn_bound: rust_host_fn_bound(f),
        with_method: rust_host_with_method(f),
        register_method: rust_host_register_method(f),
        wasm_params: rust_host_valtype_vec(rust_host_wasmtime_params(f)),
        wasm_returns: rust_host_valtype_vec(rust_host_wasmtime_returns(f)),
        results_name: if f.r#async || f.returns.is_some() {
            "results"
        } else {
            "_results"
        },
        param_reads: rust_host_param_reads(f),
        return_handling: rust_host_return_handling(f),
        error_handling: rust_host_error_handling(f),
        needs_memory: needs_memory(f),
    }
}

fn rust_host_imports(m: &Manifest) -> String {
    let ext_name = &m.extension.name;
    let wasm_module = m.extension.wasm_module.as_deref().unwrap_or(ext_name);
    let imports = m
        .functions
        .iter()
        .map(|f| rust_string_literal(&format!("{}::{}", wasm_module, f.name)))
        .collect::<Vec<_>>()
        .join(", ");
    format!("vec![{}]", imports)
}

fn rust_host_param_reads(f: &Function) -> String {
    let mut out = String::new();
    let mut arg_idx = 0;
    for (param_idx, p) in f.params.iter().enumerate() {
        let rust_name = format!("param{}", param_idx);
        match p.ty {
            Type::String => {
                out.push_str(&format!(
                    "                    let {name} = read_string(&caller, memory, expect_i32(params, {ptr_idx}, {ptr_name})?, expect_i32(params, {len_idx}, {len_name})?)?;\n",
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
                    "                    let {name} = read_bytes(&caller, memory, expect_i32(params, {ptr_idx}, {ptr_name})?, expect_i32(params, {len_idx}, {len_name})?)?;\n",
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
                    "                    let {} = expect_i32(params, {}, {})?;\n",
                    rust_name,
                    arg_idx,
                    rust_string_literal(&p.name)
                ));
                arg_idx += 1;
            }
            Type::Float => {
                out.push_str(&format!(
                    "                    let {} = expect_f64(params, {}, {})?;\n",
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
            "                    let result_ptr = expect_i32(params, {}, \"result_ptr\")?;\n",
            arg_idx
        ));
        out.push_str(&format!(
            "                    let result_max_len = expect_i32(params, {}, \"result_max_len\")?;\n",
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
            "                    let token = {}?;\n                    results[0] = Val::I64(token);\n",
            call
        );
    }

    match f.returns {
        Some(Type::String) => format!(
            "                    let result = {}?;\n                    write_buffer_result(&mut caller, memory, result_ptr, result_max_len, result.as_bytes(), results)?;\n",
            call
        ),
        Some(Type::Bytes) => format!(
            "                    let result = {}?;\n                    write_buffer_result(&mut caller, memory, result_ptr, result_max_len, &result, results)?;\n",
            call
        ),
        Some(Type::Int) => format!(
            "                    let result = {}?;\n                    results[0] = Val::I32(result);\n",
            call
        ),
        Some(Type::Float) => format!(
            "                    let result = {}?;\n                    results[0] = Val::F64(result.to_bits());\n",
            call
        ),
        None => format!("                    {}?;\n", call),
    }
}

fn rust_host_error_handling(f: &Function) -> String {
    if f.r#async {
        format!(
            "                if let Err(error) = result {{\n                    eprintln!(\"async host function {{}}::{} failed: {{error:#}}\", Self::MODULE);\n                    results[0] = Val::I64(-1);\n                }}\n                Ok(())\n",
            f.name
        )
    } else if is_buffer_return(f.returns) {
        format!(
            "                if let Err(error) = result {{\n                    eprintln!(\"host function {{}}::{} failed: {{error:#}}\", Self::MODULE);\n                    results[0] = Val::I32(-1);\n                }}\n                Ok(())\n",
            f.name
        )
    } else {
        "                result.map_err(wasmtime::Error::msg)\n".to_string()
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
        return "vec![]".to_string();
    }
    if types.len() <= 3 {
        return format!("vec![{}]", types.join(", "));
    }
    format!(
        "vec![\n                {},\n            ]",
        types.join(",\n                ")
    )
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

/// Renders the Java (Chicory) host adapter source for a manifest: a
/// `<Name>HostFunctions` class in the given package, with typed handler
/// interfaces and Chicory `HostFunction` registrations. Unlike
/// [`generate_java`], this function does not validate the manifest.
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
        .map(normalize_template_code)
        .expect("failed to render Java host functions template")
}

fn normalize_template_code(code: String) -> String {
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
