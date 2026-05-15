package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PythonExecutorTest {

  private static PythonExecutorFactory factory;

  @BeforeAll
  static void setUp() {
    factory =
      PythonExecutorFactory
        .builder()
        .withStdlibPath(SharedTestSetup.createRootPath())
        .addHostFunctions(SharedTestSetup.defaultHostFunctions())
        .build();
  }

  @Test
  void itRunsHelloWorld() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute("print('hello from boomslang')");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("hello from boomslang");
  }

  @Test
  void itRunsArithmetic() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute("print(2 + 2)");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("4");
  }

  @Test
  void itImportsNumpy() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute("import numpy as np; print(np.array([1,2,3]).sum())");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("6");
  }

  @Test
  void itImportsPandas() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        "import pandas as pd; df = pd.DataFrame({'a': [1,2,3]}); print(df['a'].sum())"
      );
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("6");
  }

  @Test
  void itImportsPydantic() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        String.join(
          "\n",
          "from pydantic import BaseModel",
          "class User(BaseModel):",
          "    name: str",
          "    age: int",
          "u = User(name='Alice', age=30)",
          "print(u.model_dump_json())"
        )
      );
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("Alice");
    assertThat(result.stdout()).contains("30");
  }

  @Test
  void itRespectsPythonPath() throws Exception {
    PythonExecutorFactory pathFactory = PythonExecutorFactory
      .builder()
      .withStdlibPath(SharedTestSetup.createRootPath())
      .withPythonPath("/work/libs")
      .addHostFunctions(SharedTestSetup.defaultHostFunctions())
      .build();

    PythonResult result = pathFactory.runOnWasmThread(() -> {
      PythonInstance instance = pathFactory.createInstance(
        SharedTestSetup.createRootPath()
      );
      return instance.execute(
        String.join(
          "\n",
          "import sys",
          "paths = [p for p in sys.path if p == '/work/libs']",
          "print(len(paths))"
        )
      );
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("1");
  }

  @Test
  void itWritesAndReadsPngWithPillow() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        String.join(
          "\n",
          "import io",
          "from PIL import Image",
          "buffer = io.BytesIO()",
          "image = Image.new('RGBA', (2, 1), (10, 20, 30, 255))",
          "image.save(buffer, format='PNG')",
          "buffer.seek(0)",
          "decoded = Image.open(buffer)",
          "decoded.load()",
          "print(decoded.mode, decoded.size, decoded.getpixel((0, 0)))"
        )
      );
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("RGBA (2, 1) (10, 20, 30, 255)");
  }

  @Test
  void itImportsIjson() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        String.join(
          "\n",
          "import io",
          "import ijson",
          "items = ijson.items(io.StringIO('{\"items\": [1, 2, 3]}'), 'items.item')",
          "print(sum(items))"
        )
      );
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("6");
  }
}
