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
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared, immutable runtime state behind a {@link PythonExecutorFactory}: the parsed WASM
 * module, the optional AOT machine factory, and the "golden" memory snapshot captured from a single
 * instantiation of the pre-initialized (Wizer) interpreter. Every {@link PythonInstance} reads from
 * this snapshot through a {@link CopyOnWriteMemory}, so one image can back many concurrent
 * instances without copying.
 */
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

  /**
   * Instantiates the module once against the extracted Python tree and captures its memory as the
   * golden snapshot. This is expensive and is normally done once, by {@link
   * PythonExecutorFactory.Builder#build()}.
   *
   * @param module the parsed Python runtime WASM module
   * @param machineFactory AOT machine factory, or null to use the interpreter
   * @param extractedPythonPath host directory containing the extracted Python runtime tree
   * @param pythonHome guest-visible {@code PYTHONHOME}
   * @param pythonPath guest-visible {@code PYTHONPATH}, or null
   * @param hostFunctions host functions the module imports (e.g. from {@link HostBridge})
   */
  public static RuntimeImage create(
    WasmModule module,
    Function<Instance, Machine> machineFactory,
    Path extractedPythonPath,
    String pythonHome,
    @Nullable String pythonPath,
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

  /** Returns the parsed Python runtime WASM module. */
  public WasmModule getModule() {
    return module;
  }

  /** Returns the AOT machine factory, or null when running interpreted. */
  public Function<Instance, Machine> getMachineFactory() {
    return machineFactory;
  }

  /** Returns the host directory containing the extracted Python runtime tree. */
  public Path getExtractedPythonPath() {
    return extractedPythonPath;
  }

  /**
   * Returns the golden memory snapshot. The returned array is shared, not copied — treat it as
   * read-only.
   */
  public byte[] getGoldenMemory() {
    return goldenMemory;
  }

  /** Returns the size of the golden snapshot in 64 KiB WASM pages. */
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
