# Running Python from Java

Create one `PythonExecutorFactory` and reuse it for the life of your application — it holds the pre-initialized interpreter snapshot. Create a `PythonInstance` per execution context; instances are cheap (a copy-on-write view of the snapshot).

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

- **`withStdlibPath`** is a host directory where boomslang extracts the packaged Python resources. The instance root passed to `createInstance` is what Python sees as `/`.
- **`addExtension(HostBridge...)`** is required with the bundled runtime — its WASM unconditionally imports the `boomslang.call` / `boomslang.log` host functions. See [Calling host functions](host-functions.md).
- **`runOnWasmThread`** runs the work on a dedicated WASM thread with a larger JVM stack. Always run Python work through it; see [Lifecycle, timeouts & limits](lifecycle.md) for the threading model and timeout semantics.

## Results and errors

`PythonResult` carries `stdout()`, `stderr()`, `exitCode()`, and `executionTimeMs()`. A Python exception does **not** throw on the Java side — it produces a result with a non-zero exit code and the traceback in `stderr`:

```java
PythonResult result = factory.runOnWasmThread(() ->
  factory.createInstance(pythonRoot).execute("1 / 0")
);
// result.exitCode() != 0; result.stderr() contains the ZeroDivisionError traceback
```

Check `exitCode()` when an execution may fail. Java exceptions are reserved for harder failures: `PythonCompilationException` from `compile(...)` on a syntax error, and `PythonExecutionException` when the WASM runtime itself traps (both include the captured stderr in their message).

## Lazy imports

The bundled Python 3.15.0a7 supports explicit lazy imports in code passed to `execute`:

```python
import sys
lazy import wave

print('wave' in sys.modules)  # False: no module loading yet.
print(wave.Error.__name__)   # First use loads the module.
```

`lazy from wave import Error` also works. For source that must still parse on older Python versions, set `__lazy_modules__ = ['wave']` before an ordinary `import wave`; Python 3.15 defers that import and older versions load it eagerly. Lazy imports belong at module scope. Import errors and initialization side effects occur when the imported name is first used.

Ordinary imports stay eager by default. Start with selected imports; `sys.set_lazy_imports('all')` affects eligible imports throughout the interpreter, including dependencies, and `sys.set_lazy_imports_filter(...)` can limit that scope. In a7, `sys.set_lazy_imports('none')` suppresses the `lazy` keyword, but a module listed in `__lazy_modules__` still opts into lazy loading; clear that declaration when forcing eager imports. See [the Python 3.15.0a7 controls](https://github.com/python/cpython/blob/v3.15.0a7/Doc/library/sys.rst).

Boomslang preloads NumPy, Pandas, Matplotlib, Pydantic, and ijson into its Wizer snapshot. Lazy bindings to those packages cannot defer initialization that already happened. Modules outside the snapshot can benefit when an execution never uses them.

To compare declaration cost and first-use cost on the locally built runtime, run `just python-test` to stage its assets and prepare the Python host environment, then run this from the repository root:

```bash
boomslang-py/.venv/bin/python scripts/benchmark-lazy-imports.py \
  --samples 7 --modules wave numpy
```

The benchmark requires exactly Python 3.15.0a7 and compares eager, explicit lazy, declared, filtered global, and disabled lazy imports. It restores a fresh interpreter snapshot for each sample and reports which modules were already loaded. Its guest timings exclude host setup, WASM compilation, and compilation of the submitted script. `--help` lists the other bundled packages and an optional native Python baseline.

## Reusing compiled code

Use `compile` and `loadCode` when the same source runs many times. Compilation happens once; each run replays the bytecode:

```java
PythonInstance instance = factory.createInstance(pythonRoot);
byte[] bytecode = instance.compile(sourceCode);

PythonResult first = instance.loadCode(bytecode);
instance.reset();
PythonResult second = instance.loadCode(bytecode);
```

The bytecode is CPython marshal data and is specific to the runtime build that produced it: cache it within a process, but don't persist it across boomslang version upgrades.

## Passing input

Feed data to Python via stdin with `setStdin(...)` on the instance, or write files into the instance root directory before executing — Python sees that directory as its filesystem.
