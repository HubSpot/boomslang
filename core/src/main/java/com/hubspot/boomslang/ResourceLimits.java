package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.Memory;
import java.time.Duration;

/**
 * Per-instance resource limits, passed to {@link PythonExecutorFactory}{@code .createInstance}.
 *
 * <p>Note: {@code executionTimeout} is currently NOT enforced (tracked in <a
 * href="https://github.com/HubSpot/boomslang/issues/42">issue #42</a>). To bound execution time,
 * use the timeout parameter of the timed {@link PythonExecutorFactory}{@code .runOnWasmThread}
 * overloads.
 *
 * @param executionTimeout intended execution time limit; currently not enforced (see above)
 * @param maximumMemoryPages cap on guest linear memory, in 64 KiB WASM pages
 * @param maximumOutputBytes cap on captured stdout/stderr size, in bytes; exceeding it throws
 *     {@link PythonInstance.PythonExecutionException}
 */
public record ResourceLimits(
  Duration executionTimeout,
  int maximumMemoryPages,
  long maximumOutputBytes
) {
  /** Default execution timeout (120 seconds); currently not enforced. */
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(120);

  /** Default memory cap: the WASM runtime maximum ({@link Memory#RUNTIME_MAX_PAGES} pages). */
  public static final int DEFAULT_MAXIMUM_MEMORY_PAGES = Memory.RUNTIME_MAX_PAGES;

  /** Default cap on captured stdout/stderr: 10 MB. */
  public static final long DEFAULT_MAXIMUM_OUTPUT_BYTES = 10L * 1024 * 1024;

  /** Returns a new builder initialized with the default limits. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns the default limits. */
  public static ResourceLimits defaults() {
    return builder().build();
  }

  /** Builder for {@link ResourceLimits}; starts from the default values. */
  public static class Builder {

    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maximumMemoryPages = DEFAULT_MAXIMUM_MEMORY_PAGES;
    private long maximumOutputBytes = DEFAULT_MAXIMUM_OUTPUT_BYTES;

    /**
     * Sets the execution timeout. Currently not enforced (see the class note); use the timed {@code
     * runOnWasmThread} overloads instead.
     */
    public Builder executionTimeout(Duration executionTimeout) {
      this.executionTimeout = executionTimeout;
      return this;
    }

    /** Sets the cap on guest linear memory, in 64 KiB WASM pages. */
    public Builder maximumMemoryPages(int maximumMemoryPages) {
      this.maximumMemoryPages = maximumMemoryPages;
      return this;
    }

    /** Sets the cap on captured stdout/stderr size, in bytes. */
    public Builder maximumOutputBytes(long maximumOutputBytes) {
      this.maximumOutputBytes = maximumOutputBytes;
      return this;
    }

    /** Builds the {@link ResourceLimits}. */
    public ResourceLimits build() {
      return new ResourceLimits(executionTimeout, maximumMemoryPages, maximumOutputBytes);
    }
  }
}
