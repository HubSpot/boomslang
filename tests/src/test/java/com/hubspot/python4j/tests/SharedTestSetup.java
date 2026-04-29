package com.hubspot.python4j.tests;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.python4j.HostBridge;

class SharedTestSetup {

  static HostFunction[] defaultHostFunctions() {
    return HostBridge.builder()
        .withCallHandler((name, args) -> "{}")
        .withLogHandler((level, message) -> {})
        .build();
  }
}
