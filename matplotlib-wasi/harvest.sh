#!/usr/bin/env bash
set -euo pipefail

# harvest.sh: collect compiled .o files from the matplotlib meson build tree
# into per-extension static archives, plus Agg + freetype support archives,
# and verify PyInit_* symbols are present.
#
# mpld3-only scope: three extensions (_c_internal_utils, _path, ft2font) plus
# Agg (used by _path) and freetype (used by ft2font). _backend_agg, _image,
# _tri, _qhull, _ttconv, _tkagg, _macosx are disabled by patch.py.

WASI_SDK_PATH="${WASI_SDK_PATH:-/opt/wasi-sdk}"
AR="${WASI_SDK_PATH}/bin/llvm-ar"
RANLIB="${WASI_SDK_PATH}/bin/llvm-ranlib"
NM="${WASI_SDK_PATH}/bin/llvm-nm"

SOURCE_DIR="/build/staging/matplotlib"
OUTPUT_DIR="/build/output"
MATPLOTLIB_TAG=$(cat /build/matplotlib_tag.txt)

log() { echo "==> $*"; }
die() { echo "!!! $*" >&2; exit 1; }

cd "${SOURCE_DIR}"
mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi"

# Extension modules to ship. Format: <so-prefix>:<archive tag>:<expected PyInit symbol>
# <so-prefix> matches the meson build-dir name "<so-prefix>.cpython-*.so.p".
EXTENSIONS=(
    "_c_internal_utils:matplotlib_c_internal_utils:PyInit__c_internal_utils"
    "_path:matplotlib_path:PyInit__path"
    "ft2font:matplotlib_ft2font:PyInit_ft2font"
    "_image:matplotlib_image:PyInit__image"
    "_backend_agg:matplotlib_backend_agg:PyInit__backend_agg"
)
# ft2font + _image + _backend_agg are enabled now that we build freetype
# with a setjmp shim and consume WLR-upstream libpng/libz. The shipped
# archives pull in freetype/libpng/libz via the support-archive harvest
# below (lib_freetype.a, lib_png.a, lib_z.a).

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

# --- harvest Agg and freetype support libs.
# _path links against Agg (matplotlib/extern/agg24-svn). meson produces a
# static library for it under build/extern/ or build/subprojects/. We need to
# find all .o files that are NOT already part of an extension, and include the
# ones used by our shipped extensions.
#
# Strategy: any archive/lib in build/ that's not a pybind11 wrapper module
# contains Agg or freetype support code. Aggregate them into two named
# archives.

log "Harvesting Agg support .o files..."
AGG_AR="${OUTPUT_DIR}/lib/wasm32-wasi/lib_matplotlib_agg.a"
mapfile -t agg_objs < <(find build -path '*/extern/*' -name '*.o' 2>/dev/null)
if [ "${#agg_objs[@]}" -gt 0 ]; then
    printf '%s\n' "${agg_objs[@]}" | xargs ${AR} rcs "${AGG_AR}"
    ${RANLIB} "${AGG_AR}"
    agg_size=$(ls -lh "${AGG_AR}" | awk '{print $5}')
    log "OK   agg: ${#agg_objs[@]} objs, ${agg_size}"
    echo "matplotlib_agg (support) ${agg_size} objs=${#agg_objs[@]}" >> "${MANIFEST}"
else
    log "WARN: no Agg .o files found under build/extern/"
fi

# Stage freetype + libpng static archives so cpython-wasi's ar -M merge pulls
# them into the final libpython3.14.a. ft2font references libfreetype; Agg
# references libpng for PNG I/O. libz is already supplied by cpython-wasi's
# own deps download so we don't re-ship it (would cause duplicate symbols).
log "Staging freetype + libpng support archives..."
for candidate in /build/wasi-libs/lib/libfreetype.a \
                 /build/wasi-libs/lib/wasm32-wasi/libfreetype.a; do
    if [ -f "${candidate}" ]; then
        cp "${candidate}" "${OUTPUT_DIR}/lib/wasm32-wasi/lib_freetype.a"
        log "  staged lib_freetype.a ($(ls -lh ${candidate} | awk '{print $5}'))"
        break
    fi
done

