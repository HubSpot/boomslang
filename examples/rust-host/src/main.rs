use anyhow::{Context, Result, anyhow, bail};
use boomslang_hostgen::{Function, Manifest, Type};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use wasmtime::{Caller, Engine, FuncType, Linker, Memory, Val, ValType};

type Handler = Arc<dyn Fn(Vec<HostValue>) -> Result<Option<HostValue>> + Send + Sync>;

#[derive(Clone, Debug, PartialEq)]
pub enum HostValue {
    String(String),
    Int(i32),
    Float(f64),
    Bytes(Vec<u8>),
    AsyncToken(i64),
}

#[derive(Clone, Default)]
pub struct HostHandlers {
    handlers: HashMap<String, Handler>,
}

impl HostHandlers {
    pub fn insert<F>(&mut self, name: impl Into<String>, handler: F)
    where
        F: Fn(Vec<HostValue>) -> Result<Option<HostValue>> + Send + Sync + 'static,
    {
        self.handlers.insert(name.into(), Arc::new(handler));
    }

    fn get(&self, name: &str) -> Result<Handler> {
        self.handlers
            .get(name)
            .cloned()
            .ok_or_else(|| anyhow!("missing host handler for {name}"))
    }
}

pub fn load_manifest(path: impl AsRef<Path>) -> Result<Manifest> {
    let path = path.as_ref();
    let abi = std::fs::read_to_string(path)
        .with_context(|| format!("read ABI JSON from {}", path.display()))?;
    serde_json::from_str(&abi).with_context(|| format!("parse ABI JSON from {}", path.display()))
}

pub fn register_extension_imports<T: Send + 'static>(
    linker: &mut Linker<T>,
    manifest: &Manifest,
    handlers: &HostHandlers,
) -> Result<Vec<String>> {
    let module = manifest
        .extension
        .wasm_module
        .as_deref()
        .unwrap_or(&manifest.extension.name)
        .to_string();
    let mut registered = Vec::new();

    for function in &manifest.functions {
        let handler = handlers.get(&function.name)?;
        let ty = FuncType::new(
            linker.engine(),
            wasm_params(function),
            wasm_results(function),
        );
        let function_name = function.name.clone();
        let function = function.clone();

        linker.func_new(
            &module,
            &function_name,
            ty,
            move |caller: Caller<'_, T>, params: &[Val], results: &mut [Val]| {
                invoke_host_function(caller, &function, handler.clone(), params, results)
                    .map_err(wasmtime::Error::msg)
            },
        )?;
        registered.push(format!("{module}::{function_name}"));
    }

    Ok(registered)
}

fn invoke_host_function<T>(
    mut caller: Caller<'_, T>,
    function: &Function,
    handler: Handler,
    params: &[Val],
    results: &mut [Val],
) -> Result<()> {
    let memory = function_needs_memory(function)
        .then(|| caller_memory(&mut caller))
        .transpose()?;
    let (args, result_buffer) = read_host_args(&caller, memory, function, params)?;

    match handler(args) {
        Ok(value) => {
            write_host_result(&mut caller, memory, function, result_buffer, value, results)
        }
        Err(error) if function.r#async => {
            results[0] = Val::I64(-1);
            eprintln!("async host function {} failed: {error:#}", function.name);
            Ok(())
        }
        Err(error) if is_buffer_return(function.returns) => {
            results[0] = Val::I32(-1);
            eprintln!("host function {} failed: {error:#}", function.name);
            Ok(())
        }
        Err(error) => Err(error),
    }
}

