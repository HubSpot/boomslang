# Glossary

Boomslang sits at the intersection of three ecosystems (JVM, WebAssembly, CPython), and some names mean different things in each. This page is the tiebreaker.

| Term | Meaning |
| --- | --- |
| **host** | The outside process embedding `boomslang.wasm` and implementing its imports — the Java/Chicory runtime in the default setup, or Rust/Wasmtime in `examples/rust-host/`. |
| **guest** | Everything inside `boomslang.wasm`: the Rust glue code and the CPython interpreter it wraps. |
| **host function** | A function implemented by the *host* and imported by the *guest* — e.g. the Java handlers registered through `HostBridge`. This is Chicory/WASM terminology. |
| **`python-host/`** | Rust code compiled *into the guest*. The name is historical: this crate "hosts" CPython (via PyO3) inside the WASM module. It is not the WASM host. |
| **`python-host-core/`** | The reusable core of the guest, published as the crate `boomslang-host-core`. Custom Python builds compose this with their own extension crates. |
| **extension** | A set of typed host functions declared with the `boomslang-hostgen` DSL in a crate's `build.rs`. The guest half is generated Rust; the host half is a generated Java or Rust adapter. |
| **`boomslang`** (import module) | The WASM import namespace the guest expects its host functions under (e.g. `boomslang.call`, `boomslang.log`). |
| **`boomslang_host`** (Python module) | The Python-side bridge module available to guest code: `boomslang_host.call(...)`, `boomslang_host.log(...)`, `boomslang_host.asyncio`. |
| **golden snapshot** | The pre-initialized guest memory image produced by [Wizer](https://github.com/bytecodealliance/wizer) at build time. New `PythonInstance`s are copy-on-write views of it, which is why instance creation is milliseconds instead of a full CPython start. |
| **prewarm** | Importing a Python module during Wizer initialization so it is frozen into the golden snapshot. Prewarmed modules are served from the snapshot's `sys.modules` at runtime. |
| **AOT** | Chicory's ahead-of-time translation of the WASM module to JVM bytecode (`com.hubspot.boomslang.compiled.*`), avoiding interpretation overhead. |
| **stdlib path** | The host directory where the factory extracts packaged Python resources; instance roots are mounted from it. |
| **overlay** | Source-controlled files under `core/src/main/resources/python-overlay/` copied over the generated Python tree at extraction time. |