for candidate in /build/wasi-libs/lib/wasm32-wasi/libpng16.a \
                 /build/wasi-libs/lib/libpng16.a \
                 /build/wasi-libs/lib/wasm32-wasi/libpng.a \
                 /build/wasi-libs/lib/libpng.a; do
    if [ -f "${candidate}" ]; then
        cp "${candidate}" "${OUTPUT_DIR}/lib/wasm32-wasi/lib_png.a"
        log "  staged lib_png.a ($(ls -lh ${candidate} | awk '{print $5}'))"
        break
    fi
done

test -f "${OUTPUT_DIR}/lib/wasm32-wasi/lib_freetype.a" || \
    die "lib_freetype.a missing — ft2font will fail to link"
test -f "${OUTPUT_DIR}/lib/wasm32-wasi/lib_png.a" || \
    die "lib_png.a missing — _backend_agg will fail to link"

# --- surface any internal static libs meson produced (sanity) ---
mapfile -t internal_archives < <(find build -name '*.a' 2>/dev/null | sort -u)
if [ "${#internal_archives[@]}" -gt 0 ]; then
    log "Internal archives produced by meson (for reference):"
    printf '  %s\n' "${internal_archives[@]}"
fi

# --- collision audit: report any T-symbols defined in BOTH matplotlib
# archives and the upstream numpy-wasi archives. wasm-ld will refuse to link
# duplicates in the merged libpython, so surface them all at once.
log "Auditing matplotlib <-> numpy symbol collisions..."
COLLISIONS=()
if [ -f /tmp/numpy-wasi/lib/wasm32-wasi/lib_numpy_multiarray_umath.a ]; then
    numpy_syms=/tmp/numpy_T_syms.txt
    > "${numpy_syms}"
    for npa in /tmp/numpy-wasi/lib/wasm32-wasi/lib_numpy_*.a; do
        ${NM} "${npa}" 2>/dev/null | awk '$2=="T"{print $3}' >> "${numpy_syms}"
    done
    sort -u -o "${numpy_syms}" "${numpy_syms}"
    log "  numpy exports $(wc -l <${numpy_syms}) T symbols"
    for pa in "${OUTPUT_DIR}/lib/wasm32-wasi/"lib_matplotlib_*.a; do
        matplotlib_syms=$(${NM} "${pa}" 2>/dev/null | awk '$2=="T"{print $3}' | sort -u)
        dupes=$(comm -12 <(printf '%s\n' "${matplotlib_syms}") "${numpy_syms}")
        if [ -n "${dupes}" ]; then
            while IFS= read -r sym; do
                COLLISIONS+=("${pa##*/}:${sym}")
            done <<<"${dupes}"
        fi
    done
    if [ "${#COLLISIONS[@]}" -gt 0 ]; then
        log "!!! ${#COLLISIONS[@]} symbol collision(s) with numpy — add a rename to patch.py:"
        printf '  - %s\n' "${COLLISIONS[@]}"
        die "symbol collisions detected; fix in matplotlib-wasi/patch.py and rebuild"
    fi
    log "  no collisions detected"
else
    log "  (skipping audit — numpy-wasi artifact not extracted at /tmp/numpy-wasi)"
fi

# --- stage python sources ---
log "Staging matplotlib Python sources..."
mkdir -p "${OUTPUT_DIR}/python"

# ---- Patch matplotlib Python sources for wasm32-wasi.
# Three edits, all baked into the artifact so cpython-wasi ships the fixed
# files and no runtime monkey-patching is needed on the aviator side:
#
# 1. font_manager.py — FontManager.__init__ uses threading.Timer to schedule
#    a "building cache" warning. WASI has no threads; threading.Thread.start()
#    raises "can't start new thread". Replace the Timer with a no-op.
#
# 2. path.py — Path.__deepcopy__ does `copy.deepcopy(super(), memo)` which
#    recurses infinitely on Python 3.14 (super() proxy dispatches back to
#    the subclass's __deepcopy__). Reconstruct the Path directly. Same fix
#    shape as matplotlib's own upstream patch.
#
# 3. cbook.py (if applicable) — reserved for future fixes.
log "Patching matplotlib Python sources for WASI..."
python3 - <<'PYPATCH'
from pathlib import Path

