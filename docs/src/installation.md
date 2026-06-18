# Installation & Runtime Variants

Boomslang is published to [Maven Central](https://central.sonatype.com/artifact/com.hubspot/boomslang) as `com.hubspot:boomslang`. Two variants of the artifact exist, distinguished by classifier.

## Default artifact

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>0.1.1</version>
</dependency>
```

The default jar includes everything needed to run Python:

- the Java API
- the bundled `boomslang.wasm` (CPython 3.14 for `wasm32-wasip1`)
- Python resources: the stdlib plus NumPy, Pandas, Matplotlib, Pillow, Pydantic, ijson, and Jinja2
- generated Chicory AOT classes (`com.hubspot.boomslang.compiled.*`), so the runtime executes as JVM bytecode

The tradeoff is size: the jar is roughly 100 MB. For most applications that's a fine price for a zero-setup Python runtime; if it isn't, use the classifier below.

## `no-python-runtime` classifier

Use this when your application — or another artifact in your dependency tree — provides the Python runtime:

```xml
<dependency>
  <groupId>com.hubspot</groupId>
  <artifactId>boomslang</artifactId>
  <version>0.1.1</version>
  <classifier>no-python-runtime</classifier>
</dependency>
```

This classifier excludes `python/**` and `com/hubspot/boomslang/compiled/**`; the Java API stays in the artifact. Your application then needs to provide:

- a WASM binary, usually at the classpath location `python/bin/boomslang.wasm`
- Python resources under `python/usr/local/lib/python3.14`
- an AOT machine factory if you want AOT instead of interpreter fallback

If your WASM is not at the default classpath location, point the factory at it with `withWasmResource(...)`.

This is the variant to use with a [custom Python build](https://github.com/HubSpot/boomslang/tree/main/examples/custom-python-build) — a runtime recompiled with your own typed extensions or extra native libraries.

## Runtime assets outside Maven

Every release also publishes raw runtime assets to [GitHub Releases](https://github.com/HubSpot/boomslang/releases): the `boomslang.wasm` binary, a `boomslang-runtime-*.tar.gz` with the Python resource tree, and sha256 checksums. Per-commit prerelease builds from `main` are published as `build-<sha>` releases. These are what non-Java hosts (or `no-python-runtime` consumers who package resources themselves) consume.

## Requirements

- Java 21 or newer.
- No system Python, no native libraries, no containers — the runtime is entirely inside the jar.
