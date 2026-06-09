# Rust Runtime Host Example

This example shows the other side of the extension ABI: a Rust program embedding a WASM runtime with typed host imports generated from a Boomslang ABI JSON file.

It is different from `examples/custom-host/`:

- `examples/custom-host/` builds a custom Rust/WASI Python host that runs inside WASM.
- `examples/rust-host/` is the outside runtime host. Its `build.rs` turns `<extension>.abi.json` into typed Wasmtime bindings.

## Run The Example

```bash
cargo run --manifest-path examples/rust-host/Cargo.toml
```

By default `build.rs` reads `examples/rust-host/abi/boomslang_host.abi.json`, generates `host_boomslang_host.rs` into `OUT_DIR`, registers `boomslang::call` and `boomslang::log`, and prints the imports it installed.

The same generation can be run manually through the CLI for another ABI JSON file:

```bash
cargo run --manifest-path boomslang-hostgen/Cargo.toml -- \
  path/to/myext.abi.json \
  --rust-host-out path/to/generated
```

## What The ABI Drives

The ABI JSON decides the Wasmtime import signatures and memory lowering:

- `string` and `bytes` params lower to `i32 ptr, i32 len`.
- `int` params lower to `i32`.
- `float` params lower to `f64`.
- `string` and `bytes` returns use caller-provided `i32 result_ptr, i32 result_max_len` params and return the written byte length as `i32`.
- async functions return an `i64` host token.

The generated host binding is typed:

```rust
let host = BoomslangHostHostFunctions::builder()
    .with_call(|name, payload| Ok(format!("{name}: {payload}")))
    .with_log(|level, message| {
        eprintln!("[guest log:{level}] {message}");
        Ok(())
    })
    .build();

host.register(&mut linker)?;
```

## Async Host Calls

Generated Rust host bindings include a minimal `AsyncHostRegistry`. Typed async imports should return a token from that registry, and the stock `boomslang_host.call` handler should route reserved `__async_*` calls through the same registry:

```rust
let async_registry = AsyncHostRegistry::default();
async_registry.register_blocking_handler("rpc", |_, payload| {
    Ok(format!("done: {payload}"))
})?;

let registry_for_call = async_registry.clone();
let host = BoomslangHostHostFunctions::builder()
    .with_call(move |name, payload| {
        registry_for_call.handle_call_or(name, payload, |name, payload| {
            Ok(format!("{name}: {payload}"))
        })
    })
    .with_log(|level, message| {
        eprintln!("[guest log:{level}] {message}");
        Ok(())
    })
    .build();
```

For a generated typed async function, return `async_registry.start_completed(...)`, `async_registry.start_failed(...)`, or `async_registry.start_blocking(...)` from its handler. The Python side awaits that token through `boomslang_host.asyncio.from_host_token(...)`; completions are delivered by `__async_poll__`, `__async_result__`, and `__async_cancel__` over the stock call bridge.

## Running A Real Boomslang WASM

The generated `register` method gives you the extension imports. For a full `boomslang.wasm` embedder, add WASI preview1 imports to the same Wasmtime `Linker`, instantiate the module, then call Boomslang's exported `alloc`, `execute`, `compile_source`, and output-buffer functions.

This example keeps that WASI setup out of the critical path so the ABI-to-runtime wiring stays small and testable.
