set dotenv-load := false

runtime_resources := "core/src/main/resources/python"

default:
    @just --list

# ============================================================
# Full pipeline
# ============================================================

# Build everything from source through the Mill artifact DAG
everything:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill --jobs "$${JOBS:-0}" artifacts.installAll
    ./mill --jobs "$${JOBS:-0}" build

# Install all Mill-built artifacts into the legacy Maven/Cargo locations
install-artifacts:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill --jobs "$${JOBS:-0}" artifacts.installAll

# Print the container engine Mill will use
show-container-cli:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.showContainerCli

# Print the artifact dependency graph as readable edges
dag:
    ./mill artifacts.dag

# Print the artifact dependency graph as Graphviz DOT
dag-dot:
    ./mill artifacts.dagDot

# Print expected cached output files for artifact tasks
cache-status:
    ./mill artifacts.cacheStatus

# Persist Docker as the Mill container engine for this checkout
use-docker:
    ./mill artifacts.setContainerCli --cli docker

# Persist Apple container as the Mill container engine for this checkout
use-container:
    ./mill artifacts.setContainerCli --cli container

# ============================================================
# Container artifact builds
# ============================================================

# Build pydantic-core as static library for wasm32-wasip1
build-pydantic-core-wasi:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installPydanticCoreWasi

# Build numpy C extensions for wasm32-wasip1
build-numpy-wasi:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installNumpyWasi

# Build pandas C extensions for wasm32-wasip1
build-pandas-wasi:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installPandasWasi

# Build matplotlib C extensions for wasm32-wasip1
build-matplotlib-wasi:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installMatplotlibWasi

# Build Pillow C extensions for wasm32-wasip1
build-pillow-wasi:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installPillowWasi

# Build ijson C extension for wasm32-wasip1
build-ijson-wasi:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installIjsonWasi

# Build cpython-wasi and extract it to cpython/build/cpython-wasi
build-cpython-wasi:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installCpythonWasi

# Build the container image used for the final Rust/WASM host build
builder-image:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.builderImage

# ============================================================
# Local Rust + Java build
# ============================================================

# Download Python pip packages into cpython/lib/pip-packages
pip-packages:
    ./mill artifacts.installPipPackages

# Build the WASM binary via the configured container engine
wasm:
    ./mill artifacts.setContainerCli --cli "$${BOOMSLANG_CONTAINER_CLI:-docker}"
    ./mill artifacts.installWasm

# Build the WASM binary locally (requires WASI SDK + Wizer + Rust on PATH)
wasm-local:
    cd python-host && ./build-wasm.sh all

# Populate runtime resources from the cpython-wasi artifact
resources:
    ./mill artifacts.installResources

# Fetch runtime resources (WASM + Python stdlib) from GitHub artifacts
fetch-main-wasm *args:
    ./scripts/fetch-main-runtime-resources.sh {{args}}

# Alias for fetch-main-wasm
fetch-main-runtime *args:
    ./scripts/fetch-main-runtime-resources.sh {{args}}

# Build Java project (AOT compile WASM + package)
build:
    ./mill build

# Run tests
test:
    ./mill test

# ============================================================
# Individual WASM step shortcuts
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
    rm -rf out
    rm -rf {{runtime_resources}}/bin {{runtime_resources}}/usr
    rm -rf cpython/build
    rm -rf python-host/target
    rm -f cpython/pydantic-core-wasi/artifact.tgz cpython/numpy-wasi/artifact.tgz cpython/pandas-wasi/artifact.tgz cpython/matplotlib-wasi/artifact.tgz cpython/pillow-wasi/artifact.tgz cpython/ijson-wasi/artifact.tgz cpython/cpython-wasi/artifact.tgz
    rm -rf cpython/cpython-wasi/vendor
    rm -rf cpython/lib/pip-packages
    mvn clean || true
