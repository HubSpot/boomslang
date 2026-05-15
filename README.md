# boomslang

Run CPython 3.14 from Java through WebAssembly using Chicory. No JNI, native process, or system Python is required at runtime.

## What ships in the runtime

- CPython 3.14 built for `wasm32-wasip1`
- stdlib plus NumPy, Pandas, Matplotlib, Pydantic, ijson, and Jinja2
- Chicory AOT classes for the bundled `boomslang.wasm`
- Copy-on-write memory snapshots for fast `PythonInstance` creation
- Built-in `boomslang_host` bridge for calling Java functions from Python

## Java usage

Add the normal artifact when you want the bundled Python runtime:

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>${boomslang.version}</version>
</dependency>
```

Create a factory once, then create/reset instances for work. The stdlib path is a host directory where boomslang extracts packaged Python resources. The instance root is the filesystem visible to Python as `/`.

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

Use `runOnWasmThread` for Python execution. It runs with a larger stack than a normal JVM thread and supports timeouts:

```java
PythonInstance instance = factory.createInstance(pythonRoot);
PythonResult result = factory.runOnWasmThread(
  () -> instance.execute("print(sum(range(10)))"),
  Duration.ofSeconds(5),
  instance
);
```

If a timeout fires, the instance is poisoned. Call `reset()` before reuse or discard it.

## Supported Python libraries

These imports are expected to work from the bundled runtime:

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

## Calling Java from Python

The stock host includes `boomslang_host.call(name, args)` and `boomslang_host.log(level, message)`.

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

For typed WASM imports or custom Python modules, build a custom host. See `examples/custom-host/`.

## Adding pure-Python modules

Install small in-memory packages at factory creation:

```java
PythonExecutorFactory factory = PythonExecutorFactory
  .builder()
  .withStdlibPath(pythonRoot)
  .withModule("my_package", "helpers", "def double(x): return x * 2")
  .build();
```

For larger packages, build them into the WASM/Python resource pipeline instead.

## Runtime variants

Use the default artifact for the stock runtime:

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>${boomslang.version}</version>
</dependency>
```

It includes the Java API, `python/bin/boomslang.wasm`, Python resources, and generated Chicory AOT classes.

Use `no-python-runtime` when another artifact or application layer provides the Python runtime:

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>${boomslang.version}</version>
  <classifier>no-python-runtime</classifier>
</dependency>
```

That classifier excludes `python/**` and `com/hubspot/boomslang/compiled/**`. It still gives you the Java API. Your app must provide:

- a WASM binary, usually at `python/bin/boomslang.wasm`
- Python resources under `python/usr/local/lib/python3.14`
- an AOT machine factory if you want AOT instead of interpreter fallback

If your WASM is not at the default classpath location, set it with `withWasmResource(...)`.

## Custom host builds

Build a custom host when the stock `boomslang_host.call(...)` bridge is not enough. Common reasons:

- typed WASM imports instead of string/JSON calls
- custom host functions exposed as Python modules
- extra Python modules prewarmed into the Wizer snapshot

Start from `examples/custom-host/`. The flow is:

1. Define an extension contract in `extension.toml`.
2. Use `boomslang-hostgen` to generate Rust and Java bridge code.
3. Compose the extension with `python-host-core` in a custom Rust host.
4. Build the host to `wasm32-wasip1`.
5. Package the custom `boomslang.wasm` and matching Python resources in your app or artifact.
6. Depend on `com.hubspot:boomslang:no-python-runtime` for the Java API.

Minimal build command from the example:

```bash
export CPYTHON_WASI_DIR=../../cpython/build/cpython-wasi
cargo build --target wasm32-wasip1 --release
```

For the stock repo build that produces the bundled runtime, use `just wasm` and `just resources`.

## Building this repo

Requirements: Java 21, Maven, `just`, and either Docker or Apple `container`.

```bash
just everything
```

That builds the native WASM artifacts, Rust host, Python resources, Java AOT classes, and Maven packages. First runs take about an hour because the CPython and library builds are container-heavy.

Use Apple container instead of Docker:

```bash
container system start
BOOMSLANG_CONTAINER_CLI=container just everything
```

Common local workflows:

```bash
just build     # Maven package with AOT, skips tests
just test      # tests module
mvn compile -pl core
mvn test -pl tests
```

After Rust or host changes:

```bash
just wasm
just resources
just build
just test
```

Full pipeline stages:

```bash
just build-pydantic-core-wasi
just build-numpy-wasi
just build-pandas-wasi
just build-matplotlib-wasi
just build-ijson-wasi
just build-cpython-wasi
just pip-packages
just wasm
just resources
just build
just test
```

## Repo map

- `core/` — Java runtime API and bundled Python resources
- `tests/` — integration tests
- `benchmarks/` — JMH benchmarks
- `python-host/` — stock Rust WASM host
- `python-host-core/` — reusable Rust host core
- `extensions/` — built-in host extensions
- `boomslang-hostgen/` — extension code generator
- `examples/custom-host/` — custom host example
- `cpython/` — CPython, native library, and container build pipeline

## License

Apache 2.0
