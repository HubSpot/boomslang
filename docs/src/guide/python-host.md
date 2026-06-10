# Python Host (boomslang-py)

Run sandboxed Python *from Python*. The `boomslang-py/` package bundles the same WASM runtime as the Java artifact — CPython 3.14 with NumPy, Pandas, Matplotlib, Pillow, Pydantic, and ijson — and executes it with [wasmtime](https://pypi.org/project/wasmtime/). Guest code has no network access and can only touch the directories you mount.

Wheels are published as [GitHub release assets](https://github.com/HubSpot/boomslang/releases) (not PyPI):

```bash
pip install https://github.com/HubSpot/boomslang/releases/download/<tag>/boomslang-<version>-py3-none-any.whl
```

From a source checkout: `just fetch-main-wasm && just python-stage`, then `pip install -e boomslang-py`.

## Quickstart

```python
from boomslang import Sandbox

with Sandbox() as sandbox:
    result = sandbox.execute("print('hello from the sandbox')")
    print(result.stdout)        # hello from the sandbox
    print(result.exit_code)     # 0
```

The semantics mirror the Java host: interpreter state persists across `execute()` calls on the same sandbox; `reset()` restores the pristine interpreter image; guest Python errors don't raise on the host — they surface as `exit_code != 0` with the traceback in `result.stderr`.

## Resource limits and timeouts

```python
from boomslang import ResourceLimits, Sandbox

sandbox = Sandbox(limits=ResourceLimits(
    timeout=10.0,                       # seconds, default 120
    max_memory_bytes=512 * 1024 * 1024, # default: wasm32 4 GiB cap
    max_output_bytes=1024 * 1024,       # per stream, default 10 MiB
))
```

A timeout raises `PythonTimeoutError` and poisons the sandbox; call `reset()` to revive it. `max_memory_bytes` must exceed the baseline runtime image (~150 MB) or instantiation fails. Note that unlike the Java host, wasmtime gives the Python host real interruption — timed-out guest code is actually stopped.

## Filesystem

The guest sees a fixed layout: `/usr` (bundled runtime, read-only), `/lib` (`lib_dir=`, on the guest `sys.path`), `/work` (`work_dir=`), and `/tmp` (managed per-sandbox). `work_dir` and `lib_dir` default to managed temp dirs exposed as `sandbox.work_dir` / `sandbox.lib_dir`. Arbitrary extra mounts are not supported — share files through `/work`, and drop extra pure-Python libraries into `lib_dir` to make them importable.

(The mount table is baked into the runtime image by Wizer; the sandbox probes the image's layout once per process and either mounts your directories directly or transparently syncs files around each execution — visible-before/appears-after semantics are the same either way.)

## Host functions

Guest code calls back into your process through the same `boomslang_host` bridge the Java host uses; arguments and results cross as JSON. Results larger than the bridge's 1 MiB buffer are fetched back in chunks transparently.

```python
sandbox = Sandbox()

@sandbox.host_function("lookup_user")
def lookup_user(args):
    return {"id": args["id"], "name": "Ada"}

result = sandbox.execute("""
import json
from boomslang_host import call
user = json.loads(call("lookup_user", json.dumps({"id": 7})))
print(user["name"])
""")
```

`call_handler=` gives raw `(name, args_json) -> json` control; `on_log=` receives `boomslang_host.log()` output (default: the `boomslang.guest` logger).

**Async:** `@sandbox.async_host_function("fetch")` handlers run on a host thread pool, and guest coroutines await them via `boomslang_host.asyncio` — the same [wire protocol](../reference/async-protocol.md) as the Java `AsyncHostRegistry`, so the guide's [async patterns](async.md) apply unchanged.

## Bytecode, functions, stdin

`sandbox.compile()` / `load_bytecode()` / `execute_function(name, json_args)` mirror the Java `compile`/`loadCode`/`executeFunction` flow, and `sandbox.set_stdin(...)` feeds the next execution's `input()` (consumed then cleared, like the Java host).

## Performance notes

- The first `Sandbox()` on a machine compiles the ~100 MB module (up to minutes); wasmtime caches the compiled module on disk, so later processes start in under a second.
- Each sandbox materializes its own copy of the runtime memory (hundreds of MB) — there is no copy-on-write sharing like the Java host's `CopyOnWriteMemory`. Reuse sandboxes with `reset()` where isolation allows.
- `pip install --no-compile` skips byte-compiling the bundled stdlib tree, which the guest never reads.

The package README ([`boomslang-py/README.md`](https://github.com/HubSpot/boomslang/blob/main/boomslang-py/README.md)) ships with the wheel and is the canonical package-level reference.
