set dotenv-load := false

cpython_wasi_dir := "cpython/build/cpython-wasi"
runtime_resources := "core/src/main/resources/python"

default:
    @just --list

# ============================================================
# Full pipeline: build everything from source
# ============================================================

# Build everything from scratch (takes a while — Docker builds are heavy)
everything: builder-image build-pydantic-core-wasi build-numpy-wasi build-pandas-wasi build-matplotlib-wasi build-cpython-wasi pip-packages wasm resources build

# ============================================================
# Docker image builds (WASM artifact production)
# ============================================================

# Build pydantic-core as static library for wasm32-wasip1
build-pydantic-core-wasi:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== Building pydantic-core-wasi ==="
    cd cpython/pydantic-core-wasi
    DOCKER_BUILDKIT=1 docker build -t boomslang-pydantic-core-wasi .
    CID=$(docker create boomslang-pydantic-core-wasi unused-cmd)
    docker cp "$CID:/artifact.tgz" artifact.tgz
    docker rm "$CID" > /dev/null
    echo "pydantic-core-wasi artifact: $(ls -lh artifact.tgz)"

# Build numpy C extensions for wasm32-wasip1
build-numpy-wasi:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== Building numpy-wasi ==="
    cd cpython/numpy-wasi
    DOCKER_BUILDKIT=1 docker build -t boomslang-numpy-wasi .
    CID=$(docker create boomslang-numpy-wasi unused-cmd)
    docker cp "$CID:/artifact.tgz" artifact.tgz
    docker rm "$CID" > /dev/null
    echo "numpy-wasi artifact: $(ls -lh artifact.tgz)"

# Build pandas C extensions for wasm32-wasip1
build-pandas-wasi:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== Building pandas-wasi ==="
    cd cpython/pandas-wasi
    DOCKER_BUILDKIT=1 docker build -t boomslang-pandas-wasi .
    CID=$(docker create boomslang-pandas-wasi unused-cmd)
    docker cp "$CID:/artifact.tgz" artifact.tgz
    docker rm "$CID" > /dev/null
    echo "pandas-wasi artifact: $(ls -lh artifact.tgz)"

# Build matplotlib C extensions for wasm32-wasip1
build-matplotlib-wasi:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== Building matplotlib-wasi ==="
    cd cpython/matplotlib-wasi
    DOCKER_BUILDKIT=1 docker build -t boomslang-matplotlib-wasi .
    CID=$(docker create boomslang-matplotlib-wasi unused-cmd)
    docker cp "$CID:/artifact.tgz" artifact.tgz
    docker rm "$CID" > /dev/null
    echo "matplotlib-wasi artifact: $(ls -lh artifact.tgz)"

# Build cpython-wasi (CPython + all libraries merged into libpython3.14.a)
# Requires: pydantic-core-wasi, numpy-wasi, pandas-wasi, matplotlib-wasi artifacts
build-cpython-wasi:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== Building cpython-wasi ==="
    cd cpython/cpython-wasi

    # Populate vendor/ from upstream artifact builds
    mkdir -p vendor
    for mod in pydantic-core-wasi numpy-wasi pandas-wasi matplotlib-wasi; do
        src="../${mod}/artifact.tgz"
        if [ ! -f "$src" ]; then
            echo "ERROR: ${mod}/artifact.tgz not found. Run 'just build-${mod}' first."
            exit 1
        fi
        cp "$src" "vendor/${mod}.tgz"
        echo "  vendor/${mod}.tgz <- $src"
    done

    DOCKER_BUILDKIT=1 docker build -t boomslang-cpython-wasi .
    CID=$(docker create boomslang-cpython-wasi unused-cmd)
    docker cp "$CID:/artifact.tgz" artifact.tgz
    docker rm "$CID" > /dev/null

    # Extract to the location the Rust host build expects
    mkdir -p ../build/cpython-wasi
    tar xzf artifact.tgz -C ../build/cpython-wasi/
    echo "cpython-wasi artifact extracted to cpython/build/cpython-wasi/"
    ls ../build/cpython-wasi/

# ============================================================
# Build the builder Docker image (for CI use)
# ============================================================

builder-image:
    docker build -t boomslang-builder cpython/builder/

# ============================================================
# Local Rust + Java build (after Docker artifacts are ready)
# ============================================================

