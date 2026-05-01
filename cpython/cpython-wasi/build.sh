#!/usr/bin/env bash
set -euo pipefail

PYTHON_VERSION=3.14.0
CPYTHON_TAG=v3.14.0

WASI_SDK_PATH="${WASI_SDK_PATH:-/opt/wasi-sdk}"
BUILD_DIR="/build/staging"
DEPS_DIR="${BUILD_DIR}/deps"
SOURCE_DIR="${BUILD_DIR}/checkout"
OUTPUT_DIR="/build/output"

CC="${WASI_SDK_PATH}/bin/clang"
AR="${WASI_SDK_PATH}/bin/llvm-ar"
RANLIB="${WASI_SDK_PATH}/bin/llvm-ranlib"
NM="${WASI_SDK_PATH}/bin/llvm-nm"
LD="${WASI_SDK_PATH}/bin/wasm-ld"

log() {
    echo "==> $*"
}

log "Downloading dependency libraries..."
mkdir -p "${DEPS_DIR}/build-output"

for url in \
    "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Flibuuid%2F1.0.3%2B20230623-2993864/libuuid-1.0.3-wasi-sdk-20.0.tar.gz" \
    "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Fzlib%2F1.2.13%2B20230623-2993864/libz-1.2.13-wasi-sdk-20.0.tar.gz" \
    "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Fsqlite%2F3.42.0%2B20230623-2993864/libsqlite-3.42.0-wasi-sdk-20.0.tar.gz" \
    "https://github.com/vmware-labs/webassembly-language-runtimes/releases/download/libs%2Fbzip2%2F1.0.8%2B20230623-2993864/libbzip2-1.0.8-wasi-sdk-20.0.tar.gz" \
; do
    log "  Downloading $(basename "${url}")..."
    curl -sL "${url}" | tar xz -C "${DEPS_DIR}/build-output/" 2>/dev/null || true
done

log "Cloning CPython ${CPYTHON_TAG}..."
mkdir -p "${BUILD_DIR}"
git clone --depth 1 --branch "${CPYTHON_TAG}" https://github.com/python/cpython "${SOURCE_DIR}"

log "Configuring CPython for WASI..."
cd "${SOURCE_DIR}"

DEPS_INCLUDE="${DEPS_DIR}/build-output/include"
DEPS_LIBDIR="${DEPS_DIR}/build-output/lib/wasm32-wasi"

export CC AR RANLIB NM LD
# -mtail-call is added AFTER configure (via EXTRA_CFLAGS in make) because
# autoconf's "can compiler create executables" test fails when the test
# binary contains return_call instructions. The actual build gets it via
# make EXTRA_CFLAGS below.
export CFLAGS="-Os -Wno-int-conversion -Wno-unknown-attributes -I${DEPS_INCLUDE}"
export LDFLAGS="-L${DEPS_LIBDIR}"
export PKG_CONFIG_PATH="${DEPS_LIBDIR}/pkgconfig"
export PKG_CONFIG_LIBDIR="${DEPS_LIBDIR}/pkgconfig"

CONFIG_SITE=./Tools/wasm/wasi/config.site-wasm32-wasi \
    ./configure -C \
    --host=wasm32-wasip1 \
    --build=$(./config.guess) \
    --with-build-python=python3 \
    --without-doc-strings \
    --with-tail-call-interp

# Split add_ast_annotations() (266 KB) into sub-functions so Chicory's AOT
# can compile them. The function is auto-generated from the ASDL grammar;
# post-processing is simpler than patching the generator.
log "Splitting add_ast_annotations for Chicory AOT..."
python3 /build/split-ast-annotations.py

log "Building CPython..."
# Pass -mtail-call at make time (not configure time) so the WASM tail-call
# proposal is enabled for actual compilation but not for autoconf tests.
make -j$(nproc) EXTRA_CFLAGS="-mtail-call" all

log "Installing stdlib..."
PYBUILDDIR=$(cat pybuilddir.txt)
touch "${PYBUILDDIR}/build-details.json"
make libinstall DESTDIR=$(pwd)

##############################
# pydantic-core (from Blazar artifact)
##############################
PYDANTIC_LIB="${BUILD_DIR}/pydantic-core-lib"

