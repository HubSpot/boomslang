#!/usr/bin/env python3
"""Patch numpy sources for wasm32-wasi cross-compilation.

Run from the numpy repo root. Idempotent (skips already-patched files).
"""
import re
import sys
from pathlib import Path


def patch_npy_cpu_h():
    path = Path("numpy/_core/include/numpy/npy_cpu.h")
    src = path.read_text()
    if "NPY_CPU_WASM32" in src:
        print("npy_cpu.h: already patched")
        return
    pat = re.compile(
        r"(#else\s*\n\s*#error Unknown CPU[^\n]*\\\s*\n\s*information about your platform[^\n]*\n)",
        re.DOTALL,
    )
    m = pat.search(src)
    if not m:
        pat = re.compile(r"(#else\s*\n\s*#error Unknown CPU[^\n]*\n)", re.DOTALL)
        m = pat.search(src)
    if not m:
        sys.exit("PATCH FAIL: could not find '#error Unknown CPU' block in npy_cpu.h")
    replacement = (
        "#elif defined(__wasm32__)\n"
        "    #define NPY_CPU_WASM32\n"
        "#elif defined(__wasm64__)\n"
        "    #define NPY_CPU_WASM64\n"
        + m.group(1)
    )
    path.write_text(src.replace(m.group(1), replacement))
    print("npy_cpu.h: patched")


def patch_visibility_hidden():
    """Remove hidden visibility from numpy internal symbols.

    numpy marks internal functions with NPY_NO_EXPORT which expands to
    __attribute__((visibility("hidden"))). When we harvest .o files into
    static archives and link them into a single wasm module, hidden
    visibility prevents wasm-ld from resolving cross-object references —
    the symbols become unresolved imports instead. Override the generated
    _numpyconfig.h.in to produce an empty macro.
    """
    path = Path("numpy/_core/include/numpy/_numpyconfig.h.in")
    src = path.read_text()
    marker = "# wasi-patch: visibility"
    if marker in src:
        print("_numpyconfig.h.in: visibility already patched")
        return
    old = "#mesondefine NPY_VISIBILITY_HIDDEN"
    if old not in src:
        sys.exit("PATCH FAIL: could not find NPY_VISIBILITY_HIDDEN in _numpyconfig.h.in")
    new = f"/* {marker} — overridden for wasm32-wasi static linking */\n#define NPY_VISIBILITY_HIDDEN"
    path.write_text(src.replace(old, new))
    print("_numpyconfig.h.in: NPY_VISIBILITY_HIDDEN set to empty")


def main():
    patch_npy_cpu_h()
    patch_visibility_hidden()


if __name__ == "__main__":
    main()
