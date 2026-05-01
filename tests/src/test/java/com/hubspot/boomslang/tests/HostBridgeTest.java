package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.hubspot.boomslang.HostBridge;
import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HostBridgeTest {

  private static final List<String> LOG_MESSAGES = new ArrayList<>();
  private static PythonExecutorFactory factory;

  @BeforeAll
  static void setUp() {
    factory =
      PythonExecutorFactory
        .builder()
        .addHostFunctions(
          HostBridge
            .builder()
            .withFunction(
              "add",
              args -> {
                String[] parts = args.replace("[", "").replace("]", "").split(",");
                int sum =
                  Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim());
                return String.valueOf(sum);
              }
            )
            .withFunction("echo", args -> args)
            .withLogHandler((level, message) ->
              LOG_MESSAGES.add("[" + level + "] " + message)
            )
            .build()
        )
        .build();
  }

  @Test
  void itCallsNamedHostFunction() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute(
        "from boomslang_host import call; print(call('add', '[3, 4]'))"
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout().trim()).isEqualTo("7");
  }

  @Test
  void itEchoesArgs() {
    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute(
        "from boomslang_host import call; print(call('echo', '{\"hello\": \"world\"}'))"
      );
    });

    assertThat(result.stderr()).as("stderr").isEmpty();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.stdout()).contains("hello");
    assertThat(result.stdout()).contains("world");
  }

  @Test
  void itLogsFromPython() {
    LOG_MESSAGES.clear();

    PythonResult result = factory.runOnWasmThread(() -> {
      PythonInstance instance = factory.createInstance();
      return instance.execute(
        "from boomslang_host import log; log(2, 'hello from python')"
      );
    });

    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(LOG_MESSAGES).contains("[2] hello from python");
  }
}
