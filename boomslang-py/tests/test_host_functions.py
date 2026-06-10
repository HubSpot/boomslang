import logging

from boomslang import Sandbox

GUEST_CALL = (
    "import json\n"
    "from boomslang_host import call\n"
    "print(call('echo', json.dumps({'a': 1})))"
)


def test_host_function_roundtrip():
    with Sandbox() as sandbox:

        @sandbox.host_function("echo")
        def echo(args):
            return {"echoed": args}

        result = sandbox.execute(GUEST_CALL)
        assert result.stdout == '{"echoed": {"a": 1}}\n', result.stderr


def test_host_functions_constructor_arg():
    with Sandbox(host_functions={"echo": lambda args: args}) as sandbox:
        result = sandbox.execute(GUEST_CALL)
        assert result.stdout == '{"a": 1}\n', result.stderr


def test_host_function_error_surfaces_in_guest():
    with Sandbox() as sandbox:

        @sandbox.host_function("boom")
        def boom(args):
            raise RuntimeError("host-side failure")

        result = sandbox.execute(
            "from boomslang_host import call\n"
            "try:\n"
            "    call('boom', '{}')\n"
            "except RuntimeError:\n"
            "    print('guest-caught')"
        )
        assert result.stdout == "guest-caught\n", result.stderr
        # The sandbox stays healthy afterwards.
        assert sandbox.execute("print('ok')").stdout == "ok\n"


def test_unregistered_host_function_errors_in_guest(sandbox):
    result = sandbox.execute(
        "from boomslang_host import call\n"
        "try:\n"
        "    call('nope', '{}')\n"
        "except RuntimeError:\n"
        "    print('guest-caught')"
    )
    assert result.stdout == "guest-caught\n", result.stderr


def test_raw_call_handler():
    with Sandbox(call_handler=lambda name, args: f'"{name}:{args}"') as sandbox:
        result = sandbox.execute(
            "from boomslang_host import call\nprint(call('anything', '{}'))"
        )
        assert result.stdout == '"anything:{}"\n', result.stderr


def test_log_bridges_to_logging(caplog):
    with Sandbox() as sandbox:
        with caplog.at_level(logging.DEBUG, logger="boomslang.guest"):
            sandbox.execute("from boomslang_host import log\nlog(2, 'hi from guest')")
    assert any(
        record.message == "hi from guest" and record.levelno == logging.INFO
        for record in caplog.records
    )


def test_custom_on_log():
    seen = []
    with Sandbox(on_log=lambda level, message: seen.append((level, message))) as sandbox:
        sandbox.execute("from boomslang_host import log\nlog(3, 'warn msg')")
    assert seen == [(3, "warn msg")]


def test_large_host_function_result():
    # Larger than the guest bridge's fixed 1 MiB native buffer — fetched back
    # through the chunked __result_pending__/__result_chunk__ protocol.
    payload = "x" * (3 * 1024 * 1024)
    with Sandbox(host_functions={"big": lambda args: payload}) as sandbox:
        result = sandbox.execute(
            "import json\n"
            "from boomslang_host import call\n"
            "data = json.loads(call('big', '{}'))\n"
            "print(len(data), data[0], data[-1])"
        )
        assert result.stdout == f"{len(payload)} x x\n", result.stderr


def test_large_async_host_function_result():
    payload = "y" * (2 * 1024 * 1024)
    with Sandbox(async_host_functions={"big": lambda args: payload}) as sandbox:
        result = sandbox.execute(
            "import asyncio, json\n"
            "from boomslang_host.asyncio import async_call\n"
            "async def main():\n"
            "    return json.loads(await async_call('big', '{}'))\n"
            "data = asyncio.run(main())\n"
            "print(len(data), data[0])"
        )
        assert result.stdout == f"{len(payload)} y\n", result.stderr


def test_error_after_large_result_still_raises():
    # A handler error must not be masked by a stale parked result.
    payload = "z" * (2 * 1024 * 1024)
    calls = {"n": 0}

    def flaky(args):
        calls["n"] += 1
        if calls["n"] > 1:
            raise RuntimeError("second call fails")
        return payload

    with Sandbox(host_functions={"flaky": flaky}) as sandbox:
        result = sandbox.execute(
            "import json\n"
            "from boomslang_host import call\n"
            "first = json.loads(call('flaky', '{}'))\n"
            "print(len(first))\n"
            "try:\n"
            "    call('flaky', '{}')\n"
            "except RuntimeError:\n"
            "    print('second-failed')"
        )
        assert result.stdout == f"{len(payload)}\nsecond-failed\n", result.stderr
