# Embedding from Rust (and Other Hosts)

The extension ABI is not tied to Java. An extension declares its contract in `build.rs` with the `boomslang-hostgen` DSL and emits ABI JSON; host-language adapters are generated from that JSON. Any WASM runtime that implements the same imports can run `boomslang.wasm`.

| Host language | Status | Runtime | Host adapter support |
| --- | --- | --- | --- |
| Java | Primary host | Chicory | Stock runtime API, `HostBridge`, generated Java adapters |
| Python | Supported host package | Wasmtime (wasmtime-py) | [`boomslang-py` wheel](python-host.md) with the `Sandbox` API |
| Rust | Supported example host | Wasmtime | Generated Rust adapters; see below |
| Other languages | ABI target | Any WASM runtime with compatible imports | Implement the ABI JSON contract directly |

## The Rust example host

[`examples/rust-host/`](https://github.com/HubSpot/boomslang/tree/main/examples/rust-host) is a runnable Wasmtime embedder. Its `build.rs` turns an `<extension>.abi.json` into typed Wasmtime bindings:

```bash
cargo run --manifest-path examples/rust-host/Cargo.toml
```

The generated binding is a typed builder plus a `register(&mut wasmtime::Linker<_>)`:

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

Generated Rust host bindings also include an `AsyncHostRegistry` mirroring the Java one: typed async imports return registry tokens, and the stock `call` handler routes the reserved `__async_*` control calls through the same registry.

## Embedding a full boomslang.wasm

The generated `register` covers the extension imports. A complete embedder additionally:

1. adds WASI preview1 imports to the same `Linker`
2. instantiates the module
3. drives execution through boomslang's exported functions (`alloc`, `compile_source`, `execute`, and the output-buffer protocol)

The exported function contract is specified in the reference section (base ABI). Runtime assets (`boomslang.wasm` + the Python resource tree) are published on [GitHub Releases](https://github.com/HubSpot/boomslang/releases) — see [Installation](../installation.md#runtime-assets-outside-maven).
