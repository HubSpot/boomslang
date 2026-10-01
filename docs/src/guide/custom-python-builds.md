# Custom Python Builds

Build a custom Python/WASM runtime when the stock `boomslang_host.call(...)` bridge is too blunt. A custom build changes the Rust/WASI guest inside `boomslang.wasm`; it is independent of whether the outside host is Java, Rust, or another language.

Use one for:

- typed WASM imports instead of string/JSON calls
- host functions exposed as custom Python modules
- extra Python modules prewarmed into the Wizer snapshot
- native libraries required by Python extensions (WASI has no dynamic linking — native code must be statically linked into the guest)

The runnable starting point is [`examples/custom-python-build/`](https://github.com/HubSpot/boomslang/tree/main/examples/custom-python-build).

## The build flow

1. Declare an extension contract in your extension crate's `build.rs` with the `boomslang-hostgen` DSL.
2. `boomslang-hostgen` emits the Rust guest code and an ABI JSON file at build time.
3. Generate host-language bridge code from the ABI JSON (Java or Rust adapters).
4. Compose the extension with `boomslang-host-core` in a custom guest crate.
5. Add any required native static libraries to the WASI build.
6. Build the guest to `wasm32-wasip1`.
7. Package the custom `boomslang.wasm` and matching Python resources with your app.
8. For Java packaging, depend on `com.hubspot:boomslang` with the [`no-python-runtime` classifier](../installation.md).

## Declaring an extension

```rust
// my-ext/build.rs
fn main() {
    let ext = boomslang_hostgen::ExtensionSpec::new("myext")
        .wasm_module("myext")
        .prewarm(["_myext"])
        .function("do_thing", |f| {
            f.param("input", boomslang_hostgen::Type::String)
                .returns(boomslang_hostgen::Type::String)
        });

    boomslang_hostgen::Build::new(ext)
        .emit()
        .generate()
        .expect("generate myext");

    println!("cargo:rerun-if-changed=build.rs");
}
```

```rust
// my-ext/src/lib.rs
include!(concat!(env!("OUT_DIR"), "/ext_myext.rs"));
```

Register it alongside the stock host bridge in your guest crate:

```rust
boomslang_host_core::init(
    || {
        boomslang_ext_host_bridge::register();
        my_extension::register();
    },
    |py| {
        boomslang_ext_host_bridge::prewarm(py);
        my_extension::prewarm(py);
    },
);
```

And build:

```bash
export CPYTHON_WASI_DIR=../../cpython/build/cpython-wasi  # or omit to download from GitHub Releases
cargo build --target wasm32-wasip1 --release
```

## Building against a package image

`boomslang-host-core`'s `build.rs` reads `$CPYTHON_WASI_DIR/sysroot.json`, which a package image (for example from hubos-pkgs) writes beside its archives. From it the build generates:

- the inittab: one `PyImport_AppendInittab` per entry in `builtins`;
- the link line: the image's `linkLibs` in manifest order, then libpython and the archives its `python-3.14-embed.pc` names, libc++, the wasi-emulated libraries and `c-printscan-long-double`;
- the Wizer prewarm: the stdlib, then the image's `prewarm`. A module in `snapshotRequired` that fails to import fails the bake.

Without a `sysroot.json` the runtime is stdlib only. A `contract` other than `1` fails the build. The bundled runtime describes itself in `cpython/bundled-sysroot.json`.

On the Java side, a `PythonImage` names an image's classpath prefix and its Endive AOT class, so several images can share one classpath:

```java
PythonExecutorFactory factory = PythonExecutorFactory
    .builder()
    .withImage(PythonImage.onClasspath("images/data/", "com.example.images.data.PythonWasm"))
    .withStdlibPath(pythonRoot)
    .build();
```

## Generating host adapters

Run the hostgen CLI against the ABI JSON your build emitted:

```bash
# Java hosts
boomslang-hostgen myext.abi.json --java-out src/main/java --java-package com.example.generated

# Rust/Wasmtime hosts
boomslang-hostgen myext.abi.json --rust-host-out src/generated
```

The generated Java class exposes typed functional interfaces and a builder — you only fill in implementations:

```java
var extension = MyextHostFunctions.builder()
    .withDoThing(input -> "result from Java")
    .buildExtension();

PythonExecutorFactory factory = PythonExecutorFactory
    .builder()
    .withStdlibPath(pythonRoot)
    .addExtension(extension)
    .build();
```

Python code then imports the extension as a real module:

```python
from myext import do_thing
print(do_thing("hello"))
```

Async functions (`f.r#async()` in the DSL) generate `CompletionStage<String>` handlers on the Java side; see [Async host calls](async.md).

The full DSL surface and the ABI JSON contract are documented in the reference section.
