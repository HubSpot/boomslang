# Build the final boomslang WASM binary: Rust host linked against libpython3.14.a
#
# For CI (Blazar): the builder image is pre-built and artifacts are downloaded
# in before: steps. For local dev: use `just wasm` instead (no Docker needed).
#
ARG BUILDER_IMAGE=boomslang-builder
FROM ${BUILDER_IMAGE}

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
