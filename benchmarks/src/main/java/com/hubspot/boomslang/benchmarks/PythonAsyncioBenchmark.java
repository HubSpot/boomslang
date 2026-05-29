package com.hubspot.boomslang.benchmarks;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

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
public class PythonAsyncioBenchmark {

  private static final String ASYNCIO_NOOP = String.join(
    "\n",
    "import asyncio",
    "from boomslang_host.asyncio import install",
    "install()",
    "async def main():",
    "    return 42",
    "print(asyncio.run(main()))"
  );

  private static final String ASYNCIO_GATHER_SLEEP_ZERO = String.join(
    "\n",
    "import asyncio",
    "from boomslang_host.asyncio import install",
    "install()",
    "async def item(value):",
    "    await asyncio.sleep(0)",
    "    return value",
    "async def main():",
    "    results = await asyncio.gather(*(item(i) for i in range(10)))",
    "    print(sum(results))",
    "asyncio.run(main())"
  );

  private static final String HOST_ASYNC_GATHER_COMPLETED = String.join(
    "\n",
    "import asyncio",
    "from boomslang_host.asyncio import async_call, install",
    "install()",
    "async def main():",
    "    results = await asyncio.gather(",
    "        async_call('completed', '1'),",
    "        async_call('completed', '2'),",
    "        async_call('completed', '3'),",
    "        async_call('completed', '4'),",
    "    )",
    "    print(''.join(results))",
    "asyncio.run(main())"
  );

  private static final String HOST_ASYNC_GATHER_EXECUTOR = String.join(
    "\n",
    "import asyncio",
    "from boomslang_host.asyncio import async_call, install",
    "install()",
    "async def main():",
    "    results = await asyncio.gather(",
    "        async_call('executor', '1'),",
    "        async_call('executor', '2'),",
    "    )",
    "    print(''.join(sorted(results)))",
    "asyncio.run(main())"
  );

  private Path rootPath;
  private ExecutorService hostExecutor;
  private PythonExecutorFactory factory;
  private PythonInstance instance;
  private byte[] asyncioNoopBytecode;
  private byte[] asyncioGatherSleepZeroBytecode;
  private byte[] hostAsyncGatherCompletedBytecode;
  private byte[] hostAsyncGatherExecutorBytecode;

  @Setup(Level.Trial)
  public void setupFactory() {
    rootPath = createRootPath();
    hostExecutor = Executors.newFixedThreadPool(2);
    factory =
      PythonExecutorFactory
        .builder()
        .withStdlibPath(rootPath)
        .addExtension(
          HostBridge
            .builder()
            .withAsyncFunction(
              "completed",
              args -> CompletableFuture.completedFuture(args)
            )
            .withAsyncFunction(
              "executor",
              args -> CompletableFuture.supplyAsync(() -> args, hostExecutor)
            )
            .withLogHandler((level, message) -> {})
            .buildExtension()
        )
        .build();

    asyncioNoopBytecode = compile(ASYNCIO_NOOP);
    asyncioGatherSleepZeroBytecode = compile(ASYNCIO_GATHER_SLEEP_ZERO);
    hostAsyncGatherCompletedBytecode = compile(HOST_ASYNC_GATHER_COMPLETED);
    hostAsyncGatherExecutorBytecode = compile(HOST_ASYNC_GATHER_EXECUTOR);
  }

  @TearDown(Level.Trial)
  public void tearDownFactory() {
    if (hostExecutor != null) {
      hostExecutor.shutdownNow();
    }
  }

  @Setup(Level.Invocation)
  public void setupInstance() {
    instance = factory.runOnWasmThread(() -> factory.createInstance(rootPath));
  }

  @TearDown(Level.Invocation)
  public void tearDownInstance() {
    if (instance != null) {
      instance.close();
    }
  }

  @Benchmark
  public PythonResult asyncioNoop() {
    return factory.runOnWasmThread(() -> instance.loadCode(asyncioNoopBytecode));
  }

  @Benchmark
  public PythonResult asyncioGatherSleepZero() {
    return factory.runOnWasmThread(() -> instance.loadCode(asyncioGatherSleepZeroBytecode)
    );
  }

  @Benchmark
  public PythonResult hostAsyncGatherCompletedFutures() {
    return factory.runOnWasmThread(() ->
      instance.loadCode(hostAsyncGatherCompletedBytecode)
    );
  }

  @Benchmark
  public PythonResult hostAsyncGatherExecutorFutures() {
    return factory.runOnWasmThread(() ->
      instance.loadCode(hostAsyncGatherExecutorBytecode)
    );
  }

  public static void main(String[] args) throws Exception {
    Options opt = new OptionsBuilder()
      .include(PythonAsyncioBenchmark.class.getSimpleName())
      .build();
    new Runner(opt).run();
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
