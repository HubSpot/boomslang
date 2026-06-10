import pytest

from boomslang import Sandbox, SandboxClosedError


def test_hello_world(sandbox):
    result = sandbox.execute("print('hello')")
    assert result.stdout == "hello\n"
    assert result.stderr == ""
    assert result.exit_code == 0
    assert result.ok


def test_stdout_stderr_split(sandbox):
    result = sandbox.execute(
        "import sys\nprint('out')\nprint('err', file=sys.stderr)"
    )
    assert result.stdout == "out\n"
    assert result.stderr == "err\n"


def test_python_error_reported_via_exit_code(sandbox):
    result = sandbox.execute("1 / 0")
    assert not result.ok
    assert result.exit_code != 0
    assert "ZeroDivisionError" in result.stderr


def test_state_persists_across_execute(sandbox):
    sandbox.execute("x = 41")
    result = sandbox.execute("print(x + 1)")
    assert result.stdout == "42\n"


def test_reset_clears_state(sandbox):
    sandbox.execute("x = 1")
    sandbox.reset()
    result = sandbox.execute("print(x)")
    assert "NameError" in result.stderr


def test_two_sandboxes_are_isolated():
    with Sandbox() as a, Sandbox() as b:
        a.execute("secret = 'a-only'")
        result = b.execute("print(secret)")
        assert "NameError" in result.stderr


def test_closed_sandbox_rejects_execute():
    sandbox = Sandbox()
    sandbox.close()
    with pytest.raises(SandboxClosedError):
        sandbox.execute("print(1)")


def test_no_stdin(sandbox):
    result = sandbox.execute("input()")
    assert not result.ok
    assert "EOFError" in result.stderr


def test_unicode_roundtrip(sandbox):
    result = sandbox.execute("print('héllo wörld 🐍')")
    assert result.stdout == "héllo wörld 🐍\n"
