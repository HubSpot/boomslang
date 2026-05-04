package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Store;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.WasmModule;
import java.nio.file.Path;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RuntimeImage {

  private static final Logger LOG = LoggerFactory.getLogger(RuntimeImage.class);

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
    HostFunction... hostFunctions
  ) {
    LOG.debug("Creating RuntimeImage with golden memory snapshot");
    long startTime = System.currentTimeMillis();

    WasiOptions wasiOptions = WasiOptions
      .builder()
      .withDirectory("/", extractedPythonPath)
      .withEnvironment("PYTHONHOME", "/usr/local")
      .build();

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

    int pages = getMemoryPages(initInstance);
    byte[] goldenMemory = initInstance.memory().readBytes(0, pages * 65536);

    long elapsed = System.currentTimeMillis() - startTime;
    LOG.info(
      "RuntimeImage created in {}ms, golden memory: {} pages ({} MB)",
      elapsed,
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
