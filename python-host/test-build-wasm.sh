#!/bin/bash
# Tests for build-wasm.sh — run with: bash python-host/test-build-wasm.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PASS=0
FAIL=0
CURL_LOG=$(mktemp)
trap 'rm -f "$CURL_LOG"' EXIT

# Source once to pull in function definitions without executing any build steps
# shellcheck source=build-wasm.sh
source "$SCRIPT_DIR/build-wasm.sh"

# Override BUILD_DIR to something that won't exist so the early-return checks
# (WASI_SDK_PATH set, or cached binary present) don't short-circuit the download.
BUILD_DIR="/nonexistent/test-$$"

# Capture the curl URL via a temp file (pipe subshells can't write back to variables)
curl() { echo "$*" > "$CURL_LOG"; }
mkdir() { true; }
mv()    { true; }
tar()   { true; }

# Mock uname to control OS/arch detection
MOCK_OS="Darwin"
MOCK_ARCH="arm64"
uname() {
    case "${1:-}" in
        -s) echo "$MOCK_OS" ;;
        -m) echo "$MOCK_ARCH" ;;
        *)  command uname "$@" ;;
    esac
}

assert_url_contains() {
    local label="$1" expected="$2"
    local captured
    captured=$(cat "$CURL_LOG")
    if [[ "$captured" == *"$expected"* ]]; then
        echo "PASS: $label"
        ((PASS++)) || true
    else
        echo "FAIL: $label"
        echo "      expected URL to contain: $expected"
        echo "      got curl args:           $captured"
        ((FAIL++)) || true
    fi
    > "$CURL_LOG"
}

run_sdk_setup() {
    MOCK_OS="$1"
    MOCK_ARCH="$2"
    WASI_SDK_PATH=""
    setup_wasi_sdk
}

run_sdk_setup "Linux"  "x86_64"
assert_url_contains "Linux x86_64 uses linux tarball"  "-linux.tar.gz"

run_sdk_setup "Linux"  "aarch64"
assert_url_contains "Linux aarch64 uses linux tarball" "-linux.tar.gz"

run_sdk_setup "Darwin" "arm64"
assert_url_contains "macOS arm64 uses macos tarball"   "-macos.tar.gz"

run_sdk_setup "Darwin" "x86_64"
assert_url_contains "macOS x86_64 uses macos tarball"  "-macos.tar.gz"

echo ""
echo "Results: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
