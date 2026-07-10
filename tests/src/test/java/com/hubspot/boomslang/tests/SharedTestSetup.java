package com.hubspot.boomslang.tests;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.hubspot.boomslang.HostBridge;
import java.nio.file.Path;
import run.endive.runtime.HostFunction;

class SharedTestSetup {

  static HostFunction[] defaultHostFunctions() {
    return HostBridge
      .builder()
      .withCallHandler((name, args) -> "{}")
      .withLogHandler((level, message) -> {})
      .build();
  }

  static Path createRootPath() {
    return Jimfs
      .newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build())
      .getPath("/");
  }
}
