package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.boomslang.generated.BoomslangHostHostFunctions;
import java.util.Map;
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

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private CallHandler callHandler;
    private LogHandler logHandler;
    private final Map<String, Function<String, String>> handlers =
      new ConcurrentHashMap<>();

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
        return (name, args) -> {
          throw new RuntimeException("No handler registered for: " + name);
        };
      }

      CallHandler handler = effectiveCallHandler;
      return (name, args) -> {
        checkInterrupted();
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
