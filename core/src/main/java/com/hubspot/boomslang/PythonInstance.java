package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single Python interpreter backed by a {@link CopyOnWriteMemory} view of the factory's golden
 * snapshot. Instances are cheap to create from {@link PythonExecutorFactory#createInstance(Path)}
 * and are intended to be disposable.
 *
 * <p>Instances are for single-threaded use only: run one task at a time against an instance, on the
 * factory's WASM pool via {@link PythonExecutorFactory}{@code .runOnWasmThread}. The typical
 * lifecycle is create, then {@link #execute(String)} / {@link #compile(String)} / {@link
 * #loadCode(byte[])} as needed, then {@link #reset()} to reuse or simply discard.
 */
public class PythonInstance implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(PythonInstance.class);
  private static final int MAX_BYTECODE_SIZE = 10 * 1024 * 1024;

  private final Instance wasmInstance;
  private final CopyOnWriteMemory cowMemory;
  private final ResourceLimits limits;
  private final AtomicBoolean codeLoaded = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean poisoned = new AtomicBoolean(false);

  private final String instanceId;
  private final ResettableByteArrayInputStream stdinStream;

  private final ExportFunction compileSourceFunc;
  private final ExportFunction loadBytecodeFunc;
  private final ExportFunction executeFunctionFunc;
  private final ExportFunction resetStateFunc;
  private final ExportFunction executeFunc;
  private final ExportFunction allocFunc;
  private final ExportFunction deallocFunc;
  private final ExportFunction getStdoutLenFunc;
  private final ExportFunction getStderrLenFunc;
  private final ExportFunction getStdoutFunc;
  private final ExportFunction getStderrFunc;
  private final ExportFunction getHeapPagesFunc;
  private final int goldenMemoryPages;

  /**
   * Creates an instance with {@link ResourceLimits#defaults()}. Prefer {@link
   * PythonExecutorFactory#createInstance(Path)}, which supplies the image and host functions
   * configured on the factory.
   */
  public PythonInstance(
    RuntimeImage image,
    HostFunction[] hostFunctions,
    Path rootPath,
    String pythonHome,
    @Nullable String pythonPath
  ) {
    this(
      image,
      hostFunctions,
      rootPath,
      pythonHome,
      pythonPath,
      ResourceLimits.defaults()
    );
  }

  /**
   * Creates an instance with the given limits. Prefer {@link
   * PythonExecutorFactory#createInstance(Path, ResourceLimits)}, which supplies the image and host
   * functions configured on the factory.
   */
  public PythonInstance(
    RuntimeImage image,
    HostFunction[] hostFunctions,
    Path rootPath,
    String pythonHome,
    @Nullable String pythonPath,
    ResourceLimits limits
  ) {
    this.limits = limits;
    this.instanceId = UUID.randomUUID().toString().substring(0, 8);
    this.stdinStream = new ResettableByteArrayInputStream();

    WasiOptions.Builder wasiBuilder = WasiOptions
      .builder()
      .withStdin(stdinStream)
      .withDirectory("/", Objects.requireNonNull(rootPath, "rootPath is required"))
      .withEnvironment("PYTHONHOME", pythonHome);
    WasiOptions wasiOptions = wasiBuilder.build();

    WasiPreview1 wasi = WasiPreview1.builder().withOptions(wasiOptions).build();

    Store store = new Store().addFunction(wasi.toHostFunctions());
    for (HostFunction hf : hostFunctions) {
      store.addFunction(hf);
    }

    byte[] goldenMemory = image.getGoldenMemory();
    int goldenMemoryPages = image.getGoldenMemoryPages();

    int callerMaxPages = limits.maximumMemoryPages();
    CopyOnWriteMemory[] memoryRef = new CopyOnWriteMemory[1];
    Instance.Builder instanceBuilder = Instance
      .builder(image.getModule())
      .withImportValues(store.toImportValues())
      .withMemoryFactory(memoryLimits -> {
        MemoryLimits adjustedLimits = new MemoryLimits(
          Math.max(memoryLimits.initialPages(), goldenMemoryPages),
          memoryLimits.maximumPages()
        );
        memoryRef[0] =
          new CopyOnWriteMemory(goldenMemory, adjustedLimits, callerMaxPages);
        return memoryRef[0];
      });

    if (image.getMachineFactory() != null) {
      instanceBuilder.withMachineFactory(image.getMachineFactory());
    }

    this.wasmInstance = store.instantiate("python", imports -> instanceBuilder.build());
    this.cowMemory =
      Objects.requireNonNull(
        memoryRef[0],
        "CopyOnWriteMemory was not created during instantiation"
      );

    this.compileSourceFunc = wasmInstance.export("compile_source");
    this.loadBytecodeFunc = wasmInstance.export("load_bytecode");
    this.executeFunctionFunc = wasmInstance.export("execute_function");
    this.resetStateFunc = wasmInstance.export("reset_state");
    this.executeFunc = wasmInstance.export("execute");
    this.allocFunc = wasmInstance.export("alloc");
    this.deallocFunc = wasmInstance.export("dealloc");
    this.getStdoutLenFunc = wasmInstance.export("get_stdout_len");
    this.getStderrLenFunc = wasmInstance.export("get_stderr_len");
    this.getStdoutFunc = wasmInstance.export("get_stdout");
    this.getStderrFunc = wasmInstance.export("get_stderr");
    this.getHeapPagesFunc = wasmInstance.export("get_heap_pages");
    this.goldenMemoryPages = image.getGoldenMemoryPages();

    if (pythonPath != null) {
      injectPythonPath(pythonPath);
    }
  }

  private void injectPythonPath(String pythonPath) {
    String[] entries = pythonPath.split(":");
    StringBuilder script = new StringBuilder("import sys");
    for (String entry : entries) {
      script.append("\nsys.path.insert(0, '").append(entry).append("')");
    }
    String scriptStr = script.toString();
    byte[] scriptBytes = scriptStr.getBytes(StandardCharsets.UTF_8);
    int scriptPtr = Math.toIntExact(allocFunc.apply(scriptBytes.length)[0]);
    try {
      wasmInstance.memory().write(scriptPtr, scriptBytes);
      long[] result = executeFunc.apply(scriptPtr, scriptBytes.length);
      if (result[0] != 0) {
        LOG.warn("pythonPath injection failed with code: {}", result[0]);
      }
    } finally {
      deallocFunc.apply(scriptPtr, scriptBytes.length);
      clearStdin();
    }
  }

  /**
   * Sets the bytes Python reads from {@code sys.stdin} during the next execution. Stdin is cleared
   * automatically after each execute/load call.
   */
  public synchronized void setStdin(byte[] data) {
    stdinStream.resetData(data);
  }

  /** Clears any pending stdin data. */
  public synchronized void clearStdin() {
    stdinStream.clear();
  }

  /**
   * Compiles Python source to bytecode without executing it. The result is CPython marshal data and
   * is only valid for the exact runtime build that produced it — pass it back to {@link
   * #loadCode(byte[])} on an instance from the same runtime; do not persist it across runtime
   * upgrades.
   *
   * @return marshaled CPython bytecode
   * @throws PythonCompilationException if the source does not compile (e.g. a syntax error)
   */
  public synchronized byte[] compile(String source) {
    checkNotClosed();

    byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
    int sourceLen = sourceBytes.length;

    int sourcePtr = Math.toIntExact(allocFunc.apply(sourceLen)[0]);
    int outputPtr = Math.toIntExact(allocFunc.apply(MAX_BYTECODE_SIZE)[0]);

    try {
      wasmInstance.memory().write(sourcePtr, sourceBytes);

      long[] result = compileSourceFunc.apply(
        sourcePtr,
        sourceLen,
        outputPtr,
        MAX_BYTECODE_SIZE
      );
      int bytecodeLen = Math.toIntExact(result[0]);

      if (bytecodeLen < 0) {
        String stderr = readStderr();
        String errorMsg = stderr.isEmpty()
          ? "Compilation failed with error code: " + bytecodeLen
          : stderr;
        throw new PythonCompilationException(errorMsg);
      }

      return wasmInstance.memory().readBytes(outputPtr, bytecodeLen);
    } finally {
      deallocFunc.apply(sourcePtr, sourceLen);
      deallocFunc.apply(outputPtr, MAX_BYTECODE_SIZE);
    }
  }

  /**
   * Loads and runs bytecode produced by {@link #compile(String)} in this instance's {@code
   * __main__} module, typically to define functions for later {@link #executeFunction(String,
   * String)} calls.
   *
   * @return the result of running the bytecode; a non-zero {@link PythonResult#exitCode()} means a
   *     Python exception was raised, with the traceback in {@link PythonResult#stderr()}
   * @throws PythonExecutionException if the runtime traps while loading
   */
  public synchronized PythonResult loadCode(byte[] bytecode) {
    checkNotClosed();

    long startTime = System.nanoTime();
    int bytecodePtr = Math.toIntExact(allocFunc.apply(bytecode.length)[0]);

    try {
      wasmInstance.memory().write(bytecodePtr, bytecode);

      long[] result;
      try {
        result = loadBytecodeFunc.apply(bytecodePtr, bytecode.length);
      } catch (RuntimeException e) {
        String stderr = readStderr();
        String message = stderr.isEmpty()
          ? e.getMessage()
          : (e.getMessage() != null ? stderr + "\nCaused by: " + e.getMessage() : stderr);
        throw new PythonExecutionException(message, e);
      }
      int exitCode = Math.toIntExact(result[0]);

      String stdout = readStdout();
      String stderr = readStderr();

      codeLoaded.set(true);

      long executionTimeMs = (System.nanoTime() - startTime) / 1_000_000;

      return PythonResult
        .builder()
        .setStdout(stdout)
        .setStderr(stderr)
        .setExitCode(exitCode)
        .setExecutionTimeMs(executionTimeMs)
        .build();
    } finally {
      deallocFunc.apply(bytecodePtr, bytecode.length);
      clearStdin();
    }
  }

  /**
   * Calls a function previously defined in {@code __main__} (usually via {@link
   * #loadCode(byte[])}), passing arguments as JSON.
   *
   * @param functionName name of the function in {@code __main__}
   * @param argsJson JSON-encoded arguments, or null for none
   * @return the result; a non-zero {@link PythonResult#exitCode()} means a Python exception was
   *     raised, with the traceback in {@link PythonResult#stderr()}
   */
  public synchronized PythonResult executeFunction(String functionName, String argsJson) {
    checkNotClosed();

    long startTime = System.nanoTime();

    byte[] funcNameBytes = functionName.getBytes(StandardCharsets.UTF_8);
    byte[] argsBytes = argsJson != null
      ? argsJson.getBytes(StandardCharsets.UTF_8)
      : new byte[0];

    int funcNamePtr = Math.toIntExact(allocFunc.apply(funcNameBytes.length)[0]);
    int argsPtr = argsBytes.length > 0
      ? Math.toIntExact(allocFunc.apply(argsBytes.length)[0])
      : 0;

    try {
      wasmInstance.memory().write(funcNamePtr, funcNameBytes);
      if (argsBytes.length > 0) {
        wasmInstance.memory().write(argsPtr, argsBytes);
      }

      long[] result = executeFunctionFunc.apply(
        funcNamePtr,
        funcNameBytes.length,
        argsPtr,
        argsBytes.length
      );
      int exitCode = Math.toIntExact(result[0]);

      String stdout = readStdout();
      String stderr = readStderr();

      long executionTimeMs = (System.nanoTime() - startTime) / 1_000_000;

      return PythonResult
        .builder()
        .setStdout(stdout)
        .setStderr(stderr)
        .setExitCode(exitCode)
        .setExecutionTimeMs(executionTimeMs)
        .build();
    } finally {
      deallocFunc.apply(funcNamePtr, funcNameBytes.length);
      if (argsPtr != 0) {
        deallocFunc.apply(argsPtr, argsBytes.length);
      }
      clearStdin();
    }
  }

  /**
   * Executes a Python script in this instance's {@code __main__} module. Python exceptions do not
   * throw: they yield a {@link PythonResult} with a non-zero exit code and the traceback in {@link
   * PythonResult#stderr()}. State created by the script persists in the instance until {@link
   * #reset()}.
   *
   * @return the captured stdout/stderr, exit code, and timing
   * @throws PythonExecutionException if the WASM runtime itself traps (not for ordinary Python
   *     exceptions)
   */
  public synchronized PythonResult execute(String script) {
    checkNotClosed();

    long startTime = System.nanoTime();

    byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_8);
    int scriptLen = scriptBytes.length;

    int scriptPtr = Math.toIntExact(allocFunc.apply(scriptLen)[0]);

    int exitCode;
    try {
      wasmInstance.memory().write(scriptPtr, scriptBytes);
      long[] result = executeFunc.apply(scriptPtr, scriptLen);
      exitCode = Math.toIntExact(result[0]);
    } catch (RuntimeException e) {
      String stderr = readStderr();
      String message = stderr.isEmpty()
        ? e.getMessage()
        : (e.getMessage() != null ? stderr + "\nCaused by: " + e.getMessage() : stderr);
      throw new PythonExecutionException(message, e);
    } finally {
      deallocFunc.apply(scriptPtr, scriptLen);
      clearStdin();
    }

    String stdout = readStdout();
    String stderr = readStderr();

    long executionTimeMs = (System.nanoTime() - startTime) / 1_000_000;

    return PythonResult
      .builder()
      .setStdout(stdout)
      .setStderr(stderr)
      .setExitCode(exitCode)
      .setExecutionTimeMs(executionTimeMs)
      .build();
  }

  /**
   * Restores the copy-on-write snapshot: all private memory pages are dropped, leaving the pristine
   * pre-initialized interpreter state with a fresh {@code __main__}. Also clears the poisoned flag
   * and pending stdin. After a timeout, prefer discarding the instance over resetting it — see the
   * timed {@link PythonExecutorFactory}{@code .runOnWasmThread} overload.
   *
   * @throws IllegalStateException if the instance has been closed
   */
  public synchronized void reset() {
    if (closed.get()) {
      throw new IllegalStateException("PythonInstance has been closed");
    }
    cowMemory.reset();
    codeLoaded.set(false);
    poisoned.set(false);
    clearStdin();
  }

  /**
   * Marks this instance unusable (e.g. after a timeout left it in an unknown state). Subsequent
   * execute/compile/load calls throw {@link IllegalStateException} until {@link #reset()}.
   */
  public void poison() {
    poisoned.set(true);
  }

  /** Returns whether {@link #poison()} has been called since the last {@link #reset()}. */
  public boolean isPoisoned() {
    return poisoned.get();
  }

  /** Returns the limits this instance was created with. */
  public ResourceLimits getResourceLimits() {
    return limits;
  }

  /** Returns whether {@link #loadCode(byte[])} has succeeded since the last {@link #reset()}. */
  public boolean isCodeLoaded() {
    return codeLoaded.get();
  }

  /** Returns whether {@link #close()} has been called. */
  public boolean isClosed() {
    return closed.get();
  }

  /** Returns the guest's current linear memory size in 64 KiB WASM pages. */
  public synchronized int getHeapPages() {
    checkNotClosed();
    return Math.toIntExact(getHeapPagesFunc.apply()[0]);
  }

  /** Returns the size of the shared golden snapshot in 64 KiB WASM pages. */
  public int getGoldenMemoryPages() {
    return goldenMemoryPages;
  }

  /** Returns how many 64 KiB pages this instance has grown beyond the golden snapshot. */
  public int getHeapGrowthPages() {
    return getHeapPages() - goldenMemoryPages;
  }

  /**
   * Returns whether this instance is safe to reuse: not closed, not poisoned, and its heap has not
   * grown by more than {@code maxGrowthPages} 64 KiB pages beyond the golden snapshot.
   */
  public boolean isHealthy(int maxGrowthPages) {
    if (closed.get() || poisoned.get()) {
      return false;
    }
    try {
      return getHeapGrowthPages() <= maxGrowthPages;
    } catch (IllegalStateException e) {
      return false;
    }
  }

  /**
   * Marks this instance closed; subsequent calls throw {@link IllegalStateException}. Idempotent.
   * Memory is reclaimed by garbage collection once the instance is unreachable.
   */
  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
  }

  /** Returns a short random identifier for this instance, useful for logging. */
  public String getInstanceId() {
    return instanceId;
  }

  /**
   * Returns the guest-visible scratch directory path ({@code /work}). Because the root path passed
   * to {@link PythonExecutorFactory#createInstance(Path)} is mounted at {@code /}, this corresponds
   * to the {@code work} subdirectory of that host path.
   */
  public String getGuestWorkPath() {
    return "/work";
  }

  private void checkNotClosed() {
    if (closed.get()) {
      throw new IllegalStateException("PythonInstance has been closed");
    }
    if (poisoned.get()) {
      throw new IllegalStateException(
        "PythonInstance has been poisoned after timeout — call reset() before reuse"
      );
    }
  }

  private String readStdout() {
    int stdoutLen = Math.toIntExact(getStdoutLenFunc.apply()[0]);
    if (stdoutLen <= 0) {
      return "";
    }

    long maxOutput = limits.maximumOutputBytes();
    if (stdoutLen > maxOutput) {
      throw new PythonExecutionException(
        "stdout size " + stdoutLen + " bytes exceeds limit of " + maxOutput + " bytes"
      );
    }

    int outBufPtr = Math.toIntExact(allocFunc.apply(stdoutLen)[0]);
    try {
      getStdoutFunc.apply(outBufPtr, stdoutLen);
      byte[] stdoutBytes = wasmInstance.memory().readBytes(outBufPtr, stdoutLen);
      return new String(stdoutBytes, StandardCharsets.UTF_8);
    } finally {
      deallocFunc.apply(outBufPtr, stdoutLen);
    }
  }

  private String readStderr() {
    int stderrLen = Math.toIntExact(getStderrLenFunc.apply()[0]);
    if (stderrLen <= 0) {
      return "";
    }

    long maxOutput = limits.maximumOutputBytes();
    if (stderrLen > maxOutput) {
      throw new PythonExecutionException(
        "stderr size " + stderrLen + " bytes exceeds limit of " + maxOutput + " bytes"
      );
    }

    int errBufPtr = Math.toIntExact(allocFunc.apply(stderrLen)[0]);
    try {
      getStderrFunc.apply(errBufPtr, stderrLen);
      byte[] stderrBytes = wasmInstance.memory().readBytes(errBufPtr, stderrLen);
      return new String(stderrBytes, StandardCharsets.UTF_8);
    } finally {
      deallocFunc.apply(errBufPtr, stderrLen);
    }
  }

  /**
   * Thrown by {@link #compile(String)} when source fails to compile (e.g. a syntax error). The
   * message contains the Python error output when available.
   */
  public static class PythonCompilationException extends RuntimeException {

    public PythonCompilationException(String message) {
      super(message);
    }
  }

  /**
   * Thrown when the WASM runtime traps during execution, or when captured output exceeds {@link
   * ResourceLimits#maximumOutputBytes()}. Ordinary Python exceptions do not throw this — they are
   * reported via {@link PythonResult#exitCode()} and {@link PythonResult#stderr()}.
   */
  public static class PythonExecutionException extends RuntimeException {

    public PythonExecutionException(String message) {
      super(message);
    }

    public PythonExecutionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Thrown by the timed {@link PythonExecutorFactory}{@code .runOnWasmThread} overloads when a task
   * does not complete within the timeout. The associated instance is poisoned and should usually be
   * discarded.
   */
  public static class PythonTimeoutException extends RuntimeException {

    public PythonTimeoutException(String message) {
      super(message);
    }

    public PythonTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
