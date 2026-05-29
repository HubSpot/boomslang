package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.boomslang.generated.BoomslangHostHostFunctions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<String, AsyncCallHandler> asyncHandlers = new ConcurrentHashMap<>();
    private final AsyncRegistry asyncRegistry = new AsyncRegistry();

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

    public Builder withAsyncFunction(
      String name,
      Function<String, CompletionStage<String>> handler
    ) {
      this.asyncHandlers.put(name, (ignoredName, args) -> handler.apply(args));
      return this;
    }

    public Builder withAsyncCallHandler(String name, AsyncCallHandler handler) {
      this.asyncHandlers.put(name, handler);
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
        if (!asyncHandlers.isEmpty() && asyncRegistry.isAsyncControlCall(name)) {
          return asyncRegistry.handleControlCall(name, args, asyncHandlers);
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

  private static class AsyncRegistry {

    private static final String START = "__async_start__";
    private static final String POLL = "__async_poll__";
    private static final String CANCEL = "__async_cancel__";

    private final AtomicLong nextToken = new AtomicLong(1);
    private final Map<Long, CompletableFuture<String>> inFlight =
      new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Completion> completions =
      new LinkedBlockingQueue<>();

    boolean isAsyncControlCall(String name) {
      return START.equals(name) || POLL.equals(name) || CANCEL.equals(name);
    }

    String handleControlCall(
      String name,
      String args,
      Map<String, AsyncCallHandler> handlers
    ) {
      if (START.equals(name)) {
        return start(args, handlers);
      }
      if (POLL.equals(name)) {
        return poll(Long.parseLong(args));
      }
      if (CANCEL.equals(name)) {
        cancel(Long.parseLong(args));
        return "";
      }
      throw new RuntimeException("Unknown async control call: " + name);
    }

    private String start(String args, Map<String, AsyncCallHandler> handlers) {
      int separator = args.indexOf('\n');
      String name = separator >= 0 ? args.substring(0, separator) : args;
      String payload = separator >= 0 ? args.substring(separator + 1) : "";
      AsyncCallHandler handler = handlers.get(name);
      if (handler == null) {
        throw new RuntimeException("No async handler registered for: " + name);
      }

      long token = nextToken.getAndIncrement();
      CompletionStage<String> stage = handler.handle(name, payload);
      CompletableFuture<String> future = stage.toCompletableFuture();
      inFlight.put(token, future);
      future.whenComplete((result, error) -> {
        inFlight.remove(token);
        completions.add(Completion.from(token, result, error));
      });
      return Long.toString(token);
    }

    private void cancel(long token) {
      CompletableFuture<String> future = inFlight.remove(token);
      if (future != null) {
        future.cancel(true);
      }
    }

    private String poll(long timeoutMillis) {
      List<Completion> drained = new ArrayList<>();
      try {
        Completion first;
        if (timeoutMillis < 0) {
          first = completions.take();
        } else if (timeoutMillis == 0) {
          first = completions.poll();
        } else {
          first = completions.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        if (first != null) {
          drained.add(first);
          completions.drainTo(drained);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(
          "Thread interrupted while polling async completions",
          e
        );
      }

      StringBuilder json = new StringBuilder("[");
      for (int i = 0; i < drained.size(); i++) {
        if (i > 0) {
          json.append(',');
        }
        json.append(drained.get(i).toJson());
      }
      json.append(']');
      return json.toString();
    }
  }

  private record Completion(long token, boolean ok, String value) {
    static Completion from(long token, String result, Throwable error) {
      if (error == null) {
        return new Completion(token, true, result);
      }
      Throwable unwrapped = error instanceof CompletionException &&
        error.getCause() != null
        ? error.getCause()
        : error;
      return new Completion(token, false, unwrapped.toString());
    }

    String toJson() {
      String field = ok ? "result" : "error";
      return (
        "{\"token\":" +
        token +
        ",\"ok\":" +
        ok +
        ",\"" +
        field +
        "\":\"" +
        escapeJson(value) +
        "\"}"
      );
    }

    private static String escapeJson(String value) {
      if (value == null) {
        return "";
      }
      StringBuilder escaped = new StringBuilder(value.length());
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
          case '"' -> escaped.append("\\\"");
          case '\\' -> escaped.append("\\\\");
          case '\b' -> escaped.append("\\b");
          case '\f' -> escaped.append("\\f");
          case '\n' -> escaped.append("\\n");
          case '\r' -> escaped.append("\\r");
          case '\t' -> escaped.append("\\t");
          default -> {
            if (c < 0x20) {
              escaped.append(String.format("\\u%04x", (int) c));
            } else {
              escaped.append(c);
            }
          }
        }
      }
      return escaped.toString();
    }
  }
}
