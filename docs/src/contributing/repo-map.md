# Repository Map

| Path | What it is |
| --- | --- |
| `core/` | Java runtime API (`PythonExecutorFactory`, `PythonInstance`, `HostBridge`, `CopyOnWriteMemory`) and the bundled Python resources. |
| `boomslang-py/` | Python host package: `Sandbox` API over wasmtime-py, shipped as a wheel bundling the WASM runtime (GitHub release asset). |
| `python-host/` | The stock Rust **guest** crate — composes `boomslang-host-core` with the built-in `host-bridge` extension and builds to `boomslang.wasm`. (Named for hosting CPython, not for being the WASM host; see the [glossary](../reference/glossary.md).) |
| `python-host-core/` | Reusable guest core (crate `boomslang-host-core`): PyO3 wrapper, base ABI exports, init plumbing for extensions. |
| `extensions/` | Extension crates. `host-bridge/` is the built-in `boomslang_host.call`/`log` bridge, including the `boomslang_host` Python package. |
| `boomslang-hostgen/` | The extension code generator: Rust DSL library + CLI, templates for guest Rust, Java adapters, and Rust/Wasmtime adapters. |
| `cpython/` | Native build pipeline: one `*-wasi/` directory per native component (CPython itself, NumPy, Pandas, Matplotlib, Pillow, ijson, pydantic-core) and `builder/`, the shared container image (WASI SDK + Wizer + Binaryen + Rust). |
| `examples/custom-python-build/` | Building a custom guest with your own typed extensions. |
| `examples/rust-host/` | Embedding from Rust/Wasmtime with adapters generated from ABI JSON. |
| `tests/` | Java integration tests (run against the packaged runtime). |
| `benchmarks/` | JMH benchmarks. |
| `docs/` | This book (mdBook). |
| `build.mill`, `justfile` | Build orchestration: Mill is the engine, `just` is the shim for common loops. |
| `scripts/` | Build support scripts, including `fetch-main-runtime-resources.sh`. |
