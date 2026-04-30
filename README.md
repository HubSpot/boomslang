# boomslang

Run Python 3.14 from Java, safely sandboxed via WebAssembly.

boomslang compiles CPython to [WebAssembly](https://webassembly.org/) and executes it through [Chicory](https://github.com/dylibso/chicory), a pure-Java WASM runtime. No native code, no JNI, no subprocess — Python runs as JVM bytecode with full memory isolation.

## What's included

- **CPython 3.14** compiled to `wasm32-wasip1`
- **NumPy**, **Pandas**, **Matplotlib**, **Pydantic** statically linked
- **Copy-on-write memory** for fast instance creation (<1ms)
- **Wizer pre-initialization** — Python interpreter + all libraries pre-imported at build time
- **AOT compilation** — WASM compiled to JVM bytecode via Chicory for near-native speed

## Usage

```java
var factory = PythonExecutorFactory.builder().build();

PythonResult result = factory.runOnWasmThread(() -> {
    PythonInstance instance = factory.createInstance();
    return instance.execute("print('hello from Python')");
});

System.out.println(result.stdout()); // "hello from Python"
```

### NumPy, Pandas, Pydantic — they just work

```java
instance.execute("""
    import numpy as np
    import pandas as pd
    from pydantic import BaseModel

    data = np.random.randn(100)
    df = pd.DataFrame({'values': data})
    print(df.describe())
    """);
```

### Compile once, run many times

```java
byte[] bytecode = instance.compile(sourceCode);

// Each call reuses the compiled bytecode — no re-parse overhead
PythonResult r1 = instance.loadCode(bytecode);
instance.reset();
PythonResult r2 = instance.loadCode(bytecode);
```

### Call Java from Python

boomslang includes a built-in host bridge for calling Java functions from Python:

```java
var factory = PythonExecutorFactory.builder()
    .addHostFunctions(
        HostBridge.builder()
            .withFunction("lookup_user", args -> {
                String userId = args;
                return userService.findById(userId).toJson();
            })
            .withLogHandler((level, msg) -> LOG.info("[Python] {}", msg))
            .build())
    .build();
```

```python
from boomslang_host import call, log

user_json = call("lookup_user", "12345")
log(2, "got user data")
```

## Building from source

Requires: Docker, Java 21+, Maven.

```bash
# Full build from scratch (~1 hour first time, Docker caches subsequent runs)
just everything

# Or step by step:
just build-pydantic-core-wasi   # Build pydantic-core static lib
just build-numpy-wasi           # Build NumPy C extensions
just build-pandas-wasi          # Build Pandas C extensions
just build-matplotlib-wasi      # Build Matplotlib C extensions
just build-cpython-wasi         # Compile CPython, merge all libraries
just pip-packages               # Download Pydantic Python package
just wasm                       # Build Rust host + Wizer pre-init
just resources                  # Populate Java resources
just build                      # Maven build with AOT compilation
just test                       # Run tests
```

All native compilation happens inside Docker — no local Rust, WASI SDK, or C toolchain needed.

## How it works

```
Python source code
        │
        ▼
┌─────────────────┐
│  PythonInstance  │  Java API
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│    Chicory       │  Pure-Java WASM runtime (AOT-compiled to JVM bytecode)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  CPython 3.14   │  Full interpreter + stdlib + numpy/pandas/pydantic
│  (wasm32-wasi)  │  Pre-initialized via Wizer snapshot
└─────────────────┘
```

Each `PythonInstance` starts from a memory snapshot of an already-initialized interpreter with all libraries imported. Instance creation costs <1ms thanks to copy-on-write memory pages — only pages that Python modifies during execution are allocated.

## Project structure

```
boomslang/
├── core/              Java runtime
├── python-host/       Rust WASM host (PyO3 + CPython)
├── cpython/           Native build infrastructure
│   ├── cpython-wasi/      CPython → WASM
│   ├── pydantic-core-wasi/
│   ├── numpy-wasi/
│   ├── pandas-wasi/
│   ├── matplotlib-wasi/
│   └── builder/           Docker image with build tools
├── extensions/        Host function extensions
├── boomslang-hostgen/      Extension code generator
├── tests/
└── benchmarks/
```

## License

Apache 2.0
