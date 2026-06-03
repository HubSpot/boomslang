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

/**
 * Java side of the async host bridge. Hands {@link CompletionStage} results to the single-threaded
 * WASM event loop ({@code boomslang_host.asyncio}) via a small, versioned control protocol invoked
 * over the stock {@code boomslang_host.call} function.
 *
 * <h3>Wire protocol (v{@value #PROTOCOL_VERSION})</h3>
 *
 * <ul>
 *   <li>{@code __async_protocol__} → the integer {@link #PROTOCOL_VERSION}. The client negotiates
 *       against this so the host can evolve while staying compatible with WASM images that froze an
 *       older client into their Wizer snapshot.
 *   <li>{@code __async_start__} with {@code name\npayload} → a decimal token for a registered named
 *       async handler. Always returns a token; synchronous failures come back as a failed
 *       completion rather than an exception.
 *   <li>{@code __async_poll__} with a timeout in millis ({@code <0} blocks, {@code 0} polls) → one
 *       header line per ready completion: {@code token\t{1|0}\t<valueByteLength>}. Values are NOT
 *       inlined.
 *   <li>{@code __async_result__} with a token → base64 of that completion's value bytes, consuming
 *       it. Fetching values one-at-a-time keeps a batch of completions from ever exceeding the
 *       single host-call result buffer.
 *   <li>{@code __async_cancel__} with a token → cancels the in-flight future.
 * </ul>
 *
 * <p>Generated extension async functions return a token directly from {@link #start} (or
 * {@link #startFailed}); the {@code __async_*} names are a reserved control namespace and must not
 * be used as extension host-function names.
 */
public class AsyncHostRegistry {

  /**
   * Async wire-protocol version. Bump only for breaking changes to the control calls or completion
   * encoding; the Python client refuses hosts older than the version it was built against.
   */
  public static final int PROTOCOL_VERSION = 1;

  public static final String PROTOCOL = "__async_protocol__";
  public static final String START = "__async_start__";
  public static final String POLL = "__async_poll__";
  public static final String RESULT = "__async_result__";
  public static final String CANCEL = "__async_cancel__";

  @FunctionalInterface
  public interface AsyncCallHandler {
    CompletionStage<String> handle(String name, String args);
  }

  private final AtomicLong nextToken = new AtomicLong(1);
  private final Map<String, AsyncCallHandler> handlers = new ConcurrentHashMap<>();
  private final Map<Long, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();
  private final LinkedBlockingQueue<Completion> completions = new LinkedBlockingQueue<>();
  private final Map<Long, Completion> ready = new ConcurrentHashMap<>();

  public void register(String name, AsyncCallHandler handler) {
    handlers.put(name, handler);
  }

  public boolean isControlCall(String name) {
    return (
      PROTOCOL.equals(name) ||
      START.equals(name) ||
      POLL.equals(name) ||
      RESULT.equals(name) ||
      CANCEL.equals(name)
    );
  }

  public String handleControlCall(String name, String args) {
    if (PROTOCOL.equals(name)) {
      return Integer.toString(PROTOCOL_VERSION);
    }
    if (START.equals(name)) {
      return start(args);
    }
    if (POLL.equals(name)) {
      return poll(Long.parseLong(args.trim()));
    }
    if (RESULT.equals(name)) {
      return result(Long.parseLong(args.trim()));
    }
    if (CANCEL.equals(name)) {
      cancel(Long.parseLong(args.trim()));
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
      return Long.toString(
        startFailed(new RuntimeException("No async handler registered for: " + name))
      );
    }
    try {
      return Long.toString(start(handler.handle(name, payload)));
    } catch (RuntimeException e) {
      return Long.toString(startFailed(e));
    }
  }

  /** Registers an in-flight async result, returning the token the Python client awaits. */
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

  /**
   * Records an already-failed async result and returns its token. Lets a host function report a
   * synchronous failure through the normal poll/result path (so the awaiting coroutine raises)
   * instead of returning a sentinel that would leave the future hanging until the script deadline.
   */
  public long startFailed(Throwable error) {
    long token = nextToken.getAndIncrement();
    completions.add(Completion.from(token, null, error));
    return token;
  }

  private void cancel(long token) {
    ready.remove(token);
    CompletableFuture<String> future = inFlight.remove(token);
    if (future != null) {
      future.cancel(true);
    }
  }

  /**
   * Blocks for at least one completion (per the timeout), drains all that are ready, parks them for
   * retrieval, and returns one {@code token\t{1|0}\t<valueByteLength>} header line each. Values are
   * fetched separately via {@link #result} so a batch can never exceed the host-call buffer.
   */
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

    StringBuilder headers = new StringBuilder();
    for (Completion completion : drained) {
      ready.put(completion.token(), completion);
      headers
        .append(completion.token())
        .append('\t')
        .append(completion.ok() ? '1' : '0')
        .append('\t')
        .append(completion.value().length)
        .append('\n');
    }
    return headers.toString();
  }

  /** Returns the base64-encoded value bytes for a previously-polled completion, consuming it. */
  private String result(long token) {
    Completion completion = ready.remove(token);
    if (completion == null) {
      return "";
    }
    return Base64.getEncoder().encodeToString(completion.value());
  }

  private record Completion(long token, boolean ok, byte[] value) {
    static Completion from(long token, String result, Throwable error) {
      if (error == null) {
        byte[] bytes = (result == null ? "" : result).getBytes(StandardCharsets.UTF_8);
        return new Completion(token, true, bytes);
      }
      Throwable unwrapped = error instanceof CompletionException &&
        error.getCause() != null
        ? error.getCause()
        : error;
      return new Completion(
        token,
        false,
        unwrapped.toString().getBytes(StandardCharsets.UTF_8)
      );
    }
  }
}
