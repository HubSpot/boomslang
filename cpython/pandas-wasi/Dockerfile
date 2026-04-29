# syntax=docker/dockerfile:1.4
FROM ubuntu:22.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    curl \
    xz-utils \
    pkg-config \
    ca-certificates \
    python3 python3-pip python3-venv \
    ninja-build \
    && rm -rf /var/lib/apt/lists/*

# Blazar mounts an HS-internal pip.conf as a build secret (see the
# --secret id=pipconf flag in its `docker build` invocation). Without
# consuming it here, pip hits pypi.org directly and gets connection-refused
# from inside the build container.
#
# Pandas 3.0.2 pyproject.toml requires Cython>3.1.0 for the `_cyutility`
# shared-utility extension. numpy is needed at meson-configure time so
# `pandas/meson.build` can resolve `numpy.get_include()` for the host.
# versioneer is invoked by pandas' `generate_version.py` at meson setup.
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    pip3 install --no-cache-dir --prefer-binary \
        'cython>3.1.0,<4.0.0a0' 'meson>=1.2.1,<2' 'meson-python>=0.17.1,<1' \
        'versioneer[toml]'
# Build-time numpy only supplies headers (via `numpy.get_include()`); pandas'
# meson sets NPY_TARGET_VERSION=NPY_1_21_API_VERSION for runtime compat.
# 2.3.3+ would need Python 3.11 which Ubuntu 22.04 doesn't carry, so we pin
# to pandas' build-system floor (numpy>=2.0.0) instead.
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    pip3 install --no-cache-dir --prefer-binary --no-build-isolation \
        'numpy>=2.0.0'

# ---- Layer: patch Cython's stdint.pxd for ILP32 wasm32 targets.
# Cython's shipped Cython/Includes/libc/stdint.pxd declares int64_t as
# `signed long`, which is correct on LP64 Linux (long is 8 bytes) but wrong
# on wasm32 (long is 4 bytes). At runtime, numpy's int64 dtype reports format
# 'q' (long long, 8 bytes) on wasm32; Cython's generated buffer-format check
# expects 'l' (long), and the init of any `cdef int64_t[::1]` blows up with
# "Buffer dtype mismatch, expected 'const int64_t' but got 'long long'".
# Retype int64_t and friends as `long long` so Cython's format matches.
RUN python3 - <<'PY'
import os, Cython
p = os.path.join(os.path.dirname(Cython.__file__), "Includes", "libc", "stdint.pxd")
src = open(p).read()
if "# aviator-cpython: wasm32 int64" in src:
    print(f"{p}: already patched"); raise SystemExit
repl = {
    "ctypedef   signed long  int64_t":          "ctypedef   signed long long int64_t",
    "ctypedef   signed long  int_least64_t":    "ctypedef   signed long long int_least64_t",
    "ctypedef   signed long  int_fast64_t":     "ctypedef   signed long long int_fast64_t",
    # uint64_t / uint_least64_t / uint_fast64_t are already `unsigned long long`.
}
for a, b in repl.items():
    assert a in src, f"pattern not found: {a!r}"
    src = src.replace(a, b)
src = "# aviator-cpython: wasm32 int64 retyped as long long\n" + src
open(p, "w").write(src)
print(f"{p}: patched ({len(repl)} ctypedefs retyped)")
PY

ARG TARGETARCH
RUN set -ex; \
    case "${TARGETARCH}" in \
        amd64) \
            URL="https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/wasi-sdk-25.0-x86_64-linux.tar.gz"; \
            SDK_DIR="wasi-sdk-25.0-x86_64-linux" ;; \
        arm64) \
            URL="https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-25/wasi-sdk-25.0-arm64-linux.tar.gz"; \
            SDK_DIR="wasi-sdk-25.0-arm64-linux" ;; \
        *) echo "unsupported TARGETARCH=${TARGETARCH}"; exit 1 ;; \
    esac; \
    curl -sL "${URL}" | tar xz -C /opt; \
    ln -s "/opt/${SDK_DIR}" /opt/wasi-sdk

ENV WASI_SDK_PATH=/opt/wasi-sdk
ENV CC=/opt/wasi-sdk/bin/clang
ENV AR=/opt/wasi-sdk/bin/llvm-ar
ENV RANLIB=/opt/wasi-sdk/bin/llvm-ranlib
ENV NM=/opt/wasi-sdk/bin/llvm-nm
ENV PATH="/opt/wasi-sdk/bin:${PATH}"

WORKDIR /build

# ---- Layer: clone pandas (cached by PANDAS_TAG arg)
# Don't use --depth 1 alone — pandas' generate_version.py uses git describe,
# which needs the tag object reachable. --depth 1 --branch <tag> fetches only
# that tagged commit and the tag ref, which is enough.
ARG PANDAS_TAG=v3.0.2
RUN git clone --depth 1 --branch ${PANDAS_TAG} https://github.com/pandas-dev/pandas /build/staging/pandas \
    && cd /build/staging/pandas \
    && git submodule update --init --recursive \
    && echo "${PANDAS_TAG}" > /build/pandas_tag.txt

# ---- Layer: set up Python headers for cross-compile.
# Same approach as numpy-wasi: pull raw CPython source for the Include/ tree,
# then lay down the real wasm32-wasi pyconfig.h and _sysconfigdata. We can't
# download the cpython-wasi Blazar artifact (cycle: cpython-wasi depends on us),
# and we only need headers anyway — we compile pandas extensions but never link.
COPY _sysconfigdata__wasi_wasm32-wasi.py /build/_sysconfigdata__wasi_wasm32-wasi.py
COPY pyconfig-wasi.h /build/pyconfig-wasi.h
RUN mkdir -p /tmp/cpython-wasi/include/python3.14 /tmp/cpython-wasi/usr/local/lib/python3.14 \
    && curl -sL https://github.com/python/cpython/archive/refs/tags/v3.14.0.tar.gz | tar xz -C /tmp \
    && cp -r /tmp/cpython-3.14.0/Include/* /tmp/cpython-wasi/include/python3.14/ \
    && cp /build/pyconfig-wasi.h /tmp/cpython-wasi/include/python3.14/pyconfig.h \
    && cp /build/_sysconfigdata__wasi_wasm32-wasi.py /tmp/cpython-wasi/usr/local/lib/python3.14/ \
    && rm -rf /tmp/cpython-3.14.0 \
    && test -f /tmp/cpython-wasi/include/python3.14/pyconfig.h \
    && test -f /tmp/cpython-wasi/usr/local/lib/python3.14/_sysconfigdata__wasi_wasm32-wasi.py

# ---- Layer: overlay numpy-wasi's patched headers onto host numpy.
# Pandas' meson.build resolves `numpy.get_include()` against the host
# pip-installed numpy. Stock numpy's npy_cpu.h emits "#error Unknown CPU"
# under __wasm32__. numpy-wasi publishes its patched header tree at
# include/numpy/ in its artifact; drop it on top of the pip install so
# every pandas extension compiles against the wasm-aware variant.
# The .blazar.yaml `before:` step stages vendor/numpy-wasi.tgz from the
# upstream numpy-wasi module (branch-match with main fallback).
COPY vendor/numpy-wasi.tgz /build/numpy-wasi.tgz
RUN HOST_NUMPY_INCLUDE=$(python3 -c 'import numpy; print(numpy.get_include())') \
    && test -d "$HOST_NUMPY_INCLUDE/numpy" \
    && mkdir -p /tmp/numpy-wasi \
    && tar xzf /build/numpy-wasi.tgz -C /tmp/numpy-wasi \
    && test -d /tmp/numpy-wasi/include/numpy \
    && cp -rf /tmp/numpy-wasi/include/numpy/* "$HOST_NUMPY_INCLUDE/numpy/" \
    && grep -q NPY_CPU_WASM32 "$HOST_NUMPY_INCLUDE/numpy/npy_cpu.h" \
    && echo "host numpy headers overlaid with numpy-wasi patched variants"

# ---- Layer: patch pandas sources (no-op today; kept so future patches
# don't invalidate the meson-setup cache below).
COPY patch.py /build/patch.py
RUN cd /build/staging/pandas && python3 /build/patch.py

# ---- Layer: meson setup
# Pandas uses system meson (no vendored-meson like numpy has). We can't use the
# meson-python build-backend here (it tries to package a wheel, which needs a
# linker run); meson directly lets us stop at ninja and harvest the .o files.
COPY cross-wasi.ini /build/cross-wasi.ini
RUN cd /build/staging/pandas \
    && { meson setup build \
            --cross-file /build/cross-wasi.ini \
            -Ddefault_library=static \
            -Dbuildtype=release \
        || { echo "=== meson-log.txt ==="; tail -n 80 build/meson-logs/meson-log.txt; exit 1; }; }

# ---- Layer: ninja compile (the expensive one; ~15-25 min under QEMU)
# Same -k 0 pattern as numpy-wasi: wasm-ld rejects the final shared-module
# link for every extension (it wants --export-dynamic with no entry); we only
# need .o files, so we tolerate per-target link failures. Fail the layer only
# if ninja itself died (exit > 1: toolchain, OOM, sysroot issues).
RUN cd /build/staging/pandas; \
    ninja -C build -k 0 > /build/ninja.log 2>&1; ninja_exit=$?; \
    tail -n 200 /build/ninja.log; \
    echo "ninja exit $ninja_exit"; \
    test "$ninja_exit" -eq 0 -o "$ninja_exit" -eq 1

# ---- Layer: harvest + verify + package (cheap; iterates fast)
COPY harvest.sh /build/harvest.sh
RUN chmod +x /build/harvest.sh && /build/harvest.sh

RUN cd /build/output && tar czf /build/artifact.tgz .

FROM scratch
COPY --from=builder /build/artifact.tgz /artifact.tgz
