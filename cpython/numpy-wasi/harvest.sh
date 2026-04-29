#!/usr/bin/env bash
set -euo pipefail

# harvest.sh: collect compiled .o files from the numpy meson build tree into
# per-extension static archives and verify the required PyInit_* symbols are
# present. Phase 1 gate for the aviator-cpython numpy-wasi plan.

WASI_SDK_PATH="${WASI_SDK_PATH:-/opt/wasi-sdk}"
AR="${WASI_SDK_PATH}/bin/llvm-ar"
RANLIB="${WASI_SDK_PATH}/bin/llvm-ranlib"
NM="${WASI_SDK_PATH}/bin/llvm-nm"

SOURCE_DIR="/build/staging/numpy"
OUTPUT_DIR="/build/output"
NUMPY_TAG=$(cat /build/numpy_tag.txt)

log() { echo "==> $*"; }
die() { echo "!!! $*" >&2; exit 1; }

cd "${SOURCE_DIR}"
mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi"

# Extension modules to ship. Each entry: <so.p dir name>:<archive name>:<expected PyInit symbol>
# Test-only modules (_multiarray_tests, _umath_tests, _rational_tests,
# _operand_flag_tests, _struct_ufunc_tests) are intentionally excluded.
EXTENSIONS=(
    "_multiarray_umath:numpy_multiarray_umath:PyInit__multiarray_umath"
    "_simd:numpy_simd:PyInit__simd"
    "_pocketfft_umath:numpy_pocketfft_umath:PyInit__pocketfft_umath"
    "_umath_linalg:numpy_umath_linalg:PyInit__umath_linalg"
    "lapack_lite:numpy_lapack_lite:PyInit_lapack_lite"
    "_mt19937:numpy_random_mt19937:PyInit__mt19937"
    "_philox:numpy_random_philox:PyInit__philox"
    "_pcg64:numpy_random_pcg64:PyInit__pcg64"
    "_sfc64:numpy_random_sfc64:PyInit__sfc64"
    "_common:numpy_random_common:PyInit__common"
    "_generator:numpy_random_generator:PyInit__generator"
    "_bounded_integers:numpy_random_bounded_integers:PyInit__bounded_integers"
    "bit_generator:numpy_random_bit_generator:PyInit_bit_generator"
    "mtrand:numpy_random_mtrand:PyInit_mtrand"
)

# --- diagnostics: which PyInit_ symbols exist anywhere in the build tree? ---
log "Scanning for PyInit_* symbols across all .o files..."
find build -name '*.o' -print0 | while IFS= read -r -d '' obj; do
    syms=$(${NM} "$obj" 2>/dev/null | grep -oE 'PyInit_[A-Za-z0-9_]+' || true)
    if [ -n "$syms" ]; then
        echo "$syms  $obj"
    fi
done | sort -u > /build/pyinit_map.txt
TOTAL_PYINIT=$(wc -l </build/pyinit_map.txt)
log "Total PyInit_* symbols discovered: ${TOTAL_PYINIT}"
cat /build/pyinit_map.txt

# --- harvest each extension ---
MANIFEST="${OUTPUT_DIR}/manifest.txt"
> "${MANIFEST}"
FAILED=()

for entry in "${EXTENSIONS[@]}"; do
    IFS=':' read -r so_name ar_name init_sym <<<"${entry}"

    # The build dir name is <ext>.cpython-<ver>-<triple>.so.p
    # Match on the so_name prefix + '.cpython-'.
    pattern="*/${so_name}.cpython-*.so.p"
    mapfile -t objs < <(find build -type d -path "${pattern}" -print0 2>/dev/null | \
        xargs -0 -I {} find {} -name '*.o' 2>/dev/null)

    if [ "${#objs[@]}" -eq 0 ]; then
        log "SKIP ${ar_name}: no .o files found (pattern=${pattern})"
        FAILED+=("${ar_name} (no objects)")
        continue
    fi

    ar_path="${OUTPUT_DIR}/lib/wasm32-wasi/lib_${ar_name}.a"
    printf '%s\n' "${objs[@]}" | xargs ${AR} rcs "${ar_path}"
    ${RANLIB} "${ar_path}"

    # Verify the PyInit symbol is present (avoid `nm|grep -q` pipefail issue).
    ${NM} "${ar_path}" > /tmp/syms.txt 2>/dev/null
    if ! grep -q "${init_sym}" /tmp/syms.txt; then
        log "FAIL ${ar_name}: ${init_sym} NOT found in ${ar_path}"
        FAILED+=("${ar_name} (missing ${init_sym})")
        continue
    fi

    size=$(ls -lh "${ar_path}" | awk '{print $5}')
    count="${#objs[@]}"
    echo "${ar_name} ${init_sym} ${size} objs=${count}" >> "${MANIFEST}"
    log "OK   ${ar_name}: ${init_sym} (${size}, ${count} objs)"
