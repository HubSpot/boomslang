# Quickstart

Run Python from Java in about five minutes. You need Java 21+ and Maven (or Gradle); nothing else — no Python install, no native libraries, no containers.

## 1. Add the dependency

Boomslang is published to Maven Central:

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>0.1.1</version>
</dependency>
```

Check [Maven Central](https://central.sonatype.com/artifact/com.hubspot/boomslang) for the latest version. The default artifact is large (~100 MB) because it bundles the entire Python runtime — CPython, the stdlib, NumPy, Pandas, and friends — plus ahead-of-time compiled classes. If that's a problem, see [Installation & Runtime Variants](installation.md).

## 2. Run some Python

```java
import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

  public static void main(String[] args) throws Exception {
    Path pythonRoot = Files.createTempDirectory("boomslang-python");
    PythonExecutorFactory factory = PythonExecutorFactory
      .builder()
      .withStdlibPath(pythonRoot)
      .addExtension(HostBridge.builder().buildExtension())
      .build();

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(pythonRoot);
      return instance.execute("print('hello from Python')");
    });

    System.out.println(result.stdout()); // hello from Python
  }
}
```

What each piece does:

- **`withStdlibPath`** — a host directory where boomslang extracts the packaged Python resources. The instance root passed to `createInstance` is what Python sees as `/`.
- **`addExtension(HostBridge.builder().buildExtension())`** — registers the host functions the bundled runtime imports. This line is required: the bundled `boomslang.wasm` unconditionally imports `boomslang.call` and `boomslang.log`, and instantiation fails without an extension that provides them. A bare `HostBridge` registers a no-op log handler; wire up real handlers when you want Python to call back into Java (see the user guide).
- **`runOnWasmThread`** — runs the WASM call on a dedicated thread with a larger JVM stack, and is where you set timeouts.

The first factory build extracts the Python resources to `stdlibPath` and takes a few seconds; creating instances afterwards is fast (copy-on-write snapshot of a pre-initialized interpreter).

## 3. Use the batteries

The bundled runtime includes NumPy, Pandas, Matplotlib, Pillow, Pydantic, ijson, and Jinja2:

```java
PythonResult result = factory.runOnWasmThread(() -> {
  PythonInstance instance = factory.createInstance(pythonRoot);
  return instance.execute("import numpy as np; print(np.arange(5).sum())");
});
System.out.println(result.stdout()); // 10
```

## Next steps

- Reuse one factory for your whole application; it holds the memory snapshot.
- Set timeouts on `runOnWasmThread` and handle poisoned instances — see the user guide on lifecycle.
- Let Python call your Java code with `HostBridge.builder().withFunction(...)`.
- Trim the dependency with the [`no-python-runtime` classifier](installation.md) if you ship your own runtime resources.
