# boomslang

boomslang runs CPython 3.14 from a WASI build. The default distribution embeds it in Java with Chicory, so Python execution stays inside the JVM and callers do not need JNI, subprocess management, or a system Python install.

## Bundled runtime

The default artifact includes:

- CPython 3.14 built for `wasm32-wasip1`
- Python stdlib plus NumPy, Pandas, Matplotlib, Pydantic, ijson, and Jinja2
- `python/bin/boomslang.wasm`
- generated Chicory AOT classes for the bundled WASM
- copy-on-write memory snapshots for fast `PythonInstance` creation
- `boomslang_host`, a small bridge for calling host functions from Python

## Supported host languages

A host language is the language embedding `boomslang.wasm`, providing a WASM runtime, and implementing any imported host functions. This is separate from the Rust/WASI code that builds the Python runtime that runs inside WASM.

The extension ABI is language-neutral: an extension crate defines its contract with the `boomslang-hostgen` Rust DSL, emits ABI JSON, and host-language adapters are generated from that ABI JSON.

| Host language | Status | Runtime | Host adapter support |
| --- | --- | --- | --- |
| Java | Primary supported host | Chicory | Stock runtime API, `HostBridge`, generated Java adapters with `--java-out` or `emit_java_host(...)` |
| Rust | Supported example host | Wasmtime | Generated Rust adapters with `--rust-host-out` or `emit_rust_host(...)`; see `examples/rust-host/` |
| Other languages | ABI target only | Any WASM runtime with compatible imports | Use the ABI JSON to implement the same pointer/length lowering and return-buffer protocol |

The default Maven artifact is Java-first and includes the bundled runtime. Rust runtime-host support is useful for embedders that want to run the same Boomslang WASM runtime from a Rust process.

## Java host usage

Use the default artifact when you want the bundled Python runtime:

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>${boomslang.version}</version>
</dependency>
```

Create one factory and reuse it. The stdlib path is a host directory where boomslang extracts packaged Python resources. The instance root is the filesystem visible to Python as `/`.

```java
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.file.Files;
import java.nio.file.Path;

Path pythonRoot = Files.createTempDirectory("boomslang-python");
PythonExecutorFactory factory = PythonExecutorFactory
  .builder()
  .withStdlibPath(pythonRoot)
  .build();

PythonResult result = factory.runOnWasmThread(() -> {
  PythonInstance instance = factory.createInstance(pythonRoot);
  return instance.execute("print('hello from Python')");
});

System.out.println(result.stdout());
```

Run Python work through `runOnWasmThread`. It uses a larger JVM stack and supports timeouts:

```java
PythonInstance instance = factory.createInstance(pythonRoot);
PythonResult result = factory.runOnWasmThread(
  () -> instance.execute("print(sum(range(10)))"),
  Duration.ofSeconds(5),
  instance
);
```

If execution times out, the instance is poisoned. Call `reset()` before reusing it, or discard it.

## Supported Python libraries

These imports are expected to work with the bundled runtime:

```python
import ijson
import jinja2
import matplotlib
import numpy as np
import pandas as pd
from pydantic import BaseModel
```

## Reusing compiled Python code

Use `compile` and `loadCode` when the same source runs many times:

```java
PythonInstance instance = factory.createInstance(pythonRoot);
byte[] bytecode = instance.compile(sourceCode);

PythonResult first = instance.loadCode(bytecode);
instance.reset();
PythonResult second = instance.loadCode(bytecode);
```

## Calling Java host functions from Python

The stock host exposes `boomslang_host.call(name, args)` and `boomslang_host.log(level, message)`.

```java
PythonExecutorFactory factory = PythonExecutorFactory
  .builder()
  .withStdlibPath(pythonRoot)
  .addExtension(
    HostBridge
      .builder()
      .withFunction("lookup_user", userId -> userService.findById(userId).toJson())
      .withLogHandler((level, message) -> LOG.info("[Python] {}", message))
      .buildExtension()
  )
  .build();
```

```python
from boomslang_host import call, log

user_json = call("lookup_user", "12345")
log(2, "loaded user")
```

Use a custom extension if you need typed WASM imports or custom Python modules. See `examples/custom-host/`.

## Adding pure-Python modules

Install small in-memory packages at factory creation:

```java
PythonExecutorFactory factory = PythonExecutorFactory
  .builder()
  .withStdlibPath(pythonRoot)
  .withModule("my_package", "helpers", "def double(x): return x * 2")
  .build();
```

Build larger packages into the WASM/Python resource pipeline instead.

## Python resource overlay

Most packaged Python runtime files under `core/src/main/resources/python/bin/` and `core/src/main/resources/python/usr/` are generated by the WASM/CPython resource pipeline and are intentionally ignored by Git. Small source-controlled Python additions that should ship with the stock runtime live under `core/src/main/resources/python-overlay/` instead.

The overlay mirrors the final guest filesystem layout. During `PythonExecutorFactory` creation, boomslang extracts the generated `python/` resources first, then copies `python-overlay/` on top of that tree. For example:

```text
core/src/main/resources/python-overlay/usr/local/lib/python3.14/boomslang_host/asyncio.py
```

is copied to:

```text
<stdlibPath>/usr/local/lib/python3.14/boomslang_host/asyncio.py
```

Use the overlay for small tracked Python helper modules or patches that should not require rebuilding the generated CPython resource tree. Larger third-party packages should still go through the WASM/Python resource pipeline.

## Runtime variants

### Default artifact

Use `com.hubspot:boomslang` for the stock runtime. It includes the Java API, bundled WASM, Python resources, and generated Chicory AOT classes.

### `no-python-runtime`

Use the `no-python-runtime` classifier when your app or another artifact provides the Python runtime:

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>${boomslang.version}</version>
  <classifier>no-python-runtime</classifier>
</dependency>
```

