package com.hubspot.boomslang;

import java.io.IOException;

@FunctionalInterface
public interface MicropipResolver {
  /** Resolves one remote micropip metadata or wheel request on the Java host side. */
  MicropipFetchResponse fetch(MicropipFetchRequest request)
    throws IOException, InterruptedException;
}
