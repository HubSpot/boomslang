# boomslang

Embed CPython 3.14 (WASI/WebAssembly) in any host runtime via a language-neutral ABI — Java (Chicory) is the primary host, Python (wasmtime-py wheel) and Rust (Wasmtime) are supported, other WASM runtimes are ABI targets. Full docs: `docs/` (mdBook, published at https://github.hubspot.com/boomslang/). The build docs at `docs/src/contributing/building.md` are the source of truth; this file is the short version.

## Build

Mill (`build.mill`) drives the build; the `justfile` is a shim over Mill targets. Enter `nix develop` first (Java 21, Maven, `just`, mdBook, WASI SDK).

```bash
# Fast loop without building the runtime (fetches main's release assets):
just fetch-main-wasm    # or: -- --sha <commit-sha>
just build              # Maven package with AOT
just test

# Full source build (~1hr first run; needs Docker or Apple container):
./mill artifacts.installAll && ./mill build && ./mill test
```

Container engine: `./mill artifacts.setContainerCli --cli docker|container` (Apple container needs `container system start`).

### After Rust/guest changes (`python-host/`, `python-host-core/`, `extensions/`)

```bash
just wasm && just resources && just build && just test
```

Warning: `fetch-main-wasm` installs *main's* runtime — it will not contain local Rust changes.

### Java-only changes

```bash
mvn compile -pl core
mvn test -pl tests
```

### Python package (boomslang-py)

`boomslang-py/` is a Python host: a wheel bundling the WASM runtime, executed with wasmtime-py. Published as a GitHub release asset by CI (not PyPI).

```bash
just python-stage   # copy runtime resources + overlay into the package (needs fetch-main-wasm or resources first)
just python-test    # staged resources + venv + pytest
just python-wheel   # build dist/boomslang-<version>-py3-none-any.whl
```

Key constraint: the guest libc's preopen table is baked into the Wizer snapshot and binds host preopens **positionally** — the guest-path strings passed to the WASI config are ignored, and mount points beyond the baked table are unreachable. The baked table differs across runtime builds (wasi-libc version dependent): current builds bake a single `/` entry (the host provides one root dir shaped like the guest fs — same contract as the Java host's rootPath), while older builds baked one entry per wizer-fs subdir (`/usr`, `/lib`, `/work`, `/tmp`) in image-specific order. The Python host probes the layout at runtime (`boomslang-py/src/boomslang/_layout.py`) instead of assuming either.

### Docs changes

```bash
mdbook build docs    # or: mdbook serve docs
```

## Layout

- `core/` — Java runtime (PythonExecutorFactory, PythonInstance, CopyOnWriteMemory)
- `boomslang-py/` — Python host package (Sandbox API, wheel bundling the WASM runtime)
- `python-host/` — stock Rust WASM guest; `python-host-core/` — reusable guest core (PyO3 + base ABI)
- `extensions/host-bridge/` — built-in `boomslang_host` bridge extension
- `boomslang-hostgen/` — extension codegen (Rust DSL + CLI)
- `cpython/` — container build pipeline (cpython-wasi, numpy/pandas/matplotlib/pillow/ijson/pydantic-core-wasi, builder image)
- `examples/` — custom-python-build, rust-host
- `tests/`, `benchmarks/` — integration tests, JMH
- `docs/` — mdBook documentation site

Terminology (host vs. guest, naming traps): see `docs/src/reference/glossary.md`.
