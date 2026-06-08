package com.hubspot.boomslang.benchmarks;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
public class PythonAsyncioReusedInstanceBenchmark {

  private static final String ASYNCIO_IMPORT_PREWARMED = String.join(
    "\n",
    "import asyncio",
    "async def main():",
    "    print(type(asyncio.get_running_loop()).__name__)",
    "asyncio.run(main())"
  );

  private static final String ASYNCIO_IMPORT_FORCED_RUNTIME = String.join(
    "\n",
    "import sys",
    "for name in list(sys.modules):",
    "    if name == 'boomslang_host.asyncio' or name == 'asyncio' or name.startswith('asyncio.'):",
    "        sys.modules.pop(name, None)",
    "import asyncio",
    "import boomslang_host.asyncio",
    "async def main():",
    "    print(type(asyncio.get_running_loop()).__name__)",
    "asyncio.run(main())"
  );

  private Path rootPath;
  private PythonExecutorFactory factory;
  private PythonInstance instance;
  private byte[] asyncioImportPrewarmedBytecode;
  private byte[] asyncioImportForcedRuntimeBytecode;

  @Setup
  public void setup() {
    rootPath = createRootPath();
    factory =
      PythonExecutorFactory
        .builder()
        .withStdlibPath(rootPath)
        .addExtension(
          HostBridge.builder().withLogHandler((level, message) -> {}).buildExtension()
        )
        .build();

    asyncioImportPrewarmedBytecode = compile(ASYNCIO_IMPORT_PREWARMED);
    asyncioImportForcedRuntimeBytecode = compile(ASYNCIO_IMPORT_FORCED_RUNTIME);
    instance = factory.runOnWasmThread(() -> factory.createInstance(rootPath));
  }

  @TearDown
  public void tearDown() {
    if (instance != null) {
      instance.close();
    }
  }

  @Benchmark
  public PythonResult asyncioImportPrewarmedReusedInstance() {
    return factory.runOnWasmThread(() -> instance.loadCode(asyncioImportPrewarmedBytecode)
    );
  }

  @Benchmark
  public PythonResult asyncioImportForcedRuntimeReusedInstance() {
    return factory.runOnWasmThread(() ->
      instance.loadCode(asyncioImportForcedRuntimeBytecode)
    );
  }

  private byte[] compile(String script) {
    return factory.runOnWasmThread(() -> {
      PythonInstance tmp = factory.createInstance(rootPath);
      return tmp.compile(script);
    });
  }

  private static Path createRootPath() {
    return Jimfs
      .newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build())
      .getPath("/");
  }
}
