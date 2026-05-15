# syntax=docker/dockerfile:1.4
FROM python:3.14.5-bookworm AS builder

RUN set -eux; \
    for tool in gcc g++ make git curl xz tar python3; do \
        command -v "${tool}"; \
    done

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

ARG PILLOW_TAG=12.2.0
RUN git clone --depth 1 --branch ${PILLOW_TAG} https://github.com/python-pillow/Pillow /build/staging/Pillow \
    && echo "${PILLOW_TAG}" > /build/pillow_tag.txt

COPY _sysconfigdata__wasi_wasm32-wasi.py /build/_sysconfigdata__wasi_wasm32-wasi.py
COPY pyconfig-wasi.h /build/pyconfig-wasi.h
RUN mkdir -p /tmp/cpython-wasi/include/python3.14 /tmp/cpython-wasi/usr/local/lib/python3.14 \
    && curl -sL https://github.com/python/cpython/archive/refs/tags/v3.14.5.tar.gz | tar xz -C /tmp \
    && cp -r /tmp/cpython-3.14.5/Include/* /tmp/cpython-wasi/include/python3.14/ \
    && cp /build/pyconfig-wasi.h /tmp/cpython-wasi/include/python3.14/pyconfig.h \
    && cp /build/_sysconfigdata__wasi_wasm32-wasi.py /tmp/cpython-wasi/usr/local/lib/python3.14/ \
    && rm -rf /tmp/cpython-3.14.5 \
    && test -f /tmp/cpython-wasi/include/python3.14/pyconfig.h

RUN mkdir -p /build/wasi-libs \
    && curl -sL "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Fzlib%2F1.2.13%2B20230623-2993864/libz-1.2.13-wasi-sdk-20.0.tar.gz" \
        | tar xz -C /build/wasi-libs \
    && test -f /build/wasi-libs/lib/wasm32-wasi/libz.a \
    && test -f /build/wasi-libs/include/zlib.h

ENV WASI_CFLAGS="-Os \
    --target=wasm32-wasi \
    --sysroot=/opt/wasi-sdk/share/wasi-sysroot \
    -fvisibility=default \
    -D_WASI_EMULATED_SIGNAL \
    -D_WASI_EMULATED_GETPID \
    -D_WASI_EMULATED_PROCESS_CLOCKS \
    -DHAVE_LIBZ \
    -DPILLOW_VERSION=\"${PILLOW_TAG}\""

RUN set -ex; \
    cd /build/staging/Pillow; \
    mkdir -p /build/obj/pillow/libImaging /build/obj/pillow/ext; \
    common_includes="-I/tmp/cpython-wasi/include/python3.14 -Isrc -Isrc/libImaging -I/build/wasi-libs/include"; \
    for src in src/libImaging/*.c; do \
        name=$(basename "$src" .c); \
        echo "Compiling libImaging/${name}.c"; \
        ${CC} ${WASI_CFLAGS} ${common_includes} -c "$src" -o "/build/obj/pillow/libImaging/${name}.o"; \
    done; \
    for src in src/_imaging.c src/decode.c src/encode.c src/map.c src/display.c src/outline.c src/path.c src/_imagingmath.c src/_imagingmorph.c; do \
        name=$(basename "$src" .c); \
        echo "Compiling ${name}.c"; \
        ${CC} ${WASI_CFLAGS} ${common_includes} -c "$src" -o "/build/obj/pillow/ext/${name}.o"; \
    done; \
    ls -la /build/obj/pillow/ext /build/obj/pillow/libImaging

COPY harvest.sh /build/harvest.sh
RUN chmod +x /build/harvest.sh && /build/harvest.sh

RUN cd /build/output && tar czf /build/artifact.tgz .

FROM scratch
COPY --from=builder /build/artifact.tgz /artifact.tgz
