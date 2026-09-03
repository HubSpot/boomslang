# syntax=docker/dockerfile:1.4
FROM ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    git \
    curl \
    xz-utils \
    pkg-config \
    ca-certificates \
    python3 python3-pip python3-venv \
    ninja-build \
    autoconf automake libtool \
    && rm -rf /var/lib/apt/lists/*

# NOTE: libqhull-dev is installed only to satisfy matplotlib's meson probe;
# we skip the _qhull extension via patch.py so qhull is never linked. Our
# wasm32-wasi freetype is built from source below and installed to
# /build/wasi-libs; matplotlib's meson picks it up via pkg-config.

# Keep build tooling isolated from Ubuntu's externally managed Python.
RUN python3 -m venv /opt/build-venv
ENV PATH="/opt/build-venv/bin:${PATH}"

# pybind11 3.1 supports Python 3.15. Its C++ locks are adapted below to
# Boomslang's single-threaded WASI runtime; subinterpreter TLS is disabled in
# the cross file because each instance owns one interpreter.
# Blazar supplies its internal pip index with the pipconf build secret.
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    pip3 install --no-cache-dir --prefer-binary \
        'meson==1.12.0' 'meson-python==0.20.0' 'pybind11==3.1.0' \
        'setuptools_scm>=8,<10'
# Build-time numpy only supplies headers (via numpy.get_include()). Matplotlib's
# _path extension pulls the numpy C API; ft2font uses it transitively. We overlay
# numpy-wasi's patched headers on top below, so the host numpy version isn't
# load-bearing.
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    pip3 install --no-cache-dir --prefer-binary --no-build-isolation \
        'numpy==2.5.2'

# wasi-sdk selection — mirror pandas-wasi's dual-arch support. wasi-sdk-20
# amd64 in Blazar; wasi-sdk-24 arm64 for local dev on Apple Silicon. ABI
# compatible so archives built on either link against cpython-wasi's wasi-sdk-20
# artifacts. ARG TARGETARCH without a default lets buildx auto-populate from
# --platform.
ARG TARGETARCH
# Use wasi-sdk-24 on BOTH arches for matplotlib. wasi-sdk-20's libc++
# (clang 16) emits external `basic_string::__grow_by` calls whose mangled
# symbol doesn't match what aviator links against at wasm-ld time — the
# test suite fails with `UnlinkableException: env._ZNSt...__grow_by...`.
# clang 18 (wasi-sdk-24) inlines those calls so no external reference is
# emitted. The wasm32-wasi ABI is stable across wasi-sdk versions, so the
# resulting archives still link cleanly against aviator's wasi-sdk-20 build.
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

# ---- Layer: clone matplotlib (cached by MATPLOTLIB_TAG arg)
# Keep the existing Matplotlib/mpld3 API pairing while upgrading the C API
# bindings. All shipped C++ extensions are rebuilt with pybind11 3.1.
ARG MATPLOTLIB_TAG=v3.9.3
RUN git clone --depth 1 --branch ${MATPLOTLIB_TAG} https://github.com/matplotlib/matplotlib /build/staging/matplotlib \
    && cd /build/staging/matplotlib \
    && git submodule update --init --recursive \
    && echo "${MATPLOTLIB_TAG}" > /build/matplotlib_tag.txt

# ---- Layer: Python headers for cross-compile.
# Pull raw CPython source for the Include/ tree, overlay the real wasm32-wasi
# pyconfig.h and _sysconfigdata. Matplotlib's meson probes pyconfig at
# configure time.
COPY _sysconfigdata__wasi_wasm32-wasi.py /build/_sysconfigdata__wasi_wasm32-wasi.py
COPY pyconfig-wasi.h /build/pyconfig-wasi.h
RUN mkdir -p /tmp/cpython-wasi/include/python3.15 /tmp/cpython-wasi/usr/local/lib/python3.15 \
    && curl -sL https://github.com/python/cpython/archive/refs/tags/v3.15.0a7.tar.gz | tar xz -C /tmp \
    && cp -r /tmp/cpython-3.15.0a7/Include/* /tmp/cpython-wasi/include/python3.15/ \
    && cp /build/pyconfig-wasi.h /tmp/cpython-wasi/include/python3.15/pyconfig.h \
    && cp /build/_sysconfigdata__wasi_wasm32-wasi.py /tmp/cpython-wasi/usr/local/lib/python3.15/ \
    && rm -rf /tmp/cpython-3.15.0a7 \
    && test -f /tmp/cpython-wasi/include/python3.15/pyconfig.h

# Run build scripts with host Python while exposing the WASI target's ABI
# to Meson/sysconfig. Python's extension loader still uses its native suffix,
# so build-time NumPy/Cython remain host executables.
ENV _PYTHON_SYSCONFIGDATA_NAME=_sysconfigdata__wasi_wasm32-wasi
ENV PYTHONPATH=/tmp/cpython-wasi/usr/local/lib/python3.15

# ---- Layer: overlay numpy-wasi's patched headers onto host numpy.
# matplotlib's _path extension uses the numpy C API. Host numpy's npy_cpu.h
# emits "#error Unknown CPU" under __wasm32__; numpy-wasi publishes a patched
# include/numpy/ tree we drop on top. Same pattern as pandas-wasi.
COPY vendor/numpy-wasi.tgz /build/numpy-wasi.tgz
RUN HOST_NUMPY_INCLUDE=$(python3 -c 'import numpy; print(numpy.get_include())') \
    && test -d "$HOST_NUMPY_INCLUDE/numpy" \
    && mkdir -p /tmp/numpy-wasi \
    && tar xzf /build/numpy-wasi.tgz -C /tmp/numpy-wasi \
    && test -d /tmp/numpy-wasi/include/numpy \
    && cp -rf /tmp/numpy-wasi/include/numpy/* "$HOST_NUMPY_INCLUDE/numpy/" \
    && grep -q NPY_CPU_WASM32 "$HOST_NUMPY_INCLUDE/numpy/npy_cpu.h" \
    && echo "host numpy headers overlaid with numpy-wasi patched variants"

# ---- Layer: download pre-built libz + libpng from WLR upstream.
# vmware-labs/webassembly-language-runtimes publishes wasi-sdk-20 binaries for
# common libs. We already curl these exact tarballs in cpython-wasi. Here we
# stage them under /build/wasi-libs so ft2font/_backend_agg can link against
# libpng and meson's pkg-config can find both. Download both amd64 and arm64
# from the same URL — these are wasm32-wasi objects so the host arch doesn't
# matter (the tarball contents target wasm).
RUN mkdir -p /build/wasi-libs \
    && curl -sL "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Fzlib%2F1.2.13%2B20230623-2993864/libz-1.2.13-wasi-sdk-20.0.tar.gz" \
        | tar xz -C /build/wasi-libs \
    && curl -sL "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Flibpng%2F1.6.39%2B20230629-ccb4cb0/libpng-1.6.39-wasi-sdk-20.0.tar.gz" \
        | tar xz -C /build/wasi-libs \
    && ls -la /build/wasi-libs/lib/wasm32-wasi/ \
    && ls -la /build/wasi-libs/include/

# ---- Layer: build freetype 2.13.x for wasm32-wasi.
# Freetype's rasterizer files (ftraster.c, ftgrays.c) use setjmp/longjmp for
# out-of-memory recovery. wasi-sdk 20's setjmp.h errors out at include time
# unless -mllvm -wasm-enable-sjlj is used and the runtime supports the WASM
# exception proposal (Endive does not). Inject a shim that no-ops setjmp and
# maps longjmp -> abort. Error recovery becomes abort; for valid font files
# the rasterizer never trips those branches, so this is benign in practice.
#
# Disable PNG glyph support (-DFT_CONFIG_OPTION_USE_PNG=0) to cut another
# setjmp site in pngshim.c — freetype supports color emoji / Apple PNG glyphs
# via libpng, but matplotlib text rendering doesn't need that path. libpng
# still gets linked later for _backend_agg's own PNG I/O.
# Disable HarfBuzz, BZip2, Brotli — we don't ship shaping, bzipped PCF, or
# WOFF2 decompression.
# Shadow wasi-sdk's <setjmp.h> by putting our stub at a higher-priority
# include dir. See shim-include/setjmp.h for the rationale.
COPY shim-include/ /build/shim-include/
ARG FREETYPE_VERSION=2.13.3
ARG FREETYPE_TAG=VER-2-13-3
# Blazar containers allow github.com (cpython-wasi already uses it) but block
# savannah / sourceforge. git-clone from freetype's GitHub mirror with
# --recurse-submodules: freetype 2.13's build system invokes `git submodule
# update --init` to fetch `subprojects/dlg` (logging library), which fails
# against a plain source tarball. The clone keeps the full git metadata so
# the submodule fetch works.
RUN git clone --depth 1 --branch ${FREETYPE_TAG} --recurse-submodules \
        https://github.com/freetype/freetype /build/freetype-${FREETYPE_VERSION} \
    && test -d /build/freetype-${FREETYPE_VERSION}/subprojects/dlg
# Git checkouts don't include the pre-built `configure` script (savannah
# release tarballs do). Run autogen.sh to generate it from autoconf/automake
# sources.
RUN cd /build/freetype-${FREETYPE_VERSION} \
    && ./autogen.sh \
    && ./configure \
        --host=wasm32-wasi \
        --prefix=/build/wasi-libs \
        --enable-static --disable-shared \
        --without-zlib --without-bzip2 --without-png \
        --without-harfbuzz --without-brotli \
        CC="${CC}" AR="${AR}" RANLIB="${RANLIB}" \
        CFLAGS="--sysroot=${WASI_SDK_PATH}/share/wasi-sysroot -O2 -I/build/shim-include -D_WASI_EMULATED_SIGNAL -D_WASI_EMULATED_GETPID" \
    && make -j$(nproc) \
    && make install \
    && test -f /build/wasi-libs/lib/libfreetype.a \
    && ls /build/wasi-libs/lib/pkgconfig/

# ---- Layer: patch matplotlib sources to skip extensions we don't ship.
# mpld3 walks the Figure artist tree and emits D3 JSON, so we never render
# pixels. Skipping _backend_agg / _image / _tri / _qhull / _ttconv avoids
# building the Agg C++ rendering pipeline + qhull + associated headers.
# See patch.py.
COPY patch-pybind11.py /build/patch-pybind11.py
RUN python3 /build/patch-pybind11.py
COPY patch.py /build/patch.py
RUN cd /build/staging/matplotlib && python3 /build/patch.py

# ---- Layer: meson setup
# We skip ft2font (no freetype needed — see patch.py) and _qhull (no qhull
# needed). Remaining extensions are _c_internal_utils and _path which only
# depend on pybind11 + the vendored Agg headers (no external libs).
COPY cross-wasi.ini /build/cross-wasi.ini
# Point pkg-config at our wasm32-wasi freetype + libpng + libz so meson's
# `dependency('freetype2')` and `dependency('libpng')` probes resolve to the
# cross-compiled libs instead of the host libs (which are deb x86_64 and
# can't link against wasm32-wasi extensions).
# PKG_CONFIG_PATH is additive (prepended to default) so pkg-config still
# finds host libs (qhull_r). PKG_CONFIG_LIBDIR would REPLACE the default and
# block host probes.
ENV PKG_CONFIG_PATH=/build/wasi-libs/lib/pkgconfig:/build/wasi-libs/lib/wasm32-wasi/pkgconfig
RUN cd /build/staging/matplotlib \
    && { meson setup build \
            --cross-file /build/cross-wasi.ini \
            -Ddefault_library=static \
            -Dbuildtype=release \
            -Db_lto=false \
            -Dsystem-freetype=true \
            -Dsystem-qhull=true \
        || { echo "=== meson-log.txt ==="; tail -n 120 build/meson-logs/meson-log.txt; exit 1; }; }
# -Db_lto=false is critical: with LTO enabled, matplotlib's .o files are
# LLVM bitcode (not native wasm32-wasi objects). bitcode is versioned to the
# compiling clang's LLVM version (wasi-sdk-24 = LLVM 18), and downstream
# consumers running an older llvm-ar (cpython-wasi on wasi-sdk-20 = LLVM 16)
# can't parse it: "Unknown attribute kind (91)". Disabling LTO produces
# plain wasm object files that any LLVM version's ar can read.

# ---- Layer: ninja compile (expensive; includes freetype + Agg headers).
# Same -k 0 pattern as pandas-wasi: wasm-ld rejects the final shared-module
# link for each extension. Harvest the .o files directly. Only fail if ninja
# itself died (exit > 1).
RUN cd /build/staging/matplotlib; \
    ninja -C build -k 0 > /build/ninja.log 2>&1; ninja_exit=$?; \
    tail -n 300 /build/ninja.log; \
    echo "ninja exit $ninja_exit"; \
    test "$ninja_exit" -eq 0 -o "$ninja_exit" -eq 1 || exit "$ninja_exit"; \
    if grep '^FAILED:' /build/ninja.log | grep -vE '^FAILED: [^ ]+\.so[[:space:]]*$'; then \
        echo "Non-link build failures are not allowed"; exit 1; \
    fi

# ---- Layer: harvest + verify + package
COPY harvest.sh /build/harvest.sh
# Mount pipconf so harvest.sh's pip download calls (jinja2, packaging, etc.)
# resolve via HubSpot's internal mirror. Without this mount pip falls back
# to pypi.org which Blazar blocks.
RUN --mount=type=secret,id=pipconf,target=/etc/pip.conf \
    chmod +x /build/harvest.sh && /build/harvest.sh

RUN cd /build/output && tar czf /build/artifact.tgz .

FROM scratch
COPY --from=builder /build/artifact.tgz /artifact.tgz
