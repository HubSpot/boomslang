#!/usr/bin/env bash
set -euo pipefail

WASI_SDK_PATH="${WASI_SDK_PATH:-/opt/wasi-sdk}"
AR="${WASI_SDK_PATH}/bin/llvm-ar"
RANLIB="${WASI_SDK_PATH}/bin/llvm-ranlib"
NM="${WASI_SDK_PATH}/bin/llvm-nm"

SOURCE_DIR="/build/staging/Pillow"
OUTPUT_DIR="/build/output"
PILLOW_TAG=$(cat /build/pillow_tag.txt)

log() { echo "==> $*"; }
die() { echo "!!! $*" >&2; exit 1; }

mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi" "${OUTPUT_DIR}/python"

MODE_AR="${OUTPUT_DIR}/lib/wasm32-wasi/lib_pillow_imaging_mode.a"
${AR} rcs "${MODE_AR}" /build/obj/pillow/libImaging/Mode.o
${RANLIB} "${MODE_AR}"

mapfile -t imaging_objs < <(find /build/obj/pillow/libImaging -name '*.o' ! -name 'Mode.o' -print | sort)
imaging_objs+=(/build/obj/pillow/ext/_imaging.o /build/obj/pillow/ext/decode.o /build/obj/pillow/ext/encode.o /build/obj/pillow/ext/map.o /build/obj/pillow/ext/display.o /build/obj/pillow/ext/outline.o /build/obj/pillow/ext/path.o)
IMAGING_AR="${OUTPUT_DIR}/lib/wasm32-wasi/lib_pillow_imaging.a"
printf '%s\n' "${imaging_objs[@]}" | xargs ${AR} rcs "${IMAGING_AR}"
${RANLIB} "${IMAGING_AR}"

MATH_AR="${OUTPUT_DIR}/lib/wasm32-wasi/lib_pillow_imagingmath.a"
${AR} rcs "${MATH_AR}" /build/obj/pillow/ext/_imagingmath.o
${RANLIB} "${MATH_AR}"

MORPH_AR="${OUTPUT_DIR}/lib/wasm32-wasi/lib_pillow_imagingmorph.a"
${AR} rcs "${MORPH_AR}" /build/obj/pillow/ext/_imagingmorph.o
${RANLIB} "${MORPH_AR}"

for check in \
    "${IMAGING_AR}:PyInit__imaging" \
    "${MATH_AR}:PyInit__imagingmath" \
    "${MORPH_AR}:PyInit__imagingmorph"; do
    IFS=':' read -r archive symbol <<<"${check}"
    if ! (${NM} "${archive}" 2>/dev/null || true) | grep -q "${symbol}"; then
        die "${symbol} missing from ${archive}"
    fi
    log "OK ${symbol} in ${archive##*/}"
done

cp -r "${SOURCE_DIR}/src/PIL" "${OUTPUT_DIR}/python/PIL"
find "${OUTPUT_DIR}/python/PIL" -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
find "${OUTPUT_DIR}/python/PIL" -type f \( -name '*.c' -o -name '*.h' \) -delete 2>/dev/null || true

cat > "${OUTPUT_DIR}/manifest.txt" <<MANIFEST
pillow_imaging PyInit__imaging $(ls -lh "${IMAGING_AR}" | awk '{print $5}')
pillow_imagingmath PyInit__imagingmath $(ls -lh "${MATH_AR}" | awk '{print $5}')
pillow_imagingmorph PyInit__imagingmorph $(ls -lh "${MORPH_AR}" | awk '{print $5}')
pillow_imaging_mode support $(ls -lh "${MODE_AR}" | awk '{print $5}')
MANIFEST

echo "${PILLOW_TAG}" > "${OUTPUT_DIR}/version.txt"

log "DONE. Manifest:"
cat "${OUTPUT_DIR}/manifest.txt"
log "Archives:"
ls -lh "${OUTPUT_DIR}/lib/wasm32-wasi/"
