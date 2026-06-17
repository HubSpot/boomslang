package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.boomslang.BoomslangExtension;
import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InstanceExtensionTest {

  @Test
  void itUsesDifferentHandlersForConcurrentInstances() throws Exception {
    CountDownLatch bothHandlersStarted = new CountDownLatch(2);
    Path rootPath = SharedTestSetup.createRootPath();
    PythonExecutorFactory factory = PythonExecutorFactory
      .builder()
      .withStdlibPath(rootPath)
      .addExtension(() -> HostBridge.builder().buildExtension())
      .build();

    CompletableFuture<PythonResult> firstResult = execute(
      factory,
      rootPath,
      extension("first", bothHandlersStarted)
    );
    CompletableFuture<PythonResult> secondResult = execute(
      factory,
      rootPath,
      extension("second", bothHandlersStarted)
    );

    assertThat(
      List.of(
        firstResult.get(10, TimeUnit.SECONDS).stdout().trim(),
        secondResult.get(10, TimeUnit.SECONDS).stdout().trim()
      )
    )
      .containsExactlyInAnyOrder("first", "second");
  }

  @Test
  void itRejectsInstanceExtensionsWithDifferentImports() {
    PythonExecutorFactory factory = PythonExecutorFactory
      .builder()
      .withStdlibPath(SharedTestSetup.createRootPath())
      .addExtension(() -> HostBridge.builder().buildExtension())
      .build();
    BoomslangExtension extension = new BoomslangExtension() {
      @Override
      public String name() {
        return "empty";
      }

      @Override
      public HostFunction[] hostFunctions() {
        return new HostFunction[0];
      }
    };

    assertThatThrownBy(() ->
        factory.createInstance(SharedTestSetup.createRootPath(), extension)
      )
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("do not match the runtime image initialization imports");
  }

  private static CompletableFuture<PythonResult> execute(
    PythonExecutorFactory factory,
    Path rootPath,
    BoomslangExtension extension
  ) {
    return CompletableFuture.supplyAsync(() ->
      factory.runOnWasmThread(() -> {
        PythonInstance instance = factory.createInstance(rootPath, extension);
        return instance.execute(
          "from boomslang_host import call; print(call('instance', ''))"
        );
      })
    );
  }

  private static BoomslangExtension extension(
    String response,
    CountDownLatch bothHandlersStarted
  ) {
    return HostBridge
      .builder()
      .withFunction(
        "instance",
        args -> {
          bothHandlersStarted.countDown();
          try {
            if (!bothHandlersStarted.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Timed out waiting for both instances");
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          }
          return response;
        }
      )
      .withLogHandler((level, message) -> {})
      .buildExtension();
  }
}
