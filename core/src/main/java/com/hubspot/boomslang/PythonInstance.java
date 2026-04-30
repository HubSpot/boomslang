package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.types.MemoryLimits;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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
  private final AtomicBoolean codeLoaded = new AtomicBoolean(false);
  private final AtomicBoolean closed = new AtomicBoolean(false);

  private final String instanceId;
  private final Path workDir;
  private final Path libDir;
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

  public PythonInstance(RuntimeImage image, HostFunction[] hostFunctions) {
    this(image, hostFunctions, null);
  }

  public PythonInstance(
      RuntimeImage image, HostFunction[] hostFunctions, @Nullable Path externalWorkDir) {
    this.instanceId = UUID.randomUUID().toString().substring(0, 8);
    this.libDir = image.getExtractedPythonPath().resolve("lib-" + instanceId);

    Path jimfsWorkDir = image.getExtractedPythonPath().resolve("work");
    try {
      Files.createDirectories(jimfsWorkDir);
      Files.createDirectories(libDir);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to create work directories", e);
    }

    this.workDir = externalWorkDir != null ? externalWorkDir : jimfsWorkDir;
    this.stdinStream = new ResettableByteArrayInputStream();

    WasiOptions.Builder wasiOptionsBuilder =
        WasiOptions.builder()
            .withStdin(stdinStream)
            .withDirectory("/usr", image.getExtractedPythonPath().resolve("usr"))
            .withDirectory("/lib", libDir)
            .withDirectory("/work", workDir)
            .withEnvironment("PYTHONHOME", "/usr/local");

    WasiPreview1 wasi = WasiPreview1.builder().withOptions(wasiOptionsBuilder.build()).build();

    Store store = new Store().addFunction(wasi.toHostFunctions());
    for (HostFunction hf : hostFunctions) {
      store.addFunction(hf);
    }

    byte[] goldenMemory = image.getGoldenMemory();
    int goldenMemoryPages = image.getGoldenMemoryPages();

    CopyOnWriteMemory[] memoryRef = new CopyOnWriteMemory[1];
    Instance.Builder instanceBuilder =
        Instance.builder(image.getModule())
            .withImportValues(store.toImportValues())
            .withMemoryFactory(
                limits -> {
                  MemoryLimits adjustedLimits =
                      new MemoryLimits(
                          Math.max(limits.initialPages(), goldenMemoryPages),
                          limits.maximumPages());
                  memoryRef[0] = new CopyOnWriteMemory(goldenMemory, adjustedLimits);
                  return memoryRef[0];
                });

    if (image.getMachineFactory() != null) {
      instanceBuilder.withMachineFactory(image.getMachineFactory());
    }

    this.wasmInstance = store.instantiate("python", imports -> instanceBuilder.build());
    this.cowMemory =
        Objects.requireNonNull(
            memoryRef[0], "CopyOnWriteMemory was not created during instantiation");

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
  }

  public Path getWorkDir() {
    return workDir;
  }

  public Path getLibDir() {
    return libDir;
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

      long[] result = compileSourceFunc.apply(sourcePtr, sourceLen, outputPtr, MAX_BYTECODE_SIZE);
      int bytecodeLen = Math.toIntExact(result[0]);

      if (bytecodeLen < 0) {
        String stderr = readStderr();
        String errorMsg =
            stderr.isEmpty() ? "Compilation failed with error code: " + bytecodeLen : stderr;
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
        String message =
            stderr.isEmpty()
                ? e.getMessage()
                : (e.getMessage() != null ? stderr + "\nCaused by: " + e.getMessage() : stderr);
        throw new PythonExecutionException(message, e);
      }
      int exitCode = Math.toIntExact(result[0]);

      String stdout = readStdout();
      String stderr = readStderr();

      codeLoaded.set(true);

      long executionTimeMs = (System.nanoTime() - startTime) / 1_000_000;

      return PythonResult.builder()
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
    byte[] argsBytes = argsJson != null ? argsJson.getBytes(StandardCharsets.UTF_8) : new byte[0];

    int funcNamePtr = Math.toIntExact(allocFunc.apply(funcNameBytes.length)[0]);
    int argsPtr = argsBytes.length > 0 ? Math.toIntExact(allocFunc.apply(argsBytes.length)[0]) : 0;

    try {
      wasmInstance.memory().write(funcNamePtr, funcNameBytes);
      if (argsBytes.length > 0) {
        wasmInstance.memory().write(argsPtr, argsBytes);
      }

      long[] result =
          executeFunctionFunc.apply(funcNamePtr, funcNameBytes.length, argsPtr, argsBytes.length);
      int exitCode = Math.toIntExact(result[0]);

      String stdout = readStdout();
      String stderr = readStderr();

      long executionTimeMs = (System.nanoTime() - startTime) / 1_000_000;

      return PythonResult.builder()
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
      String message =
          stderr.isEmpty()
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

    return PythonResult.builder()
        .setStdout(stdout)
        .setStderr(stderr)
        .setExitCode(exitCode)
        .setExecutionTimeMs(executionTimeMs)
        .build();
  }

  public synchronized void reset() {
    checkNotClosed();
    cowMemory.reset();
    codeLoaded.set(false);
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
    if (closed.get()) {
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
    deleteLibDir();
  }

  private void deleteLibDir() {
    try {
      if (Files.exists(libDir)) {
        Files.walkFileTree(
            libDir,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                  throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                  throws IOException {
                if (exc != null) {
                  throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
              }
            });
      }
    } catch (IOException e) {
      LOG.warn("Failed to clean up lib directory: {}", libDir, e);
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
  }

  private String readStdout() {
    int stdoutLen = Math.toIntExact(getStdoutLenFunc.apply()[0]);
    if (stdoutLen <= 0) {
      return "";
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

    int errBufPtr = Math.toIntExact(allocFunc.apply(stderrLen)[0]);
    try {
      getStderrFunc.apply(errBufPtr, stderrLen);
      byte[] stderrBytes = wasmInstance.memory().readBytes(errBufPtr, stderrLen);
      return new String(stderrBytes, StandardCharsets.UTF_8);
    } finally {
      deallocFunc.apply(errBufPtr, stderrLen);
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
}
