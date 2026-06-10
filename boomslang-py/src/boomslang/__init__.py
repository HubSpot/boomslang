"""Sandboxed CPython 3.14 execution via WebAssembly.

Quickstart:

    from boomslang import Sandbox

    with Sandbox() as sandbox:
        result = sandbox.execute("print('hello from the sandbox')")
        print(result.stdout)
"""

from ._version import __version__
from .errors import (
    BoomslangError,
    PythonExecutionError,
    PythonTimeoutError,
    RuntimeAssetsError,
    SandboxClosedError,
    SandboxPoisonedError,
)
from .limits import ResourceLimits
from .result import ExecutionResult
from .sandbox import Sandbox

__all__ = [
    "BoomslangError",
    "ExecutionResult",
    "PythonExecutionError",
    "PythonTimeoutError",
    "ResourceLimits",
    "RuntimeAssetsError",
    "Sandbox",
    "SandboxClosedError",
    "SandboxPoisonedError",
    "__version__",
]
