# syntax=docker/dockerfile:1.2
#
# Build the final boomslang WASM binary: Rust host linked against libpython3.14.a
# Self-contained — includes toolchain setup (no external builder image needed).
#

# ── Stage 1: Toolchain ────────────────────────────────────────
FROM rust:1.95-bookworm AS builder

ARG TARGETARCH

# WASI SDK 24
ARG WASI_SDK_VERSION=24
RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "arm64" || echo "x86_64") \
    && curl -fSL "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${WASI_SDK_VERSION}/wasi-sdk-${WASI_SDK_VERSION}.0-${ARCH}-linux.tar.gz" \
    | tar -xz -C /opt \
    && mv /opt/wasi-sdk-${WASI_SDK_VERSION}.0-${ARCH}-linux /opt/wasi-sdk

ENV WASI_SDK_PATH=/opt/wasi-sdk
ENV PATH="${WASI_SDK_PATH}/bin:${PATH}"

# Wizer
ARG WIZER_VERSION=10.0.0
RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64" || echo "x86_64") \
    && curl -fSL "https://github.com/bytecodealliance/wizer/releases/download/v${WIZER_VERSION}/wizer-v${WIZER_VERSION}-${ARCH}-linux.tar.xz" \
    -o /tmp/wizer.tar.xz \
    && mkdir -p /tmp/wizer \
    && tar -xJf /tmp/wizer.tar.xz -C /tmp/wizer --strip-components=1 \
    && mv /tmp/wizer/wizer /usr/local/bin/wizer \
    && chmod +x /usr/local/bin/wizer \
    && rm -rf /tmp/wizer /tmp/wizer.tar.xz

# Binaryen (wasm-opt)
ARG BINARYEN_VERSION=125
RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64" || echo "x86_64") \
    && curl -fSL "https://github.com/WebAssembly/binaryen/releases/download/version_${BINARYEN_VERSION}/binaryen-version_${BINARYEN_VERSION}-${ARCH}-linux.tar.gz" \
    | tar -xz -C /opt \
    && ln -s /opt/binaryen-version_${BINARYEN_VERSION}/bin/wasm-opt /usr/local/bin/wasm-opt

# wabt (wasm-strip)
ARG WABT_VERSION=1.0.40
RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "aarch64-linux" || echo "linux-x64") \
    && curl -fSL "https://github.com/WebAssembly/wabt/releases/download/${WABT_VERSION}/wabt-${WABT_VERSION}-${ARCH}.tar.gz" \
    | tar -xz -C /opt \
    && ln -s /opt/wabt-${WABT_VERSION}/bin/wasm-strip /usr/local/bin/wasm-strip

# Rust WASM target
RUN rustup target add wasm32-wasip1

# ── Stage 2: Build ────────────────────────────────────────────
WORKDIR /build

# Copy upstream cpython-wasi artifact
COPY vendor/cpython-wasi.tgz /tmp/cpython-wasi.tgz
RUN mkdir -p cpython/build/cpython-wasi \
    && tar xzf /tmp/cpython-wasi.tgz -C cpython/build/cpython-wasi/ \
    && rm /tmp/cpython-wasi.tgz \
    && ls -lh cpython/build/cpython-wasi/lib/wasm32-wasi/libpython3.14.a

# Copy pip packages
COPY vendor/pip-packages/ cpython/lib/pip-packages/

# Copy Rust host + all dependency crates
COPY vendor/python-host/ python-host/
COPY vendor/python-host-core/ python-host-core/
COPY vendor/extensions/ extensions/
COPY vendor/boomslang-hostgen/ boomslang-hostgen/

# Copy build scripts and Python packages from repo
COPY scripts/ cpython/scripts/
COPY lib/pydantic_core/ cpython/lib/pydantic_core/

# Build: compile Rust host against upstream libpython, apply Wizer, optimize
RUN chmod +x cpython/scripts/build.sh \
    && cd python-host \
    && PYTHON4J_EXTENSIONS="../extensions/host-bridge" ./build-wasm.sh all

# Package artifact
RUN set -ex \
    && mkdir -p /tmp/staging/bin /tmp/staging/usr/local/lib \
    && cp cpython/build/output/boomslang.wasm /tmp/staging/bin/ \
    && cp -r cpython/build/cpython-wasi/usr/local/lib/python3.14 /tmp/staging/usr/local/lib/ \
    && cp -r extensions/host-bridge/lib/boomslang_host /tmp/staging/usr/local/lib/python3.14/ \
    && for pkg in pydantic typing_inspection; do \
         [ -d cpython/lib/pip-packages/$pkg ] && cp -r cpython/lib/pip-packages/$pkg /tmp/staging/usr/local/lib/python3.14/; \
       done \
    && tar czf /artifact.tgz -C /tmp/staging . \
    && ls -lh /artifact.tgz
