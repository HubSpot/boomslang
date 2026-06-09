use anyhow::Result;
use wasmtime::{Engine, Linker};

mod generated {
    include!(concat!(env!("OUT_DIR"), "/host_boomslang_host.rs"));
}

mod generated_async {
    include!(concat!(env!("OUT_DIR"), "/host_demo_async.rs"));
}

use generated::{AsyncHostRegistry, BoomslangHostHostFunctions};

fn build_host(async_registry: AsyncHostRegistry) -> BoomslangHostHostFunctions {
    let registry_for_call = async_registry.clone();
    BoomslangHostHostFunctions::builder()
        .with_call(move |name, payload| {
            registry_for_call.handle_call_or(name, payload, |name, payload| {
                Ok(format!("rust handler received {name}({payload})"))
            })
        })
        .with_log(|level, message| {
            eprintln!("[guest log:{level}] {message}");
            Ok(())
        })
        .build()
}

fn main() -> Result<()> {
    let engine = Engine::default();
    let mut linker = Linker::<()>::new(&engine);
    let imports = build_host(AsyncHostRegistry::default()).register(&mut linker)?;

    println!(
        "registered {} generated imports for {}",
        imports.len(),
        BoomslangHostHostFunctions::EXTENSION_NAME
    );
    for import in imports {
        println!("  {import}");
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::{Context, bail};
    use generated_async::DemoAsyncHostFunctions;
    use wasmtime::{Module, Store};

    #[test]
    fn async_registry_routes_control_calls() -> Result<()> {
        let registry = AsyncHostRegistry::default();
        registry.register_blocking_handler("rpc", |_, payload| Ok(format!("done:{payload}")))?;

        assert_eq!(
            registry
                .handle_control_call(AsyncHostRegistry::PROTOCOL, "")?
                .context("protocol response")?,
            "1"
        );

        let token = registry
            .handle_control_call(AsyncHostRegistry::START, "rpc\nhello")?
            .context("start response")?;
        let headers = registry
            .handle_control_call(AsyncHostRegistry::POLL, "1000")?
            .context("poll response")?;
        assert_eq!(headers, format!("{token}\t1\t10\n"));

        let value = registry
            .handle_control_call(AsyncHostRegistry::RESULT, &token)?
            .context("result response")?;
        assert_eq!(value, "ZG9uZTpoZWxsbw==");

        let error = registry
            .handle_control_call(AsyncHostRegistry::POLL, "not-a-timeout")
            .unwrap_err();
        assert!(format!("{error:#}").contains("invalid async poll timeout"));

        assert_eq!(
            registry.handle_call_or(
                "echo".to_string(),
                "payload".to_string(),
                |name, payload| Ok(format!("{name}:{payload}")),
            )?,
            "echo:payload"
        );

        Ok(())
    }

    #[test]
    fn generated_async_host_returns_registry_token() -> Result<()> {
        let engine = Engine::default();
        let mut linker = Linker::<()>::new(&engine);
        let registry = AsyncHostRegistry::default();
        let registry_for_lookup = registry.clone();
        assert_eq!(DemoAsyncHostFunctions::EXTENSION_NAME, "demo_async");
        let host = DemoAsyncHostFunctions::builder()
            .with_lookup(move |request, count| {
                registry_for_lookup.start_completed(format!("{request}:{count}"))
            })
            .with_echo(|request| Ok(request))
            .build();

        let imports = host.register(&mut linker)?;
        assert_eq!(imports, vec!["demo::lookup", "demo::echo"]);

        let module = Module::new(
            &engine,
            wat::parse_str(
                r#"
                (module
                  (import "demo" "lookup"
                    (func $lookup (param i32 i32 i32) (result i64)))
                  (memory (export "memory") 1)
                  (data (i32.const 0) "request")
                  (func (export "run") (result i64)
                    (call $lookup (i32.const 0) (i32.const 7) (i32.const 3))))
                "#,
            )?,
        )?;

        let mut store = Store::new(&engine, ());
        let instance = linker.instantiate(&mut store, &module)?;
        let run = instance.get_typed_func::<(), i64>(&mut store, "run")?;
        let token = run.call(&mut store, ())?;
        assert!(token > 0);

        let headers = registry
            .handle_control_call(AsyncHostRegistry::POLL, "0")?
            .context("poll response")?;
        assert_eq!(headers, format!("{token}\t1\t9\n"));

        let value = registry
            .handle_control_call(AsyncHostRegistry::RESULT, &token.to_string())?
            .context("result response")?;
        assert_eq!(value, "cmVxdWVzdDoz");

        Ok(())
    }

    #[test]
    fn it_registers_generated_stock_call_import_from_abi_json() -> Result<()> {
        let engine = Engine::default();
        let mut linker = Linker::<()>::new(&engine);
        let host = BoomslangHostHostFunctions::builder()
            .with_call(|name, payload| {
                assert_eq!(name, "echo");
                assert_eq!(payload, "hello");
                Ok("hello from generated rust".to_string())
            })
            .with_log(|_, _| Ok(()))
            .build();

        let imports = host.register(&mut linker)?;
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
        assert_eq!(run.call(&mut store, ())?, 25);

        let memory = instance
            .get_memory(&mut store, "memory")
            .context("test module memory export")?;
        let mut result = vec![0; 25];
        memory.read(&store, 32, &mut result)?;
        assert_eq!(String::from_utf8(result)?, "hello from generated rust");

        Ok(())
    }

    #[test]
    fn generated_host_returns_negative_length_when_buffer_is_too_small() -> Result<()> {
        let engine = Engine::default();
        let mut linker = Linker::<()>::new(&engine);
        let host = BoomslangHostHostFunctions::builder()
            .with_call(|_, _| Ok("too large".to_string()))
            .with_log(|_, _| Ok(()))
            .build();
        host.register(&mut linker)?;

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
                  (data (i32.const 0) "x")
                  (data (i32.const 16) "y")
                  (func (export "run") (result i32)
                    (call $call
                      (i32.const 0) (i32.const 1)
                      (i32.const 16) (i32.const 1)
                      (i32.const 32) (i32.const 2))))
                "#,
            )?,
        )?;

        let mut store = Store::new(&engine, ());
        let instance = linker.instantiate(&mut store, &module)?;
        let run = instance.get_typed_func::<(), i32>(&mut store, "run")?;
        assert_eq!(run.call(&mut store, ())?, -2);

        Ok(())
    }

    #[test]
    fn generated_host_traps_when_void_handler_is_missing() -> Result<()> {
        let engine = Engine::default();
        let mut linker = Linker::<()>::new(&engine);
        let host = BoomslangHostHostFunctions::builder()
            .with_call(|_, _| Ok(String::new()))
            .build();
        host.register(&mut linker)?;

        let module = Module::new(
            &engine,
            wat::parse_str(
                r#"
                (module
                  (import "boomslang" "log"
                    (func $log (param i32 i32 i32)))
                  (memory (export "memory") 1)
                  (data (i32.const 0) "missing")
                  (func (export "run")
                    (call $log (i32.const 2) (i32.const 0) (i32.const 7))))
                "#,
            )?,
        )?;

        let mut store = Store::new(&engine, ());
        let instance = linker.instantiate(&mut store, &module)?;
        let run = instance.get_typed_func::<(), ()>(&mut store, "run")?;
        let error = run.call(&mut store, ()).unwrap_err();
        let message = format!("{error:#}");
        if !message.contains("No handler registered for host function boomslang::log") {
            bail!("unexpected trap message: {message}");
        }

        Ok(())
    }
}
