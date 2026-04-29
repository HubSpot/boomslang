#!/usr/bin/env bash
# Build pydantic-core-wasi locally (replicates the Blazar buildpack steps).
# Requires: cargo, wasi-sdk (or WASI_SDK_PATH set), CPython headers.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PYDANTIC_CORE_TAG="v2.41.5"
CPYTHON_HEADERS="${CPYTHON_HEADERS:-/tmp/cpython-wasi}"

# Step 1: Clone pydantic-core source if not present
if [ ! -f src/lib.rs ]; then
    echo "==> Cloning pydantic-core ${PYDANTIC_CORE_TAG}..."
    CLONE_DIR="$SCRIPT_DIR/.pydantic-core-src"
    rm -rf "$CLONE_DIR"
    git clone --depth 1 --branch "${PYDANTIC_CORE_TAG}" \
        https://github.com/pydantic/pydantic-core "$CLONE_DIR"
    cp "$CLONE_DIR/Cargo.toml" .
    cp "$CLONE_DIR/Cargo.lock" .
    cp "$CLONE_DIR/build.rs" .
    cp -r "$CLONE_DIR/src" .
    cp -r "$CLONE_DIR/python" .
    rm -rf "$CLONE_DIR"
    # Patch crate-type to staticlib only
    sed -i '' '/crate-type/s/.*/crate-type = ["staticlib"]/' Cargo.toml
    echo "Patched crate-type: $(grep crate-type Cargo.toml)"
fi

# Step 2: Set up cross-compilation env
if [ -z "${WASI_SDK_PATH:-}" ]; then
    # Try common locations
    for p in /opt/wasi-sdk "$HOME/src/wasm-opt/aviator/cpython/build/wasi-sdk"; do
        if [ -f "$p/bin/clang" ]; then
            export WASI_SDK_PATH="$p"
            break
        fi
    done
fi

if [ -z "${WASI_SDK_PATH:-}" ]; then
    echo "ERROR: WASI_SDK_PATH not set and wasi-sdk not found"
    exit 1
fi

# CPython headers — try aviator's extracted artifact
if [ ! -d "$CPYTHON_HEADERS/include/python3.14" ]; then
    AVIATOR_HEADERS="$SCRIPT_DIR/../../aviator/cpython/build/cpython-wasi"
    if [ -d "$AVIATOR_HEADERS/include/python3.14" ]; then
        CPYTHON_HEADERS="$AVIATOR_HEADERS"
    else
        echo "ERROR: CPython headers not found at $CPYTHON_HEADERS or $AVIATOR_HEADERS"
        exit 1
    fi
fi

export CC_wasm32_wasip1="$WASI_SDK_PATH/bin/clang"
export CFLAGS_wasm32_wasip1="--sysroot=$WASI_SDK_PATH/share/wasi-sysroot -I$CPYTHON_HEADERS/include/python3.14"
export PYO3_CROSS_PYTHON_VERSION=3.14
export PYO3_CROSS_LIB_DIR="$CPYTHON_HEADERS/lib/wasm32-wasi"

echo "==> Building pydantic-core for wasm32-wasip1..."
echo "    WASI_SDK: $WASI_SDK_PATH"
echo "    Headers:  $CPYTHON_HEADERS"

cargo build --target wasm32-wasip1 --release --lib

# Step 3: Package artifact
OUTPUT="$SCRIPT_DIR/build-output"
mkdir -p "$OUTPUT/lib" "$OUTPUT/python" "$OUTPUT/wheels"

cp target/wasm32-wasip1/release/lib_pydantic_core.a "$OUTPUT/lib/"
cp -r python/pydantic_core "$OUTPUT/python/"
cp wheels/*.whl "$OUTPUT/wheels/" 2>/dev/null || true

# Create tarball matching the format cpython-wasi build.sh expects
cd "$OUTPUT"
tar czf "$SCRIPT_DIR/artifact.tgz" .

echo "==> Built: $SCRIPT_DIR/artifact.tgz"
ls -lh "$SCRIPT_DIR/artifact.tgz"
