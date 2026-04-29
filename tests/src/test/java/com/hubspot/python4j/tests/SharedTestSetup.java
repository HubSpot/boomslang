package com.hubspot.python4j.tests;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.python4j.HostBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class SharedTestSetup {

  static HostFunction[] defaultHostFunctions() {
    List<HostFunction> all = new ArrayList<>();
    Collections.addAll(all, defaultBridge());
    Collections.addAll(
        all,
        DemoHostFunctions.builder()
            .withGreet(name -> "Hello, " + name + "!")
            .withLog((level, message) -> {})
            .build());
    return all.toArray(new HostFunction[0]);
  }

  static HostFunction[] defaultBridge() {
    return HostBridge.builder()
        .withCallHandler((name, args) -> "{}")
        .withLogHandler((level, message) -> {})
        .build();
  }
}
