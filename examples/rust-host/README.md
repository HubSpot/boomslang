# Rust Runtime Host Example

This example shows the other side of the extension ABI: a Rust program embedding a WASM runtime and registering host imports from a Boomslang ABI JSON file.

It is different from `examples/custom-host/`:

- `examples/custom-host/` builds a custom Rust/WASI Python host that runs inside WASM.
- `examples/rust-host/` is the outside runtime host. It reads `<extension>.abi.json` and wires typed imports into Wasmtime.

## Run The Example

```bash
cargo run --manifest-path examples/rust-host/Cargo.toml
```

By default it reads `examples/rust-host/abi/boomslang_host.abi.json`, registers `boomslang::call` and `boomslang::log`, and prints the imports it installed. Pass another ABI JSON path to register a custom extension:

```bash
cargo run --manifest-path examples/rust-host/Cargo.toml -- path/to/myext.abi.json
```

## What The ABI Drives

The ABI JSON decides the Wasmtime import signatures and memory lowering:

- `string` and `bytes` params lower to `i32 ptr, i32 len`.
- `int` params lower to `i32`.
- `float` params lower to `f64`.
- `string` and `bytes` returns use caller-provided `i32 result_ptr, i32 result_max_len` params and return the written byte length as `i32`.
- async functions return an `i64` host token.

The example handler registry is dynamic:

```rust
let mut handlers = HostHandlers::default();
handlers.insert("call", |args| {
    let [HostValue::String(name), HostValue::String(payload)] = args.as_slice() else {
        anyhow::bail!("invalid args");
    };
    Ok(Some(HostValue::String(format!("{name}: {payload}"))))
});
```

## Running A Real Boomslang WASM

`register_extension_imports` gives you the extension imports. For a full `boomslang.wasm` embedder, add WASI preview1 imports to the same Wasmtime `Linker`, instantiate the module, then call Boomslang's exported `alloc`, `execute`, `compile_source`, and output-buffer functions.

This example keeps that WASI setup out of the critical path so the ABI-to-runtime wiring stays small and testable.
