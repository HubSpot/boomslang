#!/usr/bin/env python3
"""Fix preloaded dotted import aliases in CPython 3.15.0a7.

`lazy import PIL.Image as image` must bind the module, not PIL.Image.Image.
The eager lookup in _PyEval_LazyImportFrom is valid for from-imports, whose
proxy has a tuple from-list. Dotted aliases instead use IMPORT_FROM to walk
package components and must follow the existing proxy-construction path.

Run with the CPython source directory as the sole argument. Remove/revalidate
this patch when updating CPython. The tagged a7 source contains this faulty
lookup; validate its lazy-import suite after changing the patch.
"""

import sys
from pathlib import Path


source_root = Path(sys.argv[1])
version_header = (source_root / "Include/patchlevel.h").read_text()
if '#define PY_VERSION              "3.15.0a7"' not in version_header:
    raise SystemExit("Revalidate the lazy import alias patch for this CPython version")

path = source_root / "Python/ceval.c"
source = path.read_text()
old = """    PyLazyImportObject *d = (PyLazyImportObject *)v;
    PyObject *mod = PyImport_GetModule(d->lz_from);
"""
new = """    PyLazyImportObject *d = (PyLazyImportObject *)v;
    // Only from-imports name the attribute's module directly. Dotted import
    // aliases traverse package components below, so looking up an attribute
    // on the full module here can incorrectly return a same-named class.
    PyObject *mod = NULL;
    if (d->lz_attr != NULL && PyTuple_Check(d->lz_attr)) {
        mod = PyImport_GetModule(d->lz_from);
    }
"""
if source.count(old) != 1:
    raise SystemExit("Could not locate the expected CPython lazy import alias lookup")
path.write_text(source.replace(old, new, 1))
print("Patched CPython lazy dotted import aliases")