done

if [ "${#FAILED[@]}" -gt 0 ]; then
    log "FAILED extensions:"
    printf '  - %s\n' "${FAILED[@]}"
    die "${#FAILED[@]} extension(s) failed to build"
fi

# --- verify internal API symbols are defined, not just referenced.
# ninja -k 0 silently skips .cpp files that fail to compile (e.g.
# dispatching.cpp needing <shared_mutex>). The PyInit check above passes
# because the module entry point compiles fine — but internal functions
# like PyUFunc_AddLoop end up undefined. Catch that explicitly.
UMATH_AR="${OUTPUT_DIR}/lib/wasm32-wasi/lib_numpy_multiarray_umath.a"
REQUIRED_INTERNAL_SYMS=(
    PyUFunc_AddLoop
    PyUFunc_AddPromoter
    PyArrayIdentityHash_New
    promote_and_get_ufuncimpl
)
${NM} "${UMATH_AR}" > /tmp/umath_syms.txt 2>/dev/null
MISSING_INTERNAL=()
for sym in "${REQUIRED_INTERNAL_SYMS[@]}"; do
    if ! grep -q " T ${sym}$" /tmp/umath_syms.txt; then
        MISSING_INTERNAL+=("${sym}")
    fi
done
if [ "${#MISSING_INTERNAL[@]}" -gt 0 ]; then
    log "INTERNAL SYMBOL CHECK FAILED — these symbols are undefined:"
    printf '  - %s\n' "${MISSING_INTERNAL[@]}"
    log "This usually means a .cpp file failed to compile (check ninja.log for FAILED: ...*.o)"
    die "required internal symbols missing from ${UMATH_AR##*/}"
fi
log "Internal symbol check passed (${#REQUIRED_INTERNAL_SYMS[@]} symbols verified)"

# --- ship numpy's internal static libs (npymath, highway, dispatch tables).
# These are the helper libs every numpy extension module statically links
# against (libnpymath for npy_half_to_*, libnpyrandom for random distribution
# helpers, libhighway/SIMD dispatch stubs).
#
# Meson emits these as *thin archives* that reference .o files on disk by path.
# Thin archives cannot be re-added via `ar -M addlib` from another machine —
# llvm-ar tries to read the stored .o paths and fails. So we materialize a
# single fat archive containing every .o referenced by these thin archives.
log "Harvesting internal numpy static libraries (materializing thin->fat)..."
INTERNAL_OBJS=()
while IFS= read -r src_a; do
    base=$(basename "${src_a}")
    case "${base}" in
        *_tests*) continue ;;
    esac
    # Each meson static_library lives in <name>.a.p/ alongside the .a — that
    # directory contains all the .o files the thin archive references.
    pdir="${src_a}.p"
    if [ ! -d "${pdir}" ]; then
        log "  skip ${base}: no .p directory at ${pdir}"
        continue
    fi
    mapfile -t objs < <(find "${pdir}" -name '*.o' 2>/dev/null)
    if [ "${#objs[@]}" -eq 0 ]; then
        log "  skip ${base}: no .o files in ${pdir}"
        continue
    fi
    log "  ${base}: ${#objs[@]} objs"
    INTERNAL_OBJS+=("${objs[@]}")
done < <(find build/numpy -name '*.a' -not -path '*/subprojects/*' 2>/dev/null | sort -u)

