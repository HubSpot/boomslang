#!/usr/bin/env bash
set -euo pipefail

# harvest.sh: collect compiled .o files from the pandas meson build tree into
# per-extension static archives and verify PyInit_* symbols are present.
# Phase 1 gate for the aviator-cpython pandas-wasi plan.

WASI_SDK_PATH="${WASI_SDK_PATH:-/opt/wasi-sdk}"
AR="${WASI_SDK_PATH}/bin/llvm-ar"
RANLIB="${WASI_SDK_PATH}/bin/llvm-ranlib"
NM="${WASI_SDK_PATH}/bin/llvm-nm"

SOURCE_DIR="/build/staging/pandas"
OUTPUT_DIR="/build/output"
PANDAS_TAG=$(cat /build/pandas_tag.txt)

log() { echo "==> $*"; }
die() { echo "!!! $*" >&2; exit 1; }

cd "${SOURCE_DIR}"
mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi"

# Extension modules to ship. Entry format: <so-prefix>:<archive tag>:<expected PyInit symbol>
# <so-prefix> matches the meson build-dir name "<so-prefix>.cpython-*.so.p".
# Pandas groups into three namespaces:
#   pandas._libs.*         — the bulk of the Cython code
#   pandas._libs.tslibs.*  — timestamp / timezone / period primitives
#   pandas._libs.window.*  — rolling/expanding window kernels
# Plus the Cython 3.1 shared-utility module _cyutility.
EXTENSIONS=(
    # pandas._libs.*
    "algos:pandas_algos:PyInit_algos"
    "arrays:pandas_arrays:PyInit_arrays"
    "byteswap:pandas_byteswap:PyInit_byteswap"
    "groupby:pandas_groupby:PyInit_groupby"
    "hashing:pandas_hashing:PyInit_hashing"
    "hashtable:pandas_hashtable:PyInit_hashtable"
    "index:pandas_index:PyInit_index"
    "indexing:pandas_indexing:PyInit_indexing"
    "internals:pandas_internals:PyInit_internals"
    "interval:pandas_interval:PyInit_interval"
    "join:pandas_join:PyInit_join"
    "json:pandas_json:PyInit_json"
    "lib:pandas_lib:PyInit_lib"
    "missing:pandas_missing:PyInit_missing"
    "ops:pandas_ops:PyInit_ops"
    "ops_dispatch:pandas_ops_dispatch:PyInit_ops_dispatch"
    "pandas_datetime:pandas_pandas_datetime:PyInit_pandas_datetime"
    "pandas_parser:pandas_pandas_parser:PyInit_pandas_parser"
    "parsers:pandas_parsers:PyInit_parsers"
    "properties:pandas_properties:PyInit_properties"
    "reshape:pandas_reshape:PyInit_reshape"
    "sas:pandas_sas:PyInit_sas"
    "sparse:pandas_sparse:PyInit_sparse"
    "testing:pandas_testing:PyInit_testing"
    "tslib:pandas_tslib:PyInit_tslib"
    "writers:pandas_writers:PyInit_writers"
    "_cyutility:pandas_cyutility:PyInit__cyutility"
    # pandas._libs.tslibs.*
    "base:pandas_tslibs_base:PyInit_base"
    "ccalendar:pandas_tslibs_ccalendar:PyInit_ccalendar"
    "conversion:pandas_tslibs_conversion:PyInit_conversion"
    "dtypes:pandas_tslibs_dtypes:PyInit_dtypes"
    "fields:pandas_tslibs_fields:PyInit_fields"
    "nattype:pandas_tslibs_nattype:PyInit_nattype"
    "np_datetime:pandas_tslibs_np_datetime:PyInit_np_datetime"
    "offsets:pandas_tslibs_offsets:PyInit_offsets"
    "parsing:pandas_tslibs_parsing:PyInit_parsing"
    "period:pandas_tslibs_period:PyInit_period"
    "strptime:pandas_tslibs_strptime:PyInit_strptime"
    "timedeltas:pandas_tslibs_timedeltas:PyInit_timedeltas"
    "timestamps:pandas_tslibs_timestamps:PyInit_timestamps"
    "timezones:pandas_tslibs_timezones:PyInit_timezones"
    "tzconversion:pandas_tslibs_tzconversion:PyInit_tzconversion"
    "vectorized:pandas_tslibs_vectorized:PyInit_vectorized"
    # pandas._libs.window.*
    "aggregations:pandas_window_aggregations:PyInit_aggregations"
    "indexers:pandas_window_indexers:PyInit_indexers"
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

    # The build dir name is <ext>.cpython-<ver>-<triple>.so.p alongside a .so.
    # <so-prefix> is globally unique across pandas (verified by inspection of
    # v3.0.2); if that ever changes, disambiguate on parent meson subdir.
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

# --- check for internal meson static libs (pandas doesn't declare any today,
# but this surfaces them for manual review if a future release adds some).
mapfile -t internal_archives < <(find build -name '*.a' -not -path '*/subprojects/*' 2>/dev/null | sort -u)
if [ "${#internal_archives[@]}" -gt 0 ]; then
    log "Found internal archives (review harvest.sh — may need thin->fat like numpy_internal):"
    printf '  %s\n' "${internal_archives[@]}"
fi

# --- collision audit: report any T-symbols defined in BOTH pandas archives
# and the upstream numpy-wasi archives. wasm-ld will refuse to link those as
# duplicate symbols, and each one needs a rename in patch.py. Surface them
# all at once instead of discovering them one rebuild at a time.
#
# We don't actually have libpython3.14.a here (cpython-wasi merges later), so
# we audit against numpy-wasi's own archives that we vendored. That catches
# the same collisions (numpy's symbols end up in libpython via the same path).
log "Auditing pandas <-> numpy symbol collisions..."
COLLISIONS=()
if [ -f /tmp/numpy-wasi/lib/wasm32-wasi/lib_numpy_multiarray_umath.a ]; then
    # Gather all global T symbols from numpy archives into one file for fast grep.
    numpy_syms=/tmp/numpy_T_syms.txt
    > "${numpy_syms}"
    for npa in /tmp/numpy-wasi/lib/wasm32-wasi/lib_numpy_*.a; do
        ${NM} "${npa}" 2>/dev/null | awk '$2=="T"{print $3}' >> "${numpy_syms}"
    done
    sort -u -o "${numpy_syms}" "${numpy_syms}"
    log "  numpy exports $(wc -l <${numpy_syms}) T symbols"
    for pa in "${OUTPUT_DIR}/lib/wasm32-wasi/"lib_pandas_*.a; do
        # comm -12 = lines present in both
        pandas_syms=$(${NM} "${pa}" 2>/dev/null | awk '$2=="T"{print $3}' | sort -u)
        dupes=$(comm -12 <(printf '%s\n' "${pandas_syms}") "${numpy_syms}")
        if [ -n "${dupes}" ]; then
            while IFS= read -r sym; do
                COLLISIONS+=("${pa##*/}:${sym}")
            done <<<"${dupes}"
        fi
    done
    if [ "${#COLLISIONS[@]}" -gt 0 ]; then
        log "!!! ${#COLLISIONS[@]} symbol collision(s) with numpy — add a #define rename to patch.py:"
        printf '  - %s\n' "${COLLISIONS[@]}"
        die "symbol collisions detected; fix in pandas-wasi/patch.py and rebuild"
    fi
    log "  no collisions detected"
else
    log "  (skipping audit — numpy-wasi artifact not extracted at /tmp/numpy-wasi)"
fi

# --- patch pure-Python sources for wasi runtime compatibility.
# cpython-wasi does not ship _ctypes (libffi isn't ported). Several pandas
# modules import ctypes at top level but only reference it inside Windows or
# arrow-interchange branches that WASI never hits. Wrap the imports so they
# degrade gracefully: the module still loads; attempting to *use* ctypes
# raises later if code actually hits it.
log "Applying pure-Python patches (defensive ctypes imports)..."
python3 - <<'PYPATCH'
from pathlib import Path

CTYPES_TARGETS = [
    "pandas/errors/__init__.py",
    "pandas/core/interchange/from_dataframe.py",
    "pandas/io/clipboard/__init__.py",
]
SENTINEL = "# aviator-cpython: ctypes optional"
WRAPPER = (
    f"{SENTINEL}\n"
    "try:\n"
    "    import ctypes\n"
    "except ImportError:\n"
    "    ctypes = None  # type: ignore[assignment]\n"
)
for rel in CTYPES_TARGETS:
    path = Path(rel)
    if not path.exists():
        print(f"  {path}: not present, skipping")
        continue
    src = path.read_text()
    if SENTINEL in src:
        print(f"  {path}: already patched")
        continue
    if "import ctypes\n" not in src:
        print(f"  {path}: no top-level `import ctypes` — leave alone")
        continue
    path.write_text(src.replace("import ctypes\n", WRAPPER, 1))
    print(f"  {path}: ctypes import wrapped")
PYPATCH

# --- stage python sources ---
log "Staging pandas Python sources..."
mkdir -p "${OUTPUT_DIR}/python"
cp -r pandas "${OUTPUT_DIR}/python/pandas"

# Strip build/source artifacts we don't ship at runtime:
#   - .c/.h/.cpp/.hpp: compiled; .py side imports via PyInit inittab
#   - .pyx/.pxd/.pxi/.pxi.in: Cython sources/templates (not runnable)
#   - _libs/src, _libs/include, _libs/tslibs/src: raw C trees
find "${OUTPUT_DIR}/python/pandas" -type f \
    \( -name '*.c' -o -name '*.h' -o -name '*.cpp' -o -name '*.hpp' \
       -o -name '*.pyx' -o -name '*.pxd' -o -name '*.pxi' -o -name '*.pxi.in' \) -delete
rm -rf "${OUTPUT_DIR}/python/pandas/_libs/src" 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/pandas/_libs/include" 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/pandas/_libs/tslibs/src" 2>/dev/null || true

# Drop tests/ — pandas' test suite is huge and the sandbox doesn't need it.
rm -rf "${OUTPUT_DIR}/python/pandas/tests" 2>/dev/null || true

# Copy the meson-generated _version_meson.py (produced by `generate_version.py`
# into the build tree). Without it, `import pandas` fails to resolve
# pandas.__version__.
if [ -f "build/_version_meson.py" ]; then
    cp "build/_version_meson.py" "${OUTPUT_DIR}/python/pandas/_version_meson.py"
    log "  copied generated pandas/_version_meson.py"
elif [ -f "pandas/_version_meson.py" ]; then
    log "  pandas/_version_meson.py already in source tree"
else
    log "  WARNING: _version_meson.py not found — pandas may fail to import"
fi

echo "${PANDAS_TAG}" > "${OUTPUT_DIR}/version.txt"
cp /build/pyinit_map.txt "${OUTPUT_DIR}/pyinit_map.txt"

log "DONE. Manifest:"
cat "${MANIFEST}"
log "Archives:"
ls -lh "${OUTPUT_DIR}/lib/wasm32-wasi/"