This classifier excludes `python/**` and `com/hubspot/boomslang/compiled/**`. It still includes the Java API.

Your app must provide:

- a WASM binary, usually at `python/bin/boomslang.wasm`
- Python resources under `python/usr/local/lib/python3.14`
- an AOT machine factory if you want AOT instead of interpreter fallback

If your WASM is not at the default classpath location, set it with `withWasmResource(...)`.

## Custom WASM host builds

Build a custom WASM host when the stock `boomslang_host.call(...)` bridge is not enough. This changes the Rust/WASI Python runtime that runs inside WASM. It is independent of whether the outside runtime host is Java, Rust, or another language.

Custom WASM hosts let you change the Rust host crate, add guest extensions, prewarm modules, and statically link additional native libraries into the WASI binary.

WASI does not support dynamic linking in this runtime. Any native code needed by Python extensions must be statically linked into the host build.

Use a custom WASM host for:

- typed WASM imports instead of string/JSON calls
- host functions exposed as custom Python modules
- extra Python modules prewarmed into the Wizer snapshot
- native libraries required by Python extensions

Start from `examples/custom-host/`. The flow is:

1. Define an extension contract in the extension crate's `build.rs` with the `boomslang-hostgen` Rust DSL.
2. Have `boomslang-hostgen` emit Rust guest code and an ABI JSON file.
3. Generate host-language bridge code from that ABI JSON when the runtime host needs typed adapters.
4. Compose the extension with `python-host-core` in a custom Rust host.
5. Add any required native libraries to the WASI build as static libraries.
6. Build the host to `wasm32-wasip1`.
7. Package the custom `boomslang.wasm` and matching Python resources in your app or artifact.
8. For Java packaging, depend on `com.hubspot:boomslang:no-python-runtime`.

Minimal build command from the example:

```bash
export CPYTHON_WASI_DIR=../../cpython/build/cpython-wasi
cargo build --target wasm32-wasip1 --release
```

For the stock repo build that produces the bundled runtime, use `just wasm` and `just resources`.

For a Rust embedder that generates Wasmtime host bindings from ABI JSON, see `examples/rust-host/`.

## Building this repo

Requirements: Java 21, Maven, `just`, and Docker on Linux. Apple `container` is also supported on macOS.

With Nix, use the project dev shell:

```bash
nix develop
```

The dev shell provides Java 21, Maven, `just`, Python 3, and the Maven JDK toolchain configuration required by basepom. Docker or Apple `container` still needs to be installed and running on the host for the full WASM pipeline.

```bash
./mill artifacts.installAll
./mill build
```

That builds the native WASM artifacts, Rust host, Python resources, Java AOT classes, and Maven packages. First runs take about an hour because the CPython and library builds are container-heavy.

The selected container engine is stored in the ignored `.boomslang-container-cli` file so Mill daemon builds see a stable input. The `./mill` wrapper also writes that file when `BOOMSLANG_CONTAINER_CLI` is set.

Docker is the default container engine. To be explicit on Linux:

```bash
./mill artifacts.setContainerCli --cli docker
./mill artifacts.showContainerCli
./mill artifacts.installAll
./mill build
```

Docker builds require BuildKit/buildx because the Dockerfiles use BuildKit syntax and automatic target architecture args.

Use Apple container instead of Docker:

```bash
container system start
./mill artifacts.setContainerCli --cli container
./mill artifacts.showContainerCli
./mill artifacts.installAll
./mill build
```

Common local workflows:

```bash
just fetch-main-wasm  # download latest main runtime resources from GitHub release assets
just build            # package with AOT, skips tests
just test             # tests module
mvn compile -pl core
mvn test -pl tests
```

`just fetch-main-wasm` installs the latest successful `main` runtime artifact published as a GitHub release asset into `core/src/main/resources/python/bin/` and `core/src/main/resources/python/usr/`. Use it when you want a fast local Java build without rebuilding the full WASM/CPython pipeline. To fetch a specific artifact, pass through selectors:

```bash
just fetch-main-wasm -- --branch main
just fetch-main-wasm -- --sha <commit-sha>
```

After Rust or host changes:

```bash
just wasm
just resources
just build
just test
```

Artifact DAG and cache inspection:

```bash
./mill artifacts.dag
./mill artifacts.dagDot
./mill artifacts.cacheStatus
./mill path artifacts.installAll artifacts.wasm
```

`./mill plan artifacts.installAll` prints execution order only. To check caching behavior, run `./mill artifacts.installAll` twice; the second run should skip task bodies and finish much faster.

Full pipeline stages:

```bash
just build-pydantic-core-wasi
just build-numpy-wasi
just build-pandas-wasi
just build-matplotlib-wasi
just build-pillow-wasi
just build-ijson-wasi
just build-cpython-wasi
just pip-packages
just wasm
just resources
just build
just test
```

## Repo map

- `core/`: Java runtime API and bundled Python resources
- `tests/`: integration tests
- `benchmarks/`: JMH benchmarks
- `python-host/`: stock Rust WASM host
- `python-host-core/`: reusable Rust host core
- `extensions/`: built-in host extensions
- `boomslang-hostgen/`: extension code generator
- `examples/custom-host/`: custom host example
- `examples/rust-host/`: Rust runtime host example with ABI JSON to Wasmtime hostgen
- `cpython/`: CPython, native library, and container build pipeline

## License

Apache 2.0
