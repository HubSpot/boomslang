# Building from Source

The build is driven by **Mill** (`build.mill`); the `justfile` is a thin shim over Mill targets for common loops. Requirements: Java 21, Maven, `just`, and a container engine — Docker on Linux, Docker or Apple `container` on macOS.

With Nix, the dev shell provides Java 21, Maven, `just`, mdBook, Python 3, the WASI SDK, and the Maven JDK toolchain configuration required by basepom:

```bash
nix develop
```

A container engine still needs to be installed and running on the host for the full WASM pipeline.

## Full build

```bash
./mill artifacts.installAll   # native WASM artifacts (containers), Rust guest, Python resources
./mill build                  # Maven package incl. Java AOT classes
./mill test                   # integration tests
```

First runs take about an hour: CPython and the native libraries build inside containers.

## Skipping the pipeline: `fetch-main-wasm`

For Java-only work you don't need to build the runtime at all:

```bash
just fetch-main-wasm   # installs the latest main runtime release assets into core resources
just build             # package with AOT, skips tests
just test
```

`just fetch-main-wasm` downloads the latest successful `main` runtime artifact from GitHub release assets into `core/src/main/resources/python/bin/` and `python/usr/`. Select a specific artifact with `just fetch-main-wasm -- --sha <commit-sha>` (or `--branch <name>`).

> **Mind the mismatch:** fetched resources are built from *main*, not from your working tree. If your checkout contains Rust/guest changes, a fetched runtime silently won't include them — rebuild with `just wasm` instead.

## Change loops

**Java-only changes:**

```bash
mvn compile -pl core
mvn test -pl tests
```

**Rust/guest changes** (`python-host/`, `python-host-core/`, `extensions/`):

```bash
just wasm        # rebuild WASM + Wizer snapshot
just resources   # repopulate Java resources
just build       # rebuild Java AOT classes
just test
```

**Docs:** `mdbook serve docs` (mdBook is in the Nix shell).

## Container engine selection

Docker is the default. The selected engine is stored in the git-ignored `.boomslang-container-cli` file so Mill daemon builds see a stable input; the `./mill` wrapper also writes it when `BOOMSLANG_CONTAINER_CLI` is set.

```bash
./mill artifacts.setContainerCli --cli docker      # or: --cli container (Apple)
./mill artifacts.showContainerCli
```

Docker builds require BuildKit/buildx. For Apple container, run `container system start` first.

## Pipeline stages

The native pipeline lives under `cpython/`, one container build per component:

```bash
just build-pydantic-core-wasi   # ~15 min (Rust compilation)
just build-numpy-wasi           # ~10 min
just build-pandas-wasi          # ~10 min
just build-matplotlib-wasi      # ~10 min
just build-pillow-wasi          # ~10 min
just build-ijson-wasi           # ~5 min
just build-cpython-wasi         # ~20 min (links all of the above)
just pip-packages               # pure-Python packages (pydantic, jinja2, ...)
just wasm                       # Rust guest + Wizer pre-init
just resources                  # populate core/src/main/resources
just build
just test
```

Inspect the artifact DAG and caching:

```bash
./mill artifacts.dag
./mill artifacts.dagDot
./mill artifacts.cacheStatus
./mill path artifacts.installAll artifacts.wasm
```

`./mill plan artifacts.installAll` prints execution order. To verify caching, run `./mill artifacts.installAll` twice — the second run should skip task bodies.

## CI

`.github/workflows/build.yml` rebuilds everything from source in containers, validates the generated runtime, runs the tests, and publishes runtime assets (wasm + resource tarball + checksums) to GitHub Releases — tagged releases for `v*` tags, `build-<sha>` prereleases for every `main` commit (these are what `fetch-main-wasm` consumes). `docs.yml` builds this book and deploys it to GitHub Pages on pushes to `main`.
