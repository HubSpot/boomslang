package com.hubspot.boomslang;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record MicropipFetchRequest(URI uri, Map<String, String> headers) {
  public MicropipFetchRequest {
    Objects.requireNonNull(uri, "uri");
    Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
    headers = Map.copyOf(new LinkedHashMap<>(safeHeaders));
  }
}
