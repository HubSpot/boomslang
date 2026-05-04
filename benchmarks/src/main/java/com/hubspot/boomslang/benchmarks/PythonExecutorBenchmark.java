package com.hubspot.boomslang.benchmarks;

import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
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
public class PythonExecutorBenchmark {

  private PythonExecutorFactory factory;
  private PythonInstance instance;
  private byte[] helloWorldBytecode;
  private byte[] fibBytecode;
  private byte[] numpyBytecode;

  @Setup(Level.Trial)
  public void setupFactory() {
    factory =
      PythonExecutorFactory
        .builder()
        .addHostFunctions(
          HostBridge
            .builder()
            .withCallHandler((name, args) -> "{}")
            .withLogHandler((level, message) -> {})
            .build()
        )
        .build();

    helloWorldBytecode =
      factory.runOnWasmThread(() -> {
        PythonInstance tmp = factory.createInstance();
        return tmp.compile("print('hello')");
      });

    fibBytecode =
      factory.runOnWasmThread(() -> {
        PythonInstance tmp = factory.createInstance();
        return tmp.compile(
          String.join(
            "\n",
            "def fib(n):",
            "    a, b = 0, 1",
            "    for _ in range(n):",
            "        a, b = b, a + b",
            "    return a",
            "print(fib(100))"
          )
        );
      });

    numpyBytecode =
      factory.runOnWasmThread(() -> {
        PythonInstance tmp = factory.createInstance();
        return tmp.compile(
          String.join(
            "\n",
            "import numpy as np",
            "a = np.random.randn(1000)",
            "print(a.mean(), a.std())"
          )
        );
      });
  }

  @Setup(Level.Invocation)
  public void setupInstance() {
    instance =
      factory.runOnWasmThread(() -> {
        return factory.createInstance();
      });
  }

  @TearDown(Level.Invocation)
  public void tearDownInstance() {
    if (instance != null) {
      instance.close();
    }
  }

  @Benchmark
  public PythonInstance instanceCreation() {
    return factory.runOnWasmThread(() -> factory.createInstance());
  }

  @Benchmark
  public PythonResult helloWorld() {
    return factory.runOnWasmThread(() -> instance.execute("print('hello')"));
  }

  @Benchmark
  public PythonResult helloWorldPrecompiled() {
    return factory.runOnWasmThread(() -> instance.loadCode(helloWorldBytecode));
  }

  @Benchmark
  public PythonResult fibonacci() {
    return factory.runOnWasmThread(() ->
      instance.execute(
        String.join(
          "\n",
          "def fib(n):",
          "    a, b = 0, 1",
          "    for _ in range(n):",
          "        a, b = b, a + b",
          "    return a",
          "print(fib(100))"
        )
      )
    );
  }

  @Benchmark
  public PythonResult fibonacciPrecompiled() {
    return factory.runOnWasmThread(() -> instance.loadCode(fibBytecode));
  }

  @Benchmark
  public PythonResult numpyCompute() {
    return factory.runOnWasmThread(() ->
      instance.execute(
        String.join(
          "\n",
          "import numpy as np",
          "a = np.random.randn(1000)",
          "print(a.mean(), a.std())"
        )
      )
    );
  }

  @Benchmark
  public PythonResult numpyPrecompiled() {
    return factory.runOnWasmThread(() -> instance.loadCode(numpyBytecode));
  }

  @Benchmark
  public byte[] compile() {
    return factory.runOnWasmThread(() -> instance.compile("x = sum(range(1000))"));
  }

  public static void main(String[] args) throws Exception {
    Options opt = new OptionsBuilder()
      .include(PythonExecutorBenchmark.class.getSimpleName())
      .build();
    new Runner(opt).run();
  }
}
