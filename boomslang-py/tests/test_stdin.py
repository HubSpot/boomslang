def test_stdin_input(sandbox):
    sandbox.set_stdin("Ada\n")
    result = sandbox.execute("print('hello', input())")
    assert result.stdout == "hello Ada\n", result.stderr


def test_stdin_read_all(sandbox):
    sandbox.set_stdin(b"line1\nline2\n")
    result = sandbox.execute("import sys\nprint(sys.stdin.read(), end='')")
    assert result.stdout == "line1\nline2\n", result.stderr


def test_stdin_cleared_after_execute(sandbox):
    sandbox.set_stdin("once\n")
    first = sandbox.execute("print(input())")
    assert first.stdout == "once\n", first.stderr
    second = sandbox.execute(
        "try:\n    input()\nexcept EOFError:\n    print('eof')"
    )
    assert second.stdout == "eof\n", second.stderr


def test_clear_stdin(sandbox):
    sandbox.set_stdin("never seen\n")
    sandbox.clear_stdin()
    result = sandbox.execute(
        "try:\n    input()\nexcept EOFError:\n    print('eof')"
    )
    assert result.stdout == "eof\n", result.stderr


def test_no_stdin_by_default(sandbox):
    result = sandbox.execute("input()")
    assert not result.ok
    assert "EOFError" in result.stderr
