package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.boomslang.generated.BoomslangHostHostFunctions;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class HostBridge {

  @FunctionalInterface
  public interface CallHandler {
    String handle(String name, String args);
  }

  @FunctionalInterface
  public interface LogHandler {
    void handle(int level, String message);
  }

  @FunctionalInterface
  public interface AsyncCallHandler {
    CompletionStage<String> handle(String name, String args);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private CallHandler callHandler;
    private LogHandler logHandler;
    private final Map<String, Function<String, String>> handlers =
      new ConcurrentHashMap<>();
    private AsyncHostRegistry asyncRegistry = new AsyncHostRegistry();

    public Builder withCallHandler(CallHandler handler) {
      this.callHandler = handler;
      return this;
    }

    public Builder withLogHandler(LogHandler handler) {
      this.logHandler = handler;
      return this;
    }

    public Builder withFunction(String name, Function<String, String> handler) {
      this.handlers.put(name, handler);
      return this;
    }

    public Builder withAsyncRegistry(AsyncHostRegistry asyncRegistry) {
      this.asyncRegistry = asyncRegistry;
      return this;
    }

    public Builder withAsyncFunction(
      String name,
      Function<String, CompletionStage<String>> handler
    ) {
      this.asyncRegistry.register(name, (ignoredName, args) -> handler.apply(args));
      return this;
    }

    public Builder withAsyncCallHandler(String name, AsyncCallHandler handler) {
      this.asyncRegistry.register(name, handler::handle);
      return this;
    }

    public HostFunction[] build() {
      return generatedBuilder().build();
    }

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
        // Route the reserved async control calls (__async_start__ / __async_poll__ /
        // __async_cancel__) to the registry whenever the name matches, even if no named
        // async handlers were registered via withAsyncFunction. Generated extension async
        // functions (e.g. call_rpc_async) call asyncRegistry.start(stage) directly from
        // their WASM import and never populate the handler map, so gating on hasHandlers()
        // would leave the event loop unable to poll/cancel their tokens through this bridge.
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
