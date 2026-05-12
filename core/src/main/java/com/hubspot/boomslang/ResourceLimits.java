package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.Memory;
import java.time.Duration;

public record ResourceLimits(
  Duration executionTimeout,
  int maximumMemoryPages,
  long maximumOutputBytes
) {
  public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(120);
  public static final int DEFAULT_MAXIMUM_MEMORY_PAGES = Memory.RUNTIME_MAX_PAGES;
  public static final long DEFAULT_MAXIMUM_OUTPUT_BYTES = 10L * 1024 * 1024;

  public static Builder builder() {
    return new Builder();
  }

  public static ResourceLimits defaults() {
    return builder().build();
  }

  public static class Builder {

    private Duration executionTimeout = DEFAULT_EXECUTION_TIMEOUT;
    private int maximumMemoryPages = DEFAULT_MAXIMUM_MEMORY_PAGES;
    private long maximumOutputBytes = DEFAULT_MAXIMUM_OUTPUT_BYTES;

    public Builder executionTimeout(Duration executionTimeout) {
      this.executionTimeout = executionTimeout;
      return this;
    }

    public Builder maximumMemoryPages(int maximumMemoryPages) {
      this.maximumMemoryPages = maximumMemoryPages;
      return this;
    }

    public Builder maximumOutputBytes(long maximumOutputBytes) {
      this.maximumOutputBytes = maximumOutputBytes;
      return this;
    }

    public ResourceLimits build() {
      return new ResourceLimits(executionTimeout, maximumMemoryPages, maximumOutputBytes);
    }
  }
}
