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

The guest's mount table is frozen into the runtime image at build time, so
the sandbox probes it once per process and adapts. Depending on the image,
user-supplied `work_dir`/`lib_dir` are either mounted directly or emulated
by syncing files (hardlinks where possible) into and out of the guest around
each execution — semantics are the same either way: files present before an
execution are visible to the guest, and guest-created files appear on the
host after it.

## Stdin

```python
sandbox.set_stdin("Ada\n")
sandbox.execute("print('hello', input())")
```

Mirroring the Java host, stdin is consumed by the next execution and then
cleared — call `set_stdin()` before each execution that needs it. Without it,
`input()` raises `EOFError`.

## Host functions

Guest code can call back into your process through the bundled
`boomslang_host` bridge. Arguments and results cross the boundary as JSON.
Results larger than the bridge's native 1 MiB buffer are transparently
fetched back in chunks, so there is no practical size cap.

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

### Async host functions

Async handlers run on a host thread pool, so guest coroutines can overlap
slow host work (I/O, RPCs) via the bundled `boomslang_host.asyncio` event
loop (the same wire protocol as the Java `AsyncHostRegistry`):

```python
sandbox = Sandbox()

@sandbox.async_host_function("fetch")
def fetch(args):                       # runs on a host worker thread
    return {"id": args["id"], "name": "Ada"}

result = sandbox.execute("""
import asyncio, json
from boomslang_host.asyncio import async_call

async def main():
    a, b = await asyncio.gather(
        async_call("fetch", json.dumps({"id": 1})),
        async_call("fetch", json.dumps({"id": 2})),
    )
    print(json.loads(a)["name"], json.loads(b)["name"])

asyncio.run(main())
""")
```

The execute timeout still applies while the guest is awaiting.

## Bytecode and function calls

`compile()` produces bytecode you can cache and re-run (also in other
sandboxes), skipping repeated parsing; `execute_function()` calls a function
defined in the guest's `__main__` with a JSON array of positional arguments:

```python
bytecode = sandbox.compile("def add(a, b):\n    print(a + b)")
sandbox.load_bytecode(bytecode)
sandbox.execute_function("add", "[2, 40]")   # prints 42
```

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
