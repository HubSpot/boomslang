package com.hubspot.boomslang;

import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * An in-memory pure-Python package installed into site-packages by {@link
 * PythonExecutorFactory.Builder#withLibrary(PythonLibrary)}, mapping file names (e.g. {@code
 * __init__.py}) to source contents.
 */
public record PythonLibrary(String name, ImmutableMap<String, String> modules) {
  public static PythonLibrary of(String name, Map<String, String> modules) {
    return new PythonLibrary(name, ImmutableMap.copyOf(modules));
  }

  public static Builder builder(String name) {
    return new Builder(name);
  }

  public static class Builder {

    private final String name;
    private final ImmutableMap.Builder<String, String> modules = ImmutableMap.builder();

    private Builder(String name) {
      this.name = name;
    }

    public Builder withModule(String filename, String content) {
      modules.put(filename, content);
      return this;
    }

    public Builder withInitModule(String content) {
      modules.put("__init__.py", content);
      return this;
    }

    public PythonLibrary build() {
      return new PythonLibrary(name, modules.build());
    }
  }
}