log "Extracting pydantic-core from vendor artifact..."
mkdir -p "${PYDANTIC_LIB}"
if [ -f /build/vendor/pydantic-core-wasi.tgz ]; then
    tar xzf /build/vendor/pydantic-core-wasi.tgz -C "${PYDANTIC_LIB}"
elif [ -f /build/vendor/artifact.tgz ]; then
    tar xzf /build/vendor/artifact.tgz -C "${PYDANTIC_LIB}"
else
    log "ERROR: pydantic-core artifact not found in /build/vendor/"
    ls -la /build/vendor/ 2>/dev/null || echo "vendor/ does not exist"
    exit 1
fi
cp -r "${PYDANTIC_LIB}/python/pydantic_core" usr/local/lib/python3.14/
log "pydantic-core static lib: $(ls -lh ${PYDANTIC_LIB}/lib/lib_pydantic_core.a)"

log "Building custom python.wasm with pydantic-core..."
${CC} -Os -Wno-int-conversion \
    -I. -IInclude -IInclude/internal -I$(cat pybuilddir.txt) \
    /build/main.c \
    -L. -lpython3.14 \
    -L${PYDANTIC_LIB}/lib -l_pydantic_core \
    -L${DEPS_LIBDIR} -lz -lbz2 -lsqlite3 -luuid \
    -LModules/expat -lexpat \
    -LModules/_decimal/libmpdec -lmpdec \
    -LModules/_hacl -lHacl_Hash_SHA2 -lHacl_Hash_BLAKE2 -lHacl_HMAC \
    -lwasi-emulated-getpid -lwasi-emulated-signal -lwasi-emulated-process-clocks \
    -ldl \
    -Wl,-z,stack-size=8388608 -Wl,--stack-first -Wl,--initial-memory=67108864 \
    -o python-pydantic.wasm

log "Optimizing WASM binary..."
wasm-opt -O2 -o python-optimized.wasm python-pydantic.wasm

# _pydantic_core is registered as a top-level builtin via PyImport_AppendInittab,
# but pydantic_core/__init__.py imports it as a relative submodule (from ._pydantic_core).
# This shim bridges the two.
cat > usr/local/lib/python3.14/pydantic_core/_pydantic_core.py <<'SHIMEOF'
import _pydantic_core
import sys
sys.modules[__name__] = _pydantic_core
SHIMEOF

##############################
# numpy (from Blazar artifact)
##############################
NUMPY_LIB="${BUILD_DIR}/numpy-lib"

log "Extracting numpy from vendor artifact..."
mkdir -p "${NUMPY_LIB}"
if [ -f /build/vendor/numpy-wasi.tgz ]; then
    tar xzf /build/vendor/numpy-wasi.tgz -C "${NUMPY_LIB}"
else
    log "ERROR: numpy artifact not found in /build/vendor/numpy-wasi.tgz"
    ls -la /build/vendor/ 2>/dev/null || echo "vendor/ does not exist"
    exit 1
fi
cp -r "${NUMPY_LIB}/python/numpy" usr/local/lib/python3.14/
log "numpy archives: $(ls ${NUMPY_LIB}/lib/wasm32-wasi/ | wc -l) files, $(du -sh ${NUMPY_LIB}/lib/wasm32-wasi/ | cut -f1) total"
cat "${NUMPY_LIB}/manifest.txt"

# Strip Cython/C++ source files that aren't needed at runtime.
find usr/local/lib/python3.14/numpy -type f \( \
        -name '*.pyx' -o -name '*.pxd' -o -name '*.pyx.in' -o -name '*.pxd.in' \
        -o -name '*.c.in' -o -name '*.h.in' \
    \) -delete

# numpy's generated build files (version.py, __config__.py) are normally
# produced by numpy's meson build. numpy-wasi harvests them into the
# artifact; re-emit them here from templates if the artifact didn't include
# them, so `import numpy` succeeds without having to touch the numpy source.
# Read the real tag from the artifact's version.txt so bumping NUMPY_TAG
# upstream doesn't silently leave the stubs reporting a stale version.
NPY_VER=$(sed 's/^v//' "${NUMPY_LIB}/version.txt" 2>/dev/null || echo "unknown")
log "numpy-wasi artifact reports version: ${NPY_VER}"

if [ ! -f usr/local/lib/python3.14/numpy/version.py ]; then
    log "Generating numpy/version.py stub (v=${NPY_VER})..."
    cat > usr/local/lib/python3.14/numpy/version.py <<VEREOF
