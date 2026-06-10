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
