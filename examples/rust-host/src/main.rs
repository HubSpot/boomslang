use anyhow::Result;
use wasmtime::{Engine, Linker};

mod generated {
    include!(concat!(env!("OUT_DIR"), "/host_boomslang_host.rs"));
}

use generated::BoomslangHostHostFunctions;

fn build_host() -> BoomslangHostHostFunctions {
    BoomslangHostHostFunctions::builder()
        .with_call(|name, payload| Ok(format!("rust handler received {name}({payload})")))
        .with_log(|level, message| {
            eprintln!("[guest log:{level}] {message}");
            Ok(())
        })
        .build()
}

fn main() -> Result<()> {
    let engine = Engine::default();
    let mut linker = Linker::<()>::new(&engine);
    let imports = build_host().register(&mut linker)?;

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
    use wasmtime::{Module, Store};

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
