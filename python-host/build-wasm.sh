#!/bin/bash
#
# build-wasm.sh - Build Rust-based Python WASM host with Wizer pre-initialization
#
# Usage:
#   ./build-wasm.sh          # Build, wizer, and install
#   ./build-wasm.sh build    # Build only (no wizer)
#   ./build-wasm.sh wizer    # Apply wizer to existing build
#   ./build-wasm.sh install  # Copy to runtime resources
#   ./build-wasm.sh all      # Build, wizer, and install
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
CPYTHON_DIR="$PROJECT_DIR/cpython"
BUILD_DIR="$CPYTHON_DIR/build"
RUNTIME_RESOURCES="$PROJECT_DIR/core/src/main/resources/python"

CMD="${1:-all}"

setup_wasi_sdk() {
    if [ -n "${WASI_SDK_PATH:-}" ] && [ -f "$WASI_SDK_PATH/bin/clang" ]; then
        echo "Using WASI SDK from $WASI_SDK_PATH"
        return
    fi

    if [ -f "$BUILD_DIR/wasi-sdk/bin/clang" ]; then
        export WASI_SDK_PATH="$BUILD_DIR/wasi-sdk"
        return
    fi

    echo "WASI SDK not found, downloading..."
    WASI_SDK_VERSION=24
    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64)  SDK_ARCH="x86_64" ;;
        arm64|aarch64) SDK_ARCH="arm64" ;;
        *) echo "ERROR: Unsupported architecture: $ARCH"; exit 1 ;;
    esac
    mkdir -p "$BUILD_DIR"
    curl -sL "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${WASI_SDK_VERSION}/wasi-sdk-${WASI_SDK_VERSION}.0-${SDK_ARCH}-macos.tar.gz" \
        | tar xz -C "$BUILD_DIR"
    mv "$BUILD_DIR"/wasi-sdk-${WASI_SDK_VERSION}.0-* "$BUILD_DIR/wasi-sdk"
    export WASI_SDK_PATH="$BUILD_DIR/wasi-sdk"
    echo "Downloaded WASI SDK to $WASI_SDK_PATH"
}

setup_wizer() {
    if [ -f "$BUILD_DIR/wizer/wizer" ]; then
        export PATH="$BUILD_DIR/wizer:$PATH"
        echo "Using cached Wizer from $BUILD_DIR/wizer"
        return
    fi

    if command -v wizer &> /dev/null; then
        echo "Wizer already installed"
        return
    fi

    echo "Wizer not found, downloading..."
    WIZER_VERSION=10.0.0
    ARCH=$(uname -m)
    case "$ARCH" in
        x86_64)  WIZER_ARCH="x86_64" ;;
        arm64|aarch64) WIZER_ARCH="aarch64" ;;
        *) echo "ERROR: Unsupported architecture: $ARCH"; exit 1 ;;
    esac
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    mkdir -p "$BUILD_DIR/wizer"
    curl -sL "https://github.com/bytecodealliance/wizer/releases/download/v${WIZER_VERSION}/wizer-v${WIZER_VERSION}-${WIZER_ARCH}-${OS}.tar.xz" \
        -o /tmp/wizer.tar.xz
    tar -xJf /tmp/wizer.tar.xz -C "$BUILD_DIR/wizer" --strip-components=1
    rm -f /tmp/wizer.tar.xz
    export PATH="$BUILD_DIR/wizer:$PATH"
    echo "Downloaded Wizer to $BUILD_DIR/wizer"
}

check_prerequisites() {
    if [ ! -f "$BUILD_DIR/cpython-wasi/lib/wasm32-wasi/libpython3.14.a" ]; then
        echo "ERROR: libpython not found. Build cpython-wasi first."
        exit 1
    fi

    if [ ! -f "$BUILD_DIR/cpython-wasi/usr/local/lib/python3.14/os.py" ]; then
        echo "ERROR: Python stdlib not found. Build cpython-wasi first."
        exit 1
    fi
}

do_build() {
    echo "=== Building python4j-host (Rust) ==="

    setup_wasi_sdk
    check_prerequisites

    export CC_wasm32_wasip1="$WASI_SDK_PATH/bin/clang"
    export CFLAGS_wasm32_wasip1="--sysroot=$WASI_SDK_PATH/share/wasi-sysroot -I$BUILD_DIR/cpython-wasi/include/python3.14 -Dmi_align_up_ptr=_mi_align_up_ptr"
    export PYO3_CROSS_PYTHON_VERSION=3.14
    export PYTHON_PATH="$BUILD_DIR/cpython-wasi/lib/wasm32-wasi"
    export CPYTHON_WASI_DIR="$BUILD_DIR/cpython-wasi"

    cd "$SCRIPT_DIR"

    # Build cargo features from PYTHON4J_EXTENSIONS (paths are relative to PROJECT_DIR)
    # Only enable features for optional extensions (non-optional ones are always compiled)
    CARGO_FEATURES=""
    if [ -n "${PYTHON4J_EXTENSIONS:-}" ]; then
        IFS=',' read -ra EXT_DIRS <<< "$PYTHON4J_EXTENSIONS"
        for ext_dir in "${EXT_DIRS[@]}"; do
            local abs_ext_dir="$PROJECT_DIR/$ext_dir"
            if [ -f "$abs_ext_dir/extension.toml" ]; then
                ext_name=$(grep '^name' "$abs_ext_dir/extension.toml" | head -1 | sed 's/.*= *"\(.*\)"/\1/')
                if grep "$ext_name" "$SCRIPT_DIR/Cargo.toml" 2>/dev/null | grep -q "optional"; then
                    CARGO_FEATURES="$CARGO_FEATURES $ext_name"
                    echo "Enabling optional extension: $ext_name"
                else
                    echo "Extension $ext_name is always-on (not optional)"
                fi
            fi
        done
    fi

    if [ -n "$CARGO_FEATURES" ]; then
        cargo build --target wasm32-wasip1 --release --features "$CARGO_FEATURES"
    else
        cargo build --target wasm32-wasip1 --release
    fi

    mkdir -p "$BUILD_DIR/output"
    cp "$SCRIPT_DIR/target/wasm32-wasip1/release/python4j_host.wasm" \
       "$BUILD_DIR/output/python4j.wasm"

    echo "Built: $BUILD_DIR/output/python4j.wasm"
    ls -lh "$BUILD_DIR/output/python4j.wasm"
}

