#!/usr/bin/env python3
"""Patch matplotlib sources for wasm32-wasi cross-compilation.

mpld3 is our only rendering consumer; it walks the Figure artist tree and
emits D3 JSON, so we never produce pixels. That lets us drop a large part of
matplotlib's C/C++ surface:

    _backend_agg   — Agg raster renderer (C++ rendering pipeline)
    _image         — image resample kernel (imshow)
    _tri           — triangulation
    _qhull         — Delaunay / convex hull
    _ttconv        — Type1 converter (ps backend only)
    _tkagg         — interactive Tk backend
    _macosx        — interactive macOS backend

Dropping them from src/meson.build avoids compiling tens of thousands of
lines of C++ plus the libpng / qhull support libs. The three extensions we
keep — _c_internal_utils, _path, ft2font — are the minimum matplotlib
pyplot needs to create a Figure and lay out text (for ax.set_xlabel, etc.).

Idempotent: safe to re-run.
"""
import re
import sys
from pathlib import Path

SKIP_EXTENSIONS = {
    # Triangulation / interpolation / PostScript converter / GUI backends —
    # not on the mpld3 path. We drop them to avoid compiling extra C/C++ we
    # don't need. qhull also avoids pulling the qhull library.
    "_tri",
    "_qhull",
    "_ttconv",
    "_tkagg",
    "_macosx",
}
# ft2font, _image, and _backend_agg ARE now enabled — we build freetype with
# a setjmp shim (see Dockerfile) and consume WLR-upstream libpng+libz, so the
# full text-metrics + Agg rendering surface compiles cleanly. mpld3 gets
# accurate text bboxes → correct legend / annotation layout.

SENTINEL = "# aviator-cpython: mpld3 subset"


def patch_src_meson_build():
    """Remove SKIP_EXTENSIONS from src/meson.build's extensions dict.

    Matplotlib declares each extension as a key in a single dict literal
    passed to `py.extension_module()` via `foreach`. Drop the entries we
    don't ship; everything else (agg_dep target, freetype dep, etc.) stays.
    """
    path = Path("src/meson.build")
    src = path.read_text()
    if SENTINEL in src:
        print(f"{path}: already patched")
        return

    for ext in SKIP_EXTENSIONS:
        # Each entry looks like:
        #     '_image': {
        #       ...
        #     },
        # Match the opening line through the closing `},` at same indent.
        pattern = re.compile(
            r"^(?P<indent>[ \t]+)'" + re.escape(ext) + r"':\s*\{\n"
            r"(?:.*\n)*?"  # body (non-greedy)
            r"(?P=indent)\},?\n",
            re.MULTILINE,
        )
        new_src, n = pattern.subn("", src)
        if n == 0:
            print(f"  WARN: could not find {ext} block in src/meson.build")
        else:
            print(f"  removed {ext} ({n} block)")
            src = new_src

    path.write_text(f"{SENTINEL}\n{src}")
    print(f"{path}: patched")


def patch_extern_meson_build():
    """Skip qhull dep resolution in extern/meson.build.

    We don't build _qhull, but matplotlib's extern/meson.build unconditionally
    tries to resolve qhull_r via pkg-config / cc.check_header and fails the
    meson setup when qhull isn't installed or when the cross-compile doesn't
    see the host qhull. Replace the whole `if get_option('system-qhull') ...`
    block with `qhull_dep = disabler()` so no target ever references qhull.
    """
    path = Path("extern/meson.build")
    if not path.exists():
        return
    src = path.read_text()
    if "aviator-cpython: qhull disabled" in src:
        print(f"{path}: already patched for qhull")
        return
    pattern = re.compile(
        r"if get_option\('system-qhull'\)\n"
        r"(?:.*\n)*?"  # non-greedy body
        r"endif\n",
        re.MULTILINE,
    )
    new_src, n = pattern.subn(
        "# aviator-cpython: qhull disabled (no _qhull extension)\n"
        "qhull_dep = disabler()\n",
        src,
    )
    if n == 0:
        print(f"  WARN: could not find qhull block in extern/meson.build")
    else:
        path.write_text(new_src)
        print(f"{path}: qhull block replaced with disabler()")


def main():
    if not Path("src/meson.build").exists():
        print("error: run from matplotlib repo root", file=sys.stderr)
        sys.exit(2)
    patch_src_meson_build()
    patch_extern_meson_build()


if __name__ == "__main__":
    main()
