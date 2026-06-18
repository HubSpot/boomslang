# Lifecycle, Timeouts & Limits

## The threading model

All WASM execution must go through `factory.runOnWasmThread(...)`. The factory maintains a dedicated thread pool (currently fixed at 10 threads) whose threads have an enlarged JVM stack — CPython's C stack lives on the JVM stack under Chicory, and deep Python recursion needs the headroom.

`PythonInstance` is **not thread-safe**. Use one instance from one task at a time; create separate instances for concurrent executions (they're cheap — each is a copy-on-write view of the shared snapshot).

## Timeouts

The overload `runOnWasmThread(task, timeout, instance)` enforces a wall-clock timeout:

```java
PythonInstance instance = factory.createInstance(pythonRoot);
PythonResult result = factory.runOnWasmThread(
  () -> instance.execute("print(sum(range(10)))"),
  Duration.ofSeconds(5),
  instance
);
```

On timeout the future is cancelled, the instance is **poisoned**, and a `TimeoutException` is thrown to the caller.

> **Honest caveat — timeouts do not hard-stop Python.** Cancellation is delivered as a Java thread interrupt, and the interrupt is only observed when the guest calls back into a host function. CPU-bound Python that never calls a host function (a tight pure-computation loop) keeps running on its pool thread past the timeout. Since the pool is fixed-size, enough runaway executions can exhaust it. Hard-stop interruption is tracked in [issue #42](https://github.com/HubSpot/boomslang/issues/42). Until it lands: treat timeouts as a cooperative mechanism, prefer scripts that do I/O through host functions, and consider process-level isolation if you execute fully untrusted CPU-bound code.

## Poisoned instances

A poisoned instance refuses further work. Because the timed-out execution may still be running (see above), the safest response is to **discard the instance and create a new one**. `reset()` restores the instance memory to the golden snapshot and clears the poison flag, but resetting while the abandoned execution is still on the WASM thread races with it — only `reset()` when you know the prior call actually finished.

```java
if (instance.isPoisoned()) {
  instance = factory.createInstance(pythonRoot); // preferred over reset()
}
```

`reset()` is also useful in the happy path: it returns a healthy instance to the pristine snapshot state between executions (fresh `__main__`, no leaked globals) much faster than re-importing anything.

## Resource limits

`createInstance(rootPath, limits)` accepts a `ResourceLimits`:

```java
ResourceLimits limits = ResourceLimits
  .builder()
  .maximumOutputBytes(1024 * 1024)  // cap captured stdout/stderr (default 10 MB)
  .maximumMemoryPages(4096)         // cap guest memory growth (64 KiB pages)
  .build();
PythonInstance instance = factory.createInstance(pythonRoot, limits);
```

> **Caveat:** `ResourceLimits.executionTimeout` exists on the record but is **not currently enforced** — the only enforced timeout is the one you pass to `runOnWasmThread` (also tracked in [issue #42](https://github.com/HubSpot/boomslang/issues/42)).

## Cleanup

- The factory pins the golden memory snapshot (hundreds of MB with the bundled runtime) for its lifetime — another reason to create exactly one.
- `PythonInstance.close()` marks the instance unusable; instance memory is reclaimed by GC once unreferenced.
