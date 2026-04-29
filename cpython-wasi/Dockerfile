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
    libffi-dev libssl-dev zlib1g-dev \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

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

# Python 3.14 built from source (used as --with-build-python for cross-compilation)
RUN curl -sL https://github.com/python/cpython/archive/refs/tags/v3.14.0.tar.gz | tar xz -C /tmp \
    && cd /tmp/cpython-3.14.0 \
    && ./configure --prefix=/opt/python3.14 --without-ensurepip \
    && make -j$(nproc) \
    && make install \
    && ln -sf /opt/python3.14/bin/python3.14 /usr/local/bin/python3 \
    && rm -rf /tmp/cpython-3.14.0

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

ENV WASI_SDK_PATH=/opt/wasi-sdk
ENV CC=/opt/wasi-sdk/bin/clang
ENV AR=/opt/wasi-sdk/bin/llvm-ar
ENV RANLIB=/opt/wasi-sdk/bin/llvm-ranlib
ENV PATH="/opt/wasi-sdk/bin:${PATH}"

WORKDIR /build

COPY vendor/ /build/vendor/
COPY main.c /build/main.c
COPY build.sh /build/build.sh
COPY split-ast-annotations.py /build/split-ast-annotations.py
# pip.conf secret is mounted so build.sh can pip-download pandas' pure-Python
# deps (python-dateutil, six, tzdata) from HubSpot's internal mirror. Direct
# pythonhosted.org isn't reachable from Blazar docker build containers.
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    chmod +x /build/build.sh && /build/build.sh

RUN cd /build/output && tar czf /build/artifact.tgz .

FROM scratch
COPY --from=builder /build/artifact.tgz /artifact.tgz