if [ "${#INTERNAL_OBJS[@]}" -eq 0 ]; then
    die "no internal numpy objects found — was the meson compile step skipped?"
fi

INTERNAL_AR="${OUTPUT_DIR}/lib/wasm32-wasi/lib_numpy_internal.a"
printf '%s\n' "${INTERNAL_OBJS[@]}" | xargs ${AR} rcs "${INTERNAL_AR}"
${RANLIB} "${INTERNAL_AR}"
size=$(ls -lh "${INTERNAL_AR}" | awk '{print $5}')
echo "numpy_internal (npymath/highway/dispatch) ${size} objs=${#INTERNAL_OBJS[@]}" >> "${MANIFEST}"
log "  ${INTERNAL_AR##*/}: ${size} (${#INTERNAL_OBJS[@]} objs)"

# --- publish patched numpy headers for downstream lib-wasi modules.
# pandas/scipy/matplotlib-wasi all compile C extensions that `#include
# <numpy/arrayobject.h>` and transitively pull in numpy's npy_cpu.h (which
# needs our __wasm32__ branch) and numpy's generated `_numpyconfig.h` (which
# carries target-specific NPY_SIZEOF_* values that drive the npy_int*
# typedef chain in `npy_common.h`). Both must come from the wasm32 build,
# not the LP64 host. Publish the patched+generated header tree so consumers
# overlay it onto their host numpy install and point meson at it.
# ~600 KB of headers, cheap.
log "Publishing patched numpy headers to include/numpy/ ..."
mkdir -p "${OUTPUT_DIR}/include"
cp -r numpy/_core/include/numpy "${OUTPUT_DIR}/include/numpy"
# meson-generated _numpyconfig.h lives in the build tree, not the source
# tree. It has the wasm32-specific NPY_SIZEOF_LONG=4 etc. Without it,
# consumers pick up the host LP64 copy and pandas's npy_int64 typedef
# resolves to `long` (4 bytes) instead of `long long` (8), breaking
# every Cython buffer check that uses int64_t.
gen_numpyconfig=$(find build -name '_numpyconfig.h' -not -path '*/subprojects/*' 2>/dev/null | head -1)
if [ -z "${gen_numpyconfig}" ]; then
    die "_numpyconfig.h not found in build tree — numpy's meson configure didn't generate it"
fi
log "  copying generated _numpyconfig.h from ${gen_numpyconfig}"
cp "${gen_numpyconfig}" "${OUTPUT_DIR}/include/numpy/_numpyconfig.h"
grep "NPY_SIZEOF_LONG " "${OUTPUT_DIR}/include/numpy/_numpyconfig.h" || true
ls "${OUTPUT_DIR}/include/numpy/" | head -20

# --- stage python sources ---
log "Staging numpy Python sources..."
mkdir -p "${OUTPUT_DIR}/python"
cp -r numpy "${OUTPUT_DIR}/python/numpy"
find "${OUTPUT_DIR}/python/numpy" -type f \( -name '*.c' -o -name '*.h' -o -name '*.cpp' -o -name '*.hpp' \) -delete
rm -rf "${OUTPUT_DIR}/python/numpy/_core/src" 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/numpy/_core/include" 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/numpy/random/src" 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/numpy/fft/pocketfft" 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/numpy/linalg/lapack_lite" 2>/dev/null || true

# Copy meson-generated version.py and __config__.py from the build tree —
# these are normally produced at build time and aren't in the source tree.
for gen in version.py __config__.py; do
    if [ -f "build/numpy/${gen}" ]; then
        cp "build/numpy/${gen}" "${OUTPUT_DIR}/python/numpy/${gen}"
        log "  copied generated numpy/${gen}"
    fi
done

echo "${NUMPY_TAG}" > "${OUTPUT_DIR}/version.txt"
cp /build/pyinit_map.txt "${OUTPUT_DIR}/pyinit_map.txt"

log "DONE. Manifest:"
cat "${MANIFEST}"
log "Archives:"
ls -lh "${OUTPUT_DIR}/lib/wasm32-wasi/"
