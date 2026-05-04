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
  builder/             (Docker image with WASI SDK + Wizer + Binaryen)
```

### Full build from scratch

```bash
just everything
```

This runs all Docker builds (~1hr total on first run), then Rust + Java.

### Individual stages

```bash
# Docker builds (produce WASM artifacts, all under cpython/)
just build-pydantic-core-wasi   # ~15 min (Rust compilation)
just build-numpy-wasi           # ~10 min
just build-pandas-wasi          # ~10 min
just build-matplotlib-wasi      # ~10 min
just build-ijson-wasi           # ~5 min
just build-cpython-wasi         # ~20 min (needs all five above)

# Local builds (after Docker stages are done)
just pip-packages               # Download pydantic etc.
just wasm                       # Build Rust host + Wizer pre-init (Docker)
just wasm-local                 # Build Rust host locally (needs WASI SDK)
just resources                  # Populate Java resources
just build                      # Maven build with AOT
just test                       # Run tests
```

### Extensions

Build with extensions enabled:
```bash
PYTHON4J_EXTENSIONS="extensions/demo" just wasm-local
PYTHON4J_EXTENSIONS="extensions/demo" just resources
```

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

## Project Structure

- `core/` — Java runtime (PythonExecutorFactory, PythonInstance, CopyOnWriteMemory)
- `python-host/` — Rust WASM host (PyO3 wrapper around CPython)
- `cpython/` — All native WASM build infrastructure:
  - `cpython-wasi/` — CPython → WASM build pipeline (Docker)
  - `pydantic-core-wasi/` — pydantic-core static lib build
  - `numpy-wasi/` — NumPy C extensions build
  - `pandas-wasi/` — Pandas C extensions build
  - `matplotlib-wasi/` — Matplotlib C extensions build
  - `ijson-wasi/` — ijson YAJL2 C extension build
  - `builder/` — Docker builder image (WASI SDK + Wizer + Binaryen + Rust)
- `boomslang-hostgen/` — Extension code generator (Rust CLI + library)
- `extensions/` — Extension crates (demo included)
- `tests/` — Integration tests
- `benchmarks/` — JMH benchmarks
