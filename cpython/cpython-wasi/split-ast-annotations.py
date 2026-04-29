#!/usr/bin/env python3
"""Post-process Python/Python-ast.c to split add_ast_annotations() into
smaller sub-functions so each stays under Chicory's 64KB AOT limit.

The generated function is ~4300 lines / 266 KB of WASM — a single giant
sequence of 113 independent annotation-setup blocks. We split it into N
sub-functions of ~30 blocks each, called from a small dispatcher.

Run from the CPython source root after `make regen-ast` (or after configure
generates Python-ast.c from the ASDL grammar).

Idempotent: skips if already patched.
"""
import re
import sys
from pathlib import Path

SENTINEL = "/* aviator-cpython: split add_ast_annotations */"
CHUNK_SIZE = 30  # blocks per sub-function

def split_annotations(src: str) -> str:
    if SENTINEL in src:
        print("Python-ast.c: already patched")
        return src

    # Find the function boundaries — includes the "static int\n" before the name
    func_name_pos = src.index("\nadd_ast_annotations(struct ast_state *state)\n")
    # Back up to include "static int\n"
    func_start = src.rindex("\n", 0, func_name_pos)
    # Find opening brace after the signature
    brace_start = src.index("{", func_name_pos)
    # Find "    bool cond;" declaration
    decl_end = src.index("\n", src.index("bool cond;", brace_start))

    # Find closing "    return 1;\n}\n" at end of function
    # Search for the pattern after the last annotation block
    func_body_start = decl_end + 1
    # Find the final "return 1;" before the closing brace
    closing_pattern = "    return 1;\n}\n"
    func_end = src.index(closing_pattern, func_body_start) + len(closing_pattern)

    # Extract the body (between "bool cond;" and "return 1;")
    body = src[func_body_start:src.index("    return 1;\n", func_body_start)]

    # Split body into blocks at each "    PyObject *<Name>_annotations = PyDict_New();"
    block_pattern = re.compile(r"(?=    PyObject \*\w+_annotations = PyDict_New\(\);)")
    blocks = block_pattern.split(body)
    blocks = [b for b in blocks if b.strip()]

    print(f"Python-ast.c: {len(blocks)} annotation blocks, splitting into chunks of {CHUNK_SIZE}")

    # Generate sub-functions
    sub_funcs = []
    sub_names = []
    for i in range(0, len(blocks), CHUNK_SIZE):
        chunk = blocks[i:i + CHUNK_SIZE]
        name = f"add_ast_annotations_part{i // CHUNK_SIZE}"
        sub_names.append(name)
        func_text = (
            f"static int\n"
            f"{name}(struct ast_state *state)\n"
            f"{{\n"
            f"    bool cond;\n"
            + "".join(chunk)
            + "    return 1;\n"
            f"}}\n"
        )
        sub_funcs.append(func_text)

    # Generate dispatcher
    calls = "\n".join(f"    if (!{name}(state)) return 0;" for name in sub_names)
    dispatcher = (
        f"{SENTINEL}\n"
        f"static int\n"
        f"add_ast_annotations(struct ast_state *state)\n"
        f"{{\n"
        f"{calls}\n"
        f"    return 1;\n"
        f"}}\n"
    )

    # Replace the original function with sub-functions + dispatcher
    replacement = "\n".join(sub_funcs) + "\n" + dispatcher
    new_src = src[:func_start + 1] + replacement + src[func_end:]

    print(f"Python-ast.c: split into {len(sub_funcs)} sub-functions + dispatcher")
    return new_src


def main():
    path = Path("Python/Python-ast.c")
    if not path.exists():
        print("error: run from CPython source root", file=sys.stderr)
        sys.exit(2)
    src = path.read_text()
    new_src = split_annotations(src)
    if new_src != src:
        path.write_text(new_src)
        print(f"Python-ast.c: patched successfully")


if __name__ == "__main__":
    main()