fn read_host_args<T>(
    caller: &Caller<'_, T>,
    memory: Option<Memory>,
    function: &Function,
    params: &[Val],
) -> Result<(Vec<HostValue>, Option<ResultBuffer>)> {
    let mut args = Vec::new();
    let mut index = 0;

    for param in &function.params {
        match param.ty {
            Type::String => {
                let ptr = expect_i32(params, index, &param.name)?;
                let len = expect_i32(params, index + 1, &param.name)?;
                let bytes = read_memory(caller, memory, ptr, len)?;
                args.push(HostValue::String(String::from_utf8(bytes)?));
                index += 2;
            }
            Type::Bytes => {
                let ptr = expect_i32(params, index, &param.name)?;
                let len = expect_i32(params, index + 1, &param.name)?;
                args.push(HostValue::Bytes(read_memory(caller, memory, ptr, len)?));
                index += 2;
            }
            Type::Int => {
                args.push(HostValue::Int(expect_i32(params, index, &param.name)?));
                index += 1;
            }
            Type::Float => {
                args.push(HostValue::Float(expect_f64(params, index, &param.name)?));
                index += 1;
            }
        }
    }

    let result_buffer = if !function.r#async && is_buffer_return(function.returns) {
        Some(ResultBuffer {
            ptr: expect_i32(params, index, "result_ptr")?,
            max_len: expect_i32(params, index + 1, "result_max_len")?,
        })
    } else {
        None
    };

    Ok((args, result_buffer))
}

fn write_host_result<T>(
    caller: &mut Caller<'_, T>,
    memory: Option<Memory>,
    function: &Function,
    result_buffer: Option<ResultBuffer>,
    value: Option<HostValue>,
    results: &mut [Val],
) -> Result<()> {
    if function.r#async {
        let Some(HostValue::AsyncToken(token)) = value else {
            bail!(
                "async host function {} must return an async token",
                function.name
            );
        };
        results[0] = Val::I64(token);
        return Ok(());
    }

    match function.returns {
        Some(Type::String) => {
            let Some(HostValue::String(value)) = value else {
                bail!("host function {} must return a string", function.name);
            };
            write_buffer_result(caller, memory, result_buffer, value.as_bytes(), results)
        }
        Some(Type::Bytes) => {
            let Some(HostValue::Bytes(value)) = value else {
                bail!("host function {} must return bytes", function.name);
            };
            write_buffer_result(caller, memory, result_buffer, &value, results)
        }
        Some(Type::Int) => {
            let Some(HostValue::Int(value)) = value else {
                bail!("host function {} must return an int", function.name);
            };
            results[0] = Val::I32(value);
            Ok(())
        }
        Some(Type::Float) => {
            let Some(HostValue::Float(value)) = value else {
                bail!("host function {} must return a float", function.name);
            };
            results[0] = Val::F64(value.to_bits());
            Ok(())
        }
        None => {
            if value.is_some() {
                bail!("host function {} must not return a value", function.name);
            }
            Ok(())
        }
    }
}

fn write_buffer_result<T>(
    caller: &mut Caller<'_, T>,
    memory: Option<Memory>,
    result_buffer: Option<ResultBuffer>,
    bytes: &[u8],
    results: &mut [Val],
) -> Result<()> {
    let memory = memory.context("buffer return needs an exported memory")?;
    let result_buffer = result_buffer.context("buffer return missing result buffer params")?;
    if bytes.len() > result_buffer.max_len as usize {
        results[0] = Val::I32(-2);
        return Ok(());
    }

    memory.write(caller, result_buffer.ptr as usize, bytes)?;
    results[0] = Val::I32(bytes.len() as i32);
    Ok(())
}

fn caller_memory<T>(caller: &mut Caller<'_, T>) -> Result<Memory> {
    caller
        .get_export("memory")
        .and_then(|export| export.into_memory())
        .context("host import needs the guest to export memory")
}

fn read_memory<T>(
    caller: &Caller<'_, T>,
    memory: Option<Memory>,
    ptr: i32,
    len: i32,
) -> Result<Vec<u8>> {
    let memory = memory.context("memory argument needs an exported memory")?;
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

fn expect_i32(params: &[Val], index: usize, name: &str) -> Result<i32> {
    match params.get(index) {
        Some(Val::I32(value)) => Ok(*value),
        other => bail!("expected i32 for {name} at arg {index}, got {other:?}"),
    }
}

fn expect_f64(params: &[Val], index: usize, name: &str) -> Result<f64> {
    match params.get(index) {
        Some(Val::F64(bits)) => Ok(f64::from_bits(*bits)),
        other => bail!("expected f64 for {name} at arg {index}, got {other:?}"),
    }
}

fn wasm_params(function: &Function) -> Vec<ValType> {
    let mut params = Vec::new();
    for param in &function.params {
        match param.ty {
            Type::String | Type::Bytes => {
                params.push(ValType::I32);
                params.push(ValType::I32);
            }
            Type::Int => params.push(ValType::I32),
            Type::Float => params.push(ValType::F64),
        }
    }

    if !function.r#async && is_buffer_return(function.returns) {
        params.push(ValType::I32);
        params.push(ValType::I32);
    }

    params
}

