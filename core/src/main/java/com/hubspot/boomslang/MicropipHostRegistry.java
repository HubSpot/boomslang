package com.hubspot.boomslang;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

class MicropipHostRegistry {

  static final String FETCH = "__micropip_fetch__";
  static final String CHUNK = "__micropip_chunk__";
  static final String CLEANUP = "__micropip_cleanup__";

  private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  private final MicropipResolver resolver;
  private final AtomicLong nextToken = new AtomicLong(1);
  private final Map<Long, byte[]> responses = new ConcurrentHashMap<>();

  MicropipHostRegistry(MicropipResolver resolver) {
    this.resolver = resolver;
  }

  static boolean isControlCall(String name) {
    return FETCH.equals(name) || CHUNK.equals(name) || CLEANUP.equals(name);
  }

  static String missingResolverResponse() {
    return error(
      "Boomslang micropip remote installs require a host-side resolver. Configure " +
      "HostBridge.builder().withMicropipResolver(...)."
    );
  }

  String handleControlCall(String name, String args) {
    if (FETCH.equals(name)) {
      return fetch(args);
    }
    if (CHUNK.equals(name)) {
      return chunk(args);
    }
    if (CLEANUP.equals(name)) {
      cleanup(args);
      return "";
    }
    return error("Unknown micropip control call: " + name);
  }

  private String fetch(String args) {
    try {
      MicropipFetchResponse response = resolver.fetch(parseRequest(args));
      long token = nextToken.getAndIncrement();
      byte[] body = response.body();
      responses.put(token, body);
      return ok(token, response.statusCode(), body.length, response.headers());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("Interrupted while resolving micropip package: " + e.getMessage());
    } catch (RuntimeException e) {
      return error("Failed to resolve micropip package: " + e.getMessage());
    } catch (Exception e) {
      return error("Failed to resolve micropip package: " + e.getMessage());
    }
  }

  private MicropipFetchRequest parseRequest(String args) {
    String[] lines = args.split("\n", -1);
    if (lines.length == 0 || lines[0].isBlank()) {
      throw new IllegalArgumentException("missing URL");
    }

    Map<String, String> headers = new LinkedHashMap<>();
    for (int i = 1; i < lines.length; i++) {
      String line = lines[i];
      if (line.isEmpty()) {
        continue;
      }
      int separator = line.indexOf('\t');
      if (separator <= 0) {
        throw new IllegalArgumentException("invalid header line");
      }
      headers.put(line.substring(0, separator), decode(line.substring(separator + 1)));
    }

    return new MicropipFetchRequest(URI.create(lines[0]), headers);
  }

  private String chunk(String args) {
    try {
      String[] parts = args.split("\t", 3);
      if (parts.length != 3) {
        throw new IllegalArgumentException("chunk request must be token, offset, length");
      }
      long token = Long.parseLong(parts[0]);
      int offset = Integer.parseInt(parts[1]);
      int length = Integer.parseInt(parts[2]);
      byte[] body = responses.get(token);
      if (body == null) {
        throw new IllegalArgumentException("unknown response token: " + token);
      }
      if (offset < 0 || length < 0 || offset > body.length) {
        throw new IllegalArgumentException("invalid chunk range");
      }
      int end = (int) Math.min(body.length, (long) offset + length);
      String encoded = BASE64_ENCODER.encodeToString(Arrays.copyOfRange(body, offset, end));
      if (end == body.length) {
        responses.remove(token);
      }
      return encoded;
    } catch (RuntimeException e) {
      return error(e.getMessage());
    }
  }

  private void cleanup(String args) {
    try {
      responses.remove(Long.parseLong(args.trim()));
    } catch (RuntimeException ignored) {
      // Cleanup is best-effort; invalid tokens should not mask the Python-side error path.
    }
  }

  private static String ok(
    long token,
    int statusCode,
    int bodyLength,
    Map<String, String> headers
  ) {
    StringBuilder response = new StringBuilder();
    response
      .append("OK")
      .append('\t')
      .append(token)
      .append('\t')
      .append(statusCode)
      .append('\t')
      .append(bodyLength);
    for (Map.Entry<String, String> header : headers.entrySet()) {
      response
        .append('\n')
        .append(header.getKey())
        .append('\t')
        .append(encode(header.getValue()));
    }
    return response.toString();
  }

  private static String error(String message) {
    return "ERR\t" + encode(message == null ? "unknown error" : message);
  }

  private static String encode(String value) {
    return BASE64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String decode(String value) {
    return new String(BASE64_DECODER.decode(value), StandardCharsets.UTF_8);
  }
}
