def test_python_version(sandbox):
    result = sandbox.execute(
        "import sys\nassert sys.version_info == (3, 15, 0, 'alpha', 7), sys.version\n"
        "print(sys.implementation.cache_tag)"
    )
    assert result.stdout == "cpython-315\n", result.stderr


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


def test_native_decimal(sandbox):
    # Python 3.15 requires explicitly selecting the bundled libmpdec.
    result = sandbox.execute(
        "from _decimal import Decimal\nprint(Decimal('0.1') + Decimal('0.2'))"
    )
    assert result.stdout == "0.3\n", result.stderr


def test_all_registered_native_extensions_import(sandbox):
    result = sandbox.execute(
        "import importlib, sys\n"
        "prefixes = ('numpy.', 'pandas.', 'matplotlib.', 'PIL.', 'ijson.')\n"
        "exact = {'_pydantic_core', '_boomslang_host'}\n"
        "registered = set(sys.builtin_module_names)\n"
        "assert exact <= registered, exact - registered\n"
        "for prefix in prefixes:\n"
        "    assert any(name.startswith(prefix) for name in registered), prefix\n"
        "native_modules = sorted(name for name in registered\n"
        "    if name in exact or name.startswith(prefixes))\n"
        "for name in native_modules:\n"
        "    importlib.import_module(name)\n"
        "print('all native extensions imported')"
    )
    assert result.ok, result.stderr
    assert result.stdout == "all native extensions imported\n"


def test_matplotlib_native_path_and_plot(sandbox):
    result = sandbox.execute(
        "from matplotlib.figure import Figure\n"
        "from matplotlib.path import Path\n"
        "print(len(Figure().subplots().plot([1, 2, 3])))\n"
        "path = Path([(0, 0), (1, 0), (1, 1), (0, 1), (0, 0)], closed=True)\n"
        "print(path.contains_point((0.5, 0.5)), path.contains_point((2, 2)))"
    )
    assert result.ok, result.stderr
    assert result.stdout == "1\nTrue False\n"
