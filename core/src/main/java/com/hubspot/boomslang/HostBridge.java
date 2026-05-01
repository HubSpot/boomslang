package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HostBridge {

  private static final Logger LOG = LoggerFactory.getLogger(HostBridge.class);
  private static final String MODULE = "boomslang";

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
      List<HostFunction> functions = new ArrayList<>();

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

      if (effectiveCallHandler != null) {
        functions.add(createCallFunction(effectiveCallHandler));
      }
      if (logHandler != null) {
        functions.add(createLogFunction(logHandler));
      }
      return functions.toArray(new HostFunction[0]);
    }

    private static HostFunction createCallFunction(CallHandler handler) {
      return new HostFunction(
        MODULE,
        "call",
        List.of(
          ValueType.I32,
          ValueType.I32,
          ValueType.I32,
          ValueType.I32,
          ValueType.I32,
          ValueType.I32
        ),
        List.of(ValueType.I32),
        (Instance instance, long... args) -> {
          Memory memory = instance.memory();
          int namePtr = Math.toIntExact(args[0]);
          int nameLen = Math.toIntExact(args[1]);
          String name = memory.readString(namePtr, nameLen, StandardCharsets.UTF_8);
          int argsPtr = Math.toIntExact(args[2]);
          int argsLen = Math.toIntExact(args[3]);
          String argsJson = memory.readString(argsPtr, argsLen, StandardCharsets.UTF_8);
          int resultPtr = Math.toIntExact(args[4]);
          int resultMaxLen = Math.toIntExact(args[5]);

          try {
            String result = handler.handle(name, argsJson);
            byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
            if (resultBytes.length > resultMaxLen) {
              return new long[] { -2 };
            }
            memory.write(resultPtr, resultBytes);
            return new long[] { resultBytes.length };
          } catch (RuntimeException e) {
            LOG.error("Host call '{}' failed", name, e);
            return new long[] { -1 };
          }
        }
      );
    }

    private static HostFunction createLogFunction(LogHandler handler) {
      return new HostFunction(
        MODULE,
        "log",
        List.of(ValueType.I32, ValueType.I32, ValueType.I32),
        List.of(),
        (Instance instance, long... args) -> {
          Memory memory = instance.memory();
          int level = Math.toIntExact(args[0]);
          int messagePtr = Math.toIntExact(args[1]);
          int messageLen = Math.toIntExact(args[2]);
          String message = memory.readString(
            messagePtr,
            messageLen,
            StandardCharsets.UTF_8
          );
          handler.handle(level, message);
          return null;
        }
      );
    }
  }
}
