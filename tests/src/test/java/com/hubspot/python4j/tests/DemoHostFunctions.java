package com.hubspot.python4j.tests;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasm.types.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DemoHostFunctions {

  private static final String MODULE = "demo";

  @FunctionalInterface
  public interface GreetHandler {
    String handle(String name);
  }

  @FunctionalInterface
  public interface LogHandler {
    void handle(int level, String message);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private GreetHandler greet;
    private LogHandler log;

    public Builder withGreet(GreetHandler handler) {
      this.greet = handler;
      return this;
    }

    public Builder withLog(LogHandler handler) {
      this.log = handler;
      return this;
    }

    public HostFunction[] build() {
      List<HostFunction> functions = new ArrayList<>();
      if (greet != null) {
        functions.add(createGreetFunction());
      }
      if (log != null) {
        functions.add(createLogFunction());
      }
      return functions.toArray(new HostFunction[0]);
    }

    private HostFunction createGreetFunction() {
      return new HostFunction(
          MODULE,
          "greet",
          List.of(ValueType.I32, ValueType.I32, ValueType.I32, ValueType.I32),
          List.of(ValueType.I32),
          (Instance instance, long... args) -> {
            Memory memory = instance.memory();
            int namePtr = Math.toIntExact(args[0]);
            int nameLen = Math.toIntExact(args[1]);
            String name = memory.readString(namePtr, nameLen, StandardCharsets.UTF_8);
            int resultPtr = Math.toIntExact(args[2]);
            int resultMaxLen = Math.toIntExact(args[3]);
            String result = greet.handle(name);
            byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);
            if (resultBytes.length > resultMaxLen) {
              return new long[] {-2};
            }
            memory.write(resultPtr, resultBytes);
            return new long[] {resultBytes.length};
          });
    }

    private HostFunction createLogFunction() {
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
            String message = memory.readString(messagePtr, messageLen, StandardCharsets.UTF_8);
            log.handle(level, message);
            return null;
          });
    }
  }
}
