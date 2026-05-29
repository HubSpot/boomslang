package com.hubspot.boomslang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class AsyncHostRegistry {

  public static final String START = "__async_start__";
  public static final String POLL = "__async_poll__";
  public static final String CANCEL = "__async_cancel__";

  @FunctionalInterface
  public interface AsyncCallHandler {
    CompletionStage<String> handle(String name, String args);
  }

  private final AtomicLong nextToken = new AtomicLong(1);
  private final Map<String, AsyncCallHandler> handlers = new ConcurrentHashMap<>();
  private final Map<Long, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
  private final LinkedBlockingQueue<Completion> completions = new LinkedBlockingQueue<>();

  public void register(String name, AsyncCallHandler handler) {
    handlers.put(name, handler);
  }

  public boolean hasHandlers() {
    return !handlers.isEmpty();
  }

  public boolean isControlCall(String name) {
    return START.equals(name) || POLL.equals(name) || CANCEL.equals(name);
  }

  public String handleControlCall(String name, String args) {
    if (START.equals(name)) {
      return start(args);
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

  private String start(String args) {
    int separator = args.indexOf('\n');
    String name = separator >= 0 ? args.substring(0, separator) : args;
    String payload = separator >= 0 ? args.substring(separator + 1) : "";
    AsyncCallHandler handler = handlers.get(name);
    if (handler == null) {
      throw new RuntimeException("No async handler registered for: " + name);
    }

    return Long.toString(start(handler.handle(name, payload)));
  }

  public long start(CompletionStage<String> stage) {
    long token = nextToken.getAndIncrement();
    CompletableFuture<String> future = stage.toCompletableFuture();
    inFlight.put(token, future);
    future.whenComplete((result, error) -> {
      inFlight.remove(token);
      completions.add(Completion.from(token, result, error));
    });
    return token;
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
      throw new RuntimeException("Thread interrupted while polling async completions", e);
    }

    StringBuilder encoded = new StringBuilder();
    for (Completion completion : drained) {
      encoded.append(completion.toLine()).append('\n');
    }
    return encoded.toString();
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

    String toLine() {
      String encodedValue = Base64
        .getEncoder()
        .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      return token + "\t" + (ok ? "1" : "0") + "\t" + encodedValue;
    }
  }
}
