package com.hubspot.python4j.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.python4j.PythonExecutorFactory;
import com.hubspot.python4j.PythonInstance;
import com.hubspot.python4j.PythonResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HostFunctionExtensionTest {

  private static final List<String> LOG_MESSAGES = new ArrayList<>();
  private static PythonExecutorFactory factory;

  @BeforeAll
  static void setUp() {
    HostFunction[] demoFunctions = DemoHostFunctions.builder()
        .withGreet(name -> "Hello, " + name + "!")
        .withLog((level, message) -> {
          LOG_MESSAGES.add("[" + level + "] " + message);
        })
        .build();

    factory = PythonExecutorFactory.builder()
        .addHostFunctions(demoFunctions)
        .build();
  }

  @Test
  void itCallsGreetHostFunction() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute("from demo import greet; print(greet('World'))");
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("Hello, World!");
  }

  @Test
  void itCallsLogHostFunction() {
    LOG_MESSAGES.clear();

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute("from demo import log; log(2, 'test message')");
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(LOG_MESSAGES).contains("[2] test message");
  }
}