# Fabricated at cpython-wasi build time; numpy's real version.py is meson-generated.
version = "${NPY_VER}"
__version__ = "${NPY_VER}"
full_version = "${NPY_VER}"
short_version = "${NPY_VER}"
git_revision = "unknown"
release = True
VEREOF
fi

if [ ! -f usr/local/lib/python3.14/numpy/__config__.py ]; then
    log "Generating numpy/__config__.py stub..."
    cat > usr/local/lib/python3.14/numpy/__config__.py <<'CFGEOF'
# Fabricated at cpython-wasi build time. numpy.show_config() reads this.
CONFIG = {
    "Compilers": {
        "c": {"name": "clang", "linker": "wasm-ld", "version": "16.0.0", "commands": "clang"},
        "cython": {"name": "cython", "linker": "cython", "version": "3", "commands": "cython"},
        "c++": {"name": "clang", "linker": "wasm-ld", "version": "16.0.0", "commands": "clang++"},
    },
    "Machine Information": {
        "host": {"cpu": "wasm32", "family": "wasm32", "endian": "little", "system": "wasi"},
        "build": {"cpu": "x86_64", "family": "x86_64", "endian": "little", "system": "linux"},
        "cross-compiled": True,
    },
    "Build Dependencies": {
        "blas": {"name": "none", "found": False},
        "lapack": {"name": "none", "found": False},
    },
    "Python Information": {"path": "/usr/bin/python3", "version": "3.14"},
    "SIMD Extensions": {"baseline": [], "found": [], "not found": []},
}

def show(mode="stdout"):
    import pprint
    pprint.pprint(CONFIG)

def show_config(mode="stdout"):
    show(mode)
CFGEOF
fi

# No shim .py files needed: the aviator host registers each numpy C
# extension at its DOTTED import path (e.g. numpy._core._multiarray_umath)
# via PyImport_AppendInittab, so Python's BuiltinImporter resolves them
# directly. See aviator-python-host/src/lib.rs.

##############################
# pandas (from Blazar artifact)
##############################
PANDAS_LIB="${BUILD_DIR}/pandas-lib"

log "Extracting pandas from vendor artifact..."
mkdir -p "${PANDAS_LIB}"
if [ -f /build/vendor/pandas-wasi.tgz ]; then
    tar xzf /build/vendor/pandas-wasi.tgz -C "${PANDAS_LIB}"
else
    log "ERROR: pandas artifact not found in /build/vendor/pandas-wasi.tgz"
    ls -la /build/vendor/ 2>/dev/null || echo "vendor/ does not exist"
    exit 1
fi
cp -r "${PANDAS_LIB}/python/pandas" usr/local/lib/python3.14/
log "pandas archives: $(ls ${PANDAS_LIB}/lib/wasm32-wasi/ | wc -l) files, $(du -sh ${PANDAS_LIB}/lib/wasm32-wasi/ | cut -f1) total"
cat "${PANDAS_LIB}/manifest.txt"

# Strip Cython/C sources we don't need at runtime.
find usr/local/lib/python3.14/pandas -type f \( \
        -name '*.pyx' -o -name '*.pxd' -o -name '*.pxi' -o -name '*.pxi.in' \
        -o -name '*.c' -o -name '*.h' -o -name '*.cpp' -o -name '*.hpp' \
    \) -delete
# Drop the test tree — huge and not used by the sandbox.
rm -rf usr/local/lib/python3.14/pandas/tests 2>/dev/null || true

PANDAS_VER=$(sed 's/^v//' "${PANDAS_LIB}/version.txt" 2>/dev/null || echo "unknown")
log "pandas-wasi artifact reports version: ${PANDAS_VER}"

