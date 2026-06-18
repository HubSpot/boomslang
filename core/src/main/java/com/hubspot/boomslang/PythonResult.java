package com.hubspot.boomslang;

/**
 * Result of running Python code in a {@link PythonInstance}. Python exceptions are reported here
 * rather than thrown: a non-zero {@link #exitCode()} means the code raised, and the traceback is in
 * {@link #stderr()}.
 *
 * @param stdout captured standard output
 * @param stderr captured standard error, including the traceback when a Python exception was raised
 * @param exitCode 0 on success; non-zero means a Python exception was raised
 * @param executionTimeMs wall-clock execution time in milliseconds
 */
public record PythonResult(
  String stdout,
  String stderr,
  int exitCode,
  long executionTimeMs
) {
  /** Returns a new builder with empty output and a zero exit code. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link PythonResult}. */
  public static class Builder {

    private String stdout = "";
    private String stderr = "";
    private int exitCode;
    private long executionTimeMs;

    /** Sets the captured standard output. */
    public Builder setStdout(String stdout) {
      this.stdout = stdout;
      return this;
    }

    /** Sets the captured standard error. */
    public Builder setStderr(String stderr) {
      this.stderr = stderr;
      return this;
    }

    /** Sets the exit code; 0 means success. */
    public Builder setExitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    /** Sets the wall-clock execution time in milliseconds. */
    public Builder setExecutionTimeMs(long executionTimeMs) {
      this.executionTimeMs = executionTimeMs;
      return this;
    }

    /** Builds the {@link PythonResult}. */
    public PythonResult build() {
      return new PythonResult(stdout, stderr, exitCode, executionTimeMs);
    }
  }
}
