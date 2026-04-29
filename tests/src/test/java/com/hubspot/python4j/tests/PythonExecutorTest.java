package com.hubspot.python4j.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.hubspot.python4j.PythonExecutorFactory;
import com.hubspot.python4j.PythonInstance;
import com.hubspot.python4j.PythonResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PythonExecutorTest {

  private static PythonExecutorFactory factory;

  @BeforeAll
  static void setUp() {
    factory = PythonExecutorFactory.builder()
        .addHostFunctions(SharedTestSetup.defaultHostFunctions())
        .build();
  }

  @Test
  void itRunsHelloWorld() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute("print('hello from python4j')");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("hello from python4j");
  }

  @Test
  void itRunsArithmetic() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute("print(2 + 2)");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("4");
  }

  @Test
  void itImportsNumpy() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute("import numpy as np; print(np.array([1,2,3]).sum())");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("6");
  }

  @Test
  void itImportsPandas() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute(
          "import pandas as pd; df = pd.DataFrame({'a': [1,2,3]}); print(df['a'].sum())");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("6");
  }

  @Test
  void itImportsPydantic() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute(String.join("\n",
          "from pydantic import BaseModel",
          "class User(BaseModel):",
          "    name: str",
          "    age: int",
          "u = User(name='Alice', age=30)",
          "print(u.model_dump_json())"));
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("Alice");
    assertThat(result.stdout()).contains("30");
  }
}
