# hostgen DSL Reference

`boomslang-hostgen` is both a Rust library (used from an extension crate's `build.rs`) and a CLI. The library declares an extension and emits generated code + [ABI JSON](extension-abi.md); the CLI consumes ABI JSON and generates host adapters.

## Declaring an extension (`build.rs`)

```rust
use boomslang_hostgen::{Build, ExtensionSpec, Type};

fn main() {
    let ext = ExtensionSpec::new("myext")
        .wasm_module("myext")
        .prewarm(["_myext"])
        .function("do_thing", |f| {
            f.param("input", Type::String).returns(Type::String)
        })
        .function("lookup", |f| {
            f.r#async()
                .param("request", Type::String)
                .param("shard", Type::Int)
                .returns(Type::String)
        });

    Build::new(ext).emit().generate().expect("generate myext");

    println!("cargo:rerun-if-changed=build.rs");
}
```

### `ExtensionSpec`

| Method | Effect |
| --- | --- |
| `ExtensionSpec::new(name)` | Start a spec; `name` is the extension/Python module name. |
| `.wasm_module(module)` | WASM import module for the functions (defaults to the extension name). |
| `.prewarm([modules])` | Python modules to import during Wizer init (frozen into the snapshot). |
| `.function(name, \|f\| ...)` | Declare a host function via the closure. |

### `FunctionSpec` (inside the closure)

| Method | Effect |
| --- | --- |
| `.param(name, Type)` | Append a typed parameter (order matters). |
| `.returns(Type)` | Declare the return type (omit for none). |
| `.r#async()` | Mark as an async host call — Python awaits it; the host handler is asynchronous. Async functions must return `Type::String`. |

`Type` is `String`, `Int`, `Float`, or `Bytes`. See [lowering rules](extension-abi.md#lowering-to-wasm-signatures) for the WASM signatures these produce.

### `Build`

| Method | Output |
| --- | --- |
| `Build::new(spec)` | Start from a spec. |
| `.emit()` | Shorthand for `.emit_rust_guest().emit_abi_json()` — the standard build.rs setup. |
| `.emit_rust_guest()` | `$OUT_DIR/ext_<name>.rs` — guest code, consumed by `include!(concat!(env!("OUT_DIR"), "/ext_<name>.rs"))`. |
| `.emit_abi_json()` | `$OUT_DIR/<name>.abi.json`. |
| `.emit_abi_json_to(path)` | ABI JSON at a stable path of your choosing (recommended when other builds consume it — `$OUT_DIR` paths contain build fingerprints). |
| `.emit_java_host(out_dir, package)` | `<Name>HostFunctions.java` under `out_dir/<package path>/`. Prefer running the CLI after the build instead of writing into a source tree from `build.rs`. |
| `.emit_rust_host(out_dir)` | `host_<name>.rs` Wasmtime adapter. |
| `.generate()` | Validate the manifest and write everything requested. |

Validation enforces: exact `abi_version` match, identifier-safe names (no Java/Rust keywords), no reserved `__async_*` function names, and string returns for async functions.

## The CLI

```text
boomslang-hostgen <abi.json> [--java-out DIR [--java-package PKG]] [--rust-host-out DIR]
```

| Flag | Effect |
| --- | --- |
| `--java-out DIR` | Generate the Java host adapter into `DIR` (package subdirectories created). |
| `--java-package PKG` | Java package for generated code (default `com.hubspot.boomslang.extensions`). |
| `--rust-host-out DIR` | Generate the Rust Wasmtime host adapter into `DIR`. |

With no output flag the CLI validates the ABI JSON, then exits nonzero with `no output requested`.

From source: `cargo run --manifest-path boomslang-hostgen/Cargo.toml -- <args>`.

## Library entry points

For build tooling that wants codegen without the CLI:

- `read_abi(path) -> Manifest` — parse + validate an ABI JSON file.
- `generate_java(abi_path, out_dir, package)` — Java adapter from a file.
- `generate_rust_host(abi_path, out_dir)` — Rust host adapter from a file.

The serde-serializable `Manifest` / `Extension` / `Function` / `Param` / `Type` structs are public; the [ABI JSON schema](extension-abi.md#schema) is their serialized form.
