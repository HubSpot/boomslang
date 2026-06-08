package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.MicropipFetchResponse;
import com.hubspot.boomslang.MicropipResolver;
import com.hubspot.boomslang.MicropipResolvers;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MicropipTest {

  @Test
  void itInstallsPurePythonWheelFromResolverAndListsAndUninstalls() throws Exception {
    byte[] wheel = wheel(
      "tiny-pkg",
      "1.0.0",
      Map.of("tiny_pkg/__init__.py", "VALUE = 'resolver'\n"),
      List.of()
    );
    MicropipResolver resolver = mapResolver(
      Map.of(
        "https://packages.example/files/tiny_pkg-1.0.0-py3-none-any.whl",
        wheelResponse(wheel)
      )
    );
    Path root = SharedTestSetup.createRootPath();
    PythonExecutorFactory factory = factory(root, resolver);

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(root);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "import micropip",
          "async def main():",
          "    await micropip.install('https://packages.example/files/tiny_pkg-1.0.0-py3-none-any.whl')",
          "    import tiny_pkg",
          "    print(tiny_pkg.VALUE)",
          "    print('listed', 'tiny-pkg' in micropip.list())",
          "    micropip.uninstall('tiny-pkg')",
          "    print('listed-after', 'tiny-pkg' in micropip.list())",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("resolver");
    assertThat(result.stdout()).contains("listed True");
    assertThat(result.stdout()).contains("listed-after False");
  }

  @Test
  void itInstallsFromLocalHttpStyleIndexThroughDefaultResolver() throws Exception {
    byte[] childWheel = wheel(
      "child-pkg",
      "1.0.0",
      Map.of("child_pkg/__init__.py", "VALUE = 'child'\n"),
      List.of()
    );
    byte[] parentWheel = wheel(
      "parent-pkg",
      "1.0.0",
      Map.of("parent_pkg/__init__.py", "VALUE = 'parent'\n"),
      List.of("child-pkg==1.0.0")
    );

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
      "/simple/parent-pkg/",
      exchange ->
        respondJson(
          exchange,
          simpleJson(
            "parent-pkg",
            "parent_pkg-1.0.0-py3-none-any.whl",
            base + "/files/parent_pkg-1.0.0-py3-none-any.whl",
            parentWheel
          )
        )
    );
    server.createContext(
      "/simple/child-pkg/",
      exchange ->
        respondJson(
          exchange,
          simpleJson(
            "child-pkg",
            "child_pkg-1.0.0-py3-none-any.whl",
            base + "/files/child_pkg-1.0.0-py3-none-any.whl",
            childWheel
          )
        )
    );
    server.createContext(
      "/files/parent_pkg-1.0.0-py3-none-any.whl",
      exchange -> respondWheel(exchange, parentWheel)
    );
    server.createContext(
      "/files/child_pkg-1.0.0-py3-none-any.whl",
      exchange -> respondWheel(exchange, childWheel)
    );
    server.start();

    try {
      Path root = SharedTestSetup.createRootPath();
      PythonExecutorFactory factory = factory(root, MicropipResolvers.pypi());

      List<PythonResult> results = factory.runOnWasmThread(() -> {
        PythonInstance instance = factory.createInstance(root);
        PythonResult installResult = instance.execute(
          String.join(
            "\n",
            "import asyncio",
            "import micropip",
            "asyncio.run(micropip.install('parent-pkg==1.0.0', index_urls='" +
            base +
            "/simple'))"
          )
        );
        PythonResult importResult = instance.execute(
          String.join(
            "\n",
            "import child_pkg",
            "import parent_pkg",
            "print(parent_pkg.VALUE + '|' + child_pkg.VALUE)"
          )
        );
        return List.of(installResult, importResult);
      });

      assertSuccess(results.get(0), "install");
      assertSuccess(results.get(1), "import");
      assertThat(results.get(1).stdout().trim()).isEqualTo("parent|child");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void itInstallsFileAndEmfsWheels() throws Exception {
    byte[] emfsWheel = wheel(
      "emfs-pkg",
      "1.0.0",
      Map.of("emfs_pkg/__init__.py", "VALUE = 'emfs'\n"),
      List.of()
    );
    byte[] fileWheel = wheel(
      "file-pkg",
      "1.0.0",
      Map.of("file_pkg/__init__.py", "VALUE = 'file'\n"),
      List.of()
    );
    Path root = SharedTestSetup.createRootPath();
    Files.createDirectories(root.resolve("wheels"));
    Files.write(root.resolve("wheels/emfs_pkg-1.0.0-py3-none-any.whl"), emfsWheel);
    Files.write(root.resolve("wheels/file_pkg-1.0.0-py3-none-any.whl"), fileWheel);
    PythonExecutorFactory factory = factory(root, null);

    List<PythonResult> results = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(root);
      PythonResult installResult = instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "import micropip",
          "asyncio.run(micropip.install([",
          "        'emfs:/wheels/emfs_pkg-1.0.0-py3-none-any.whl',",
          "        'file:/wheels/file_pkg-1.0.0-py3-none-any.whl',",
          "    ]))"
        )
      );
      PythonResult importResult = instance.execute(
        String.join(
          "\n",
          "import emfs_pkg",
          "import file_pkg",
          "print(emfs_pkg.VALUE + '|' + file_pkg.VALUE)"
        )
      );
      return List.of(installResult, importResult);
    });

    assertSuccess(results.get(0), "install");
    assertSuccess(results.get(1), "import");
    assertThat(results.get(1).stdout().trim()).isEqualTo("emfs|file");
  }

  @Test
  void itRejectsNativeWheelsBeforeFetching() throws Exception {
    AtomicBoolean called = new AtomicBoolean(false);
    MicropipResolver resolver = request -> {
      called.set(true);
      return wheelResponse(new byte[0]);
    };
    Path root = SharedTestSetup.createRootPath();
    PythonExecutorFactory factory = factory(root, resolver);

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(root);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "import micropip",
          "async def main():",
          "    try:",
          "        await micropip.install('https://packages.example/files/native_pkg-1.0.0-cp314-cp314-wasm32_wasi.whl')",
          "    except ValueError as err:",
          "        print(str(err))",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("Boomslang micropip only supports pure-Python");
    assertThat(called.get()).isFalse();
  }

  @Test
  void itReportsMissingRemoteResolver() throws Exception {
    Path root = SharedTestSetup.createRootPath();
    PythonExecutorFactory factory = factory(root, null);

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(root);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "import micropip",
          "async def main():",
          "    try:",
          "        await micropip.install('https://files.pythonhosted.org/packages/tiny_pkg-1.0.0-py3-none-any.whl')",
          "    except Exception as err:",
          "        print(str(err))",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("withMicropipResolver");
  }

  @Test
  void itStreamsLargeWheelsInChunks() throws Exception {
    String largeValue = "x".repeat(1_100_000);
    byte[] wheel = wheel(
      "large-pkg",
      "1.0.0",
      Map.of("large_pkg/__init__.py", "VALUE = '" + largeValue + "'\n"),
      List.of()
    );
    MicropipResolver resolver = mapResolver(
      Map.of(
        "https://packages.example/files/large_pkg-1.0.0-py3-none-any.whl",
        wheelResponse(wheel)
      )
    );
    Path root = SharedTestSetup.createRootPath();
    PythonExecutorFactory factory = factory(root, resolver);

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(root);
      return instance.execute(
        String.join(
          "\n",
          "import asyncio",
          "import micropip",
          "async def main():",
          "    await micropip.install('https://packages.example/files/large_pkg-1.0.0-py3-none-any.whl')",
          "    import large_pkg",
          "    print(len(large_pkg.VALUE))",
          "asyncio.run(main())"
        )
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("1100000");
  }

  private static PythonExecutorFactory factory(Path root, MicropipResolver resolver) {
    HostBridge.Builder hostBridge = HostBridge
      .builder()
      .withLogHandler((level, message) -> {});
    if (resolver != null) {
      hostBridge.withMicropipResolver(resolver);
    }
    return PythonExecutorFactory
      .builder()
      .withStdlibPath(root)
      .addExtension(hostBridge.buildExtension())
      .build();
  }

  private static void assertSuccess(PythonResult result, String label) {
    assertThat(result.stderr()).as(label + " stderr").isEmpty();
    assertThat(result.exitCode()).as(label + " exit").isEqualTo(0);
  }

  private static MicropipResolver mapResolver(
    Map<String, MicropipFetchResponse> responses
  ) {
    return request ->
      responses.getOrDefault(
        request.uri().toString(),
        new MicropipFetchResponse(404, Map.of("content-type", "text/plain"), new byte[0])
      );
  }

  private static MicropipFetchResponse wheelResponse(byte[] wheel) {
    return new MicropipFetchResponse(
      200,
      Map.of("content-type", "application/octet-stream"),
      wheel
    );
  }

  private static byte[] wheel(
    String name,
    String version,
    Map<String, String> files,
    List<String> requires
  ) throws IOException {
    String normalized = name.replace("-", "_");
    String distInfo = normalized + "-" + version + ".dist-info";
    Map<String, byte[]> entries = new LinkedHashMap<>();
    files.forEach((path, content) ->
      entries.put(path, content.getBytes(StandardCharsets.UTF_8))
    );

    StringBuilder metadata = new StringBuilder()
      .append("Metadata-Version: 2.1\n")
      .append("Name: ")
      .append(name)
      .append('\n')
      .append("Version: ")
      .append(version)
      .append('\n');
    for (String requirement : requires) {
      metadata.append("Requires-Dist: ").append(requirement).append('\n');
    }
    entries.put(
      distInfo + "/METADATA",
      metadata.toString().getBytes(StandardCharsets.UTF_8)
    );
    entries.put(
      distInfo + "/WHEEL",
      String
        .join(
          "\n",
          "Wheel-Version: 1.0",
          "Generator: boomslang-test",
          "Root-Is-Purelib: true",
          "Tag: py3-none-any",
          ""
        )
        .getBytes(StandardCharsets.UTF_8)
    );

    StringBuilder record = new StringBuilder();
    entries.keySet().forEach(path -> record.append(path).append(",,\n"));
    record.append(distInfo).append("/RECORD,,\n");
    entries.put(distInfo + "/RECORD", record.toString().getBytes(StandardCharsets.UTF_8));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.setLevel(0);
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static String simpleJson(
    String name,
    String filename,
    String url,
    byte[] body
  ) {
    return (
      "{\"name\":\"" +
      name +
      "\",\"versions\":[\"1.0.0\"],\"files\":[{\"filename\":\"" +
      filename +
      "\",\"url\":\"" +
      url +
      "\",\"hashes\":{\"sha256\":\"" +
      sha256(body) +
      "\"}}]}"
    );
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void respondJson(HttpExchange exchange, String body) throws IOException {
    respond(
      exchange,
      "application/vnd.pypi.simple.v1+json",
      body.getBytes(StandardCharsets.UTF_8)
    );
  }

  private static void respondWheel(HttpExchange exchange, byte[] body)
    throws IOException {
    respond(exchange, "application/octet-stream", body);
  }

  private static void respond(HttpExchange exchange, String contentType, byte[] body)
    throws IOException {
    exchange.getResponseHeaders().add("content-type", contentType);
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
