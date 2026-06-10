def test_non_prewarmed_stdlib_import(sandbox):
    # Pick a module that is NOT already in sys.modules (i.e. not pre-imported
    # at Wizer time) so this genuinely exercises filesystem-based module
    # loading through the read-only /usr preopen.
    result = sandbox.execute(
        "import sys\n"
        "assert 'wave' not in sys.modules, 'wave was pre-imported; pick another module'\n"
        "import wave\n"
        "print(wave.Error.__name__)"
    )
    assert result.stdout == "Error\n", result.stderr


def test_numpy(sandbox):
    result = sandbox.execute(
        "import numpy as np\nprint(int(np.array([1, 2, 3]).sum()))"
    )
    assert result.stdout == "6\n", result.stderr


def test_pydantic(sandbox):
    result = sandbox.execute(
        "from pydantic import BaseModel\n"
        "class User(BaseModel):\n"
        "    name: str\n"
        "    age: int\n"
        "print(User(name='Ada', age=36).model_dump_json())"
    )
    assert result.stdout == '{"name":"Ada","age":36}\n', result.stderr


def test_deep_recursion(sandbox):
    # Canary for the native wasm stack size: CPython recursion should hit
    # RecursionError (a normal Python error), not a stack-overflow trap.
    result = sandbox.execute(
        "def f(n):\n"
        "    return f(n + 1)\n"
        "try:\n"
        "    f(0)\n"
        "except RecursionError:\n"
        "    print('recursion-error')"
    )
    assert result.stdout == "recursion-error\n", result.stderr
