# Calling Host Functions from Python

The bundled runtime exposes two host functions to Python through the `boomslang_host` module: `call(name, args)` and `log(level, message)`. The Java side decides what they do.

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

## Handler options

`HostBridge.Builder` gives you three levels of control:

- **`withFunction(name, fn)`** — register named `String -> String` handlers; unknown names raise in Python.
- **`withCallHandler((name, args) -> ...)`** — one handler that receives every `call(name, args)`; use it for dynamic dispatch. Mutually exclusive with `withFunction` registrations (a `withCallHandler` takes precedence).
- **`withLogHandler((level, message) -> ...)`** — receives `log(...)` calls; the default is a no-op.

Values cross the boundary as strings. The common pattern is JSON in, JSON out — serialize on whichever side is more convenient.

If you register no handlers at all (`HostBridge.builder().buildExtension()`, as in the [quickstart](../quickstart.md)), logging is a no-op and any `call(...)` raises a `RuntimeError` in Python. The extension still must be registered: the bundled WASM unconditionally imports `boomslang.call` and `boomslang.log`, and the factory fails to instantiate it without them.

## Errors and interruption

- An exception thrown by a Java handler surfaces as a Python exception at the `call(...)` site.
- Host-function entry is also where thread interruption (from [timeouts](lifecycle.md)) is observed — handlers should not swallow `InterruptedException`.

## Beyond strings: typed extensions

`boomslang_host.call` is deliberately blunt: one stringly-typed entry point. When you want dedicated Python functions with typed signatures (`def lookup(request: str, shard: int) -> str`) and no JSON overhead, define a custom extension with the `boomslang-hostgen` DSL and build a [custom Python runtime](custom-python-builds.md). For Java `CompletionStage` work awaited from Python `asyncio`, see [Async host calls](async.md).
