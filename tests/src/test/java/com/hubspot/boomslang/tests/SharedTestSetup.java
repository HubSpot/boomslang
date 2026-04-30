package com.hubspot.boomslang.tests;

import com.dylibso.chicory.runtime.HostFunction;
import com.hubspot.boomslang.HostBridge;

class SharedTestSetup {

  static HostFunction[] defaultHostFunctions() {
    return HostBridge.builder()
        .withCallHandler((name, args) -> "{}")
        .withLogHandler((level, message) -> {})
        .build();
  }
}
