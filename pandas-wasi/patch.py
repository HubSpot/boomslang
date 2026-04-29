#!/usr/bin/env python3
"""Patch pandas sources for wasm32-wasi cross-compilation.

Run from the pandas repo root. Idempotent (skips already-patched files).
"""
import sys
from pathlib import Path


# pandas vendors a subset of numpy's datetime helpers under
# pandas/_libs/include/pandas/vendored/numpy/datetime/. Two of the functions
# it exports -- add_minutes_to_datetimestruct and get_datetimestruct_days --
# are also exported by numpy's multiarray. In aviator-cpython these end up in
# the same libpython3.14.a (cpython-wasi merges numpy + pandas via `ar -M`),
# and wasm-ld rejects them as duplicate symbols.
#
# Rename pandas's copies with a pandas_ prefix so they no longer collide. The
# #define at the top of the vendored header flows into every translation unit
# that includes it -- both pandas's own callers and the np_datetime.c that
# defines the functions -- so the rename is consistent across declaration and
# definition without having to edit the .c files.
RENAME_SENTINEL = "PANDAS_WASI_RENAMES"

RENAME_BLOCK = f"""\
#ifndef {RENAME_SENTINEL}
#define {RENAME_SENTINEL}
/* aviator-cpython: avoid duplicate-symbol collisions with numpy in the
 * merged libpython. See pandas-wasi/patch.py. These are pandas's local copies
 * of numpy datetime helpers; numpy's multiarray exports the same names, so
 * wasm-ld rejects the merged archive as ambiguous.
 *
 * The #define in this header flows into every translation unit that #includes
 * np_datetime.h (pandas callers + the np_datetime.c that defines them), so
 * declaration and definition stay in sync without editing the .c sources.
 *
 * Only safe to rename symbols that are NOT also struct members / macro names
 * in pd_datetime.h. `get_datetime_metadata_from_dtype` is both, so we handle
 * it separately via `patch_pd_datetime_h()`. */
#define add_minutes_to_datetimestruct pandas_add_minutes_to_datetimestruct
#define get_datetimestruct_days       pandas_get_datetimestruct_days
#define is_leapyear                   pandas_is_leapyear
#endif

"""


def patch_np_datetime_h():
    path = Path(
        "pandas/_libs/include/pandas/vendored/numpy/datetime/np_datetime.h"
    )
    src = path.read_text()
    if RENAME_SENTINEL in src:
        print(f"{path}: already patched")
        return
    path.write_text(RENAME_BLOCK + src)
    print(f"{path}: patched (numpy symbol rename block prepended)")


def rename_api_symbol(old, new):
    """Rename a pandas C-API symbol everywhere it appears (declaration,
    definition, struct field, macro body, struct initialization).

    Needed for symbols that are BOTH global functions (colliding with numpy
    at link time) AND pandas C-API struct members. A simple #define rename
    in np_datetime.h breaks: pd_datetime.h's function-like macro clobbers
    the object macro, leaving the rename active only for code that sees it
    first — the struct field becomes `pandas_X` while access code keeps
    seeing `X`.

    Rewriting source-level substitutes the new name everywhere in one pass,
    producing a consistent rename without fighting macro rescan order.
    """
    roots = [
        Path("pandas/_libs/include/pandas/vendored/numpy/datetime/np_datetime.h"),
        Path("pandas/_libs/src/vendored/numpy/datetime/np_datetime.c"),
        Path("pandas/_libs/include/pandas/datetime/pd_datetime.h"),
        Path("pandas/_libs/src/datetime/pd_datetime.c"),
        Path("pandas/_libs/src/vendored/ujson/python/objToJSON.c"),
        Path("pandas/_libs/tslibs/np_datetime.pyx"),
    ]
    # Cython .pyx files use Python `#` comment syntax; C/H use /* */. Pick
    # the right style per extension so the marker doesn't break compilation.
    for p in roots:
        if not p.exists():
            continue
        if p.suffix == ".pyx" or p.suffix == ".pxd":
            marker = f"# aviator-cpython: renamed {old} -> {new}"
        else:
            marker = f"/* aviator-cpython: renamed {old} -> {new} */"
        s = p.read_text()
        if marker in s:
            print(f"{p}: already patched for {old}")
            continue
        if old not in s:
            continue
        # Plain string replace: the identifier-context references pandas uses
        # are all bare tokens, no substring hazard.
        s = s.replace(old, new)
        p.write_text(marker + "\n" + s)
        print(f"{p}: renamed {old} -> {new}")


def main():
    patch_np_datetime_h()
    # `get_datetime_metadata_from_dtype` is a pandas C-API struct member in
    # pd_datetime.h, so the naive #define trick fails (pd_datetime.h's
    # function-like macro redefinition hides our object macro; downstream
    # expansions then reference the un-renamed struct field). Do a
    # source-level rename across all four files that touch the name.
    rename_api_symbol(
        "get_datetime_metadata_from_dtype",
        "pandas_get_datetime_metadata_from_dtype",
    )


if __name__ == "__main__":
    main()
