import time

import pytest

from boomslang import PythonTimeoutError, ResourceLimits, Sandbox

ASYNC_ROUNDTRIP = """
import asyncio
import json
from boomslang_host.asyncio import async_call

async def main():
    result = await async_call("fetch", json.dumps({"id": 7}))
    print(json.loads(result)["name"])

asyncio.run(main())
"""

ASYNC_CONCURRENT = """
import asyncio
import json
from boomslang_host.asyncio import async_call

async def main():
    results = await asyncio.gather(
        async_call("slow_echo", json.dumps("a")),
        async_call("slow_echo", json.dumps("b")),
        async_call("slow_echo", json.dumps("c")),
    )
    print(",".join(json.loads(r) for r in results))

asyncio.run(main())
"""

ASYNC_ERROR = """
import asyncio
from boomslang_host.asyncio import async_call, HostAsyncError

async def main():
    try:
        await async_call("boom", "{}")
    except HostAsyncError:
        print("caught")

asyncio.run(main())
"""


def test_async_roundtrip():
    with Sandbox(async_host_functions={"fetch": lambda args: {"name": "Ada", "id": args["id"]}}) as sandbox:
        result = sandbox.execute(ASYNC_ROUNDTRIP)
        assert result.stdout == "Ada\n", result.stderr


def test_async_decorator():
    with Sandbox() as sandbox:

        @sandbox.async_host_function("fetch")
        def fetch(args):
            return {"name": "Ada"}

        result = sandbox.execute(ASYNC_ROUNDTRIP)
        assert result.stdout == "Ada\n", result.stderr


def test_async_calls_run_concurrently():
    def slow_echo(value):
        time.sleep(0.3)
        return value

    with Sandbox(async_host_functions={"slow_echo": slow_echo}) as sandbox:
        start = time.monotonic()
        result = sandbox.execute(ASYNC_CONCURRENT)
        elapsed = time.monotonic() - start
        assert result.stdout == "a,b,c\n", result.stderr
        # Three 0.3s handlers awaited via gather should overlap, not serialize.
        assert elapsed < 0.8, f"async handlers appear serialized ({elapsed:.2f}s)"


def test_async_handler_error_raises_in_guest():
    def boom(args):
        raise RuntimeError("async host failure")

    with Sandbox(async_host_functions={"boom": boom}) as sandbox:
        result = sandbox.execute(ASYNC_ERROR)
        assert result.stdout == "caught\n", result.stderr


def test_unregistered_async_function_fails_future():
    with Sandbox() as sandbox:
        result = sandbox.execute(ASYNC_ERROR)
        assert result.stdout == "caught\n", result.stderr


def test_async_await_respects_execute_timeout():
    def hang(args):
        time.sleep(60)
        return None

    with Sandbox(
        limits=ResourceLimits(timeout=1.0), async_host_functions={"hang": hang}
    ) as sandbox:
        with pytest.raises(PythonTimeoutError):
            sandbox.execute(
                "import asyncio\n"
                "from boomslang_host.asyncio import async_call\n"
                "async def main():\n"
                "    await async_call('hang', '{}')\n"
                "asyncio.run(main())"
            )
        assert sandbox.is_poisoned


def test_asyncio_sleep_works():
    with Sandbox() as sandbox:
        result = sandbox.execute(
            "import asyncio\n"
            "import boomslang_host.asyncio\n"
            "async def main():\n"
            "    await asyncio.sleep(0.05)\n"
            "    print('slept')\n"
            "asyncio.run(main())"
        )
        assert result.stdout == "slept\n", result.stderr
