#!/usr/bin/env python3
"""Compare Python 3.15.0a7 PEP 810 imports in fresh Boomslang interpreter snapshots.

After `just python-stage`, run with boomslang installed in the host environment:
    python scripts/benchmark-lazy-imports.py --samples 7
    python scripts/benchmark-lazy-imports.py --modules wave numpy pandas jinja2

A native Python 3.15.0a7 can validate the same snippets, but its numbers do not
measure Boomslang:
    python scripts/benchmark-lazy-imports.py --native-python /path/to/python3.15 --modules wave

Guest timings exclude host setup, WASM compilation and source compilation.
Wizer-prewarmed packages are reported explicitly; their initialization cost is
already paid, so a lazy binding cannot defer that cost. Each sample restores the
snapshot (or starts a fresh native process), without deleting sys.modules entries.
"""

import argparse
import json
import statistics
import subprocess
import sys
from contextlib import nullcontext


# The final expression exercises the package instead of merely reading __name__.
CASES = {
    "wave": ("module.Error.__name__", "Error"),
    "numpy": ("int(module.array([1, 2, 3]).sum())", 6),
    "pandas": ("int(module.Series([1, 2, 3]).sum())", 6),
    "matplotlib.figure": ("len(module.Figure().subplots().plot([1, 2, 3]))", 1),
    "PIL.Image": ("list(module.new('RGB', (2, 3)).size)", [2, 3]),
    "pydantic": ("module.TypeAdapter(int).validate_python('42')", 42),
    "pydantic_core": ("module.from_json(b'{\"value\": 42}')['value']", 42),
    "ijson": ("sum(module.items(io.BytesIO(b'[1, 2, 3]'), 'item'))", 6),
    "jinja2": ("module.Template('Hello {{ name }}').render(name='Ada')", "Hello Ada"),
}
MODES = ("eager", "lazy", "declared", "filtered-all", "disabled")


def source_for(name, mode):
    expression, expected = CASES[name]
    setup = ""
    declaration = f"import {name} as module"
    if mode == "lazy":
        declaration = "lazy " + declaration
    elif mode == "disabled":
        setup = "sys.set_lazy_imports('none')"
        declaration = "lazy " + declaration
    elif mode == "declared":
        setup = f"__lazy_modules__ = [{name!r}]"
    elif mode == "filtered-all":
        # Keep imports within dependencies eager, including registration code.
        setup = (
            "sys.set_lazy_imports_filter(\n"
            f"    lambda importer, name, names: importer == '__main__' and name == {name!r})\n"
            "sys.set_lazy_imports('all')"
        )
    return f"""import sys, time, json, io
assert sys.version_info == (3, 15, 0, 'alpha', 7), sys.version
assert sys.get_lazy_imports() == 'normal'
initial_modules = set(sys.modules)
preloaded = {name!r} in initial_modules
{setup}
start = time.perf_counter_ns()
{declaration}
after_declaration = time.perf_counter_ns()
loaded_at_declaration = {name!r} in sys.modules
modules_at_declaration = len(set(sys.modules) - initial_modules)
use_start = time.perf_counter_ns()
value = {expression}
after_use = time.perf_counter_ns()
assert value == {expected!r}, value
assert {name!r} in sys.modules
assert loaded_at_declaration == (preloaded or {mode!r} in ('eager', 'disabled'))
sys.set_lazy_imports('normal')
sys.set_lazy_imports_filter(None)
print(json.dumps({{
    'python': sys.version.split()[0],
    'preloaded': preloaded,
    'declaration_us': (after_declaration - start) / 1000,
    'first_use_us': (after_use - use_start) / 1000,
    'total_us': ((after_declaration - start) + (after_use - use_start)) / 1000,
    'new_modules_at_declaration': modules_at_declaration,
    'new_modules_after_use': len(set(sys.modules) - initial_modules),
}}))
"""


def positive_int(value):
    count = int(value)
    if count < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return count


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--samples", type=positive_int, default=7)
    parser.add_argument("--modules", nargs="+", choices=CASES, default=["wave", "numpy"])
    parser.add_argument("--native-python", help="use this Python 3.15.0a7 executable instead of Boomslang")
    args = parser.parse_args()

    if args.native_python:
        context = nullcontext(None)
    else:
        from boomslang import Sandbox

        context = Sandbox()

    report = {
        "backend": "native" if args.native_python else "boomslang-wasmtime",
        "samples": args.samples,
        "timing": "guest declaration and first use; setup and compilation excluded",
        "results": [],
    }
    with context as sandbox:
        for name in args.modules:
            for mode in MODES:
                print(f"Benchmarking {name}: {mode}", file=sys.stderr, flush=True)
                source = source_for(name, mode)
                measurements = []
                # One discarded warmup per case, then independently fresh samples.
                for index in range(args.samples + 1):
                    if args.native_python:
                        result = subprocess.run(
                            [args.native_python, "-I", "-c", source],
                            capture_output=True,
                            text=True,
                            timeout=120,
                        )
                        ok = result.returncode == 0
                    else:
                        sandbox.reset()
                        result = sandbox.execute(source)
                        ok = result.ok
                    if not ok:
                        raise RuntimeError(f"{name} ({mode}): {result.stderr}")
                    measurement = json.loads(result.stdout)
                    if index:
                        measurements.append(measurement)
                report["python"] = measurements[0]["python"]
                row = {"module": name, "mode": mode, "preloaded": measurements[0]["preloaded"]}
                for key in ("declaration_us", "first_use_us", "total_us",
                            "new_modules_at_declaration", "new_modules_after_use"):
                    row[key] = statistics.median(item[key] for item in measurements)
                report["results"].append(row)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
