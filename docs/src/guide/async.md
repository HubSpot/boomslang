# Async Host Calls

Python code can `await` asynchronous Java work. The Java side returns a `CompletionStage<String>`; the Python side awaits it with standard `asyncio` APIs; the `AsyncHostRegistry` brokers completions between the two.

This works with the bundled runtime's string bridge, and — more ergonomically — with typed async functions in a custom extension.

## Java setup

Share one `AsyncHostRegistry` between the `HostBridge` and any async extensions:

```java
AsyncHostRegistry asyncRegistry = new AsyncHostRegistry();

var hostBridge = HostBridge
  .builder()
  .withAsyncRegistry(asyncRegistry)
  .withAsyncFunction("lookup", payload -> rpcClient.lookupAsync(payload))
  .buildExtension();

PythonExecutorFactory factory = PythonExecutorFactory
  .builder()
  .withStdlibPath(pythonRoot)
  .addExtension(hostBridge)
  .build();
```

The handler returns a `CompletionStage<String>`. Java completion threads only enqueue results into the registry — Python is resumed by the Boomslang event loop polling for completions on the WASM thread, so no Java thread ever touches guest memory concurrently.

## Python side

Install the Boomslang event loop, then use normal `asyncio`:

```python
import asyncio
from boomslang_host.asyncio import install, async_call

install()

async def main():
    first = async_call("lookup", '{"id": 1}')
    second = async_call("lookup", '{"id": 2}')
    results = await asyncio.gather(first, second)
    print(results)

asyncio.run(main())
```

Concurrency comes from overlapping the Java-side work: both lookups run in Java simultaneously while Python awaits.

## Typed async functions in custom extensions

A [custom Python build](custom-python-builds.md) can declare async functions with typed parameters in the hostgen DSL (`f.r#async().param(...).returns(Type::String)`). Python then imports them as real module functions and awaits them directly:

```python
import asyncio
from boomslang_host.asyncio import install
from my_async_ext import lookup

install()

async def main():
    results = await asyncio.gather(lookup('{"id": 1}', 0), lookup('{"id": 2}', 1))
    print(results)

asyncio.run(main())
```

On the Java side the generated builder takes typed handlers returning `CompletionStage<String>`, plus the shared registry via `withAsyncRegistry(asyncRegistry)`.

Async functions currently support typed parameters with a `string` return.

## Failure semantics

- A handler that throws synchronously, or a stage that completes exceptionally, surfaces as a `HostAsyncError` raised from the awaiting coroutine — failures never hang the event loop.
- Cancelling the Python task cancels the in-flight Java future.

## Under the hood

The two sides speak a small, versioned wire protocol (`__async_protocol__`, `__async_start__`, `__async_poll__`, `__async_result__`, `__async_cancel__`) over the stock call bridge. The `__async_*` names are a reserved control namespace — don't define extension functions with those names. The full protocol is specified in the reference section (async wire protocol).
