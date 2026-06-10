# Base ABI Specification

This page specifies the contract between a host and `boomslang.wasm`: the functions the guest exports and the conventions for calling them. It is the contract `PythonInstance` implements on the Java side, and what a non-Java embedder must implement directly. (Host functions the guest *imports* are covered by the [extension ABI](extension-abi.md).)

Source of truth: `python-host-core/src/export.rs` (guest) and `core/src/main/java/com/hubspot/boomslang/PythonInstance.java` (Java host).

> There is currently no ABI version export; compatibility between a host and a wasm artifact is by construction (build them from the same commit). A version handshake is tracked in [issue #43](https://github.com/HubSpot/boomslang/issues/43).

## Conventions

- The guest exports a single linear memory. All pointers are `i32` offsets into it.
- **The host owns buffer lifecycles.** Allocate guest buffers with `alloc`, write through the exported memory, pass `(ptr, len)` pairs, and free with `dealloc` after the call. The guest never frees host-allocated buffers, and the guest's internal allocations are not the host's concern.
- All strings are UTF-8. Passing invalid UTF-8 where a string is expected returns `-1`.
- Every execution-family export (`compile_source`, `load_bytecode`, `execute`, `execute_function`, `install_module`, `uninstall_module`) **clears the captured stdout/stderr buffers on entry**. Read outputs after each call, before the next one.
- Error reporting is two-channel: a coarse return code, plus the Python traceback captured in the stderr buffer. Detailed error strings only exist in stderr.

## Exports

| Export | Signature | Semantics |
| --- | --- | --- |
| `alloc` | `(size: i32) -> i32` | Allocate `size` bytes in guest memory (mimalloc); returns pointer. |
| `dealloc` | `(ptr: i32, size: i32)` | Free an `alloc`'d buffer. `size` is currently ignored but pass the allocated size. |
| `compile_source` | `(source_ptr: i32, source_len: i32, output_ptr: i32, output_max_len: i32) -> i32` | Compile Python source to marshal bytecode, written to the caller-provided output buffer. Returns the bytecode length, `-1` on invalid UTF-8 or compile error (traceback in stderr), `-3` if the bytecode exceeds `output_max_len`. |
| `load_bytecode` | `(ptr: i32, len: i32) -> i32` | Unmarshal and execute bytecode from `compile_source`. `0` ok; `1` Python error (traceback in stderr). |
| `execute` | `(script_ptr: i32, script_len: i32) -> i32` | Execute Python source in `__main__`. `0` ok; `1` Python error; `-1` invalid UTF-8. |
| `execute_function` | `(name_ptr: i32, name_len: i32, args_ptr: i32, args_len: i32) -> i32` | Call a named function from previously loaded code with one string argument (`args_len` 0 → empty string). `0` / `1` / `-1` as above. |
| `get_stdout_len` / `get_stderr_len` | `() -> i32` | Byte length of the captured stream. |
| `get_stdout` / `get_stderr` | `(ptr: i32, max_len: i32) -> i32` | Copy up to `max_len` bytes of the captured stream into the caller's buffer; returns bytes written. |
| `install_module` | `(name_ptr: i32, name_len: i32, source_ptr: i32, source_len: i32) -> i32` | Install a pure-Python module under `name` (dotted names allowed). `0` / `1` / `-1`. |
| `uninstall_module` | `(name_ptr: i32, name_len: i32) -> i32` | Remove an installed module. `0` / `1` / `-1`. |
| `reset_state` | `()` | Clear capture buffers and reset the `__main__` namespace. Note: the Java host does not call this — it resets by restoring the copy-on-write memory snapshot, which is stricter. |
| `get_heap_pages` | `() -> i32` | Current guest memory size in 64 KiB pages. Used by hosts to size snapshots. |

## Imports

A complete embedder must provide, on the same linker/instance:

1. **WASI preview1** — filesystem, clock, random, stdio.
2. **Extension imports** — the bundled runtime imports `boomslang.call` and `boomslang.log` ([extension ABI](extension-abi.md)); custom builds import whatever their extensions declare.

Instantiation fails on any missing import.

## Call sequences

**Execute a script and read output** (what `PythonInstance.execute` does):

```text
ptr = alloc(len(script))          # write script bytes at ptr
rc  = execute(ptr, len(script))   # 0 ok, 1 python error, -1 bad utf-8
dealloc(ptr, len(script))
n   = get_stdout_len()
buf = alloc(n); get_stdout(buf, n)   # read n bytes from memory at buf
dealloc(buf, n)                      # same dance for stderr
```

**Compile once, run many** (`compile` / `loadCode`):

```text
out = alloc(MAX)                                   # Java uses MAX = 10 MiB
n   = compile_source(src, len, out, MAX)           # n = bytecode length, or -1 / -3
bytecode = memory[out .. out+n]; dealloc(out, MAX)
...
ptr = alloc(len(bytecode))                          # later, possibly many times
rc  = load_bytecode(ptr, len(bytecode))             # 0 / 1
```

The bytecode is CPython marshal data — valid only for the exact runtime build that produced it.

## Known sharp edges

- `-1` is overloaded: it means both "invalid UTF-8 input" and "Python-level failure" for `compile_source`. Disambiguate via stderr.
- There is no structured error channel; hosts surface failures by pairing the return code with the captured stderr.
- Output larger than the host's configured cap (Java default 10 MB) is rejected host-side, not guest-side.
