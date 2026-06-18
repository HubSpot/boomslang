# Architecture

How a `print('hello')` gets executed, from the bottom up. Terms used precisely here are defined in the [glossary](../reference/glossary.md) — in particular *host* (the JVM/Wasmtime side) vs. *guest* (everything inside `boomslang.wasm`).

## The guest: CPython on WASI

CPython 3.14 is compiled to `wasm32-wasip1` with native extension modules (NumPy, Pandas, Matplotlib, Pillow, ijson, pydantic-core) **statically linked** — WASI has no dynamic linking. A thin Rust layer (`python-host-core/`, composed into the stock guest by `python-host/`) wraps the interpreter with PyO3 and exposes the [base ABI](../reference/abi.md): `execute`, `compile_source`, the output-buffer protocol, and friends.

Extensions add typed WASM imports: each is declared with the [hostgen DSL](../reference/hostgen-dsl.md), which generates the guest-side Rust (a Python module backed by WASM imports) and the host-side adapters from a shared [ABI JSON](../reference/extension-abi.md).

## The golden snapshot (Wizer)

Starting CPython — initializing the interpreter, importing NumPy and Pandas — takes seconds. Boomslang does it **once, at build time**: [Wizer](https://github.com/bytecodealliance/wizer) runs the guest's initialization (including importing every `prewarm` module) and snapshots the resulting linear memory into the module itself. The shipped `boomslang.wasm` wakes up already initialized.

Consequence worth knowing: prewarmed modules live in the snapshot's `sys.modules`. Changing their source on disk (e.g. via the resource overlay) has no effect until the WASM is rebuilt.

## Copy-on-write instances

At runtime the Java host goes one step further. `RuntimeImage` instantiates the module once and keeps its post-initialization memory as the shared **golden memory**. Each `PythonInstance` gets a `CopyOnWriteMemory`: reads are served from the shared golden pages; writes materialize private copies of just the touched 64 KiB pages.

- Creating an instance is O(1) — no memory copy up front.
- Instances are isolated: one instance's writes never affect another's.
- `reset()` discards the private pages, snapping the instance back to the pristine snapshot.

This is why the factory should be a process-wide singleton (it pins the golden memory) while instances are disposable.

## AOT execution (Chicory)

Chicory can interpret WASM, but boomslang ships ahead-of-time compiled JVM bytecode for the bundled module: the `chicory-compiler-maven-plugin` translates `boomslang.wasm` into `com.hubspot.boomslang.compiled.*` classes at build time. The factory uses them when present (`isAotAvailable()`) and falls back to the interpreter otherwise — roughly an order of magnitude slower, so a missing-AOT warning in logs deserves attention.

Python is thus executing as JVM bytecode, JIT-compiled by HotSpot like everything else — no JNI, no native memory outside the Java heap.

## Execution flow

```text
Java caller
  └─ factory.runOnWasmThread(...)           dedicated thread, enlarged JVM stack
       └─ instance.execute(src)             PythonInstance, one per context
            ├─ alloc/write/execute          base ABI calls into the guest
            │    └─ PyO3 → CPython          runs the script
            │         └─ import boomslang_host → WASM imports → Java HostBridge handlers
            └─ get_stdout/get_stderr        captured output back to Java
```

Host functions are the only way out of the sandbox: the guest sees WASI (rooted at the instance directory) plus exactly the imports you registered.

## The build pipeline

```text
cpython/
  pydantic-core-wasi ─┐
  numpy-wasi ─────────┤
  pandas-wasi ────────┤
  matplotlib-wasi ────┼→ cpython-wasi → python-host (Rust guest) → Wizer → Java AOT
  pillow-wasi ────────┤        (containers)            (just wasm)          (mvn)
  ijson-wasi ─────────┘
```

Native components build in containers (`cpython/builder/` provides the WASI SDK + Wizer + Binaryen image); Mill orchestrates the DAG and caches each stage. See [Building from Source](building.md).
