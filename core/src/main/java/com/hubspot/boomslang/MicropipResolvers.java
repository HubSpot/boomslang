package com.hubspot.boomslang;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class MicropipResolvers {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  private MicropipResolvers() {}

  /**
   * Returns an HTTP(S) resolver suitable for PyPI, compatible package indexes, and direct wheel
   * URLs. Applications that need mirrors, allowlists, caching, or offline behavior should provide
   * their own {@link MicropipResolver}.
   */
  public static MicropipResolver pypi() {
    return http(
      HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    );
  }

  static MicropipResolver http(HttpClient client) {
    Objects.requireNonNull(client, "client");
    return request -> {
      URI uri = request.uri();
      String scheme = uri.getScheme();
      if (
        scheme == null ||
        (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
      ) {
        throw new IOException("Micropip HTTP resolver only supports http and https URLs");
      }

      HttpRequest.Builder builder = HttpRequest
        .newBuilder(uri)
        .timeout(DEFAULT_TIMEOUT)
        .GET();
      request.headers().forEach(builder::header);

      HttpResponse<byte[]> response = client.send(
        builder.build(),
        HttpResponse.BodyHandlers.ofByteArray()
      );
      return new MicropipFetchResponse(
        response.statusCode(),
        flattenHeaders(response.headers().map()),
        response.body()
      );
    };
  }

  private static Map<String, String> flattenHeaders(Map<String, java.util.List<String>> raw) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (Map.Entry<String, java.util.List<String>> entry : raw.entrySet()) {
      if (!entry.getValue().isEmpty()) {
        headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().get(0));
      }
    }
    return headers;
  }
}
