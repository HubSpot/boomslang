# syntax=docker/dockerfile:1.4
#
# Build pydantic-core as a static library for wasm32-wasip1.
# Produces: lib_pydantic_core.a + Python stubs + dependency wheels
#
FROM rust:1.85-bookworm AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl xz-utils ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*

# WASI SDK 20 (matches the version pydantic-core was originally built with)
RUN curl -sL https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-20/wasi-sdk-20.0-linux.tar.gz \
    | tar xz -C /opt \
    && ln -sf /opt/wasi-sdk-20.0 /opt/wasi-sdk

ENV WASI_SDK_PATH=/opt/wasi-sdk

# Rust wasm32-wasip1 target
RUN rustup target add wasm32-wasip1

WORKDIR /build

# Clone pydantic-core source
ARG PYDANTIC_CORE_TAG=v2.41.5
RUN git clone --depth 1 --branch ${PYDANTIC_CORE_TAG} \
    https://github.com/pydantic/pydantic-core /build/pydantic-core

WORKDIR /build/pydantic-core

# Patch crate-type to staticlib only (WASI doesn't support cdylib)
RUN sed -i '/crate-type/s/.*/crate-type = ["staticlib"]/' Cargo.toml

# CPython 3.14 headers for PyO3 cross-compilation
RUN curl -sL https://github.com/python/cpython/archive/refs/tags/v3.14.0.tar.gz | tar xz -C /tmp \
    && mkdir -p /tmp/cpython-wasi/include/python3.14 \
    && cp -r /tmp/cpython-3.14.0/Include/* /tmp/cpython-wasi/include/python3.14/ \
    && rm -rf /tmp/cpython-3.14.0

# Minimal pyconfig.h for wasm32-wasi cross-compilation
RUN PYH=/tmp/cpython-wasi/include/python3.14/pyconfig.h \
    && echo "#ifndef Py_PYCONFIG_H" > $PYH \
    && echo "#define Py_PYCONFIG_H" >> $PYH \
    && echo "#define SIZEOF_VOID_P 4" >> $PYH \
    && echo "#define SIZEOF_SHORT 2" >> $PYH \
    && echo "#define SIZEOF_INT 4" >> $PYH \
    && echo "#define SIZEOF_LONG 4" >> $PYH \
    && echo "#define SIZEOF_LONG_LONG 8" >> $PYH \
    && echo "#define SIZEOF_FLOAT 4" >> $PYH \
    && echo "#define SIZEOF_DOUBLE 8" >> $PYH \
    && echo "#define SIZEOF_SIZE_T 4" >> $PYH \
    && echo "#define SIZEOF_OFF_T 8" >> $PYH \
    && echo "#define SIZEOF_WCHAR_T 4" >> $PYH \
    && echo "#define SIZEOF_PID_T 4" >> $PYH \
    && echo "#define HAVE_STDINT_H 1" >> $PYH \
    && echo "#define HAVE_STDLIB_H 1" >> $PYH \
    && echo "#define HAVE_STRING_H 1" >> $PYH \
    && echo "#define HAVE_UNISTD_H 1" >> $PYH \
    && echo "#define HAVE_WCHAR_H 1" >> $PYH \
    && echo '#define PY_FORMAT_LONG_LONG "ll"' >> $PYH \
    && echo "#define Py_GIL_DISABLED 0" >> $PYH \
    && echo "#define DOUBLE_IS_IEEE_754_BINARY64_LE 1" >> $PYH \
    && echo "#define ALIGNOF_MAX_ALIGN_T 8" >> $PYH \
    && echo "#endif" >> $PYH

# Empty libpython stub (PyO3 needs it to link check, actual linking happens later)
RUN mkdir -p /tmp/cpython-wasi/lib/wasm32-wasi \
    && ar rcs /tmp/cpython-wasi/lib/wasm32-wasi/libpython3.14.a

# Copy sysconfigdata from repo
COPY _sysconfigdata__wasi_wasm32-wasi.py /tmp/cpython-wasi/lib/wasm32-wasi/

# Build pydantic-core
ENV CC_wasm32_wasip1="/opt/wasi-sdk/bin/clang"
ENV CFLAGS_wasm32_wasip1="--sysroot=/opt/wasi-sdk/share/wasi-sysroot -I/tmp/cpython-wasi/include/python3.14"
ENV PYO3_CROSS_PYTHON_VERSION=3.14
ENV PYO3_CROSS_LIB_DIR="/tmp/cpython-wasi/lib/wasm32-wasi"

RUN cargo build --target wasm32-wasip1 --release --lib

# Package artifact
RUN mkdir -p /tmp/artifact/lib /tmp/artifact/python /tmp/artifact/wheels \
    && cp target/wasm32-wasip1/release/lib_pydantic_core.a /tmp/artifact/lib/ \
    && cp -r python/pydantic_core /tmp/artifact/python/ \
    && cd /tmp/artifact && tar czf /artifact.tgz .

FROM scratch
COPY --from=builder /artifact.tgz /artifact.tgz
