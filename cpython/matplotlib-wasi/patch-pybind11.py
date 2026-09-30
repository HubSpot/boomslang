#!/usr/bin/env python3
"""Use threadless WASI synchronization without shadowing standard C++ headers."""
import re
from pathlib import Path

import pybind11

root = Path(pybind11.get_include()) / "pybind11"
assert pybind11.__version__ == "3.1.0", "Revalidate the WASI adapter when upgrading pybind11"

for relative in ("detail/internals.h", "gil_safe_call_once.h", "chrono.h", "subinterpreter.h"):
    path = root / relative
    src = path.read_text()
    marker = "// boomslang: single-threaded WASI synchronization"
    if marker in src:
        continue
    src = src.replace('#include <mutex>', '#include <pybind11-wasi-threading.h>')
    # hardware_concurrency is only used by the free-threaded implementation.
    src = src.replace('#include <thread>', '#ifdef Py_GIL_DISABLED\n#include <thread>\n#endif')
    src, count = re.subn(r"std::(mutex|lock_guard|once_flag|call_once)\b", r"boomslang_wasi::\1", src)
    assert count, f"No threading uses found in {path}"
    path.write_text(marker + "\n" + src)
    print(f"{path}: adapted {count} threading references")
