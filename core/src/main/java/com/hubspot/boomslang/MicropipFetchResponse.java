package com.hubspot.boomslang;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public record MicropipFetchResponse(
  int statusCode,
  Map<String, String> headers,
  byte[] body
) {
  public MicropipFetchResponse {
    Map<String, String> safeHeaders = headers == null ? Map.of() : headers;
    headers = Map.copyOf(new LinkedHashMap<>(safeHeaders));
    body = body == null ? new byte[0] : body.clone();
  }

  public byte[] body() {
    return body.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof MicropipFetchResponse that)) {
      return false;
    }
    return (
      statusCode == that.statusCode &&
      headers.equals(that.headers) &&
      Arrays.equals(body, that.body)
    );
  }

  @Override
  public int hashCode() {
    int result = Integer.hashCode(statusCode);
    result = 31 * result + headers.hashCode();
    result = 31 * result + Arrays.hashCode(body);
    return result;
  }

  @Override
  public String toString() {
    return (
      "MicropipFetchResponse[statusCode=" +
      statusCode +
      ", headers=" +
      headers +
      ", body=" +
      body.length +
      " bytes]"
    );
  }
}
