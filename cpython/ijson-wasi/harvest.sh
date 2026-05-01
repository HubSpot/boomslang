#!/usr/bin/env bash
set -euo pipefail

WASI_SDK_PATH="${WASI_SDK_PATH:-/opt/wasi-sdk}"
AR="${WASI_SDK_PATH}/bin/llvm-ar"
NM="${WASI_SDK_PATH}/bin/llvm-nm"

OUTPUT_DIR="/build/output"
IJSON_TAG="${IJSON_TAG:-v3.3.0}"

log() { echo "==> $*"; }
die() { echo "!!! $*" >&2; exit 1; }

mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi"
mkdir -p "${OUTPUT_DIR}/python"

log "Creating lib_ijson_yajl2.a from yajl + ijson .o files..."
${AR} rcs "${OUTPUT_DIR}/lib/wasm32-wasi/lib_ijson_yajl2.a" \
    /build/obj/yajl/*.o \
    /build/obj/ijson/*.o

log "Verifying PyInit__yajl2 symbol..."
NM_OUTPUT=$(${NM} "${OUTPUT_DIR}/lib/wasm32-wasi/lib_ijson_yajl2.a")
if echo "$NM_OUTPUT" | grep -q "T PyInit__yajl2"; then
    log "OK: PyInit__yajl2 found"
else
    log "Symbols containing PyInit:"
    echo "$NM_OUTPUT" | grep -i "pyinit" || true
    die "PyInit__yajl2 symbol NOT found in archive"
fi

log "Copying ijson Python sources..."
cp -r /build/ijson/ijson "${OUTPUT_DIR}/python/ijson"

find "${OUTPUT_DIR}/python/ijson" -type f \( \
        -name '*.c' -o -name '*.h' -o -name '*.so' -o -name '*.o' \
    \) -delete
rm -rf "${OUTPUT_DIR}/python/ijson/backends/yajl2_c" 2>/dev/null || true

log "Writing version and manifest..."
echo "${IJSON_TAG}" > "${OUTPUT_DIR}/version.txt"

ARCHIVE_SIZE=$(du -h "${OUTPUT_DIR}/lib/wasm32-wasi/lib_ijson_yajl2.a" | cut -f1)
cat > "${OUTPUT_DIR}/manifest.txt" <<MEOF
ijson-wasi extensions:
  _yajl2  ${ARCHIVE_SIZE}  PyInit__yajl2
MEOF

log "Packaging artifact..."
tar czf /artifact.tgz -C "${OUTPUT_DIR}" .

log "Artifact contents:"
tar tzf /artifact.tgz
log "DONE"
