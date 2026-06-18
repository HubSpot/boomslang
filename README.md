# boomslang

Python, but Java.

Boomslang runs CPython 3.14 from a WASI build. The default artifact embeds that runtime in Java through [Chicory](https://github.com/dylibso/chicory), so Python runs inside the JVM without JNI, subprocesses, or a system Python install — sandboxed, with the stdlib plus NumPy, Pandas, Matplotlib, Pillow, Pydantic, ijson, and Jinja2 included. The same runtime is also packaged as a Python wheel (`boomslang-py/`, executed with wasmtime) and embeddable from Rust or any WASM runtime via a language-neutral ABI.

**Documentation: <https://github.hubspot.com/boomslang/>**

## Quickstart (Java)

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>0.1.1</version>
</dependency>
```

```java
Path pythonRoot = Files.createTempDirectory("boomslang-python");
PythonExecutorFactory factory = PythonExecutorFactory
  .builder()
  .withStdlibPath(pythonRoot)
  .addExtension(HostBridge.builder().buildExtension())
  .build();

PythonResult result = factory.runOnWasmThread(() -> {
  PythonInstance instance = factory.createInstance(pythonRoot);
  return instance.execute("print('hello from Python')");
});

System.out.println(result.stdout());
```

## Quickstart (Python)

Sandboxed Python from Python — wheels are attached to [GitHub releases](https://github.com/HubSpot/boomslang/releases):

```python
from boomslang import Sandbox

with Sandbox() as sandbox:
    result = sandbox.execute("print('hello from the sandbox')")
    print(result.stdout)
```

See the [Quickstart](https://github.hubspot.com/boomslang/quickstart.html) for a walkthrough, the [User Guide](https://github.hubspot.com/boomslang/guide/running-python.html) for timeouts, host functions, async, the Python host, and custom runtimes, and [Installation](https://github.hubspot.com/boomslang/installation.html) for the slim `no-python-runtime` variant. The Python package is documented in [`boomslang-py/README.md`](boomslang-py/README.md).

## Building this repo

The full pipeline (CPython → WASM in containers, Rust guest, Java AOT) is driven by Mill, with a `just` shim for common loops:

```bash
nix develop            # Java 21, Maven, just, mdBook, WASI toolchain
just fetch-main-wasm   # prebuilt runtime resources from GitHub Releases (skips the ~1hr container build)
just build             # Maven package with AOT
just test              # integration tests
just python-test       # boomslang-py package tests (staged resources + venv + pytest)
```

Building everything from source: `./mill artifacts.installAll && ./mill build`. Details, the artifact DAG, container-engine selection (Docker / Apple container), and the Rust change loop are in the [contributor docs](https://github.hubspot.com/boomslang/contributing/building.html).

## Repo map

- `core/` — Java runtime API and bundled Python resources
- `boomslang-py/` — Python host package (Sandbox API, wheel bundling the WASM runtime)
- `python-host/`, `python-host-core/` — Rust code compiled into the WASM guest
- `extensions/` — built-in host extensions
- `boomslang-hostgen/` — extension code generator (Rust DSL + CLI)
- `examples/` — custom Python build and Rust/Wasmtime host examples
- `cpython/` — CPython, native library, and container build pipeline
- `tests/`, `benchmarks/` — integration tests and JMH benchmarks

## License

Apache 2.0
