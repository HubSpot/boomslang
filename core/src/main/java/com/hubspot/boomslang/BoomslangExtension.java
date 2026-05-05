package com.hubspot.boomslang;

import com.dylibso.chicory.runtime.HostFunction;

public interface BoomslangExtension {
  String name();

  HostFunction[] hostFunctions();
}
