# syntax=docker/dockerfile:1.4
# Cross-compile lxml (etree + objectify) for wasm32-wasip1.
#
# Strategy:
#   - libxml2: reuse the prebuilt WASI static lib from vmware-labs/webassembly-language-runtimes
#     (same source already used for libuuid/zlib/sqlite/bzip2 in cpython-wasi/build.sh).
#   - libxslt: no prebuilt available; clone and cross-compile from source against the
#     prebuilt libxml2. libxslt is required because lxml/etree.c unconditionally includes
#     <libxslt/xslt.h> even when XSLT transforms aren't used at runtime.
#   - lxml:    download the sdist (pre-generated C, no Cython needed). Compile
#              src/lxml/etree.c → lib_lxml_etree.a and
#              src/lxml/objectify.c → lib_lxml_objectify.a.
FROM python:3.14.5-bookworm AS builder

RUN set -eux; \
    for tool in gcc g++ make git curl xz tar python3 pip3 autoconf libtool pkg-config; do \
        command -v "${tool}" || apt-get install -y "${tool}"; \
    done; \
    apt-get install -y --no-install-recommends autoconf automake libtool pkg-config

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

# ── CPython headers (same as ijson-wasi / pillow-wasi) ──────────────────────
COPY pyconfig-wasi.h /build/pyconfig-wasi.h
RUN mkdir -p /tmp/cpython-wasi/include/python3.14 /tmp/cpython-wasi/usr/local/lib/python3.14 \
    && curl -sL https://github.com/python/cpython/archive/refs/tags/v3.14.5.tar.gz | tar xz -C /tmp \
    && cp -r /tmp/cpython-3.14.5/Include/* /tmp/cpython-wasi/include/python3.14/ \
    && cp /build/pyconfig-wasi.h /tmp/cpython-wasi/include/python3.14/pyconfig.h \
    && rm -rf /tmp/cpython-3.14.5 \
    && test -f /tmp/cpython-wasi/include/python3.14/pyconfig.h

# ── libxml2: prebuilt wasm32-wasi static archive from vmware-labs ───────────
# Same vendor used for libuuid/zlib/sqlite/bzip2 in cpython-wasi/build.sh.
# wasi-sdk-20.0 ABI is compatible with wasi-sdk-25 (same wasm32-wasip1 target).
RUN mkdir -p /build/wasi-libs \
    && curl -sL "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Flibxml2%2F2.11.4%2B20230623-2993864/libxml2-2.11.4-wasi-sdk-20.0.tar.gz" \
        | tar xz -C /build/wasi-libs \
    && test -f /build/wasi-libs/lib/wasm32-wasi/libxml2.a \
    && test -f /build/wasi-libs/include/libxml2/libxml/parser.h

ENV WASI_CFLAGS="-Os \
    --target=wasm32-wasi \
    --sysroot=/opt/wasi-sdk/share/wasi-sysroot \
    -fvisibility=default \
    -D_WASI_EMULATED_SIGNAL \
    -D_WASI_EMULATED_GETPID \
    -D_WASI_EMULATED_PROCESS_CLOCKS"

# ── libxslt: cross-compile from source ───────────────────────────────────────
# No prebuilt wasm32-wasi libxslt exists. lxml's etree.c unconditionally
# includes <libxslt/xslt.h>, so we must provide headers + a static archive.
# EXSLT is included in the libxslt source tree; lxml links it when available.
ARG LIBXSLT_TAG=v1.1.42
RUN git clone --depth 1 --branch "${LIBXSLT_TAG}" \
    https://github.com/GNOME/libxslt /build/staging/libxslt \
    && echo "${LIBXSLT_TAG}" > /build/libxslt_tag.txt

RUN set -ex; \
    cd /build/staging/libxslt; \
    # autogen.sh calls autoreconf which regenerates configure from configure.ac
    ./autogen.sh; \
    ./configure \
        --host=wasm32-wasip1 \
        --build="$(./config.guess)" \
        --prefix=/build/xslt-install \
        --disable-shared \
        --enable-static \
        --without-python \
        --without-plugins \
        --without-crypto \
        --without-debugger \
        CC="${CC}" \
        AR="${AR}" \
        RANLIB="${RANLIB}" \
        CFLAGS="${WASI_CFLAGS}" \
        LDFLAGS="--sysroot=/opt/wasi-sdk/share/wasi-sysroot" \
        LIBXML_CFLAGS="-I/build/wasi-libs/include/libxml2" \
        LIBXML_LIBS="-L/build/wasi-libs/lib/wasm32-wasi -lxml2" \
    ; \
    make -j$(nproc); \
    make install; \
    test -f /build/xslt-install/lib/libxslt.a; \
    test -f /build/xslt-install/include/libxslt/xslt.h

# ── lxml: download sdist (ships pre-generated C, no Cython needed) ───────────
ARG LXML_VERSION=5.3.2
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    mkdir -p /tmp/lxml-sdist \
    && pip3 download --no-binary :all: --no-deps --no-cache-dir \
        "lxml==${LXML_VERSION}" \
        -d /tmp/lxml-sdist \
    && ls /tmp/lxml-sdist/

RUN cd /build/staging \
    && tar xzf /tmp/lxml-sdist/lxml-*.tar.gz \
    && ls /build/staging/ \
    && echo "${LXML_VERSION}" > /build/lxml_tag.txt

# Compile lxml/etree.c and lxml/objectify.c against WASI headers.
# The two generated C files are each a self-contained compilation unit that
# #includes all lxml internals. Key include paths:
#   - src/lxml/           — lxml's own headers (etree.h, lxml-version.h, …)
#   - src/lxml/includes/  — lxml C API helpers (etree_api.h, …)
#   - CPython 3.14 headers
#   - libxml2 headers     — from vmware-labs prebuilt
#   - libxslt headers     — from our cross-compiled build
RUN set -ex; \
    LXML_SRC=$(ls -d /build/staging/lxml-*/src/lxml); \
    LXML_ROOT=$(ls -d /build/staging/lxml-*); \
    mkdir -p /build/obj/lxml; \
    LXML_CFLAGS="${WASI_CFLAGS} \
        -I${LXML_SRC} \
        -I${LXML_SRC}/includes \
        -I/tmp/cpython-wasi/include/python3.14 \
        -I/build/wasi-libs/include/libxml2 \
        -I/build/xslt-install/include \
        -DPYREX_WITHOUT_ASSERTIONS \
        -DLXML_FIXED_COMPILATION \
        -Wno-unreachable-code \
        -Wno-unused-variable \
        -Wno-implicit-function-declaration \
        -Wno-incompatible-function-pointer-types"; \
    echo "Compiling lxml/etree.c..."; \
    ${CC} ${LXML_CFLAGS} -c "${LXML_SRC}/etree.c" -o /build/obj/lxml/etree.o; \
    echo "Compiling lxml/objectify.c..."; \
    ${CC} ${LXML_CFLAGS} -c "${LXML_SRC}/objectify.c" -o /build/obj/lxml/objectify.o; \
    echo "lxml objects:"; ls -la /build/obj/lxml/

COPY harvest.sh /build/harvest.sh
RUN chmod +x /build/harvest.sh && /build/harvest.sh

RUN cd /build/output && tar czf /build/artifact.tgz .

FROM scratch
COPY --from=builder /build/artifact.tgz /artifact.tgz
