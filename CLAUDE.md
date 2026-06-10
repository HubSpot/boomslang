# boomslang

Execute Python 3.14 from Java via WebAssembly (Chicory).

## Build Pipeline

The full build is a multi-stage pipeline. All WASM build components live under `cpython/`:

```
cpython/
  pydantic-core-wasi ─┐
  numpy-wasi ─────────┤
  pandas-wasi ────────┼→ cpython-wasi → python-host (Rust) → Java AOT
  matplotlib-wasi ────┤
  ijson-wasi ─────────┘
  builder/             (container image with WASI SDK + Wizer + Binaryen)
```

### Full build from scratch

Enter the Nix dev shell before running local build commands:

```bash
nix develop
```

The shell provides Java 21, Maven, `just`, Python 3, and the Maven toolchain file required by basepom.

```bash
just everything
```

This runs all container builds (~1hr total on first run), then Rust + Java.

To use Apple container instead of Docker:

```bash
container system start
BOOMSLANG_CONTAINER_CLI=container just everything
```

### Individual stages

```bash
# Container builds (produce WASM artifacts, all under cpython/)
just build-pydantic-core-wasi   # ~15 min (Rust compilation)
just build-numpy-wasi           # ~10 min
just build-pandas-wasi          # ~10 min
just build-matplotlib-wasi      # ~10 min
just build-ijson-wasi           # ~5 min
just build-cpython-wasi         # ~20 min (needs all five above)

# Local builds (after container stages are done)
just pip-packages               # Download pydantic etc.
just wasm                       # Build Rust host + Wizer pre-init in a container
just wasm-local                 # Build Rust host locally (needs WASI SDK)
just resources                  # Populate Java resources
just fetch-main-wasm            # Fetch latest main runtime resources from GitHub release assets (or pass -- --sha <commit-sha>)
just build                      # Maven build with AOT
just test                       # Run tests
```

### Extensions

The stock host includes the built-in `host-bridge` extension. For custom typed extensions, build a custom Python/WASM runtime that composes `boomslang-host-core` with your extension crate; see `examples/custom-python-build/`.

### Rust changes

When modifying Rust code in `python-host/`:
1. `just wasm` — rebuild WASM + Wizer
2. `just build` — rebuild Java AOT classes
3. `just test` — run tests

### Java-only changes

```bash
mvn compile -pl core
mvn test -pl tests
```

### Python package (boomslang-py)

`boomslang-py/` is a Python host: a wheel bundling the WASM runtime, executed
with wasmtime-py. Published as a GitHub release asset by CI (not PyPI).

```bash
just python-stage   # copy runtime resources + overlay into the package (needs fetch-main-wasm or resources first)
just python-test    # staged resources + venv + pytest
just python-wheel   # build dist/boomslang-<version>-py3-none-any.whl
```

Key constraint: the guest libc's preopen table is baked into the Wizer
snapshot and binds host preopens **positionally** — the guest-path strings
passed to the WASI config are ignored, and mount points beyond the baked
table are unreachable. The baked table differs across runtime builds
(wasi-libc version dependent): current builds bake a single `/` entry (the
host provides one root dir shaped like the guest fs — same contract as the
Java host's rootPath), while older builds baked one entry per wizer-fs
subdir (`/usr`, `/lib`, `/work`, `/tmp`) in image-specific order. The Python
host probes the layout at runtime (`boomslang-py/src/boomslang/_layout.py`)
instead of assuming either.

## Project Structure

- `core/` — Java runtime (PythonExecutorFactory, PythonInstance, CopyOnWriteMemory)
- `boomslang-py/` — Python host package (Sandbox API, wheel bundling the WASM runtime)
- `python-host/` — Rust WASM host (PyO3 wrapper around CPython)
- `cpython/` — All native WASM build infrastructure:
  - `cpython-wasi/` — CPython → WASM build pipeline
  - `pydantic-core-wasi/` — pydantic-core static lib build
  - `numpy-wasi/` — NumPy C extensions build
  - `pandas-wasi/` — Pandas C extensions build
  - `matplotlib-wasi/` — Matplotlib C extensions build
  - `ijson-wasi/` — ijson YAJL2 C extension build
  - `builder/` — container builder image (WASI SDK + Wizer + Binaryen + Rust)
- `boomslang-hostgen/` — Extension code generator (Rust CLI + library)
- `extensions/` — Extension crates (demo included)
- `examples/rust-host/` — Rust runtime host example with ABI JSON to Wasmtime hostgen
- `tests/` — Integration tests
- `benchmarks/` — JMH benchmarks
