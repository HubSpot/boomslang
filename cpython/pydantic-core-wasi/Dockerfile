# syntax=docker/dockerfile:1.4
#
# Build pydantic-core as a static library for wasm32-wasip1.
# Produces: lib_pydantic_core.a + Python stubs + dependency wheels
#
FROM rust:1.95.0-bookworm AS builder

# WASI SDK 24 (arch-aware)
ARG TARGETARCH
RUN ARCH=$([ "$TARGETARCH" = "arm64" ] && echo "arm64" || echo "x86_64") \
    && curl -sL "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-24/wasi-sdk-24.0-${ARCH}-linux.tar.gz" \
    | tar xz -C /opt \
    && ln -sf "/opt/wasi-sdk-24.0-${ARCH}-linux" /opt/wasi-sdk

ENV WASI_SDK_PATH=/opt/wasi-sdk

# Rust wasm32-wasip1 target
RUN rustup target add wasm32-wasip1

WORKDIR /build

# Clone pydantic-core source
ARG PYDANTIC_TAG=v2.13.5
RUN git clone --depth 1 --branch ${PYDANTIC_TAG} \
    https://github.com/pydantic/pydantic /build/pydantic

WORKDIR /build/pydantic/pydantic-core

# Patch crate-type to staticlib only (WASI doesn't support cdylib)
RUN sed -i '/crate-type/s/.*/crate-type = ["staticlib"]/' Cargo.toml

# CPython 3.15 headers for PyO3 cross-compilation
RUN curl -sL https://github.com/python/cpython/archive/refs/tags/v3.15.0a7.tar.gz | tar xz -C /tmp \
    && mkdir -p /tmp/cpython-wasi/include/python3.15 \
    && cp -r /tmp/cpython-3.15.0a7/Include/* /tmp/cpython-wasi/include/python3.15/ \
    && rm -rf /tmp/cpython-3.15.0a7

# Use the generated configuration for the same CPython WASI target.
COPY pyconfig-wasi.h /tmp/cpython-wasi/include/python3.15/pyconfig.h

# Empty libpython stub (PyO3 needs it to link check, actual linking happens later)
RUN mkdir -p /tmp/cpython-wasi/lib/wasm32-wasi \
    && ar rcs /tmp/cpython-wasi/lib/wasm32-wasi/libpython3.15.a

# Copy sysconfigdata and wheels from repo
COPY _sysconfigdata__wasi_wasm32-wasi.py /tmp/cpython-wasi/lib/wasm32-wasi/
COPY wheels/ /build/wheels/

# Build pydantic-core
ENV CC_wasm32_wasip1="/opt/wasi-sdk/bin/clang"
ENV CFLAGS_wasm32_wasip1="--sysroot=/opt/wasi-sdk/share/wasi-sysroot -I/tmp/cpython-wasi/include/python3.15"
ENV PYO3_CROSS_PYTHON_VERSION=3.15
ENV PYO3_CROSS_LIB_DIR="/tmp/cpython-wasi/lib/wasm32-wasi"

RUN cargo build --target wasm32-wasip1 --release --lib

# Package artifact
RUN mkdir -p /tmp/artifact/lib /tmp/artifact/python /tmp/artifact/wheels \
    && cp target/wasm32-wasip1/release/lib_pydantic_core.a /tmp/artifact/lib/ \
    && cp -r python/pydantic_core /tmp/artifact/python/ \
    && cp /build/wheels/*.whl /tmp/artifact/wheels/ \
    && cd /tmp/artifact && tar czf /artifact.tgz .

FROM scratch
COPY --from=builder /artifact.tgz /artifact.tgz
