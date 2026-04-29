package com.hubspot.python4j.tests;

import com.dylibso.chicory.runtime.HostFunction;

class SharedTestSetup {

  static HostFunction[] defaultHostFunctions() {
    return DemoHostFunctions.builder()
        .withGreet(name -> "Hello, " + name + "!")
        .withLog((level, message) -> {})
        .build();
  }
}