do_wizer() {
    echo "=== Applying Wizer pre-initialization ==="

    setup_wasi_sdk
    setup_wizer

    local input_wasm="$BUILD_DIR/output/python4j.wasm"
    local output_wasm="$BUILD_DIR/output/python4j-wizer.wasm"

    if [ ! -f "$input_wasm" ]; then
        echo "ERROR: WASM binary not found at $input_wasm"
        echo "Run './build-wasm.sh build' first."
        exit 1
    fi

    rm -rf "$BUILD_DIR/wizer-fs"
    mkdir -p "$BUILD_DIR/wizer-fs/usr/local/lib"
    mkdir -p "$BUILD_DIR/wizer-fs/work"
    mkdir -p "$BUILD_DIR/wizer-fs/lib"
    mkdir -p "$BUILD_DIR/wizer-fs/tmp"
    cp -r "$BUILD_DIR/cpython-wasi/usr/local/lib/python3.14" "$BUILD_DIR/wizer-fs/usr/local/lib/" || true

    # Copy pydantic_core Python stubs
    [ -d "$CPYTHON_DIR/lib/pydantic_core" ] && cp -r "$CPYTHON_DIR/lib/pydantic_core" "$BUILD_DIR/wizer-fs/usr/local/lib/python3.14/"

    # Copy pip packages for prewarm
    local pip_packages="$CPYTHON_DIR/lib/pip-packages"
    if [ -d "$pip_packages" ]; then
        echo "Copying pip packages from $pip_packages"
        for pkg in pydantic typing_inspection typing_extensions.py annotated_types; do
            [ -e "$pip_packages/$pkg" ] && cp -r "$pip_packages/$pkg" "$BUILD_DIR/wizer-fs/usr/local/lib/python3.14/" 2>/dev/null || true
        done
    fi

    # Copy extension Python packages (PYTHON4J_EXTENSIONS paths are relative to PROJECT_DIR)
    if [ -n "${PYTHON4J_EXTENSIONS:-}" ]; then
        IFS=',' read -ra EXT_DIRS <<< "$PYTHON4J_EXTENSIONS"
        for ext_dir in "${EXT_DIRS[@]}"; do
            local abs_ext_dir="$PROJECT_DIR/$ext_dir"
            if [ -d "$abs_ext_dir/lib" ]; then
                echo "Copying extension packages from $abs_ext_dir/lib"
                cp -r "$abs_ext_dir/lib/"* "$BUILD_DIR/wizer-fs/usr/local/lib/python3.14/" 2>/dev/null || true
            fi
        done
    fi

    wizer \
        --init-func wizer_initialize \
        --allow-wasi \
        --wasm-bulk-memory true \
        --wasm-reference-types true \
        --mapdir /usr::$BUILD_DIR/wizer-fs/usr \
        --mapdir /lib::$BUILD_DIR/wizer-fs/lib \
        --mapdir /work::$BUILD_DIR/wizer-fs/work \
        --mapdir /tmp::$BUILD_DIR/wizer-fs/tmp \
        -o "$output_wasm" \
        "$input_wasm" || {
            echo "WARNING: Wizer pre-initialization failed. Using non-wizered WASM."
            return
        }

    mv "$output_wasm" "$input_wasm"

    echo "Wizer pre-initialization complete"
    ls -lh "$input_wasm"

    if command -v wasm-opt &> /dev/null; then
        echo "Optimizing WASM with wasm-opt..."
        wasm-opt -O3 --enable-tail-call \
            --one-caller-inline-max-function-size=144 \
            --gufa-optimizing \
            "$input_wasm" -o "${input_wasm}.opt"
        mv "${input_wasm}.opt" "$input_wasm"
        ls -lh "$input_wasm"
    fi

    if command -v wasm-strip &> /dev/null; then
        echo "Stripping WASM debug info..."
        wasm-strip "$input_wasm"
        ls -lh "$input_wasm"
    fi
}

do_install() {
    if [ ! -f "$BUILD_DIR/output/python4j.wasm" ]; then
        echo "ERROR: WASM binary not found at $BUILD_DIR/output/python4j.wasm"
        echo "Run './build-wasm.sh build' first."
        exit 1
    fi

    echo "=== Installing to runtime module ==="

    mkdir -p "$RUNTIME_RESOURCES/bin"
    cp "$BUILD_DIR/output/python4j.wasm" "$RUNTIME_RESOURCES/bin/python4j.wasm"

    echo "Installed: $RUNTIME_RESOURCES/bin/python4j.wasm"
}

do_all() {
    do_build
    do_wizer
    do_install
}

case "$CMD" in
    build)   do_build ;;
    wizer)   do_wizer ;;
    install) do_install ;;
    all)     do_all ;;
    *)
        echo "Usage: $0 {build|wizer|install|all}"
        exit 1
        ;;
esac
