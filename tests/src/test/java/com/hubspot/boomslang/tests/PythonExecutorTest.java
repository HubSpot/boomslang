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

  private static Path pythonRoot;
  private static PythonExecutorFactory factory;

  @BeforeAll
  static void setUp() {
    pythonRoot = SharedTestSetup.createRootPath();
    factory =
      PythonExecutorFactory
        .builder()
        .withStdlibPath(pythonRoot)
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
          "pixels = bytes([10, 20, 30, 255, 40, 50, 60, 255])",
          "image = Image.frombuffer('RGBA', (2, 1), pixels, 'raw', 'RGBA', 0, 1)",
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

  @Test
  void itQueriesXmlWithLxml() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        String.join(
          "\n",
          "from lxml import etree",
          "root = etree.fromstring('<r><n>1</n><n>2</n><n>3</n></r>')",
          "print(sum(int(n) for n in root.xpath('//n/text()')))"
        )
      );
    });

    assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("6");
  }

  @Test
  void itTransformsXmlWithExsltThroughLxml() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        String.join(
          "\n",
          "from lxml import etree",
          "xslt = etree.XSLT(etree.fromstring(\"\"\"",
          "<xsl:stylesheet version='1.0'",
          "    xmlns:xsl='http://www.w3.org/1999/XSL/Transform'",
          "    xmlns:math='http://exslt.org/math'",
          "    extension-element-prefixes='math'>",
          "  <xsl:output method='text'/>",
          "  <xsl:template match='/'><xsl:value-of select='math:max(//n)'/></xsl:template>",
          "</xsl:stylesheet>\"\"\"))",
          "print(str(xslt(etree.fromstring('<r><n>1</n><n>3</n><n>2</n></r>'))))"
        )
      );
    });

    assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("3");
  }

  @Test
  void itParsesSlideXmlWithPythonPptx() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance(SharedTestSetup.createRootPath());
      return instance.execute(
        String.join(
          "\n",
          "from pptx.oxml import parse_xml",
          "from pptx.oxml.ns import nsdecls",
          "run = parse_xml('<a:r %s><a:t>Hello</a:t></a:r>' % nsdecls('a'))",
          "print(type(run).__name__, run.text)"
        )
      );
    });

    assertThat(result.exitCode()).as(result.stderr()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("CT_RegularTextRun Hello");
  }
}
