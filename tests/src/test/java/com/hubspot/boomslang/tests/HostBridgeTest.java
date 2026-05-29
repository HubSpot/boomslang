package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HostBridgeTest {

  private static final List<String> LOG_MESSAGES = new ArrayList<>();
  private static PythonExecutorFactory factory;

  @BeforeAll
  static void setUp() {
    factory =
      PythonExecutorFactory
        .builder()
        .withStdlibPath(SharedTestSetup.createRootPath())
        .addExtension(
          HostBridge
            .builder()
            .withFunction(
              "add",
              args -> {
                String[] parts = args.replace("[", "").replace("]", "").split(",");
                int sum =
                  Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim());
                return String.valueOf(sum);
              }
            )
            .withFunction("echo", args -> args)
            .withLogHandler((level, message) ->
              LOG_MESSAGES.add("[" + level + "] " + message)
            )
            .buildExtension()
        )
        .build();
  }

  @Test
  void itCallsNamedHostFunction() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        "from boomslang_host import call; print(call('add', '[3, 4]'))"
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("7");
  }

  @Test
  void itEchoesArgs() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        "from boomslang_host import call; print(call('echo', '{\"hello\": \"world\"}'))"
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("hello");
    assertThat(result.stdout()).contains("world");
  }

  @Test
  void itGathersAsyncHostCalls() {
    ExecutorService rpcExecutor = Executors.newFixedThreadPool(2);
    CountDownLatch bothStarted = new CountDownLatch(2);
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    Path asyncRoot = SharedTestSetup.createRootPath();
    PythonExecutorFactory asyncFactory = PythonExecutorFactory
      .builder()
      .withStdlibPath(asyncRoot)
      .withAsyncioSupport()
      .addExtension(
        HostBridge
          .builder()
          .withAsyncFunction(
            "rpc",
            args ->
              CompletableFuture.supplyAsync(
                () -> {
                  int current = inFlight.incrementAndGet();
                  maxInFlight.accumulateAndGet(current, Math::max);
                  bothStarted.countDown();
                  try {
                    if (!bothStarted.await(2, TimeUnit.SECONDS)) {
                      throw new RuntimeException("Timed out waiting for parallel starts");
                    }
                    Thread.sleep(25);
                    return args;
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                  } finally {
                    inFlight.decrementAndGet();
                  }
                },
                rpcExecutor
              )
          )
          .withLogHandler((level, message) -> {})
          .buildExtension()
      )
      .build();

    try {
      PythonResult result = asyncFactory.runOnWasmThread(() -> {
        PythonInstance instance = asyncFactory.createInstance(asyncRoot);
        return instance.execute(
          String.join(
            "\n",
            "import asyncio",
            "from boomslang_host.asyncio import async_call",
            "async def main():",
            "    first = async_call('rpc', 'first')",
            "    second = async_call('rpc', 'second')",
            "    results = await asyncio.gather(first, second)",
            "    print('|'.join(sorted(results)))",
            "asyncio.run(main())"
          )
        );
      });

      assertThat(result.stderr()).as("stderr").isEmpty();
      assertThat(result.exitCode()).isEqualTo(0);
      assertThat(result.stdout().trim()).isEqualTo("first|second");
      assertThat(maxInFlight.get()).isEqualTo(2);
    } finally {
      rpcExecutor.shutdownNow();
    }
  }

  @Test
  void itLogsFromPython() {
    LOG_MESSAGES.clear();

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        "from boomslang_host import log; log(2, 'hello from python')"
      );
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(LOG_MESSAGES).contains("[2] hello from python");
  }
}
