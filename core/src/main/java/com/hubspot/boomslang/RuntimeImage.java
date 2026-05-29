package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RuntimeImage {

  private static final Logger LOG = LoggerFactory.getLogger(RuntimeImage.class);
  private static final String INSTALL_ASYNCIO_SCRIPT =
    "from boomslang_host.asyncio import install\ninstall()";

  private final WasmModule module;
  private final Function<Instance, Machine> machineFactory;
  private final Path extractedPythonPath;
  private final byte[] goldenMemory;
  private final int goldenMemoryPages;

  private RuntimeImage(
    WasmModule module,
    Function<Instance, Machine> machineFactory,
    Path extractedPythonPath,
    byte[] goldenMemory,
    int goldenMemoryPages
  ) {
    this.module = module;
    this.machineFactory = machineFactory;
    this.extractedPythonPath = extractedPythonPath;
    this.goldenMemory = goldenMemory;
    this.goldenMemoryPages = goldenMemoryPages;
  }

  public static RuntimeImage create(
    WasmModule module,
    Function<Instance, Machine> machineFactory,
    Path extractedPythonPath,
    String pythonHome,
    @Nullable String pythonPath,
    boolean installAsyncio,
    HostFunction... hostFunctions
  ) {
    LOG.debug("Creating RuntimeImage with golden memory snapshot");
    long startTime = System.nanoTime();

    WasiOptions.Builder wasiBuilder = WasiOptions
      .builder()
      .withDirectory("/", extractedPythonPath)
      .withEnvironment("PYTHONHOME", pythonHome);
    if (pythonPath != null) {
      wasiBuilder.withEnvironment("PYTHONPATH", pythonPath);
    }
    WasiOptions wasiOptions = wasiBuilder.build();

    WasiPreview1 wasi = WasiPreview1.builder().withOptions(wasiOptions).build();

    Store store = new Store().addFunction(wasi.toHostFunctions());
    for (HostFunction hf : hostFunctions) {
      store.addFunction(hf);
    }

    Instance.Builder instanceBuilder = Instance
      .builder(module)
      .withImportValues(store.toImportValues());

    if (machineFactory != null) {
      instanceBuilder.withMachineFactory(machineFactory);
    }

    Instance initInstance = store.instantiate(
      "python-init",
      imports -> instanceBuilder.build()
    );

    if (installAsyncio) {
      executeInitScript(initInstance, INSTALL_ASYNCIO_SCRIPT);
    }

    int pages = getMemoryPages(initInstance);
    byte[] goldenMemory = initInstance.memory().readBytes(0, pages * 65536);

    long elapsedNanos = System.nanoTime() - startTime;
    LOG.info(
      "RuntimeImage created in {}ms, golden memory: {} pages ({} MB)",
      elapsedNanos / 1_000_000,
      pages,
      (pages * 65536) / (1024 * 1024)
    );

    return new RuntimeImage(
      module,
      machineFactory,
      extractedPythonPath,
      goldenMemory,
      pages
    );
  }

  public WasmModule getModule() {
    return module;
  }

  public Function<Instance, Machine> getMachineFactory() {
    return machineFactory;
  }

  public Path getExtractedPythonPath() {
    return extractedPythonPath;
  }

  public byte[] getGoldenMemory() {
    return goldenMemory;
  }

  public int getGoldenMemoryPages() {
    return goldenMemoryPages;
  }

  private static void executeInitScript(Instance instance, String script) {
    byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_8);
    ExportFunction allocFunc = instance.export("alloc");
    ExportFunction executeFunc = instance.export("execute");
    ExportFunction deallocFunc = instance.export("dealloc");
    int scriptPtr = Math.toIntExact(allocFunc.apply(scriptBytes.length)[0]);
    try {
      instance.memory().write(scriptPtr, scriptBytes);
      long[] result = executeFunc.apply(scriptPtr, scriptBytes.length);
      if (result[0] != 0) {
        throw new IllegalStateException(
          "Failed to install Boomslang asyncio event loop policy"
        );
      }
    } finally {
      deallocFunc.apply(scriptPtr, scriptBytes.length);
    }
  }

  private static int getMemoryPages(Instance instance) {
    try {
      ExportFunction getHeapPagesFunc = instance.export("get_heap_pages");
      return Math.toIntExact(getHeapPagesFunc.apply()[0]);
    } catch (Exception e) {
      LOG.debug("get_heap_pages export not found, using memory.pages()");
      return instance.memory().pages();
    }
  }
}
