package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code Builder.withWasmModule(WasmModule)} plumbing: when a pre-parsed module is
 * supplied, the factory uses it verbatim (skipping the internal classpath parse) and still runs
 * Python. This is the library-layer contract that the aviator-cpython {@code .meta} reclaim relies
 * on; the end-to-end reclaim + AOT parity proof lives in aviator-cpython-tests
 * ({@code MetaModuleReclaimTest}).
 */
class MetaModuleBuilderTest {

  private static final String WASM_RESOURCE = "/python/bin/boomslang.wasm";

  private static WasmModule parseBundledModule() {
    try (
      InputStream in = MetaModuleBuilderTest.class.getResourceAsStream(WASM_RESOURCE)
    ) {
      if (in == null) {
        throw new IllegalStateException(
          "Bundled WASM resource missing: " + WASM_RESOURCE
        );
      }
      return Parser.parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read " + WASM_RESOURCE, e);
    }
  }

  @Test
  void itUsesTheSuppliedModuleVerbatim() {
    WasmModule supplied = parseBundledModule();

    PythonExecutorFactory factory = PythonExecutorFactory
      .builder()
      .withStdlibPath(SharedTestSetup.createRootPath())
      .withWasmModule(supplied)
      .addHostFunctions(SharedTestSetup.defaultHostFunctions())
      .build();

    // The factory must reuse the exact module instance we handed it, proving it did not re-parse
    // the classpath binary.
    assertThat(factory.getRuntimeImage().getModule()).isSameAs(supplied);
  }

  @Test
  void itExecutesWithASuppliedModule() {
    PythonExecutorFactory factory = PythonExecutorFactory
      .builder()
      .withStdlibPath(SharedTestSetup.createRootPath())
      .withWasmModule(parseBundledModule())
      .addHostFunctions(SharedTestSetup.defaultHostFunctions())
      .build();

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute("print('hello from supplied module')");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("hello from supplied module");
  }
}
