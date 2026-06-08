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
fn emit_enables_standard_build_outputs() {
    let build = Build::new(ExtensionSpec::new("demo")).emit();

    assert!(build.rust_guest);
    assert!(build.abi_json);
}

#[test]
fn emit_defaults_enables_standard_build_outputs() {
    let build = Build::new(ExtensionSpec::new("demo")).emit_defaults();

    assert!(build.rust_guest);
    assert!(build.abi_json);
}

#[test]
fn it_generates_stock_java_equivalent_to_checked_in_class() {
    let expected = include_str!(
        "../../core/src/main/java/com/hubspot/boomslang/generated/BoomslangHostHostFunctions.java"
    );

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
    let expected = serde_json::to_string_pretty(&stock_manifest()).unwrap() + "\n";

    assert_eq!(
        include_str!("../../examples/rust-host/abi/boomslang_host.abi.json"),
        expected
    );
}

#[test]
fn it_omits_absent_wasm_module_from_abi_json() {
    let abi = serde_json::to_string_pretty(&ExtensionSpec::new("demo").into_manifest()).unwrap();

    assert!(!abi.contains("wasm_module"));
}

#[test]
fn it_generates_stock_rust_host_from_abi() {
    let code = generate_rust_host_code(&stock_manifest()).unwrap();

    assert!(code.contains("pub struct BoomslangHostHostFunctions"));
    assert!(code.contains("pub fn with_call<F>(mut self, handler: F) -> Self"));
    assert!(code.contains("F: Fn(String, String) -> Result<String> + Send + Sync + 'static"));
    assert!(code.contains("pub fn with_log<F>(mut self, handler: F) -> Self"));
    assert!(code.contains("F: Fn(i32, String) -> Result<()> + Send + Sync + 'static"));
    assert!(code.contains("linker.func_new(Self::MODULE, \"call\""));
    assert!(code.contains(
        "vec![ValType::I32, ValType::I32, ValType::I32, ValType::I32, ValType::I32, ValType::I32]"
    ));
    assert!(code.contains("write_buffer_result(&mut caller, memory, result_ptr, result_max_len, result.as_bytes(), results)?;"));
    assert!(code.contains("linker.func_new(Self::MODULE, \"log\""));
    assert!(code.contains("Ok(vec![\"boomslang::call\", \"boomslang::log\"])"));
}

#[test]
fn it_rejects_async_rust_host_generation() {
    let err = generate_rust_host_code(&async_manifest())
        .unwrap_err()
        .to_string();

    assert!(err.contains("Rust host generation does not support async function 'lookup' yet"));
}

#[test]
fn it_rejects_invalid_identifiers() {
    let err = validate_manifest(
        &ExtensionSpec::new("demo")
            .function("foo-bar", |f| f.param("valid", Type::String))
            .into_manifest(),
    )
    .unwrap_err()
    .to_string();

    assert!(err.contains("function name 'foo-bar' must be an ASCII Rust/Java identifier"));
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

    assert!(
        code.contains("fn py_lookup(py: Python, request: &str, count: i32) -> PyResult<Py<PyAny>>")
    );
    assert!(code.contains("py.import(\"boomslang_host.asyncio\")?"));
    assert!(code.contains("let token = lookup("));
    assert!(code.contains("if token < 0"));
    assert!(code.contains("request_bytes.as_ptr()"));
    assert!(code.contains("count,"));
    assert!(code.contains("from_host_token.call1((token,))?"));
    assert!(code.contains("fn echo("));
    assert!(code.contains("fn lookup(\n"));
}