# --- font_manager.py: Timer thread no-op ---
fm = Path("lib/matplotlib/font_manager.py")
src = fm.read_text()
m1 = "# aviator-cpython: WASI no-threads Timer"
if m1 not in src:
    old = (
        "        # Delay the warning by 5s.\n"
        "        timer = threading.Timer(5, lambda: _log.warning(\n"
        "            'Matplotlib is building the font cache; this may take a moment.'))\n"
        "        timer.start()\n"
    )
    new = (
        f"        {m1}\n"
        "        class _NoopTimer:\n"
        "            def cancel(self): pass\n"
        "        timer = _NoopTimer()\n"
    )
    if old in src:
        fm.write_text(src.replace(old, new))
        print(f"  {fm}: Timer -> _NoopTimer")
    else:
        print(f"  WARN: {fm} Timer pattern not found")
else:
    print(f"  {fm}: already patched")

# --- path.py: fix __deepcopy__ for Python 3.14 ---
pp = Path("lib/matplotlib/path.py")
src = pp.read_text()
m2 = "# aviator-cpython: Python 3.14 super()-deepcopy fix"
if m2 not in src:
    old = (
        "        # Deepcopying arrays (vertices, codes) strips the writeable=False flag.\n"
        "        p = copy.deepcopy(super(), memo)\n"
        "        p._readonly = False\n"
        "        return p\n"
    )
    new = (
        f"        {m2}\n"
        "        # copy.deepcopy(super(), memo) recurses infinitely on Python 3.14\n"
        "        # because the super() proxy dispatches __deepcopy__ back to the\n"
        "        # subclass. Rebuild the Path directly — closed is only used in\n"
        "        # __init__ when codes is None, which we handle by passing codes.\n"
        "        vertices = self._vertices.copy()\n"
        "        codes = self._codes.copy() if self._codes is not None else None\n"
        "        p = self.__class__(\n"
        "            vertices, codes,\n"
        "            _interpolation_steps=self._interpolation_steps,\n"
        "        )\n"
        "        p._readonly = False\n"
        "        return p\n"
    )
    if old in src:
        pp.write_text(src.replace(old, new))
        print(f"  {pp}: __deepcopy__ patched for Python 3.14")
    else:
        print(f"  WARN: {pp} __deepcopy__ pattern not found")
else:
    print(f"  {pp}: already patched")
PYPATCH

# ---- Patch mpld3's mplexporter to skip the PNG-roundtrip calibration step.
# mpld3 0.5.x's Exporter.run() does fig.savefig(io.BytesIO(), format='png')
# to force a draw pass. That requires Pillow's _imaging C ext to encode the
# PNG — we don't ship Pillow (its C exts won't load on wasm). Replace with
# fig.draw_without_rendering() which runs the same draw chain without PNG
# encoding.
log "Patching mpld3 exporter to skip PNG roundtrip..."
python3 - <<'PYPATCH'
from pathlib import Path
import glob
# mpld3 is staged later in the wheel-unpack step — patch it after we stage.
# But we can't know the path yet here; do it in the wheel-unpack loop below.
print("  mpld3 patch deferred to wheel-unpack stage")
PYPATCH

# matplotlib's python tree lives at lib/matplotlib/. Copy it under the final
# python/matplotlib/ directory so cpython-wasi's consumer step can drop it
# straight into usr/local/lib/python3.14/.
if [ -d "lib/matplotlib" ]; then
    cp -r lib/matplotlib "${OUTPUT_DIR}/python/matplotlib"
    # mpl_toolkits is a sibling top-level package under lib/
    if [ -d "lib/mpl_toolkits" ]; then
        cp -r lib/mpl_toolkits "${OUTPUT_DIR}/python/mpl_toolkits"
    fi
else
    die "lib/matplotlib/ not found — matplotlib source tree shape changed?"
fi

# Copy the meson-generated _version.py (setuptools_scm produces it at setup).
if [ -f "build/lib/matplotlib/_version.py" ]; then
    cp "build/lib/matplotlib/_version.py" "${OUTPUT_DIR}/python/matplotlib/_version.py"
    log "  copied generated matplotlib/_version.py"
elif [ -f "lib/matplotlib/_version.py" ]; then
    log "  matplotlib/_version.py already in source tree"
else
    log "  WARNING: _version.py not found — matplotlib may fail to import"
fi

# Strip build/source artifacts we don't ship at runtime:
#   - .c/.h/.cpp/.hpp: the C sources we compiled; .py side uses PyInit inittab
#   - tests/: huge; not needed in sandbox
find "${OUTPUT_DIR}/python/matplotlib" -type f \
    \( -name '*.c' -o -name '*.h' -o -name '*.cpp' -o -name '*.hpp' \) -delete
