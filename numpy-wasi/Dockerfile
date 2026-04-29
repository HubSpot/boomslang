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
# Install in two passes with --prefer-binary:
#   1. cython + meson + meson-python first, so they're on PATH before any
#      PEP 517 build that needs them.
#   2. numpy separately, --no-build-isolation so it picks up the cython we
#      just installed instead of spawning a fresh isolated env (which on our
#      internal mirror couldn't find a cython wheel fast enough).
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    pip3 install --no-cache-dir --prefer-binary \
        'cython>=3.0' 'meson==1.5.2' 'meson-python==0.17.1'
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    pip3 install --no-cache-dir --prefer-binary --no-build-isolation \
        'numpy>=2.0'

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

# ---- Layer: clone numpy + submodules (cached by NUMPY_TAG arg)
ARG NUMPY_TAG=v2.4.4
RUN git clone --depth 1 --branch ${NUMPY_TAG} https://github.com/numpy/numpy /build/staging/numpy \
    && cd /build/staging/numpy \
    && git submodule update --init --recursive \
    && echo "${NUMPY_TAG}" > /build/numpy_tag.txt

# ---- Layer: set up Python headers for cross-compile.
# Pull raw CPython source for the Include/ tree, plus check-in the real
# wasm32-wasi-specific pyconfig.h and _sysconfigdata (produced by a previous
# cpython-wasi configure — see numpy-wasi/pyconfig-wasi.h). We can't
# download the cpython-wasi Blazar artifact here because cpython-wasi
# depends on numpy-wasi (cycle), and we only need headers anyway — we
# compile numpy extensions but never link them.
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

# ---- Layer: shim headers for wasi-sdk-24's threadless libc++.
# numpy's dispatching.cpp and npy_hashtable.cpp include <shared_mutex>
# for ufunc dispatch cache locking. wasi-sdk-24's libc++ is built without
# thread support so that header errors out. Provide a no-op stub since
# WASI is single-threaded and the locks are never contended.
COPY shim-include/ /build/shim-include/

# ---- Layer: patch numpy sources (wasm CPU recognition, etc.)
COPY patch.py /build/patch.py
RUN cd /build/staging/numpy && python3 /build/patch.py

# ---- Layer: meson setup
COPY cross-wasi.ini /build/cross-wasi.ini
RUN cd /build/staging/numpy \
    && python3 vendored-meson/meson/meson.py setup build \
        --cross-file /build/cross-wasi.ini \
        -Dblas=none -Dlapack=none -Dallow-noblas=true \
        -Ddisable-optimization=true -Ddefault_library=static \
        -Dbuildtype=release

# ---- Layer: ninja compile (the expensive one; ~10-15 min under QEMU)
# -k 0 keeps going past the link-step errors from wasm-ld rejecting
# --start-group. We only need .o files, so ninja's partial-failure
# exit 1 is expected and tolerated. Anything else (toolchain missing,
# OOM, sysroot wrong, ...) should fail this layer — otherwise harvest.sh
# would produce an empty .o tree and report a misleading "gate failed"
# error much later.
RUN cd /build/staging/numpy; \
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
