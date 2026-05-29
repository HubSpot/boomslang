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
# Define your extension contract
cat > my-ext/extension.toml <<EOF
[extension]
name = "myext"
wasm_module = "myext"
prewarm = ["_myext"]

[[functions]]
name = "do_thing"
params = [{ name = "input", type = "string" }]
returns = "string"
EOF

# Create the crate
mkdir -p my-ext/src
cat > my-ext/build.rs <<EOF
fn main() { boomslang_hostgen::generate_rust("extension.toml"); }
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
  ../my-ext/extension.toml \
  --java-out ../../core/src/main/java \
  --java-package com.hubspot.boomslang.generated
```

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

Custom extensions can also expose Java `CompletionStage<String>` work as Python awaitables. Use `async = true` in the extension manifest. See `async-extension.toml` for a complete manifest example:

```toml
[extension]
name = "my_async_ext"
wasm_module = "my_async_ext"
prewarm = ["_my_async_ext"]

[[functions]]
name = "lookup"
async = true
params = [
  { name = "request", type = "string" },
  { name = "shard", type = "int" },
]
returns = "string"
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

5. Build and use.
