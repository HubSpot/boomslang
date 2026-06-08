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

Rust host generation currently supports synchronous imports only. ABI JSON with async functions is rejected because the Rust adapter does not yet generate the `__async_poll__`, `__async_result__`, and `__async_cancel__` control imports needed to complete Boomslang awaitables. Use the generated Java host adapter for async extensions for now.

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

## Running A Real Boomslang WASM

The generated `register` method gives you the extension imports. For a full `boomslang.wasm` embedder, add WASI preview1 imports to the same Wasmtime `Linker`, instantiate the module, then call Boomslang's exported `alloc`, `execute`, `compile_source`, and output-buffer functions.

This example keeps that WASI setup out of the critical path so the ABI-to-runtime wiring stays small and testable.
