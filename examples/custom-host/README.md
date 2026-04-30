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

4. Build and use.
