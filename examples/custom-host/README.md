# Custom boomslang Host Example

This example shows how to build a custom boomslang WASM binary with your own extensions.

## Why build a custom host?

The stock `python-host` ships with the generic host bridge (`boomslang_host.call()` / `boomslang_host.log()`). If you need:
- Dedicated WASM imports with typed signatures (no JSON overhead)
- Custom Python builtin modules
- Additional prewarm modules

...you build a custom host that composes `boomslang-host-core` with your extensions.

## Building

```bash
# Point at a local cpython-wasi build (or omit to download from GitHub Releases)
export CPYTHON_WASI_DIR=../../cpython/build/cpython-wasi

# Build
cargo build --target wasm32-wasip1 --release

# The output is at target/wasm32-wasip1/release/my_custom_python.wasm
```

## Adding your own extension

1. Create an extension crate using `boomslang-hostgen`:

```bash
# Create the crate
mkdir -p my-ext/src
cat > my-ext/build.rs <<EOF
fn main() {
    let ext = boomslang_hostgen::ExtensionSpec::new("myext")
        .wasm_module("myext")
        .prewarm(["_myext"])
        .function("do_thing", |f| {
            f.param("input", boomslang_hostgen::Type::String)
                .returns(boomslang_hostgen::Type::String)
        });

    boomslang_hostgen::Build::new(ext)
        .emit_rust_guest()
        .emit_abi_json()
        .generate()
        .expect("generate myext");

    println!("cargo:rerun-if-changed=build.rs");
}
EOF
cat > my-ext/src/lib.rs <<EOF
include!(concat!(env!("OUT_DIR"), "/ext_myext.rs"));
EOF
```

2. Add it to this crate's `Cargo.toml`:
```toml
[dependencies]
my-extension = { path = "../my-ext" }
```

3. Register it in `src/lib.rs`:
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

4. Generate the typed Java host-function bridge for the extension:

```bash
cargo run --manifest-path ../../boomslang-hostgen/Cargo.toml -- \
  ../my-ext/target/wasm32-wasip1/release/build/my-ext-*/out/myext.abi.json \
  --java-out ../../core/src/main/java \
  --java-package com.hubspot.boomslang.generated
```

For build systems that want Java generation during the Rust extension build, call
`emit_java_host("../../core/src/main/java", "com.hubspot.boomslang.generated")` on the
`Build` value instead of running the CLI later.

The generated class exposes typed functional interfaces and a builder, so Java users only fill in the host implementations:

```java
var extension = MyextHostFunctions.builder()
    .withDoThing(input -> "result from Java")
    .buildExtension();

var factory = PythonExecutorFactory.builder()
    .addExtension(extension)
    .build();
```

## Async extension functions

Custom extensions can also expose Java `CompletionStage<String>` work as Python awaitables. Use `r#async()` in the extension DSL:

```rust
fn main() {
    let ext = boomslang_hostgen::ExtensionSpec::new("my_async_ext")
        .wasm_module("my_async_ext")
        .prewarm(["_my_async_ext"])
        .function("lookup", |f| {
            f.r#async()
                .param("request", boomslang_hostgen::Type::String)
                .param("shard", boomslang_hostgen::Type::Int)
                .returns(boomslang_hostgen::Type::String)
        });

    boomslang_hostgen::Build::new(ext)
        .emit_rust_guest()
        .emit_abi_json()
        .generate()
        .expect("generate my_async_ext");
}
```

Generated async functions preserve the normal typed argument handling. The Java handler receives typed params and returns a `CompletionStage<String>`:

```java
AsyncHostRegistry asyncRegistry = new AsyncHostRegistry();

var hostBridge = HostBridge.builder()
    .withAsyncRegistry(asyncRegistry)
    .buildExtension();

var asyncExtension = MyAsyncExtHostFunctions.builder()
    .withAsyncRegistry(asyncRegistry)
    .withLookup((request, shard) -> rpcClient.lookup(request, shard))
    .buildExtension();

var factory = PythonExecutorFactory.builder()
    .addExtension(hostBridge)
    .addExtension(asyncExtension)
    .build();
```

Python installs the Boomslang event loop, calls typed extension functions, and awaits them with standard asyncio APIs:

```python
import asyncio
from boomslang_host.asyncio import install
from my_async_ext import lookup

install()

async def main():
    first = lookup('{"id": 1}', 0)
    second = lookup('{"id": 2}', 1)
    result = await asyncio.gather(first, second)
    print(result)

asyncio.run(main())
```

The `AsyncHostRegistry` is shared between `boomslang_host.asyncio` and generated async extensions. Java completion threads only queue results in that registry; Python/WASM is resumed by the Boomslang event loop polling completions on the WASM thread.

Current generated async functions support typed parameters with a `string` return, represented as `CompletionStage<String>` on the Java side.

### Async wire protocol (v1)

`boomslang_host.asyncio` and `AsyncHostRegistry` talk over a small, versioned protocol invoked
through the stock `boomslang_host.call(name, args)` function. The `__async_*` names are a
**reserved control namespace** — do not define extension host functions with these names:

| Control call | Args | Returns |
|---|---|---|
| `__async_protocol__` | — | integer protocol version (currently `1`) |
| `__async_start__` | `name\npayload` | decimal token for a registered named async handler |
| `__async_poll__` | timeout ms (`<0` blocks, `0` polls) | one header line per ready completion: `token\t{1\|0}\t<valueByteLength>` |
| `__async_result__` | token | base64 of that completion's value bytes (consumes it) |
| `__async_cancel__` | token | cancels the in-flight future |

Why this shape matters:

- **Versioned.** The Python client is frozen into each consumer's WASM Wizer snapshot, so the Java
  host must stay compatible with already-shipped clients. `__async_protocol__` lets a client refuse
  a host older than the protocol it was built against; bump `AsyncHostRegistry.PROTOCOL_VERSION`
  only for breaking wire changes.
- **Poll and result are decoupled.** `__async_poll__` returns only headers (token, ok, length);
  values are fetched one at a time via `__async_result__`. A batch of completions therefore never
  exceeds the single host-call result buffer. (A single value larger than that buffer is still a
  limitation — chunked retrieval is a future protocol addition.)
- **Failures never hang.** Synchronous handler errors are recorded via `AsyncHostRegistry.startFailed`
  and surface as a failed completion (the coroutine raises `HostAsyncError`); the client also rejects
  any non-positive token immediately.
- **Binary-safe value channel.** Completion values are carried as base64 of raw bytes, so extending
  async returns to `bytes` later needs no wire change.

5. Build and use.
