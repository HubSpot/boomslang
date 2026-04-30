# syntax=docker/dockerfile:1.2
#
# boomslang WASM builder image
# Provides: Rust toolchain, WASI SDK, Wizer, Binaryen for building
# CPython + Rust host to wasm32-wasip1.
#
ARG WASI_SDK_VERSION=24

FROM rust:1.85-bookworm

ARG WASI_SDK_VERSION
ARG TARGETARCH

# WASI SDK
RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "arm64" || echo "x86_64") \
    && curl -fSL "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${WASI_SDK_VERSION}/wasi-sdk-${WASI_SDK_VERSION}.0-${ARCH}-linux.tar.gz" \
    | tar -xz -C /opt \
    && mv /opt/wasi-sdk-${WASI_SDK_VERSION}.0-${ARCH}-linux /opt/wasi-sdk

ENV WASI_SDK_PATH=/opt/wasi-sdk
ENV PATH="${WASI_SDK_PATH}/bin:${PATH}"

# Wizer (WASM pre-initialization)
ARG WIZER_VERSION=10.0.0

RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64" || echo "x86_64") \
    && curl -fSL "https://github.com/bytecodealliance/wizer/releases/download/v${WIZER_VERSION}/wizer-v${WIZER_VERSION}-${ARCH}-linux.tar.xz" \
    -o /tmp/wizer.tar.xz \
    && mkdir -p /tmp/wizer \
    && tar -xJf /tmp/wizer.tar.xz -C /tmp/wizer --strip-components=1 \
    && mv /tmp/wizer/wizer /usr/local/bin/wizer \
    && chmod +x /usr/local/bin/wizer \
    && rm -rf /tmp/wizer /tmp/wizer.tar.xz \
    && wizer --version

# Binaryen (wasm-opt)
ARG BINARYEN_VERSION=125
RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64" || echo "x86_64") \
    && curl -fSL "https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-${ARCH}-linux.tar.gz" \
    | tar -xz -C /opt \
    && ln -s /opt/binaryen-version_${BINARYEN_VERSION}/bin/wasm-opt /usr/local/bin/wasm-opt \
    && wasm-opt --version

# Rust WASM target
RUN rustup target add wasm32-wasip1

# wabt (wasm-strip)
RUN apt-get update && apt-get install -y --no-install-recommends wabt python3 && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
