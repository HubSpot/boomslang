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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InstanceExtensionTest {

  @Test
  void itUsesDifferentHandlersForConcurrentInstances() throws Exception {
    CountDownLatch bothHandlersStarted = new CountDownLatch(2);
    AtomicInteger extensionNumber = new AtomicInteger();
    Path rootPath = SharedTestSetup.createRootPath();
    PythonExecutorFactory factory =
        PythonExecutorFactory.builder()
            .withStdlibPath(rootPath)
            .addExtension(
                () ->
                    extension("instance-" + extensionNumber.getAndIncrement(), bothHandlersStarted))
            .build();

    CompletableFuture<PythonResult> firstResult = execute(factory, rootPath);
    CompletableFuture<PythonResult> secondResult = execute(factory, rootPath);

    assertThat(
            List.of(
                firstResult.get(10, TimeUnit.SECONDS).stdout().trim(),
                secondResult.get(10, TimeUnit.SECONDS).stdout().trim()))
        .containsExactlyInAnyOrder("instance-1", "instance-2");
  }

  @Test
  void itRejectsInstanceExtensionsWithDifferentImports() {
    AtomicInteger extensionNumber = new AtomicInteger();
    PythonExecutorFactory factory =
        PythonExecutorFactory.builder()
            .withStdlibPath(SharedTestSetup.createRootPath())
            .addExtension(
                () -> {
                  if (extensionNumber.getAndIncrement() == 0) {
                    return HostBridge.builder().buildExtension();
                  }
                  return new BoomslangExtension() {
                    @Override
                    public String name() {
                      return "empty";
                    }

                    @Override
                    public HostFunction[] hostFunctions() {
                      return new HostFunction[0];
                    }
                  };
                })
            .build();

    assertThatThrownBy(() -> factory.createInstance(SharedTestSetup.createRootPath()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("do not match the runtime image initialization imports");
  }

  private static CompletableFuture<PythonResult> execute(
      PythonExecutorFactory factory, Path rootPath) {
    return CompletableFuture.supplyAsync(
        () ->
            factory.runOnWasmThread(
                () -> {
                  PythonInstance instance = factory.createInstance(rootPath);
                  return instance.execute(
                      "from boomslang_host import call; print(call('instance', ''))");
                }));
  }

  private static BoomslangExtension extension(String response, CountDownLatch bothHandlersStarted) {
    return HostBridge.builder()
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
            })
        .withLogHandler((level, message) -> {})
        .buildExtension();
  }
}
