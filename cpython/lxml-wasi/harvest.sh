#!/usr/bin/env bash
set -euo pipefail

# harvest.sh: collect compiled lxml .o files into per-extension static archives,
# copy the lxml Python source tree, and verify PyInit_* symbols are present.
# Mirrors the pattern from numpy-wasi/harvest.sh and ijson-wasi/harvest.sh.

WASI_SDK_PATH="${WASI_SDK_PATH:-/opt/wasi-sdk}"
AR="${WASI_SDK_PATH}/bin/llvm-ar"
RANLIB="${WASI_SDK_PATH}/bin/llvm-ranlib"
NM="${WASI_SDK_PATH}/bin/llvm-nm"

OUTPUT_DIR="/build/output"
LXML_TAG=$(cat /build/lxml_tag.txt)
LXML_SRC=$(ls -d /build/staging/lxml-*/src/lxml)

log() { echo "==> $*"; }
die() { echo "!!! $*" >&2; exit 1; }

mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi"
mkdir -p "${OUTPUT_DIR}/python"

# Extension modules to ship.
# Each entry: <module_name>:<archive_basename>:<expected PyInit symbol>
EXTENSIONS=(
    "etree:lxml_etree:PyInit_etree"
    "objectify:lxml_objectify:PyInit_objectify"
)

MANIFEST="${OUTPUT_DIR}/manifest.txt"
> "${MANIFEST}"
FAILED=()

for entry in "${EXTENSIONS[@]}"; do
    IFS=':' read -r mod_name ar_name init_sym <<<"${entry}"

    obj_path="/build/obj/lxml/${mod_name}.o"
    if [ ! -f "${obj_path}" ]; then
        log "FAIL ${ar_name}: ${obj_path} not found"
        FAILED+=("${ar_name} (missing ${obj_path})")
        continue
    fi

    ar_path="${OUTPUT_DIR}/lib/wasm32-wasi/lib_${ar_name}.a"
    ${AR} rcs "${ar_path}" "${obj_path}"
    ${RANLIB} "${ar_path}"

    ${NM} "${ar_path}" > /tmp/syms.txt 2>/dev/null
    if ! grep -q "${init_sym}" /tmp/syms.txt; then
        log "FAIL ${ar_name}: ${init_sym} NOT found in ${ar_path}"
        ${NM} "${ar_path}" 2>/dev/null | grep -i "pyinit" || true
        FAILED+=("${ar_name} (missing ${init_sym})")
        continue
    fi

    size=$(ls -lh "${ar_path}" | awk '{print $5}')
    echo "${ar_name} ${init_sym} ${size}" >> "${MANIFEST}"
    log "OK   ${ar_name}: ${init_sym} (${size})"
done

if [ "${#FAILED[@]}" -gt 0 ]; then
    log "FAILED extensions:"
    printf '  - %s\n' "${FAILED[@]}"
    die "${#FAILED[@]} extension(s) failed"
fi

# Stage the lxml Python source tree (pure-Python files only; no .c/.h/.so).
log "Staging lxml Python sources..."
cp -r "${LXML_SRC}" "${OUTPUT_DIR}/python/lxml"
find "${OUTPUT_DIR}/python/lxml" -type f \( \
        -name '*.c' -o -name '*.h' -o -name '*.cpp' -o -name '*.hpp' \
        -o -name '*.so' -o -name '*.pyx' -o -name '*.pxd' \
    \) -delete
find "${OUTPUT_DIR}/python/lxml" -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
find "${OUTPUT_DIR}/python/lxml" -type d -name tests -exec rm -rf {} + 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/lxml/includes" 2>/dev/null || true

# Bundle libxml2 and libxslt so that downstream consumers (e.g. aviator-cpython)
# that don't carry these libs in their own cpython-wasi can link against them.
log "Bundling libxml2 and libxslt static archives..."
cp /build/wasi-libs/lib/wasm32-wasi/libxml2.a "${OUTPUT_DIR}/lib/wasm32-wasi/"
cp /build/xslt-install/lib/libxslt.a          "${OUTPUT_DIR}/lib/wasm32-wasi/"

echo "${LXML_TAG}" > "${OUTPUT_DIR}/version.txt"

log "DONE. Manifest:"
cat "${MANIFEST}"
log "Archives:"
ls -lh "${OUTPUT_DIR}/lib/wasm32-wasi/"
log "Python sources staged:"
ls "${OUTPUT_DIR}/python/lxml/" | head -20

# Package artifact.
log "Packaging artifact..."
tar czf /artifact.tgz -C "${OUTPUT_DIR}" .
log "Artifact contents:"
tar tzf /artifact.tgz | head -30
