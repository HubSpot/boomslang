package com.hubspot.boomslang.benchmarks;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(
  value = 1,
  jvmArgs = {
    "-XX:+UseG1GC",
    "-XX:CompileThreshold=1500",
    "-XX:+UnlockDiagnosticVMOptions",
    "-XX:-DontCompileHugeMethods",
  }
)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
public class PythonCompileBenchmark {

  private static final String ARGPARSE_RESOURCE =
    "python/usr/local/lib/python3.14/argparse.py";

  private PythonExecutorFactory factory;
  private PythonInstance instance;
  private String argparseSource;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    try (
      InputStream in =
        PythonCompileBenchmark.class.getClassLoader()
          .getResourceAsStream(ARGPARSE_RESOURCE)
    ) {
      argparseSource = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    factory =
      PythonExecutorFactory
        .builder()
        .withStdlibPath(createRootPath())
        .addExtension(
          HostBridge
            .builder()
            .withCallHandler((name, args) -> "{}")
            .withLogHandler((level, message) -> {})
            .buildExtension()
        )
        .build();
    instance = factory.runOnWasmThread(() -> factory.createInstance(createRootPath()));
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    instance.close();
  }

  @Benchmark
  public byte[] compileOneLiner() {
    return factory.runOnWasmThread(() -> instance.compile("x = sum(range(1000))"));
  }

  @Benchmark
  public byte[] compileArgparse() {
    return factory.runOnWasmThread(() -> instance.compile(argparseSource));
  }

  private static Path createRootPath() {
    return Jimfs
      .newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build())
      .getPath("/");
  }
}
