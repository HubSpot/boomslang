package com.hubspot.boomslang.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.hubspot.boomslang.PythonExecutorFactory;
import com.hubspot.boomslang.PythonInstance;
import com.hubspot.boomslang.PythonResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SystemExitTest {

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
  void itKeepsStdoutWhenScriptRaisesSystemExitZero() {
    PythonResult result = execute("print('a')\nraise SystemExit(0)");

    assertThat(result).isEqualTo(result("a\n", "", 0, result));
  }

  @Test
  void itReturnsSysExitStatusAndKeepsStdout() {
    PythonResult result = execute("import sys\nprint('b')\nsys.exit(3)");

    assertThat(result).isEqualTo(result("b\n", "", 3, result));
  }

  @Test
  void itWritesNonIntegerExitCodeToStderrAndExitsOne() {
    PythonResult result = execute("import sys\nsys.exit('bad')");

    assertThat(result).isEqualTo(result("", "bad\n", 1, result));
  }

  @Test
  void itTreatsMissingOrNoneExitCodeAsSuccess() {
    assertThat(execute("import sys\nsys.exit()").exitCode()).isEqualTo(0);
    assertThat(execute("import sys\nsys.exit(None)").exitCode()).isEqualTo(0);
    assertThat(execute("print('q')\nexit()").exitCode()).isEqualTo(0);
  }

  @Test
  void itMasksExitStatusToPosixRange() {
    assertThat(execute("import sys\nsys.exit(256)").exitCode()).isEqualTo(0);
    assertThat(execute("import sys\nsys.exit(-1)").exitCode()).isEqualTo(255);
    assertThat(execute("import sys\nsys.exit(2 ** 40)").exitCode()).isEqualTo(255);
    assertThat(execute("import sys\nsys.exit(True)").exitCode()).isEqualTo(1);
  }

  @Test
  void itKeepsInstanceUsableWithStateAfterSystemExit() {
    List<PythonResult> results = onInstance(instance ->
      List.of(
        instance.execute("marker = 41\nraise SystemExit(5)"),
        instance.execute("print(marker + 1)")
      )
    );

    assertThat(results.get(0).exitCode()).isEqualTo(5);
    assertThat(results.get(1)).isEqualTo(result("42\n", "", 0, results.get(1)));
  }

  @Test
  void itReopensStdinClosedByBuiltinExit() {
    List<PythonResult> results = onInstance(instance -> {
      PythonResult exited = instance.execute("exit(0)");
      instance.setStdin("hi\n".getBytes(StandardCharsets.UTF_8));
      PythonResult read = instance.execute("import sys\nprint(sys.stdin.read().strip())");
      return List.of(exited, read);
    });

    assertThat(results.get(0).exitCode()).isEqualTo(0);
    assertThat(results.get(1)).isEqualTo(result("hi\n", "", 0, results.get(1)));
  }

  @Test
  void itReturnsSystemExitStatusFromLoadedCodeAndFunctions() {
    List<PythonResult> results = onInstance(instance -> {
      byte[] bytecode = instance.compile(
        "import sys\nprint('loaded')\ndef stop(code):\n    print('stopping')\n    sys.exit(code)\n"
      );
      return List.of(
        instance.loadCode(bytecode),
        instance.executeFunction("stop", "[7]"),
        instance.loadCode(instance.compile("print('top')\nraise SystemExit(4)"))
      );
    });

    assertThat(results.get(0)).isEqualTo(result("loaded\n", "", 0, results.get(0)));
    assertThat(results.get(1)).isEqualTo(result("stopping\n", "", 7, results.get(1)));
    assertThat(results.get(2)).isEqualTo(result("top\n", "", 4, results.get(2)));
  }

  @Test
  void itStillReportsOrdinaryExceptionsAsExitOneWithTraceback() {
    PythonResult result = execute("print('before')\n1 / 0");

    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.stdout()).isEqualTo("before\n");
    assertThat(result.stderr()).contains("ZeroDivisionError");
  }

  private static PythonResult execute(String script) {
    return onInstance(instance -> instance.execute(script));
  }

  private static <T> T onInstance(Function<PythonInstance, T> body) {
    return factory.runOnWasmThread(() ->
      body.apply(factory.createInstance(SharedTestSetup.createRootPath()))
    );
  }

  /** Expected result with the actual execution time, so whole-object comparison ignores timing. */
  private static PythonResult result(
    String stdout,
    String stderr,
    int exitCode,
    PythonResult actual
  ) {
    return new PythonResult(stdout, stderr, exitCode, actual.executionTimeMs());
  }
}