# Install pandas's pure-Python dependencies from wheels. python-dateutil
# pulls in six transitively. Pandas's top-level `import pandas` chain reaches
# pandas/_libs/tslibs/timezones.pyx -> dateutil.tz -> six, so both must be
# present at module load. tzdata is optional (zoneinfo is in stdlib 3.14 but
# WASI has no /usr/share/zoneinfo; include it so ZoneInfo lookups work).
log "Downloading python-dateutil, six, tzdata..."
# Blazar containers can't reach pythonhosted.org directly — curl gets exit 7.
# Use pip which consumes the pip.conf Blazar mounts as a build secret (same
# mechanism the dependent-lib Dockerfiles use) to hit HubSpot's internal
# mirror. --no-deps because we only want those three; dateutil pulls in six
# transitively otherwise but we stage it explicitly too.
mkdir -p /tmp/wheels-pandas
pip3 download --no-cache-dir --no-deps --prefer-binary \
    -d /tmp/wheels-pandas \
    "python-dateutil>=2.8.2" "six>=1.16" "tzdata"
python3 -c "
import zipfile, glob
target = '${SOURCE_DIR}/usr/local/lib/python3.14'
for whl in glob.glob('/tmp/wheels-pandas/*.whl'):
    print(f'Extracting {whl}')
    with zipfile.ZipFile(whl) as z:
        for name in z.namelist():
            if '.dist-info/' in name:
                continue
            z.extract(name, target)
"

# Stub mmap so pandas.io.common's top-level `import mmap` succeeds. cpython-
# wasi doesn't build the mmap C extension (WASI has no real mmap), but pandas
# only uses it as an opt-in type for CSV memory-mapping; a stub module whose
# .mmap class raises on instantiation is sufficient to let pandas import and
# keeps the isinstance(x, mmap.mmap) checks against normal file objects safe
# (they just return False).
if [ ! -f usr/local/lib/python3.14/mmap.py ]; then
    log "Writing mmap stub for WASI..."
    cat > usr/local/lib/python3.14/mmap.py <<'MMAPEOF'
"""Stub mmap module for the WASI sandbox.

cpython-wasi doesn't build the mmap C extension — WASI has no real mmap
syscall. Pandas (and other libs) do `import mmap` at module load, but only
use it in opt-in code paths (CSV memory-mapping, isinstance checks against
normal file objects). This stub lets those imports succeed; any actual
mmap() call raises OSError.
"""
ACCESS_READ = 1
ACCESS_WRITE = 2
ACCESS_COPY = 3
ACCESS_DEFAULT = 0
PROT_READ = 1
PROT_WRITE = 2
PROT_EXEC = 4

class mmap:
    def __init__(self, *args, **kwargs):
        raise OSError("mmap is not available on wasi")
    @classmethod
    def __class_getitem__(cls, item):
        return cls

class error(OSError):
    pass
MMAPEOF
fi

##############################
# matplotlib (from Blazar artifact)
##############################
MATPLOTLIB_LIB="${BUILD_DIR}/matplotlib-lib"

log "Extracting matplotlib from vendor artifact..."
mkdir -p "${MATPLOTLIB_LIB}"
if [ -f /build/vendor/matplotlib-wasi.tgz ]; then
    tar xzf /build/vendor/matplotlib-wasi.tgz -C "${MATPLOTLIB_LIB}"
else
    log "ERROR: matplotlib artifact not found in /build/vendor/matplotlib-wasi.tgz"
    ls -la /build/vendor/ 2>/dev/null || echo "vendor/ does not exist"
    exit 1
fi
cp -r "${MATPLOTLIB_LIB}/python/matplotlib" usr/local/lib/python3.14/
# mpl_toolkits is a sibling top-level package shipped by matplotlib.
if [ -d "${MATPLOTLIB_LIB}/python/mpl_toolkits" ]; then
    cp -r "${MATPLOTLIB_LIB}/python/mpl_toolkits" usr/local/lib/python3.14/
