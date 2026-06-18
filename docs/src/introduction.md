# Introduction

Boomslang runs CPython 3.14 from a WASI build. The default artifact embeds that runtime in Java through [Chicory](https://github.com/dylibso/chicory), so Python runs inside the JVM without JNI, subprocesses, or a system Python install.

Python code executes in a fully sandboxed WebAssembly memory space: it sees only the filesystem you give it, calls only the host functions you register, and a misbehaving script cannot take down the JVM.

## What's in the box

The default Maven artifact ships with:

- CPython 3.14 built for `wasm32-wasip1`
- the Python stdlib plus NumPy, Pandas, Matplotlib, Pillow, Pydantic, ijson, and Jinja2
- `python/bin/boomslang.wasm`, the runtime module
- generated Chicory AOT classes, so the WASM runs as compiled JVM bytecode instead of being interpreted
- copy-on-write memory snapshots: the interpreter is pre-initialized at build time ([Wizer](https://github.com/bytecodealliance/wizer)), so creating a `PythonInstance` is a memory copy measured in milliseconds, not a full CPython startup
- `boomslang_host`, a small Python-side bridge for calling host functions

## Supported hosts

A *host* is the outside process embedding `boomslang.wasm`: it supplies the WASM runtime and implements imported host functions. (See the [glossary](reference/glossary.md) — "host" deliberately does not mean the Rust code inside the module.)

| Host language | Status | Runtime | Host adapter support |
| --- | --- | --- | --- |
| Java | Primary host | Chicory | Stock runtime API, `HostBridge`, generated Java adapters |
| Rust | Supported example host | Wasmtime | Generated Rust adapters; see `examples/rust-host/` |
| Other languages | ABI target | Any WASM runtime with compatible imports | Implement the ABI JSON contract directly |

## Where to go next

- [Quickstart](quickstart.md) — run Python from Java in five minutes
- [Installation & Runtime Variants](installation.md) — Maven coordinates and the `no-python-runtime` classifier
- [Glossary](reference/glossary.md) — host vs. guest, and what the directory names actually mean
