"""Python 3.15.0a7 PEP 810 through WASM, bytecode, and native modules."""

import pytest


@pytest.mark.parametrize(
    "declaration",
    ["lazy import wave", "__lazy_modules__ = ['wave']\nimport wave"],
    ids=["keyword", "module-declaration"],
)
def test_import_is_deferred_until_used(sandbox, declaration):
    result = sandbox.execute(
        "import sys\n"
        "assert sys.get_lazy_imports() == 'normal'\n"
        "assert 'wave' not in sys.modules, 'wave must not be prewarmed'\n"
        f"{declaration}\n"
        "assert 'wave' not in sys.modules\n"
        "print('deferred')"
    )
    assert result.ok, result.stderr
    assert result.stdout == "deferred\n"

    # The proxy must also survive a boundary between host execute() calls.
    result = sandbox.execute(
        "print(wave.Error.__name__)\nassert 'wave' in sys.modules"
    )
    assert result.ok, result.stderr
    assert result.stdout == "Error\n"


def test_regular_import_remains_eager(sandbox):
    result = sandbox.execute(
        "import sys\n"
        "assert sys.get_lazy_imports() == 'normal'\n"
        "assert 'wave' not in sys.modules\n"
        "import wave\n"
        "assert 'wave' in sys.modules"
    )
    assert result.ok, result.stderr


def test_lazy_from_import_survives_bytecode(sandbox):
    bytecode = sandbox.compile(
        "import sys\n"
        "assert 'wave' not in sys.modules\n"
        "lazy from wave import Error\n"
        "assert 'wave' not in sys.modules\n"
        "print(Error.__name__)\n"
        "assert 'wave' in sys.modules"
    )
    result = sandbox.load_bytecode(bytecode)
    assert result.ok, result.stderr
    assert result.stdout == "Error\n"


def test_missing_lazy_import_fails_at_use(sandbox):
    result = sandbox.execute("lazy import boomslang_missing_lazy_module")
    assert result.ok, result.stderr

    result = sandbox.execute("print(boomslang_missing_lazy_module.value)")
    assert not result.ok
    assert "ModuleNotFoundError" in result.stderr
    assert "boomslang_missing_lazy_module" in result.stderr

    result = sandbox.execute("print(6 * 7)")
    assert result.ok, result.stderr
    assert result.stdout == "42\n"


def test_global_mode_filter_and_reset(sandbox):
    result = sandbox.execute(
        "import sys\n"
        "assert sys.get_lazy_imports() == 'normal'\n"
        "assert sys.get_lazy_imports_filter() is None\n"
        "sys.set_lazy_imports_filter(lambda importer, name, names: name == 'wave')\n"
        "sys.set_lazy_imports('all')\n"
        "import wave\n"
        "assert 'wave' not in sys.modules\n"
        "sys.set_lazy_imports_filter(lambda importer, name, names: False)\n"
        "import wave as eager_wave\n"
        "assert 'wave' in sys.modules\n"
        "assert wave is eager_wave"
    )
    assert result.ok, result.stderr

    sandbox.reset()
    result = sandbox.execute(
        "import sys\n"
        "assert sys.get_lazy_imports() == 'normal'\n"
        "assert sys.get_lazy_imports_filter() is None\n"
        "assert 'wave' not in sys.modules"
    )
    assert result.ok, result.stderr


def test_lazy_host_bridge_call(sandbox):
    received = []

    @sandbox.host_function("lazy_echo")
    def echo(args):
        received.append(args)
        return args["value"]

    result = sandbox.execute("lazy from boomslang_host import call")
    assert result.ok, result.stderr
    assert received == []

    result = sandbox.execute("print(call('lazy_echo', '{\"value\": 42}'))")
    assert result.ok, result.stderr
    assert result.stdout == "42\n"
    assert received == [{"value": 42}]


@pytest.mark.parametrize(
    ("source", "expected"),
    [
        ("lazy import numpy as np\nprint(int(np.array([1, 2, 3]).sum()))", "6"),
        ("lazy import pandas as pd\nprint(int(pd.Series([1, 2, 3]).sum()))", "6"),
        (
            "lazy from matplotlib.figure import Figure\n"
            "print(len(Figure().subplots().plot([1, 2, 3])))",
            "1",
        ),
        (
            "lazy from PIL import Image\n"
            "print(Image.new('RGB', (2, 3)).size)",
            "(2, 3)",
        ),
        (
            "lazy from pydantic import BaseModel\n"
            "class Model(BaseModel):\n"
            "    value: int\n"
            "print(Model(value='42').value)",
            "42",
        ),
        (
            "lazy from pydantic_core import from_json\n"
            "print(from_json(b'{\"value\": 42}')[\"value\"])",
            "42",
        ),
        (
            "import io\n"
            "lazy import ijson\n"
            "print(sum(ijson.items(io.BytesIO(b'[1, 2, 3]'), 'item')))",
            "6",
        ),
        (
            "lazy from jinja2 import Template\n"
            "print(Template('Hello {{ name }}').render(name='Ada'))",
            "Hello Ada",
        ),
    ],
    ids=["numpy", "pandas", "matplotlib", "pillow", "pydantic", "pydantic-core", "ijson", "jinja2"],
)
def test_lazy_bundled_packages(sandbox, source, expected):
    # Most packages already exist in the Wizer image. This tests successful
    # lazy binding and native calls, not deferred initialization of those packages.
    result = sandbox.execute(source)
    assert result.ok, result.stderr
    assert result.stdout == expected + "\n"


@pytest.mark.parametrize(
    "declaration",
    [
        "lazy import PIL.Image as image",
        "__lazy_modules__ = ['PIL.Image']\nimport PIL.Image as image",
        "sys.set_lazy_imports_filter(lambda importer, name, names: name == 'PIL.Image')\n"
        "sys.set_lazy_imports('all')\nimport PIL.Image as image",
    ],
    ids=["keyword", "module-declaration", "filtered-all"],
)
def test_preloaded_dotted_import_alias_is_module(sandbox, declaration):
    # CPython 3.15.0a7 incorrectly resolved the final Image component against
    # PIL.Image itself, returning its Image class rather than the module.
    result = sandbox.execute(
        "import sys\n"
        "import PIL.Image as eager_image\n"
        f"{declaration}\n"
        "assert image is eager_image\n"
        "print(image.new('RGB', (2, 3)).size)\n"
        "sys.set_lazy_imports('normal')\n"
        "sys.set_lazy_imports_filter(None)"
    )
    assert result.ok, result.stderr
    assert result.stdout == "(2, 3)\n"


@pytest.mark.parametrize(
    "declaration",
    ["lazy import wave", "lazy from wave import Error"],
    ids=["import", "from-import"],
)
def test_none_mode_suppresses_lazy_keyword(sandbox, declaration):
    result = sandbox.execute(
        "import sys\n"
        "assert 'wave' not in sys.modules\n"
        "filter_calls = []\n"
        "sys.set_lazy_imports_filter(\n"
        "    lambda importer, name, names: filter_calls.append(name) or True)\n"
        "sys.set_lazy_imports('none')\n"
        f"{declaration}\n"
        "assert 'wave' in sys.modules\n"
        "assert filter_calls == []\n"
        "assert sys.get_lazy_imports() == 'none'\n"
        "sys.set_lazy_imports('normal')\n"
        "sys.set_lazy_imports_filter(None)"
    )
    assert result.ok, result.stderr