rm -rf "${OUTPUT_DIR}/python/matplotlib/tests" 2>/dev/null || true
rm -rf "${OUTPUT_DIR}/python/mpl_toolkits/tests" 2>/dev/null || true

# matplotlib ships font files in mpl-data/fonts/. Even with ft2font stubbed
# we keep them — the fontlist cache references their paths (the stub never
# reads them).
if [ -d "${OUTPUT_DIR}/python/matplotlib/mpl-data/fonts" ]; then
    font_count=$(find "${OUTPUT_DIR}/python/matplotlib/mpl-data/fonts" -name '*.ttf' | wc -l)
    log "  mpl-data/fonts: ${font_count} .ttf files"
fi


# --- mpld3 + its Python deps (jinja2 already in host) ---
# mpld3 is pure-Python. Pip-download the wheel, unpack, strip tests.
# jinja2 is likewise pure-Python; MarkupSafe is a transitive dep, also pure-Python
# since the pure-python path is selected when the C speedup wheel fails.
log "Downloading mpld3 from GitHub (not in HubSpot pip mirror)..."
# mpld3 isn't mirrored in HubSpot's internal pip index; pip download exits
# with "Connection refused" from inside Blazar. Pull it from github.com
# (Blazar-whitelisted) as a source tarball and stage the package dir
# directly. Its pure-Python submodule mplexporter gets picked up too.
mkdir -p /tmp/mpld3_src
curl -fsSL "https://github.com/mpld3/mpld3/archive/refs/tags/v0.5.10.tar.gz" \
    | tar xz -C /tmp/mpld3_src
test -d /tmp/mpld3_src/mpld3-0.5.10/mpld3 \
    || die "mpld3 GitHub extract failed — no mpld3/ dir at expected path"
cp -r /tmp/mpld3_src/mpld3-0.5.10/mpld3 "${OUTPUT_DIR}/python/mpld3"
log "  staged mpld3 from github (v0.5.10 tag)"

log "Downloading matplotlib pure-Python deps from pip mirror..."
WHEELS_DIR="${OUTPUT_DIR}/wheels"
mkdir -p "${WHEELS_DIR}"
# All of these ARE in HubSpot's internal pip mirror (they're common deps).
# Download each wheel separately so a transient pip failure for one doesn't
# silently leave the rest working. Fail fast on any missing wheel —
# matplotlib.__init__ does `from packaging.version import parse` and won't
# import without them.
PYPI_PKGS=(
    'jinja2'
    'markupsafe'
    'packaging'
    'cycler'
    'pyparsing'
    'fonttools'
    'python-dateutil'
)
# contourpy is deliberately NOT in this list. It's a C extension with no
# pre-built wasm32-wasi wheel, so `pip download` falls through to the sdist
# and tries to build from source — which fails because the builder image
# doesn't have a python-dev target matching wasm32-wasi. matplotlib uses
# contourpy only for plt.contour/contourf; mpld3 never invokes either.
# Matplotlib's module-level `from contourpy import contour_generator` is in
# a function body guarded by lazy import, so matplotlib imports cleanly
# without it.
# --prefer-binary tells pip to avoid sdist builds even for packages that
# have both. fonttools has optional C extensions; the manylinux wheel we
# get here has them pre-compiled for host which are irrelevant, but pip
# download just grabs the wheel without trying to use them.
for pkg in "${PYPI_PKGS[@]}"; do
    log "  pip3 download $pkg"
    pip3 download --no-deps --prefer-binary --dest "${WHEELS_DIR}" "$pkg" \
        || die "pip3 download failed for $pkg — is pip.conf / internal mirror reachable?"
done
ls "${WHEELS_DIR}"/packaging-*.whl >/dev/null 2>&1 \
    || die "packaging wheel missing from ${WHEELS_DIR}; wheel pipeline is broken"
