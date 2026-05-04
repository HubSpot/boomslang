package com.hubspot.boomslang;

public record PythonResult(
  String stdout,
  String stderr,
  int exitCode,
  long executionTimeMs
) {
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private String stdout = "";
    private String stderr = "";
    private int exitCode;
    private long executionTimeMs;

    public Builder setStdout(String stdout) {
      this.stdout = stdout;
      return this;
    }

    public Builder setStderr(String stderr) {
      this.stderr = stderr;
      return this;
    }

    public Builder setExitCode(int exitCode) {
      this.exitCode = exitCode;
      return this;
    }

    public Builder setExecutionTimeMs(long executionTimeMs) {
      this.executionTimeMs = executionTimeMs;
      return this;
    }

    public PythonResult build() {
      return new PythonResult(stdout, stderr, exitCode, executionTimeMs);
    }
  }
}
