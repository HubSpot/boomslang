# syntax=docker/dockerfile:1.4
# Cross-compile lxml (etree + objectify) for wasm32-wasip1.
#
# Strategy:
#   - libxml2, libxslt: cross-compile from release tarballs. libxslt is required
#     because lxml/etree.c unconditionally includes <libxslt/xslt.h>.
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

ENV WASI_CFLAGS="-Os \
    --target=wasm32-wasi \
    --sysroot=/opt/wasi-sdk/share/wasi-sysroot \
    -fvisibility=default \
    -D_WASI_EMULATED_SIGNAL \
    -D_WASI_EMULATED_GETPID \
    -D_WASI_EMULATED_PROCESS_CLOCKS"

# ── libxml2 + libxslt: cross-compile from release tarballs ─────────────────
# Release tarballs ship a generated configure, so no autoreconf is needed.
# libxslt 1.1.43 is the first release that builds against libxml2 2.14+.
# lxml's etree.c unconditionally includes <libxslt/xslt.h>, and links EXSLT
# when it is available.
ARG LIBXML2_VERSION=2.15.4
ARG LIBXML2_SHA256=98087fd181d9070724f3fbc65c7377db03038eb92bd882374daff44940138821
ARG LIBXSLT_VERSION=1.1.45
ARG LIBXSLT_SHA256=9acfe68419c4d06a45c550321b3212762d92f41465062ca4ea19e632ee5d216e
RUN set -ex; \
    mkdir -p /build/staging; \
    cd /build/staging; \
    curl -sLo libxml2.tar.xz "https://download.gnome.org/sources/libxml2/${LIBXML2_VERSION%.*}/libxml2-${LIBXML2_VERSION}.tar.xz"; \
    curl -sLo libxslt.tar.xz "https://download.gnome.org/sources/libxslt/${LIBXSLT_VERSION%.*}/libxslt-${LIBXSLT_VERSION}.tar.xz"; \
    echo "${LIBXML2_SHA256}  libxml2.tar.xz" | sha256sum -c; \
    echo "${LIBXSLT_SHA256}  libxslt.tar.xz" | sha256sum -c; \
    tar xf libxml2.tar.xz; \
    tar xf libxslt.tar.xz

# WASI preview 1 has no dup(). The patch hands the fd that libxml2's default
# file opener just opened to the input buffer instead of duplicating it.
COPY libxml2-wasi-no-dup.patch /build/libxml2-wasi-no-dup.patch

# WASI has no threads, dlopen or usable iconv. Without iconv, libxml2 falls
# back to its built-in UTF-8/UTF-16/ISO-8859-x codecs. Schematron is off by
# default since 2.15; enable it to keep lxml.isoschematron working.
RUN set -ex; \
    cd /build/staging/libxml2-${LIBXML2_VERSION}; \
    patch -p1 < /build/libxml2-wasi-no-dup.patch; \
    ./configure \
        --host=wasm32-wasip1 \
        --build="$(./config.guess)" \
        --prefix=/build/xml2-install \
        --disable-shared \
        --enable-static \
        --without-python \
        --without-threads \
        --without-modules \
        --without-iconv \
        --without-zlib \
        --with-schematron \
        CC="${CC}" \
        AR="${AR}" \
        RANLIB="${RANLIB}" \
        CFLAGS="${WASI_CFLAGS}" \
        LDFLAGS="--sysroot=/opt/wasi-sdk/share/wasi-sysroot" \
    ; \
    make -j$(nproc) libxml2.la; \
    make install-libLTLIBRARIES install-data; \
    test -f /build/xml2-install/lib/libxml2.a; \
    test -f /build/xml2-install/include/libxml2/libxml/parser.h

RUN set -ex; \
    cd /build/staging/libxslt-${LIBXSLT_VERSION}; \
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
        LIBXML_CFLAGS="-I/build/xml2-install/include/libxml2" \
        LIBXML_LIBS="-L/build/xml2-install/lib -lxml2" \
    ; \
    make -j$(nproc) -C libxslt; \
    make -j$(nproc) -C libexslt; \
    make -C libxslt install; \
    make -C libexslt install; \
    test -f /build/xslt-install/lib/libxslt.a; \
    test -f /build/xslt-install/lib/libexslt.a; \
    test -f /build/xslt-install/include/libxslt/xslt.h

# ── lxml: download sdist (ships pre-generated C, no Cython needed) ───────────
ARG LXML_VERSION=6.1.3
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    mkdir -p /tmp/lxml-sdist \
    && env -u CC -u AR -u RANLIB -u NM \
        pip3 download --no-binary lxml --no-deps --no-cache-dir \
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
#   - libxml2 and libxslt headers — from our cross-compiled builds
RUN set -ex; \
    LXML_SRC=$(ls -d /build/staging/lxml-*/src/lxml); \
    LXML_ROOT=$(ls -d /build/staging/lxml-*); \
    mkdir -p /build/obj/lxml; \
    LXML_CFLAGS="${WASI_CFLAGS} \
        -I${LXML_SRC} \
        -I${LXML_SRC}/includes \
        -I/tmp/cpython-wasi/include/python3.14 \
        -I/build/xml2-install/include/libxml2 \
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
