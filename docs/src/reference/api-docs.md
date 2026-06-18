# API Docs

## Java

Javadoc for the published artifact is served by javadoc.io, generated from the `-javadoc` jar that ships to Maven Central with every release:

**<https://javadoc.io/doc/com.hubspot/boomslang>**

Key entry points:

- `PythonExecutorFactory` — build one per process; creates instances and owns the WASM thread pool.
- `PythonInstance` — a single execution context: `execute`, `compile`/`loadCode`, `reset`.
- `PythonResult` — captured stdout/stderr, exit code, timing.
- `HostBridge` — register Java handlers callable from Python.
- `AsyncHostRegistry` — broker for async host calls.
- `ResourceLimits` — per-instance output/memory caps.

## Rust

Rustdoc for `boomslang-hostgen` (the extension DSL and codegen library) is built in CI and published with this site at [`/api/rust/`](https://github.hubspot.com/boomslang/api/rust/boomslang_hostgen/).

The guest crates (`boomslang-host-core`, `python-host`) require the WASI/PyO3 build environment and are not yet on the docs site; read them in the repo.