fi
# mpld3 + its pure-Python deps (jinja2, markupsafe) are unpacked into
# python/ by matplotlib-wasi/harvest.sh. Copy whatever landed there.
# Copy matplotlib's pure-Python deps from the artifact. The artifact's
# python/ tree includes mpld3, jinja2, markupsafe, packaging, cycler,
# pyparsing, fontTools, dateutil — all downloaded by harvest.sh. Copy
# every top-level dir that isn't matplotlib or mpl_toolkits (those were
# copied above).
for pkg in "${MATPLOTLIB_LIB}"/python/*/; do
    name=$(basename "$pkg")
    case "$name" in
        matplotlib|mpl_toolkits) continue ;;  # already copied above
    esac
    cp -r "$pkg" usr/local/lib/python3.14/
    log "  staged python/${name}"
done
log "matplotlib archives: $(ls ${MATPLOTLIB_LIB}/lib/wasm32-wasi/ | wc -l) files, $(du -sh ${MATPLOTLIB_LIB}/lib/wasm32-wasi/ | cut -f1) total"
cat "${MATPLOTLIB_LIB}/manifest.txt"

# Strip source files we don't need at runtime.
find usr/local/lib/python3.14/matplotlib -type f \( \
        -name '*.c' -o -name '*.h' -o -name '*.cpp' -o -name '*.hpp' \
    \) -delete
rm -rf usr/local/lib/python3.14/matplotlib/tests 2>/dev/null || true

MATPLOTLIB_VER=$(sed 's/^v//' "${MATPLOTLIB_LIB}/version.txt" 2>/dev/null || echo "unknown")
log "matplotlib-wasi artifact reports version: ${MATPLOTLIB_VER}"

# PIL stub — matplotlib.colors imports `from PIL import Image` unconditionally.
# Real Pillow has C extensions that don't work on wasm32-wasi. This minimal
# stub satisfies the import; mpld3 never exercises actual image I/O.
log "Writing PIL + kiwisolver stubs..."
mkdir -p usr/local/lib/python3.14/PIL
cat > usr/local/lib/python3.14/PIL/__init__.py <<'STUB'
__version__ = "0.0.0-stub"
STUB
cat > usr/local/lib/python3.14/PIL/Image.py <<'STUB'
NEAREST = 0; LANCZOS = 1; BILINEAR = 2; BICUBIC = 3; BOX = 4; HAMMING = 5
class Resampling:
    NEAREST = NEAREST; LANCZOS = LANCZOS; BILINEAR = BILINEAR; BICUBIC = BICUBIC; BOX = BOX; HAMMING = HAMMING
class Image:
    format = None; mode = None; size = (0, 0)
    def __init__(self, *a, **k): pass
    def save(self, *a, **k): raise NotImplementedError("PIL not available on wasi")
    def convert(self, *a, **k): return self
    def resize(self, *a, **k): return self
def new(*a, **k): return Image()
def open(*a, **k): raise NotImplementedError("PIL not available on wasi")
def fromarray(*a, **k): raise NotImplementedError("PIL not available on wasi")
STUB
cat > usr/local/lib/python3.14/PIL/PngImagePlugin.py <<'STUB'
class PngInfo:
    def __init__(self): self.chunks = []
    def add(self, *a, **k): pass
    def add_text(self, *a, **k): pass
_MODES = {}
STUB

# kiwisolver stub — matplotlib's layout engine (constrained_layout).
# Real kiwisolver is a C extension. mpld3 doesn't exercise layout.
cat > usr/local/lib/python3.14/kiwisolver.py <<'STUB'
__version__ = "1.4.5"
class Variable:
    def __init__(self, *a, **k): pass
class Constraint:
    def __init__(self, *a, **k): pass
class Solver:
    def __init__(self, *a, **k): pass
    def addConstraint(self, *a, **k): pass
    def addEditVariable(self, *a, **k): pass
    def suggestValue(self, *a, **k): pass
    def updateVariables(self, *a, **k): pass
STUB

##############################
# ijson (from Blazar artifact)
##############################
IJSON_LIB="${BUILD_DIR}/ijson-lib"

log "Extracting ijson from vendor artifact..."
mkdir -p "${IJSON_LIB}"
if [ -f /build/vendor/ijson-wasi.tgz ]; then
    tar xzf /build/vendor/ijson-wasi.tgz -C "${IJSON_LIB}"
else
    log "ERROR: ijson artifact not found in /build/vendor/ijson-wasi.tgz"
    ls -la /build/vendor/ 2>/dev/null || echo "vendor/ does not exist"
    exit 1
fi
cp -r "${IJSON_LIB}/python/ijson" usr/local/lib/python3.14/
log "ijson archive: $(ls -lh ${IJSON_LIB}/lib/wasm32-wasi/lib_ijson_yajl2.a)"
cat "${IJSON_LIB}/manifest.txt"

find usr/local/lib/python3.14/ijson -type f \( \
        -name '*.c' -o -name '*.h' -o -name '*.so' \
    \) -delete
rm -rf usr/local/lib/python3.14/ijson/tests 2>/dev/null || true

IJSON_VER=$(sed 's/^v//' "${IJSON_LIB}/version.txt" 2>/dev/null || echo "unknown")
log "ijson-wasi artifact reports version: ${IJSON_VER}"

log "Installing typing_extensions and annotated_types from vendor..."
WHEELS_DIR="${PYDANTIC_LIB}/wheels"
ls -la "${WHEELS_DIR}/" 2>/dev/null || log "No wheels dir, downloading from PyPI..."
mkdir -p /tmp/wheels
if compgen -G "${WHEELS_DIR}/*.whl" > /dev/null 2>&1; then
    cp "${WHEELS_DIR}"/*.whl /tmp/wheels/
else
    curl -sL -o /tmp/wheels/typing_extensions.whl \
        "https://files.pythonhosted.org/packages/18/67/36e9267722cc04a6b9f15c7f3441c2363321a3ea07da7ae0c0707beb2a9c/typing_extensions-4.15.0-py3-none-any.whl"
    curl -sL -o /tmp/wheels/annotated_types.whl \
        "https://files.pythonhosted.org/packages/78/b6/6307fbef88d9b5ee7421e68d78a9f162e0da4900bc5f5793f6d3d0e34fb8/annotated_types-0.7.0-py3-none-any.whl"
fi
python3 -c "
import zipfile, glob
target = '${SOURCE_DIR}/usr/local/lib/python3.14'
for whl in glob.glob('/tmp/wheels/*.whl'):
    print(f'Extracting {whl}')
    with zipfile.ZipFile(whl) as z:
        for name in z.namelist():
            if '.dist-info/' in name:
                continue
            z.extract(name, target)
            print(f'  extracted {name}')
"

log "Packaging artifacts..."
mkdir -p "${OUTPUT_DIR}/bin"
mkdir -p "${OUTPUT_DIR}/usr"
mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi"

cp -v python-optimized.wasm "${OUTPUT_DIR}/bin/python-${PYTHON_VERSION}.wasm"
# Aggressive prune of test directories + type stubs before copy. Every new
# stdlib library (numpy, pandas, matplotlib, mpld3, fontTools, contourpy...)
# ships a big tests/ tree we don't need at runtime. Without pruning, the
# staged stdlib tops ~400 MB and the `cp -TRv` step fails under Blazar's
# default pod disk budget (the log truncates mid-copy with a corrupt docker
# error message).
log "Pre-copy stdlib size (before prune):"
du -sh usr/local/lib/python3.14 | awk '{print "  " $0}'
find usr/local/lib/python3.14 -depth -type d -name tests -exec rm -rf {} + 2>/dev/null || true
find usr/local/lib/python3.14 -depth -type d -name test -exec rm -rf {} + 2>/dev/null || true
find usr/local/lib/python3.14 -type f -name '*.pyi' -delete 2>/dev/null || true
find usr/local/lib/python3.14 -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
log "Post-copy stdlib size (after prune):"
du -sh usr/local/lib/python3.14 | awk '{print "  " $0}'

# Use cp without -v (less log spam) and without -T (portable):
# copy CONTENTS of usr/ into OUTPUT_DIR/usr/ preserving structure.
mkdir -p "${OUTPUT_DIR}/usr"
cp -R usr/. "${OUTPUT_DIR}/usr/"

# Ship pydantic-core static lib separately for downstream linking
cp -v "${PYDANTIC_LIB}/lib/lib_pydantic_core.a" "${OUTPUT_DIR}/lib/wasm32-wasi/"

log "Installing headers..."
make inclinstall \
    prefix="${OUTPUT_DIR}" \
    libdir="${OUTPUT_DIR}/lib/wasm32-wasi" \
    pkgconfigdir="${OUTPUT_DIR}/lib/wasm32-wasi/pkgconfig"

log "Creating combined static library..."
# Diagnostics: verify every input archive exists before the ar -M merge. A
# missing file causes llvm-ar -M to exit 1 with minimal output, which has
# burned cycles before.
log "  Verifying matplotlib + support archives..."
ls -lh ${MATPLOTLIB_LIB}/lib/wasm32-wasi/ 2>&1 || log "    MATPLOTLIB_LIB dir missing"
log "  Verifying pandas archives..."
ls ${PANDAS_LIB}/lib/wasm32-wasi/ 2>&1 | head -5 || log "    PANDAS_LIB dir missing"
log "  Generated addlib lines (sanity):"
for pa in ${PANDAS_LIB}/lib/wasm32-wasi/lib_pandas_*.a; do echo "    addlib ${pa}"; done | head -3
for ma in ${MATPLOTLIB_LIB}/lib/wasm32-wasi/lib_matplotlib_*.a; do echo "    addlib ${ma}"; done

(${AR} -M <<EOF
create libpython3.14-aio.a
addlib libpython3.14.a
addlib ${DEPS_LIBDIR}/libz.a
addlib ${DEPS_LIBDIR}/libbz2.a
addlib ${DEPS_LIBDIR}/libsqlite3.a
addlib ${DEPS_LIBDIR}/libuuid.a
addlib Modules/expat/libexpat.a
addlib Modules/_decimal/libmpdec/libmpdec.a
addlib Modules/_hacl/libHacl_Hash_SHA2.a
addlib Modules/_hacl/libHacl_Hash_BLAKE2.a
addlib Modules/_hacl/libHacl_HMAC.a
addlib ${PYDANTIC_LIB}/lib/lib_pydantic_core.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_multiarray_umath.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_simd.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_pocketfft_umath.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_umath_linalg.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_lapack_lite.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_mt19937.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_philox.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_pcg64.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_sfc64.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_common.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_generator.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_bounded_integers.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_bit_generator.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_random_mtrand.a
addlib ${NUMPY_LIB}/lib/wasm32-wasi/lib_numpy_internal.a
$(for pa in ${PANDAS_LIB}/lib/wasm32-wasi/lib_pandas_*.a; do echo "addlib ${pa}"; done)
$(for ma in ${MATPLOTLIB_LIB}/lib/wasm32-wasi/lib_matplotlib_*.a; do echo "addlib ${ma}"; done)
addlib ${MATPLOTLIB_LIB}/lib/wasm32-wasi/lib_freetype.a
addlib ${MATPLOTLIB_LIB}/lib/wasm32-wasi/lib_png.a
addlib ${IJSON_LIB}/lib/wasm32-wasi/lib_ijson_yajl2.a
save
end
EOF
) 2>&1 | tee /tmp/ar-merge.log
ar_exit=${PIPESTATUS[0]}
if [ "$ar_exit" -ne 0 ]; then
    log "ar -M failed (exit $ar_exit). Tail of output:"
    tail -40 /tmp/ar-merge.log
    exit $ar_exit
fi

cp -v libpython3.14-aio.a "${OUTPUT_DIR}/lib/wasm32-wasi/libpython3.14.a"

# Also ship numpy/pandas archives separately for downstream visibility.
cp -v "${NUMPY_LIB}/lib/wasm32-wasi/"lib_numpy_*.a "${OUTPUT_DIR}/lib/wasm32-wasi/"
cp -v "${PANDAS_LIB}/lib/wasm32-wasi/"lib_pandas_*.a "${OUTPUT_DIR}/lib/wasm32-wasi/"
cp -v "${MATPLOTLIB_LIB}/lib/wasm32-wasi/"lib_matplotlib_*.a "${OUTPUT_DIR}/lib/wasm32-wasi/"
cp -v "${IJSON_LIB}/lib/wasm32-wasi/lib_ijson_yajl2.a" "${OUTPUT_DIR}/lib/wasm32-wasi/"

log "Generating pkg-config file..."
mkdir -p "${OUTPUT_DIR}/lib/wasm32-wasi/pkgconfig"
cat > "${OUTPUT_DIR}/lib/wasm32-wasi/pkgconfig/libpython3.14.pc" <<PCEOF
prefix=\${pcfiledir}/../..
libdir=\${prefix}/lib/wasm32-wasi
includedir=\${prefix}/include/python3.14

Name: libpython3.14
Description: libpython3.14 allows embedding the CPython interpreter
Version: ${PYTHON_VERSION}
Libs: -L\${libdir} -lpython3.14 -Wl,-z,stack-size=524288 -Wl,--stack-first -Wl,--initial-memory=10485760 -lwasi-emulated-getpid -lwasi-emulated-signal -lwasi-emulated-process-clocks
Cflags: -I\${includedir}
PCEOF

log "DONE. Artifacts in ${OUTPUT_DIR}"
