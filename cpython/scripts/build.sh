#!/bin/bash
# Build version: 22 (bake in -Os upstream + matplotlib link flags + GUFA + JVM tuning)
#
# build.sh - Build Python WASM with Rust-based host using PyO3
#
# Expects the upstream cpython-wasi artifact to be extracted at
# build/cpython-wasi/ (done by Blazar before step, or manually for local dev).
#
# Usage:
#   ./scripts/build.sh          # Full build and install
#   ./scripts/build.sh build    # Full build and install
#   ./scripts/build.sh install  # Just copy outputs to runtime module
#   ./scripts/build.sh clean    # Remove build artifacts

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_DIR/build"
RUST_HOST_DIR="$PROJECT_DIR/../python-host"

PYTHON_VERSION="3.14.0"

CMD="${1:-build}"

setup_wasi_sdk() {
    if [ -n "${WASI_SDK_PATH:-}" ] && [ -f "$WASI_SDK_PATH/bin/clang" ]; then
        echo "Using WASI SDK from $WASI_SDK_PATH"
        return
    fi

    if [ -f "$BUILD_DIR/wasi-sdk/bin/clang" ]; then
        export WASI_SDK_PATH="$BUILD_DIR/wasi-sdk"
        return
    fi

    echo "=== Installing WASI SDK ==="
    mkdir -p "$BUILD_DIR"

    case "$(uname -s)" in
        Darwin) WASI_OS="macos" ;;
        Linux) WASI_OS="linux" ;;
        *) echo "Unsupported OS"; exit 1 ;;
    esac

    case "$(uname -m)" in
        x86_64) WASI_ARCH="x86_64" ;;
        arm64|aarch64) WASI_ARCH="arm64" ;;
        *) echo "Unsupported arch"; exit 1 ;;
    esac

    WASI_VERSION="24"
    WASI_URL="https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${WASI_VERSION}/wasi-sdk-${WASI_VERSION}.0-${WASI_ARCH}-${WASI_OS}.tar.gz"

    echo "Downloading WASI SDK from $WASI_URL"
    WASI_ARCHIVE="$BUILD_DIR/wasi-sdk.tar.gz"
    curl --fail --show-error -L "$WASI_URL" -o "$WASI_ARCHIVE" || \
        curl --fail --show-error -k -L "$WASI_URL" -o "$WASI_ARCHIVE"
    tar -xzf "$WASI_ARCHIVE" -C "$BUILD_DIR"
    rm -f "$WASI_ARCHIVE"
    mv "$BUILD_DIR/wasi-sdk-${WASI_VERSION}.0-${WASI_ARCH}-${WASI_OS}" "$BUILD_DIR/wasi-sdk"
    export WASI_SDK_PATH="$BUILD_DIR/wasi-sdk"
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

    echo "=== Installing Wizer ==="
    mkdir -p "$BUILD_DIR/wizer"

    WIZER_VERSION="10.0.0"
    WIZER_URL="https://github.com/bytecodealliance/wizer/releases/download/v${WIZER_VERSION}/wizer-v${WIZER_VERSION}-x86_64-linux.tar.xz"

    echo "Downloading Wizer from $WIZER_URL"
    WIZER_ARCHIVE="$BUILD_DIR/wizer.tar.xz"
    curl --fail --show-error -L "$WIZER_URL" -o "$WIZER_ARCHIVE"
    tar -xJf "$WIZER_ARCHIVE" -C "$BUILD_DIR/wizer" --strip-components=1
    rm -f "$WIZER_ARCHIVE"
    chmod +x "$BUILD_DIR/wizer/wizer"
    export PATH="$BUILD_DIR/wizer:$PATH"
    echo "Installed Wizer to $BUILD_DIR/wizer"
}

check_cpython_wasi() {
    if [ ! -f "$BUILD_DIR/cpython-wasi/lib/wasm32-wasi/libpython3.14.a" ]; then
        echo "ERROR: cpython-wasi artifact not found at $BUILD_DIR/cpython-wasi/"
        echo "In CI, the Blazar before step downloads this automatically."
        echo "For local dev, extract the cpython-wasi artifact to $BUILD_DIR/cpython-wasi/"
        exit 1
    fi
    echo "Found cpython-wasi artifact at $BUILD_DIR/cpython-wasi/"
}

do_build() {
    setup_wasi_sdk
    setup_wizer
    check_cpython_wasi

    if [ ! -f "$RUST_HOST_DIR/build-wasm.sh" ]; then
        echo "ERROR: Rust host build script not found at $RUST_HOST_DIR/build-wasm.sh"
        exit 1
    fi

    if ! command -v cargo &> /dev/null; then
        echo "ERROR: Rust toolchain not found. Install Rust with: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
        exit 1
    fi

    echo "=== Building Rust WASM host ==="
    "$RUST_HOST_DIR/build-wasm.sh" all
}

do_install() {
    "$RUST_HOST_DIR/build-wasm.sh" install
}

do_clean() {
    echo "=== Cleaning build directory ==="
    rm -rf "$BUILD_DIR/output"
    rm -rf "$RUST_HOST_DIR/target"
    echo "Cleaned output and target directories."
    echo "Run 'rm -rf $BUILD_DIR' to remove everything."
}

case "$CMD" in
    build)   do_build ;;
    install) do_install ;;
    clean)   do_clean ;;
    *)
        echo "Usage: $0 {build|install|clean}"
        echo ""
        echo "Commands:"
        echo "  build   - Build Rust WASM host (requires cpython-wasi artifact)"
        echo "  install - Copy WASM binary to runtime module"
        echo "  clean   - Remove output artifacts"
        exit 1
        ;;
esac
