import pytest

from boomslang import PythonCompilationError, Sandbox


def test_compile_and_load_bytecode(sandbox):
    bytecode = sandbox.compile("print('from bytecode')")
    assert isinstance(bytecode, bytes)
    assert len(bytecode) > 0
    result = sandbox.load_bytecode(bytecode)
    assert result.stdout == "from bytecode\n", result.stderr
    assert result.ok


def test_bytecode_reusable_across_sandboxes(sandbox):
    bytecode = sandbox.compile("print(6 * 7)")
    with Sandbox() as other:
        result = other.load_bytecode(bytecode)
        assert result.stdout == "42\n", result.stderr


def test_compile_syntax_error(sandbox):
    with pytest.raises(PythonCompilationError):
        sandbox.compile("def broken(:")


def test_execute_function(sandbox):
    sandbox.execute("def add(a, b):\n    print(a + b)")
    result = sandbox.execute_function("add", "[2, 40]")
    assert result.stdout == "42\n", result.stderr
    assert result.ok


def test_execute_function_no_args(sandbox):
    sandbox.execute("def hello():\n    print('hi')")
    result = sandbox.execute_function("hello")
    assert result.stdout == "hi\n", result.stderr


def test_execute_function_missing(sandbox):
    result = sandbox.execute_function("nope")
    assert not result.ok