fn wasm_results(function: &Function) -> Vec<ValType> {
    if function.r#async {
        return vec![ValType::I64];
    }

    match function.returns {
        Some(Type::String) | Some(Type::Bytes) | Some(Type::Int) => vec![ValType::I32],
        Some(Type::Float) => vec![ValType::F64],
        None => vec![],
    }
}

fn function_needs_memory(function: &Function) -> bool {
    function
        .params
        .iter()
        .any(|param| matches!(param.ty, Type::String | Type::Bytes))
        || (!function.r#async && is_buffer_return(function.returns))
}

fn is_buffer_return(ty: Option<Type>) -> bool {
    matches!(ty, Some(Type::String) | Some(Type::Bytes))
}

#[derive(Clone, Copy)]
struct ResultBuffer {
    ptr: i32,
    max_len: i32,
}

fn default_abi_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("abi/boomslang_host.abi.json")
}

fn main() -> Result<()> {
    let abi_path = std::env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(default_abi_path);
    let manifest = load_manifest(&abi_path)?;

    let engine = Engine::default();
    let mut linker = Linker::<()>::new(&engine);
    let mut handlers = HostHandlers::default();

    handlers.insert("call", |args| {
        let [HostValue::String(name), HostValue::String(payload)] = args.as_slice() else {
            bail!("boomslang_host.call expects name and args strings");
        };
        Ok(Some(HostValue::String(format!(
            "rust handler received {name}({payload})"
        ))))
    });
    handlers.insert("log", |args| {
        let [HostValue::Int(level), HostValue::String(message)] = args.as_slice() else {
            bail!("boomslang_host.log expects level int and message string");
        };
        eprintln!("[guest log:{level}] {message}");
        Ok(None)
    });

    let imports = register_extension_imports(&mut linker, &manifest, &handlers)?;
    println!(
        "registered {} imports from {}",
        imports.len(),
        abi_path.display()
    );
    for import in imports {
        println!("  {import}");
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use wasmtime::{Module, Store};

    #[test]
    fn it_registers_stock_call_import_from_abi_json() -> Result<()> {
        let manifest = load_manifest(default_abi_path())?;
        let engine = Engine::default();
        let mut linker = Linker::<()>::new(&engine);
        let mut handlers = HostHandlers::default();
        handlers.insert("call", |args| {
            assert_eq!(
                args,
                vec![
                    HostValue::String("echo".to_string()),
                    HostValue::String("hello".to_string())
                ]
            );
            Ok(Some(HostValue::String("hello from rust".to_string())))
        });
        handlers.insert("log", |_| Ok(None));

        let imports = register_extension_imports(&mut linker, &manifest, &handlers)?;
        assert_eq!(imports, vec!["boomslang::call", "boomslang::log"]);

        let module = Module::new(
            &engine,
            wat::parse_str(
                r#"
                (module
                  (import "boomslang" "call"
                    (func $call
                      (param i32 i32 i32 i32 i32 i32)
                      (result i32)))
                  (memory (export "memory") 1)
                  (data (i32.const 0) "echo")
                  (data (i32.const 16) "hello")
                  (func (export "run") (result i32)
                    (call $call
                      (i32.const 0) (i32.const 4)
                      (i32.const 16) (i32.const 5)
                      (i32.const 32) (i32.const 64))))
                "#,
            )?,
        )?;

        let mut store = Store::new(&engine, ());
        let instance = linker.instantiate(&mut store, &module)?;
        let run = instance.get_typed_func::<(), i32>(&mut store, "run")?;
        assert_eq!(run.call(&mut store, ())?, 15);

        let memory = instance
            .get_memory(&mut store, "memory")
            .context("test module memory export")?;
        let mut result = vec![0; 15];
        memory.read(&store, 32, &mut result)?;
        assert_eq!(String::from_utf8(result)?, "hello from rust");

        Ok(())
    }
}
