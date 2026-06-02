package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.hubspot.boomslang.AsyncHostRegistry;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
            "from boomslang_host.asyncio import async_call, install",
            "install()",
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
  void itReportsAsyncProtocolVersion() {
    // Routed by HostBridge to the AsyncHostRegistry regardless of registered handlers; only uses
    // the generic call() bridge, so it validates the version handshake the client negotiates on.
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        "from boomslang_host import call; print(call('__async_protocol__', ''))"
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("1");
  }

  @Test
  void itAwaitsSharedRegistryTokensWithoutGenericAsyncHandlers() {
    // Mirrors how generated extension async functions work: a token is created directly on the
    // shared registry and awaited via from_host_token, with no named async handler registered.
    Path asyncRoot = SharedTestSetup.createRootPath();
    AsyncHostRegistry asyncRegistry = new AsyncHostRegistry();
    PythonExecutorFactory asyncFactory = PythonExecutorFactory
      .builder()
      .withStdlibPath(asyncRoot)
      .addExtension(
        HostBridge
          .builder()
          .withAsyncRegistry(asyncRegistry)
          .withFunction(
            "make_token",
            args ->
              Long.toString(
                asyncRegistry.start(CompletableFuture.completedFuture("typed-result"))
              )
          )
          .withLogHandler((level, message) -> {})
          .buildExtension()
      )
      .build();

    PythonResult result = asyncFactory.runOnWasmThread(() -> {
      PythonInstance instance = asyncFactory.createInstance(asyncRoot);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "from boomslang_host import call",
          "from boomslang_host.asyncio import from_host_token, install",
          "install()",
          "async def main():",
          "    token = int(call('make_token', ''))",
          "    print(await from_host_token(token))",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("typed-result");
  }

  @Test
  void itPropagatesFailedAsyncHostCalls() {
    Path asyncRoot = SharedTestSetup.createRootPath();
    PythonExecutorFactory asyncFactory = PythonExecutorFactory
      .builder()
      .withStdlibPath(asyncRoot)
      .addExtension(
        HostBridge
          .builder()
          .withAsyncFunction(
            "fail",
            args -> CompletableFuture.failedFuture(new IllegalStateException("boom"))
          )
          .withLogHandler((level, message) -> {})
          .buildExtension()
      )
      .build();

    PythonResult result = asyncFactory.runOnWasmThread(() -> {
      PythonInstance instance = asyncFactory.createInstance(asyncRoot);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "from boomslang_host.asyncio import HostAsyncError, async_call, install",
          "install()",
          "async def main():",
          "    try:",
          "        await async_call('fail', '')",
          "    except HostAsyncError as err:",
          "        print(str(err))",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("boom");
  }

  @Test
  void itCancelsAsyncHostCallOnWaitForTimeout() {
    Path asyncRoot = SharedTestSetup.createRootPath();
    AtomicBoolean cancelled = new AtomicBoolean(false);
    CompletableFuture<String> future = new CompletableFuture<>() {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        cancelled.set(true);
        return super.cancel(mayInterruptIfRunning);
      }
    };
    PythonExecutorFactory asyncFactory = PythonExecutorFactory
      .builder()
      .withStdlibPath(asyncRoot)
      .addExtension(
        HostBridge
          .builder()
          .withAsyncFunction("never", args -> future)
          .withLogHandler((level, message) -> {})
          .buildExtension()
      )
      .build();

    PythonResult result = asyncFactory.runOnWasmThread(() -> {
      PythonInstance instance = asyncFactory.createInstance(asyncRoot);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "from boomslang_host.asyncio import async_call, install",
          "install()",
          "async def main():",
          "    try:",
          "        await asyncio.wait_for(async_call('never', ''), 0.01)",
          "    except TimeoutError:",
          "        print('timed out')",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("timed out");
    assertThat(cancelled.get()).isTrue();
  }

  @Test
  void itGathersManyLargeResultsWithoutExceedingHostBuffer() {
    // Four ~400 KB results complete together (~1.6 MB total). With values inlined into the poll
    // response this would blow the 1 MB host-call buffer; fetching each result separately keeps
    // every transfer bounded.
    int chunk = 400_000;
    int count = 4;
    Path asyncRoot = SharedTestSetup.createRootPath();
    PythonExecutorFactory asyncFactory = PythonExecutorFactory
      .builder()
      .withStdlibPath(asyncRoot)
      .addExtension(
        HostBridge
          .builder()
          .withAsyncFunction(
            "big",
            args -> CompletableFuture.completedFuture("x".repeat(chunk))
          )
          .withLogHandler((level, message) -> {})
          .buildExtension()
      )
      .build();

    PythonResult result = asyncFactory.runOnWasmThread(() -> {
      PythonInstance instance = asyncFactory.createInstance(asyncRoot);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "from boomslang_host.asyncio import async_call, install",
          "install()",
          "async def main():",
          "    calls = [async_call('big', str(i)) for i in range(" + count + ")]",
          "    results = await asyncio.gather(*calls)",
          "    print(sum(len(r) for r in results))",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo(Integer.toString(chunk * count));
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