# Download pip packages for local dev
pip-packages:
    #!/usr/bin/env bash
    set -euo pipefail
    pip_tmp="/tmp/pip-packages-boomslang"
    rm -rf "$pip_tmp" && mkdir -p "$pip_tmp"
    python3 -m pip download "pydantic==2.12.5" "annotated-types>=0.6.0" "typing-extensions>=4.14.1" "typing-inspection>=0.4.2" --no-deps -d "$pip_tmp" --quiet
    for whl in "$pip_tmp"/*.whl; do
        [ -f "$whl" ] || continue
        python3 -m zipfile -e "$whl" "$pip_tmp/extracted"
    done
    mkdir -p cpython/lib/pip-packages
    for pkg in pydantic annotated_types typing_inspection; do
        [ -d "$pip_tmp/extracted/$pkg" ] && cp -r "$pip_tmp/extracted/$pkg" cpython/lib/pip-packages/
    done
    [ -f "$pip_tmp/extracted/typing_extensions.py" ] && cp "$pip_tmp/extracted/typing_extensions.py" cpython/lib/pip-packages/
    echo "Pip packages installed to cpython/lib/pip-packages/"
    ls cpython/lib/pip-packages/

# Build the WASM binary via Docker (no local WASI SDK/Wizer/Rust needed)
wasm:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "=== Building boomslang WASM (Docker) ==="

    # Ensure builder image exists
    if ! docker image inspect boomslang-builder > /dev/null 2>&1; then
        echo "Building builder image..."
        docker build -t boomslang-builder cpython/builder/
    fi

    # Prepare build context
    TMPCTX=$(mktemp -d)
    trap "rm -rf $TMPCTX" EXIT

    cp -r python-host "$TMPCTX/python-host"
    cp -r cpython/build/cpython-wasi "$TMPCTX/cpython-wasi"
    cp -r cpython/lib "$TMPCTX/lib"
    [ -d cpython/lib/pip-packages ] && cp -r cpython/lib/pip-packages "$TMPCTX/pip-packages" || mkdir -p "$TMPCTX/pip-packages"

    cat > "$TMPCTX/Dockerfile" <<'DOCKERFILE'
    ARG BUILDER_IMAGE=boomslang-builder
    FROM ${BUILDER_IMAGE}
    WORKDIR /build

    COPY cpython-wasi/ cpython/build/cpython-wasi/
    COPY lib/ cpython/lib/
    COPY pip-packages/ cpython/lib/pip-packages/
    COPY python-host/ python-host/

    RUN cd python-host && chmod +x build-wasm.sh && ./build-wasm.sh all

    FROM scratch
    COPY --from=0 /build/cpython/build/output/boomslang.wasm /boomslang.wasm
    DOCKERFILE

    DOCKER_BUILDKIT=1 docker build -t boomslang-wasm "$TMPCTX"
    CID=$(docker create boomslang-wasm unused-cmd)
    docker cp "$CID:/boomslang.wasm" core/src/main/resources/python/bin/boomslang.wasm
    docker rm "$CID" > /dev/null

    echo "Installed: core/src/main/resources/python/bin/boomslang.wasm"
    ls -lh core/src/main/resources/python/bin/boomslang.wasm

# Build the WASM binary locally (requires WASI SDK + Wizer + Rust on PATH)
wasm-local:
    cd python-host && ./build-wasm.sh all

# Populate runtime resources from the cpython-wasi artifact
resources:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Populating runtime resources..."
    rm -rf {{runtime_resources}}/usr
    mkdir -p {{runtime_resources}}/usr/local/lib
    cp -r {{cpython_wasi_dir}}/usr/local/lib/python3.14 {{runtime_resources}}/usr/local/lib/
    # Copy pydantic_core Python stubs
    [ -d cpython/lib/pydantic_core ] && cp -r cpython/lib/pydantic_core {{runtime_resources}}/usr/local/lib/python3.14/
    # Copy pip packages
    if [ -d cpython/lib/pip-packages ]; then
        for pkg in pydantic annotated_types typing_inspection; do
            [ -d "cpython/lib/pip-packages/$pkg" ] && cp -r "cpython/lib/pip-packages/$pkg" {{runtime_resources}}/usr/local/lib/python3.14/
        done
        [ -f "cpython/lib/pip-packages/typing_extensions.py" ] && cp "cpython/lib/pip-packages/typing_extensions.py" {{runtime_resources}}/usr/local/lib/python3.14/
    fi
    # Copy extension Python packages
    if [ -n "${PYTHON4J_EXTENSIONS:-}" ]; then
        IFS=',' read -ra EXT_DIRS <<< "$PYTHON4J_EXTENSIONS"
        for ext_dir in "${EXT_DIRS[@]}"; do
            if [ -d "$ext_dir/lib" ]; then
                echo "Copying extension packages from $ext_dir/lib"
                cp -r "$ext_dir/lib/"* {{runtime_resources}}/usr/local/lib/python3.14/ 2>/dev/null || true
            fi
        done
    fi
    echo "Resources populated at {{runtime_resources}}/usr/local/lib/python3.14/"

# Build Java project (AOT compile WASM + package)
build:
    mvn clean install -DskipTests

# Run tests
test:
    mvn test -pl tests

# ============================================================
# Individual step shortcuts
# ============================================================

# Build WASM only (no Wizer)
wasm-build:
    cd python-host && ./build-wasm.sh build

# Apply Wizer pre-initialization only
wasm-wizer:
    cd python-host && ./build-wasm.sh wizer

# Install WASM to runtime resources only
wasm-install:
    cd python-host && ./build-wasm.sh install

# ============================================================
# Cleanup
# ============================================================

# Clean all build artifacts
clean:
    rm -rf {{runtime_resources}}/bin {{runtime_resources}}/usr
    rm -rf cpython/build
    rm -rf python-host/target
    rm -f cpython/pydantic-core-wasi/artifact.tgz cpython/numpy-wasi/artifact.tgz cpython/pandas-wasi/artifact.tgz cpython/matplotlib-wasi/artifact.tgz cpython/cpython-wasi/artifact.tgz
    rm -rf cpython/cpython-wasi/vendor
    mvn clean || true
