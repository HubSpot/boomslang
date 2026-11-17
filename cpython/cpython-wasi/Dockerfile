# syntax=docker/dockerfile:1.4
FROM python:3.15.0a7-bookworm AS toolchain

RUN set -eux; \
    for tool in gcc g++ make git curl xz tar python3 pip3; do \
        command -v "${tool}"; \
    done

# wasi-sdk — dual-arch for local dev speed. ARG TARGETARCH without a default
# lets buildx auto-populate from --platform. Native arm64 is ~5x faster than
# QEMU-emulated amd64 on Apple Silicon. Both produce ABI-compatible wasm32-wasi.
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

# Python 3.15 built from source (used as --with-build-python for cross-compilation)
RUN curl -sL https://github.com/python/cpython/archive/refs/tags/v3.15.0a7.tar.gz | tar xz -C /tmp \
    && cd /tmp/cpython-3.15.0a7 \
    && ./configure --prefix=/opt/python3.15 --without-ensurepip \
    && make -j$(nproc) \
    && make install \
    && ln -sf /opt/python3.15/bin/python3.15 /usr/local/bin/python3 \
    && rm -rf /tmp/cpython-3.15.0a7

# binaryen (wasm-opt) — dual-arch. Version 111 only has x86_64; arm64
# needs version 125+.
RUN set -ex; \
    case "${TARGETARCH}" in \
        amd64) \
            URL="https://github.com/WebAssembly/binaryen/releases/download/version_111/binaryen-version_111-x86_64-linux.tar.gz"; \
            DIR="binaryen-version_111" ;; \
        arm64) \
            URL="https://github.com/WebAssembly/binaryen/releases/download/version_125/binaryen-version_125-aarch64-linux.tar.gz"; \
            DIR="binaryen-version_125" ;; \
        *) echo "unsupported TARGETARCH=${TARGETARCH}"; exit 1 ;; \
    esac; \
    curl -sL "${URL}" | tar xz -C /opt \
    && ln -s "/opt/${DIR}/bin/wasm-opt" /usr/local/bin/wasm-opt

# CPython 3.15 requires an explicit WASI host runner during configure.
ARG WASMTIME_VERSION=48.0.1
RUN ARCH=$([ "${TARGETARCH}" = "arm64" ] && echo "aarch64" || echo "x86_64") \
    && curl -fSL "https://github.com/bytecodealliance/wasmtime/releases/download/v${WASMTIME_VERSION}/wasmtime-v${WASMTIME_VERSION}-${ARCH}-linux.tar.xz" \
    | tar -xJ -C /opt \
    && ln -s "/opt/wasmtime-v${WASMTIME_VERSION}-${ARCH}-linux/wasmtime" /usr/local/bin/wasmtime

ENV WASI_SDK_PATH=/opt/wasi-sdk
ENV CC=/opt/wasi-sdk/bin/clang
ENV AR=/opt/wasi-sdk/bin/llvm-ar
ENV RANLIB=/opt/wasi-sdk/bin/llvm-ranlib
ENV PATH="/opt/wasi-sdk/bin:${PATH}"

FROM toolchain AS builder

WORKDIR /build

COPY vendor/ /build/vendor/
COPY main.c /build/main.c
COPY build.sh /build/build.sh
COPY split-ast-annotations.py /build/split-ast-annotations.py
COPY patch-lazy-imports.py /build/patch-lazy-imports.py
# pip.conf secret is mounted so build.sh can pip-download pandas' pure-Python
# deps (python-dateutil, six, tzdata) from HubSpot's internal mirror. Direct
# pythonhosted.org isn't reachable from Blazar docker build containers.
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    chmod +x /build/build.sh && /build/build.sh

RUN cd /build/output && tar czf /build/artifact.tgz .

FROM scratch
COPY --from=builder /build/artifact.tgz /artifact.tgz
