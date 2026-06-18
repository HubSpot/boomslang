package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.boomslang.generated.BoomslangHostHostFunctions;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implements the {@code boomslang.call} and {@code boomslang.log} host function imports that the
 * bundled Python runtime requires. Because the runtime's WASM module imports them unconditionally,
 * a HostBridge must be registered on every {@link PythonExecutorFactory.Builder} via {@code
 * addExtension(HostBridge.builder().buildExtension())} — even when no handlers are configured — or
 * factory creation fails.
 *
 * <p>From Python, {@code boomslang.call(name, args)} is dispatched to the configured {@link
 * CallHandler} or named functions; calling a name with no registered handler raises an exception in
 * Python. {@code boomslang.log} goes to the {@link LogHandler}, which defaults to a no-op.
 */
public class HostBridge {

  /** Handles {@code boomslang.call(name, args)} invocations from Python. */
  @FunctionalInterface
  public interface CallHandler {
    /**
     * Handles a call from Python.
     *
     * @param name the call name passed from Python
     * @param args the serialized arguments passed from Python
     * @return the result string returned to Python; must not be null
     */
    String handle(String name, String args);
  }

  /** Handles {@code boomslang.log(level, message)} invocations from Python. */
  @FunctionalInterface
  public interface LogHandler {
    /**
     * Handles a log message from Python.
     *
     * @param level numeric log level passed from Python
     * @param message the log message
     */
    void handle(int level, String message);
  }

  /** Returns a new {@link Builder}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Configures call/log handlers and produces the host functions or extension. */
  public static class Builder {

    private CallHandler callHandler;
    private LogHandler logHandler;
    private final Map<String, Function<String, String>> handlers =
      new ConcurrentHashMap<>();
    private AsyncHostRegistry asyncRegistry = new AsyncHostRegistry();

    /**
     * Sets a single handler that receives every {@code boomslang.call} by name. When set, it takes
     * precedence and any {@link #withFunction(String, Function)} registrations are ignored.
     * Reserved async control calls are still routed to the {@link AsyncHostRegistry} first.
     */
    public Builder withCallHandler(CallHandler handler) {
      this.callHandler = handler;
      return this;
    }

    /**
     * Sets the handler for {@code boomslang.log}. When unset, log messages are silently discarded.
     */
    public Builder withLogHandler(LogHandler handler) {
      this.logHandler = handler;
      return this;
    }

    /**
     * Registers a handler for a single call name. Calls to names with no registered handler raise
     * an exception in Python. Ignored if {@link #withCallHandler(CallHandler)} is set.
     */
    public Builder withFunction(String name, Function<String, String> handler) {
      this.handlers.put(name, handler);
      return this;
    }

    /**
     * Replaces the {@link AsyncHostRegistry} used for async functions and the reserved async
     * control calls. Useful to share one registry across bridges or extensions.
     */
    public Builder withAsyncRegistry(AsyncHostRegistry asyncRegistry) {
      this.asyncRegistry = asyncRegistry;
      return this;
    }

    /**
     * Registers an async handler for a call name, returning a {@link CompletionStage} whose
     * completion is observed by the guest through the async polling protocol.
     */
    public Builder withAsyncFunction(
      String name,
      Function<String, CompletionStage<String>> handler
    ) {
      this.asyncRegistry.register(name, (ignoredName, args) -> handler.apply(args));
      return this;
    }

    /**
     * Registers an async handler for a call name that also receives the call name, for handlers
     * shared across multiple names.
     */
    public Builder withAsyncCallHandler(
      String name,
      AsyncHostRegistry.AsyncCallHandler handler
    ) {
      this.asyncRegistry.register(name, handler);
      return this;
    }

    /**
     * Builds the raw host functions implementing {@code boomslang.call} and {@code boomslang.log},
     * for {@link PythonExecutorFactory.Builder#addHostFunctions}.
     */
    public HostFunction[] build() {
      return generatedBuilder().build();
    }

    /**
     * Builds the bridge as a {@link BoomslangExtension}, for {@link
     * PythonExecutorFactory.Builder#addExtension(BoomslangExtension)}.
     */
    public BoomslangExtension buildExtension() {
      return generatedBuilder().buildExtension();
    }

    private BoomslangHostHostFunctions.Builder generatedBuilder() {
      return BoomslangHostHostFunctions
        .builder()
        .withCall(effectiveCallHandler())
        .withLog(effectiveLogHandler());
    }

    private BoomslangHostHostFunctions.CallHandler effectiveCallHandler() {
      CallHandler effectiveCallHandler = callHandler;
      if (effectiveCallHandler == null && !handlers.isEmpty()) {
        effectiveCallHandler =
          (name, args) -> {
            Function<String, String> h = handlers.get(name);
            if (h == null) {
              throw new RuntimeException("No handler registered for: " + name);
            }
            return h.apply(args);
          };
      }

      if (effectiveCallHandler == null) {
        effectiveCallHandler =
          (name, args) -> {
            throw new RuntimeException("No handler registered for: " + name);
          };
      }

      CallHandler handler = effectiveCallHandler;
      return (name, args) -> {
        checkInterrupted();
        // Always route the reserved async control calls (__async_protocol__ / __async_start__ /
        // __async_poll__ / __async_result__ / __async_cancel__) to the registry, regardless of
        // whether any named async handlers were registered. Generated extension async functions
        // (e.g. call_rpc_async) call asyncRegistry.start(stage) directly from their WASM import and
        // never populate the handler map, so the event loop must still be able to poll/cancel
        // their tokens through this bridge.
        if (asyncRegistry.isControlCall(name)) {
          return asyncRegistry.handleControlCall(name, args);
        }
        return handler.handle(name, args);
      };
    }

    private BoomslangHostHostFunctions.LogHandler effectiveLogHandler() {
      if (logHandler == null) {
        return (level, message) -> checkInterrupted();
      }
      return (level, message) -> {
        checkInterrupted();
        logHandler.handle(level, message);
      };
    }

    private static void checkInterrupted() {
      if (Thread.currentThread().isInterrupted()) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Thread interrupted during host function execution");
      }
    }
  }
}
