# syntax=docker/dockerfile:1.4
FROM ubuntu:22.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    curl \
    xz-utils \
    ca-certificates \
    python3 \
    && rm -rf /var/lib/apt/lists/*

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
ENV NM=/opt/wasi-sdk/bin/llvm-nm

ARG CPYTHON_TAG=v3.14.0
RUN git clone --depth 1 --branch ${CPYTHON_TAG} \
    https://github.com/python/cpython /build/cpython-src

COPY pyconfig-wasi.h /build/cpython-src/Include/pyconfig.h

ARG YAJL_TAG=2.1.0
RUN git clone --depth 1 --branch ${YAJL_TAG} \
    https://github.com/lloyd/yajl /build/yajl

RUN set -ex; \
    mkdir -p /build/yajl/build/yajl; \
    cp /build/yajl/src/api/yajl_common.h /build/yajl/build/yajl/; \
    cp /build/yajl/src/api/yajl_gen.h /build/yajl/build/yajl/; \
    cp /build/yajl/src/api/yajl_parse.h /build/yajl/build/yajl/; \
    cp /build/yajl/src/api/yajl_tree.h /build/yajl/build/yajl/; \
    printf '%s\n' \
        '#ifndef YAJL_VERSION_H_' \
        '#define YAJL_VERSION_H_' \
        '#include "yajl_common.h"' \
        '#define YAJL_MAJOR 2' \
        '#define YAJL_MINOR 1' \
        '#define YAJL_MICRO 0' \
        '#define YAJL_VERSION ((YAJL_MAJOR * 10000) + (YAJL_MINOR * 100) + YAJL_MICRO)' \
        '#endif' \
        > /build/yajl/build/yajl/yajl_version.h

ARG IJSON_TAG=v3.3.0
RUN git clone --depth 1 --branch ${IJSON_TAG} \
    https://github.com/ICRAR/ijson /build/ijson

WORKDIR /build

ENV WASI_CFLAGS="-Os \
    --target=wasm32-wasi \
    --sysroot=/opt/wasi-sdk/share/wasi-sysroot \
    -fvisibility=default \
    -D_WASI_EMULATED_SIGNAL \
    -D_WASI_EMULATED_GETPID \
    -D_WASI_EMULATED_PROCESS_CLOCKS"

RUN set -ex; \
    mkdir -p /build/obj/yajl; \
    for src in yajl.c yajl_alloc.c yajl_buf.c yajl_encode.c \
               yajl_gen.c yajl_lex.c yajl_parser.c yajl_tree.c \
               yajl_version.c; do \
        echo "Compiling yajl/$src"; \
        ${CC} ${WASI_CFLAGS} \
            -I/build/yajl/src \
            -I/build/yajl/build \
            -c /build/yajl/src/$src \
            -o /build/obj/yajl/${src%.c}.o; \
    done; \
    echo "YAJL objects:"; ls -la /build/obj/yajl/

RUN set -ex; \
    mkdir -p /build/obj/ijson; \
    IJSON_EXT_DIR=/build/ijson/ijson/backends/yajl2_c; \
    for src in ${IJSON_EXT_DIR}/*.c; do \
        name=$(basename "$src"); \
        echo "Compiling ijson/$name"; \
        ${CC} ${WASI_CFLAGS} \
            -I/build/cpython-src/Include \
            -I/build/cpython-src/Include/cpython \
            -I/build/cpython-src/Include/internal \
            -I/build/yajl/build \
            -I${IJSON_EXT_DIR} \
            -c "$src" \
            -o /build/obj/ijson/${name%.c}.o; \
    done; \
    echo "ijson objects:"; ls -la /build/obj/ijson/

COPY harvest.sh /build/harvest.sh
RUN chmod +x /build/harvest.sh && /build/harvest.sh

FROM scratch
COPY --from=builder /artifact.tgz /artifact.tgz
