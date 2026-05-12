package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  public synchronized void setStdin(byte[] data) {
    stdinStream.resetData(data);
  }

  public synchronized void clearStdin() {
    stdinStream.clear();
  }

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

  public synchronized void reset() {
    if (closed.get()) {
      throw new IllegalStateException("PythonInstance has been closed");
    }
    cowMemory.reset();
    codeLoaded.set(false);
    poisoned.set(false);
    clearStdin();
  }

  public void poison() {
    poisoned.set(true);
  }

  public boolean isPoisoned() {
    return poisoned.get();
  }

  public ResourceLimits getResourceLimits() {
    return limits;
  }

  public boolean isCodeLoaded() {
    return codeLoaded.get();
  }

  public boolean isClosed() {
    return closed.get();
  }

  public synchronized int getHeapPages() {
    checkNotClosed();
    return Math.toIntExact(getHeapPagesFunc.apply()[0]);
  }

  public int getGoldenMemoryPages() {
    return goldenMemoryPages;
  }

  public int getHeapGrowthPages() {
    return getHeapPages() - goldenMemoryPages;
  }

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

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
  }

  public String getInstanceId() {
    return instanceId;
  }

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
    boolean truncated = stdoutLen > maxOutput;
    int readLen = truncated ? Math.toIntExact(maxOutput) : stdoutLen;

    int outBufPtr = Math.toIntExact(allocFunc.apply(readLen)[0]);
    try {
      getStdoutFunc.apply(outBufPtr, readLen);
      byte[] stdoutBytes = wasmInstance.memory().readBytes(outBufPtr, readLen);
      String result = new String(stdoutBytes, StandardCharsets.UTF_8);
      if (truncated) {
        LOG.warn("stdout truncated: {} bytes exceeds limit {}", stdoutLen, maxOutput);
        return result + "\n[truncated: output exceeded " + maxOutput + " byte limit]";
      }
      return result;
    } finally {
      deallocFunc.apply(outBufPtr, readLen);
    }
  }

  private String readStderr() {
    int stderrLen = Math.toIntExact(getStderrLenFunc.apply()[0]);
    if (stderrLen <= 0) {
      return "";
    }

    long maxOutput = limits.maximumOutputBytes();
    boolean truncated = stderrLen > maxOutput;
    int readLen = truncated ? Math.toIntExact(maxOutput) : stderrLen;

    int errBufPtr = Math.toIntExact(allocFunc.apply(readLen)[0]);
    try {
      getStderrFunc.apply(errBufPtr, readLen);
      byte[] stderrBytes = wasmInstance.memory().readBytes(errBufPtr, readLen);
      String result = new String(stderrBytes, StandardCharsets.UTF_8);
      if (truncated) {
        LOG.warn("stderr truncated: {} bytes exceeds limit {}", stderrLen, maxOutput);
        return result + "\n[truncated: output exceeded " + maxOutput + " byte limit]";
      }
      return result;
    } finally {
      deallocFunc.apply(errBufPtr, readLen);
    }
  }

  public static class PythonCompilationException extends RuntimeException {

    public PythonCompilationException(String message) {
      super(message);
    }
  }

  public static class PythonExecutionException extends RuntimeException {

    public PythonExecutionException(String message) {
      super(message);
    }

    public PythonExecutionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static class PythonTimeoutException extends RuntimeException {

    public PythonTimeoutException(String message) {
      super(message);
    }

    public PythonTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
