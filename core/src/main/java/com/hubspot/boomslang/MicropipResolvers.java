package com.hubspot.boomslang;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MicropipResolvers {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
  private static final Pattern NORMALIZE_NAME_PATTERN = Pattern.compile("[-_.]+");
  private static final Pattern LOCK_NAME_PATTERN = Pattern.compile(
    "(?:\"name\"\\s*:\\s*\"([A-Za-z0-9_.-]+)\")|(?:\\bname\\s*=\\s*\"([A-Za-z0-9_.-]+)\")"
  );
  private static final Pattern LOCK_VERSION_PATTERN = Pattern.compile(
    "(?:\"version\"\\s*:\\s*\"([^\"]+)\")|(?:\\bversion\\s*=\\s*\"([^\"]+)\")"
  );

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

  /**
   * Returns a PyPI resolver that rejects package metadata and wheel requests not present in the
   * supplied requirements or lock-style allowlist before making the HTTP request.
   */
  public static MicropipResolver pypiAllowing(Path allowlistFile) throws IOException {
    return withAllowlist(pypi(), allowlistFile);
  }

  /**
   * Returns a PyPI resolver that rejects package metadata and wheel requests not present in the
   * supplied requirements or lock-style allowlist before making the HTTP request.
   */
  public static MicropipResolver pypiAllowing(Iterable<String> allowlistLines) {
    return withAllowlist(pypi(), allowlistLines);
  }

  /**
   * Wraps a resolver with a host-side package allowlist. The allowlist accepts requirement-style
   * lines such as {@code requests==2.32.0}, direct wheel URLs, and lock-style {@code name =
   * "..."} / {@code version = "..."} entries. Unpinned entries allow any version of that package;
   * pinned entries allow package index metadata requests but restrict wheel downloads to listed
   * versions.
   */
  public static MicropipResolver withAllowlist(
    MicropipResolver delegate,
    Path allowlistFile
  ) throws IOException {
    Objects.requireNonNull(allowlistFile, "allowlistFile");
    return withAllowlist(delegate, Files.readAllLines(allowlistFile));
  }

  /**
   * Wraps a resolver with a host-side package allowlist. The allowlist accepts requirement-style
   * lines such as {@code requests==2.32.0}, direct wheel URLs, and lock-style {@code name =
   * "..."} / {@code version = "..."} entries. Unpinned entries allow any version of that package;
   * pinned entries allow package index metadata requests but restrict wheel downloads to listed
   * versions.
   */
  public static MicropipResolver withAllowlist(
    MicropipResolver delegate,
    Iterable<String> allowlistLines
  ) {
    Objects.requireNonNull(delegate, "delegate");
    Allowlist allowlist = Allowlist.parse(allowlistLines);
    return request -> {
      AllowedRequest target = AllowedRequest
        .from(request.uri())
        .orElseThrow(() ->
          new IOException(
            "Micropip request is not allowlisted and could not be mapped to a package: " +
            request.uri()
          )
        );
      if (!allowlist.allows(target)) {
        throw new IOException(
          "Micropip request is not allowlisted: " +
          request.uri() +
          " (package " +
          target.describe() +
          ")"
        );
      }
      return delegate.fetch(request);
    };
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

  private static Map<String, String> flattenHeaders(
    Map<String, java.util.List<String>> raw
  ) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (Map.Entry<String, java.util.List<String>> entry : raw.entrySet()) {
      if (!entry.getValue().isEmpty()) {
        headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().get(0));
      }
    }
    return headers;
  }

  private record Allowlist(Map<String, Optional<Set<String>>> packages) {
    static Allowlist parse(Iterable<String> lines) {
      Objects.requireNonNull(lines, "allowlistLines");
      Map<String, Optional<Set<String>>> packages = new HashMap<>();
      PendingLockEntry pending = new PendingLockEntry();
      for (String rawLine : lines) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }

        Matcher nameMatcher = LOCK_NAME_PATTERN.matcher(line);
        if (nameMatcher.find()) {
          pending.flushTo(packages);
          pending.name =
            normalizeName(firstPresent(nameMatcher.group(1), nameMatcher.group(2)));

          Matcher versionMatcher = LOCK_VERSION_PATTERN.matcher(line);
          if (versionMatcher.find()) {
            pending.version =
              firstPresent(versionMatcher.group(1), versionMatcher.group(2));
            pending.flushTo(packages);
          }
          continue;
        }

        Matcher versionMatcher = LOCK_VERSION_PATTERN.matcher(line);
        if (versionMatcher.find() && pending.name != null) {
          pending.version =
            firstPresent(versionMatcher.group(1), versionMatcher.group(2));
          pending.flushTo(packages);
          continue;
        }

        parseRequirementLine(line).ifPresent(entry -> entry.addTo(packages));
      }
      pending.flushTo(packages);
      return new Allowlist(Map.copyOf(packages));
    }

    boolean allows(AllowedRequest request) {
      Optional<Set<String>> versions = packages.get(request.packageName());
      if (versions == null) {
        return false;
      }
      if (versions.isEmpty() || request.version().isEmpty()) {
        return true;
      }
      return versions.get().contains(request.version().get());
    }
  }

  private record AllowedRequest(String packageName, Optional<String> version) {
    static Optional<AllowedRequest> from(URI uri) {
      String path = uri.getPath();
      if (path == null || path.isBlank()) {
        return Optional.empty();
      }

      String wheelPath = path.endsWith(".metadata")
        ? path.substring(0, path.length() - ".metadata".length())
        : path;
      if (wheelPath.endsWith(".whl")) {
        return parseWheelFilename(Path.of(wheelPath).getFileName().toString())
          .map(entry -> new AllowedRequest(entry.packageName(), entry.version()));
      }

      List<String> segments = pathSegments(path);
      for (int i = 0; i < segments.size() - 1; i++) {
        if ("simple".equals(segments.get(i))) {
          return Optional.of(
            new AllowedRequest(normalizeName(segments.get(i + 1)), Optional.empty())
          );
        }
      }

      if (segments.size() >= 2 && "pypi".equals(segments.get(0))) {
        return Optional.of(
          new AllowedRequest(normalizeName(segments.get(1)), Optional.empty())
        );
      }

      if (!segments.isEmpty()) {
        String last = segments.get(segments.size() - 1);
        if (!last.contains(".")) {
          return Optional.of(new AllowedRequest(normalizeName(last), Optional.empty()));
        }
      }
      return Optional.empty();
    }

    String describe() {
      return version
        .map(value -> "'" + packageName + "' version '" + value + "'")
        .orElse("'" + packageName + "'");
    }
  }

  private record AllowlistEntry(String packageName, Optional<String> version) {
    void addTo(Map<String, Optional<Set<String>>> packages) {
      if (version.isEmpty()) {
        packages.put(packageName, Optional.empty());
        return;
      }

      Optional<Set<String>> existing = packages.get(packageName);
      if (existing != null && existing.isEmpty()) {
        return;
      }

      Set<String> versions = existing == null
        ? new HashSet<>()
        : existing.map(HashSet::new).orElseGet(HashSet::new);
      versions.add(version.get());
      packages.put(packageName, Optional.of(Set.copyOf(versions)));
    }
  }

  private static final class PendingLockEntry {

    private String name;
    private String version;

    private void flushTo(Map<String, Optional<Set<String>>> packages) {
      if (name == null) {
        return;
      }
      new AllowlistEntry(name, Optional.ofNullable(version)).addTo(packages);
      name = null;
      version = null;
    }
  }

  private static Optional<AllowlistEntry> parseRequirementLine(String line) {
    if (line.startsWith("-")) {
      return Optional.empty();
    }

    String withoutComment = stripRequirementComment(line);
    int marker = withoutComment.indexOf(';');
    if (marker >= 0) {
      withoutComment = withoutComment.substring(0, marker).trim();
    }
    if (withoutComment.isBlank()) {
      return Optional.empty();
    }

    if (withoutComment.startsWith("http://") || withoutComment.startsWith("https://")) {
      return parseWheelFilename(
        Path.of(URI.create(withoutComment).getPath()).getFileName().toString()
      );
    }

    int at = withoutComment.indexOf('@');
    if (at > 0) {
      return packageFromNamePart(withoutComment.substring(0, at), Optional.empty());
    }

    Optional<String> exactVersion = exactVersion(withoutComment);
    int end = 0;
    while (end < withoutComment.length()) {
      char c = withoutComment.charAt(end);
      if (
        Character.isWhitespace(c) ||
        c == '[' ||
        c == '<' ||
        c == '>' ||
        c == '=' ||
        c == '!' ||
        c == '~'
      ) {
        break;
      }
      end++;
    }
    if (end == 0) {
      return Optional.empty();
    }
    return packageFromNamePart(withoutComment.substring(0, end), exactVersion);
  }

  private static Optional<AllowlistEntry> parseWheelFilename(String filename) {
    String wheel = filename.endsWith(".metadata")
      ? filename.substring(0, filename.length() - ".metadata".length())
      : filename;
    if (!wheel.endsWith(".whl")) {
      return Optional.empty();
    }

    String[] parts = wheel.substring(0, wheel.length() - ".whl".length()).split("-");
    if (parts.length < 5) {
      return Optional.empty();
    }
    return Optional.of(
      new AllowlistEntry(normalizeName(parts[0]), Optional.of(parts[1]))
    );
  }

  private static Optional<AllowlistEntry> packageFromNamePart(
    String rawName,
    Optional<String> version
  ) {
    String name = rawName.trim();
    int extras = name.indexOf('[');
    if (extras >= 0) {
      name = name.substring(0, extras);
    }
    if (name.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new AllowlistEntry(normalizeName(name), version));
  }

  private static Optional<String> exactVersion(String requirement) {
    int index = requirement.indexOf("==");
    if (index < 0) {
      return Optional.empty();
    }
    int start = index + 2;
    int end = start;
    while (end < requirement.length()) {
      char c = requirement.charAt(end);
      if (Character.isWhitespace(c) || c == ',' || c == ';') {
        break;
      }
      end++;
    }
    if (end == start) {
      return Optional.empty();
    }
    return Optional.of(requirement.substring(start, end));
  }

  private static String stripRequirementComment(String line) {
    int comment = line.indexOf(" #");
    return comment >= 0 ? line.substring(0, comment).trim() : line;
  }

  private static String normalizeName(String name) {
    return NORMALIZE_NAME_PATTERN.matcher(name).replaceAll("-").toLowerCase(Locale.ROOT);
  }

  private static String firstPresent(String first, String second) {
    return first == null ? second : first;
  }

  private static List<String> pathSegments(String path) {
    List<String> segments = new ArrayList<>();
    for (String segment : path.split("/")) {
      if (!segment.isBlank()) {
        segments.add(segment.toLowerCase(Locale.ROOT));
      }
    }
    return segments;
  }
}
