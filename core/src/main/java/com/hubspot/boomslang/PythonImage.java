package com.hubspot.boomslang;

import java.util.Optional;
import run.endive.runtime.CompiledModule;

/**
 * A Python runtime on the classpath: a Wizer-snapshotted WASM module, the Python tree it was baked
 * against, and optionally its AOT-compiled form. Each image jar has its own resource prefix and AOT
 * class name, so several images can share one classpath.
 *
 * <p>Layout under {@link #resourcePrefix()}: {@code bin/boomslang.wasm} and {@code
 * usr/local/lib/python3.14/}.
 */
public interface PythonImage {
  /** The classpath directory holding the image, with a trailing slash, e.g. {@code python/}. */
  String resourcePrefix();

  /** The classpath resource of the snapshotted WASM module. */
  default String wasmResource() {
    return resourcePrefix() + "bin/boomslang.wasm";
  }

  /** The AOT-compiled module, or empty to run the module in Endive's interpreter. */
  Optional<CompiledModule> compiledModule();

  /** The runtime bundled in the boomslang jar. */
  static PythonImage bundled() {
    return onClasspath("python/", "com.hubspot.boomslang.compiled.PythonWasm");
  }

  /**
   * An image whose AOT output came from the Endive compiler plugin with {@code <name>aotName</name>}:
   * the machine class {@code aotName + "Machine"} and the stripped module {@code
   * <SimpleName>.meta} beside it.
   */
  static PythonImage onClasspath(String resourcePrefix, String aotName) {
    return new ClasspathPythonImage(resourcePrefix, aotName);
  }
}
