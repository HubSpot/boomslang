# boomslang (Python)

Run sandboxed Python code from Python. This package bundles boomslang's
CPython 3.14 runtime compiled to WebAssembly (with numpy, pandas, pydantic,
matplotlib, Pillow, and ijson preloaded) and executes it with
[wasmtime](https://pypi.org/project/wasmtime/). Guest code has no network
access and can only touch the directories you mount.

## Install

Wheels are published as GitHub release assets (not PyPI):

```bash
pip install https://github.com/HubSpot/boomslang/releases/download/<tag>/boomslang-<version>-py3-none-any.whl
```

From a source checkout: `just fetch-main-wasm && just python-stage`, then
`pip install -e boomslang-py`.

## Quickstart

```python
from boomslang import Sandbox

with Sandbox() as sandbox:
    result = sandbox.execute("print('hello from the sandbox')")
    print(result.stdout)        # hello from the sandbox
    print(result.exit_code)     # 0
```

Interpreter state persists across `execute()` calls on the same sandbox;
`sandbox.reset()` restores the pristine interpreter image (files under the
work dir persist). Python errors in guest code don't raise on the host — they
surface as `exit_code != 0` with the traceback in `result.stderr`.

## Resource limits

```python
from boomslang import ResourceLimits, Sandbox

sandbox = Sandbox(limits=ResourceLimits(
    timeout=10.0,                       # seconds, default 120
    max_memory_bytes=512 * 1024 * 1024, # default: wasm32 4 GiB cap
    max_output_bytes=1024 * 1024,       # per stream, default 10 MiB
))
```

A timeout raises `PythonTimeoutError` and poisons the sandbox; call
`reset()` to revive it. `max_memory_bytes` must exceed the baseline runtime
image (~150 MB) or instantiation fails.

## Filesystem

The guest filesystem layout is fixed by the runtime image (the guest libc's
preopen table is baked in at build time):

| Guest path | Host side                          | Access     |
|------------|------------------------------------|------------|
| `/usr`     | bundled runtime + stdlib           | read-only  |
| `/lib`     | `lib_dir=` (on the guest sys.path) | read-write |
| `/work`    | `work_dir=`                        | read-write |
| `/tmp`     | managed per-sandbox temp dir       | read-write |

`work_dir` and `lib_dir` default to managed temporary directories
(`sandbox.work_dir` / `sandbox.lib_dir` expose the host paths). Arbitrary
additional mount points are not supported — share files through `/work`, and
make extra pure-Python libraries importable by placing them in `lib_dir`.

There is no stdin: `input()` raises `EOFError`.

## Host functions

Guest code can call back into your process through the bundled
`boomslang_host` bridge. Arguments and results cross the boundary as JSON;
results are capped at 1 MiB by the guest-side buffer.

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

For full control pass `call_handler=lambda name, args_json: ...` (raw JSON
strings in and out), and `on_log=lambda level, message: ...` to receive
`boomslang_host.log()` output (default: forwarded to the `boomslang.guest`
logger).

## Performance notes

- The first `Sandbox()` ever created on a machine compiles the ~100 MB WASM
  module (seconds to a couple of minutes depending on hardware). The compiled
  module is cached on disk by wasmtime, so subsequent processes start in
  under a second.
- Each sandbox materializes its own copy of the runtime's linear memory
  (hundreds of MB). Reuse sandboxes (with `reset()`) where isolation
  requirements allow.
- `pip install --no-compile` skips byte-compiling the bundled stdlib tree,
  which the guest never reads anyway.