log "  downloaded wheels:"
ls -1 "${WHEELS_DIR}" | sed 's/^/    /'
# mpld3 0.5.10 is pinned because 0.5.12 (the current release) calls
# `axis.get_converter()` — a method added in matplotlib 3.10. We ship
# matplotlib 3.9.3 (3.10 requires pybind11>=2.13 which pulls <thread>,
# unusable with wasi-sdk 20's libc++). 0.5.10 uses the `.converter`
# attribute directly, which works with 3.9.
#
# kiwisolver + Pillow are skipped: both ship C-extension wheels that won't
# run on wasm32-wasi. cpython-wasi provides pure-Python stubs alongside;
# matplotlib's layout engine and imshow path fall back gracefully when
# those exts are absent. mpld3 never hits them.
# matplotlib's pure-Python deps at module import time:
#   packaging  — used in matplotlib.__init__ for version parsing
#   cycler     — rcParams['axes.prop_cycle'] is a Cycler
#   pyparsing  — mathtext parser
#   kiwisolver — layout engine (actually a C ext upstream; pure-Python
#                fallback wheel ships no binaries on wasm anyway; the
#                import will fail if anything really uses the C path, but
#                matplotlib checks for its availability lazily)
#   fonttools  — pure-Python font I/O
#   contourpy  — contour code (C ext upstream; same as kiwisolver above,
#                optional at import time)
#   pillow     — PIL binding (image I/O; optional for mpld3)
# Our mpld3 target doesn't rasterize or contour, so a missing kiwisolver/
# contourpy/pillow at a later call should only fail the feature that uses
# them, not matplotlib's import.

# Unpack whatever we got into python/ so cpython-wasi ships them.
for whl in "${WHEELS_DIR}"/*.whl; do
    [ -f "$whl" ] || continue
    pkg=$(basename "$whl" | cut -d- -f1 | tr '_' '.')
    log "  unpacking $whl"
    mkdir -p /tmp/whl_extract
    (cd /tmp/whl_extract && python3 -m zipfile -e "$whl" .)
    # Wheel layout: package/<pkg>/..., but some wheels have different top-level names
    for top in /tmp/whl_extract/*/; do
        name=$(basename "$top")
        # Skip metadata directories
        case "$name" in
            *.dist-info|*.data) continue ;;
        esac
        cp -r "$top" "${OUTPUT_DIR}/python/"
        log "    staged ${OUTPUT_DIR}/python/${name}"
    done
    rm -rf /tmp/whl_extract
done

# ---- Patch mpld3's mplexporter after unpack.
# Exporter.run() does fig.savefig(io.BytesIO(), format='png') as a draw-
# trigger hack. That path requires Pillow's PNG encoder (we don't ship a
# working Pillow). Replace with fig.draw_without_rendering() which runs the
# same artist-draw chain without PNG encoding.
EXPORTER="${OUTPUT_DIR}/python/mpld3/mplexporter/exporter.py"
if [ -f "${EXPORTER}" ]; then
    log "Patching mpld3 mplexporter exporter.py..."
    python3 - <<PYPATCH
from pathlib import Path
p = Path("${EXPORTER}")
src = p.read_text()
m = "# aviator-cpython: skip PNG savefig"
if m in src:
    print(f"  {p}: already patched")
else:
    old = (
        "        # Calling savefig executes the draw() command, putting elements\n"
        "        # in the correct place.\n"
        "        if fig.canvas is None:\n"
        "            canvas = FigureCanvasAgg(fig)\n"
        "        fig.savefig(io.BytesIO(), format='png', dpi=fig.dpi)\n"
    )
    new = (
        f"        {m}\n"
        "        # savefig(..., format='png') needs Pillow's PNG encoder, which\n"
        "        # we don't ship. draw_without_rendering() runs the same draw()\n"
        "        # chain (layout, text metrics, etc.) without the encode at the end.\n"
        "        if fig.canvas is None:\n"
        "            canvas = FigureCanvasAgg(fig)\n"
        "        try:\n"
        "            fig.draw_without_rendering()\n"
        "        except AttributeError:\n"
        "            from matplotlib.backends.backend_agg import FigureCanvasAgg as _C\n"
        "            _C(fig).draw()\n"
    )
    if old in src:
        p.write_text(src.replace(old, new))
        print(f"  {p}: savefig -> draw_without_rendering")
    else:
        print(f"  WARN: {p} savefig pattern not found")
PYPATCH
else
    log "  WARN: mpld3 exporter.py not found; skipping patch"
fi

echo "${MATPLOTLIB_TAG}" > "${OUTPUT_DIR}/version.txt"
cp /build/pyinit_map.txt "${OUTPUT_DIR}/pyinit_map.txt"

log "DONE. Manifest:"
cat "${MANIFEST}"
log "Archives:"
ls -lh "${OUTPUT_DIR}/lib/wasm32-wasi/"
log "Python tree top-level:"
ls "${OUTPUT_DIR}/python/"
