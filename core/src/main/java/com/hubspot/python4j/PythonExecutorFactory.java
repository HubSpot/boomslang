package com.hubspot.python4j;

import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PythonExecutorFactory {

  private static final Logger LOG = LoggerFactory.getLogger(PythonExecutorFactory.class);
  private static final Object JAR_EXTRACTION_LOCK = new Object();
  private static final String PYTHON_WASM_FILENAME = "python4j.wasm";
  private static final String AOT_CLASS_NAME = "com.hubspot.python4j.compiled.PythonWasmMachine";
  private static final List<String> PYTHON_RESOURCES =
      ImmutableList.of("bin/" + PYTHON_WASM_FILENAME);
  private static final String PYTHON_LIB_DIR = "usr/local/lib/python3.14";
  private static final long WASM_THREAD_STACK_SIZE = 16L * 1024 * 1024;

  private final WasmModule module;
  private final FileSystem pythonFileSystem;
  private final Path extractedPythonPath;
  private final ExecutorService executorService;
  private final boolean aotAvailable;
  private final RuntimeImage runtimeImage;

  public PythonExecutorFactory() {
    this(builder());
  }

  private PythonExecutorFactory(Builder builder) {
    this.aotAvailable = checkAotAvailable();
    this.pythonFileSystem = createPythonFileSystem();
    this.extractedPythonPath = extractPythonResources();
    this.module = loadWasmModule();
    this.executorService = createWasmExecutorService();

    installCustomLibraries(builder.libraries);

    Function<Instance, Machine> machineFactory = aotAvailable ? loadAotFactory() : null;

    this.runtimeImage = RuntimeImage.create(module, machineFactory, extractedPythonPath);

    LOG.info(
        "PythonExecutorFactory initialized with Jimfs at: {}, custom libraries: {}",
        extractedPythonPath,
        builder.libraries.size());
  }

  public static Builder builder() {
    return new Builder();
  }

  public PythonInstance createInstance(Path workDir) {
    return new PythonInstance(runtimeImage, workDir);
  }

  public PythonInstance createInstance() {
    return new PythonInstance(runtimeImage);
  }

  public <T> T runOnWasmThread(Callable<T> task) {
    Future<T> future = executorService.submit(task);
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Python execution interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new RuntimeException("Python execution failed", cause);
    }
  }

  public boolean isAotAvailable() {
    return aotAvailable;
  }

  public Path getSitePackagesPath() {
    return extractedPythonPath.resolve(PYTHON_LIB_DIR).resolve("site-packages");
  }

  public RuntimeImage getRuntimeImage() {
    return runtimeImage;
  }

  private void installCustomLibraries(List<PythonLibrary> libraries) {
    if (libraries.isEmpty()) {
      return;
    }

    Path sitePackages = getSitePackagesPath();
    for (PythonLibrary library : libraries) {
      try {
        installLibrary(sitePackages, library);
        LOG.debug("Installed custom library: {}", library.name());
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to install custom library: " + library.name(), e);
      }
    }
  }

  private void installLibrary(Path sitePackages, PythonLibrary library) throws IOException {
    Path packageDir = sitePackages.resolve(library.name());
    Files.createDirectories(packageDir);

    for (Map.Entry<String, String> entry : library.modules().entrySet()) {
      String modulePath = entry.getKey();
      String content = entry.getValue();

      Path modulefile = packageDir.resolve(modulePath);
      Files.createDirectories(modulefile.getParent());
      Files.writeString(modulefile, content, StandardCharsets.UTF_8);
    }
  }

  private FileSystem createPythonFileSystem() {
    return Jimfs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build());
  }

  private boolean checkAotAvailable() {
    try {
      Class.forName(AOT_CLASS_NAME);
      LOG.debug("AOT compiled Python WASM module is available");
      return true;
    } catch (ClassNotFoundException e) {
      LOG.warn(
          "AOT compiled Python WASM module NOT found (class {} missing). "
              + "Python execution will use interpreted mode which is significantly slower.",
          AOT_CLASS_NAME);
      return false;
    }
  }

  private Function<Instance, Machine> loadAotFactory() {
    try {
      Class<?> aotClass = Class.forName(AOT_CLASS_NAME);
      java.lang.reflect.Constructor<?> ctor = aotClass.getConstructor(Instance.class);
      return instance -> {
        try {
          return (Machine) ctor.newInstance(instance);
        } catch (ReflectiveOperationException e) {
          throw new RuntimeException("Failed to create AOT machine", e);
        }
      };
    } catch (ReflectiveOperationException e) {
      LOG.warn("Failed to load AOT factory, falling back to interpreter", e);
      return null;
    }
  }

  private WasmModule loadWasmModule() {
    Path wasmPath = extractedPythonPath.resolve("bin/" + PYTHON_WASM_FILENAME);
    if (!Files.exists(wasmPath)) {
      throw new IllegalStateException(
          "Python WASM binary not found at: "
              + wasmPath
              + ". Ensure the WASM binary has been built and placed in resources.");
    }

    LOG.debug("Loading Python WASM module from: {}", wasmPath);
    return Parser.parse(wasmPath);
  }

  private Path extractPythonResources() {
    try {
      Path rootDir = pythonFileSystem.getPath("/");
      LOG.debug("Extracting Python resources to Jimfs");

      for (String resource : PYTHON_RESOURCES) {
        extractResource(rootDir, resource);
      }

      extractPythonStdlib(rootDir);
      extractLibDynload(rootDir);

      return rootDir;
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to extract Python resources", e);
    }
  }

  private void extractPythonStdlib(Path tempDir) throws IOException {
    URL resourceUrl = getClass().getResource("/python/" + PYTHON_LIB_DIR);
    if (resourceUrl == null) {
      LOG.warn("Python stdlib not found in resources");
      return;
    }

    Path targetDir = tempDir.resolve(PYTHON_LIB_DIR);

    if ("file".equals(resourceUrl.getProtocol())) {
      try {
        Path sourceDir = Paths.get(resourceUrl.toURI());
        copyDirectory(sourceDir, targetDir);
        LOG.debug("Copied stdlib from filesystem: {}", sourceDir);
      } catch (URISyntaxException e) {
        throw new IOException("Invalid URI for stdlib resource", e);
      }
    } else if ("jar".equals(resourceUrl.getProtocol())) {
      extractStdlibFromJar(resourceUrl, targetDir);
    } else {
      LOG.warn("Stdlib resource has unsupported protocol: {}", resourceUrl);
    }
  }

  private void extractStdlibFromJar(URL jarUrl, Path targetDir) throws IOException {
    String urlString = jarUrl.toString();
    int separator = urlString.indexOf("!");
    String jarPath = urlString.substring(0, separator);
    String resourcePath = urlString.substring(separator + 1);

    LOG.debug("Extracting stdlib from JAR: {} path: {}", jarPath, resourcePath);

    URI jarUri = URI.create(jarPath);
    synchronized (JAR_EXTRACTION_LOCK) {
      try (FileSystem jarFs = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
        Path jarResourcePath = jarFs.getPath(resourcePath);
        copyDirectory(jarResourcePath, targetDir);
        LOG.debug("Extracted stdlib from JAR to: {}", targetDir);
      }
    }
  }

  private void copyDirectory(Path source, Path target) throws IOException {
    Files.walk(source)
        .forEach(
            sourcePath -> {
              try {
                Path targetPath = target.resolve(source.relativize(sourcePath).toString());
                if (Files.isDirectory(sourcePath)) {
                  Files.createDirectories(targetPath);
                } else {
                  Files.createDirectories(targetPath.getParent());
                  Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
  }

  private void extractResource(Path tempDir, String relativePath) throws IOException {
    String resourcePath = "/python/" + relativePath;
    try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
      if (is == null) {
        LOG.warn("Resource not found: {}", resourcePath);
        return;
      }

      Path targetFile = tempDir.resolve(relativePath);
      Files.createDirectories(targetFile.getParent());
      Files.write(targetFile, ByteStreams.toByteArray(is));
      LOG.debug("Extracted {} to: {}", relativePath, targetFile);
    }
  }

  private void extractLibDynload(Path tempDir) throws IOException {
    Path libDynloadDir = tempDir.resolve(PYTHON_LIB_DIR).resolve("lib-dynload");
    Files.createDirectories(libDynloadDir);

    Path sitePackagesDir = tempDir.resolve(PYTHON_LIB_DIR).resolve("site-packages");
    Files.createDirectories(sitePackagesDir);
  }

  private ExecutorService createWasmExecutorService() {
    ThreadFactory threadFactory =
        new ThreadFactory() {
          private final ThreadGroup group = new ThreadGroup("python-executor");
          private final AtomicInteger threadNumber = new AtomicInteger(1);

          @Override
          public Thread newThread(Runnable r) {
            Thread t =
                new Thread(
                    group,
                    r,
                    "python-executor-" + threadNumber.getAndIncrement(),
                    WASM_THREAD_STACK_SIZE);
            t.setDaemon(true);
            return t;
          }
        };

    return new ThreadPoolExecutor(
        10, 10, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), threadFactory);
  }

  public static class Builder {

    private final List<PythonLibrary> libraries = new ArrayList<>();

    private Builder() {}

    public Builder withLibrary(PythonLibrary library) {
      this.libraries.add(library);
      return this;
    }

    public Builder withLibrary(String name, Map<String, String> modules) {
      this.libraries.add(PythonLibrary.of(name, modules));
      return this;
    }

    public Builder withModule(String packageName, String moduleName, String content) {
      this.libraries.add(PythonLibrary.of(packageName, Map.of(moduleName + ".py", content)));
      return this;
    }

    public PythonExecutorFactory build() {
      return new PythonExecutorFactory(this);
    }
  }
}
