package com.hubspot.boomslang;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import run.endive.runtime.CompiledModule;
import run.endive.runtime.Instance;
import run.endive.runtime.Machine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

final class ClasspathPythonImage implements PythonImage {

  private static final Logger LOG = LoggerFactory.getLogger(ClasspathPythonImage.class);

  private final String resourcePrefix;
  private final String aotName;

  ClasspathPythonImage(String resourcePrefix, String aotName) {
    this.resourcePrefix =
      resourcePrefix.endsWith("/") ? resourcePrefix : resourcePrefix + "/";
    this.aotName = aotName;
  }

  @Override
  public String resourcePrefix() {
    return resourcePrefix;
  }

  @Override
  public Optional<CompiledModule> compiledModule() {
    Constructor<? extends Machine> machine;
    try {
      machine =
        Class
          .forName(aotName + "Machine")
          .asSubclass(Machine.class)
          .getConstructor(Instance.class);
    } catch (ReflectiveOperationException e) {
      LOG.warn(
        "AOT class {}Machine not found; image {} runs in the interpreter, which is much slower",
        aotName,
        resourcePrefix
      );
      return Optional.empty();
    }
    WasmModule module = parseMeta().orElseGet(() -> parseResource("/" + wasmResource()));
    Function<Instance, Machine> factory = instance -> {
      try {
        return machine.newInstance(instance);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("cannot create AOT machine " + aotName, e);
      }
    };
    return Optional.of(
      new CompiledModule() {
        @Override
        public WasmModule wasmModule() {
          return module;
        }

        @Override
        public Function<Instance, Machine> machineFactory() {
          return factory;
        }
      }
    );
  }

  private Optional<WasmModule> parseMeta() {
    String meta = "/" + aotName.replace('.', '/') + ".meta";
    if (getClass().getResource(meta) == null) {
      return Optional.empty();
    }
    return Optional.of(parseResource(meta));
  }

  private WasmModule parseResource(String resource) {
    try (InputStream is = getClass().getResourceAsStream(resource)) {
      if (is == null) {
        throw new IllegalStateException("WASM resource not found: " + resource);
      }
      return Parser.parse(is);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
