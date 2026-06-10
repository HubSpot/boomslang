class BoomslangError(Exception):
    """Base class for all boomslang errors."""


class RuntimeAssetsError(BoomslangError):
    """The bundled WASM runtime assets are missing or invalid."""


class PythonExecutionError(BoomslangError):
    """Guest execution failed at the runtime level (trap, output limit, bad input).

    Note: ordinary Python exceptions inside the sandbox do NOT raise this — they
    are reported via ExecutionResult.exit_code and the traceback on stderr.
    """


class PythonCompilationError(BoomslangError):
    """compile() failed — Python syntax error or oversized bytecode."""


class PythonTimeoutError(BoomslangError):
    """Guest execution exceeded ResourceLimits.timeout. The sandbox is poisoned
    until reset() is called."""


class SandboxClosedError(BoomslangError):
    """The sandbox has been closed."""


class SandboxPoisonedError(BoomslangError):
    """The sandbox was poisoned by a timeout or trap — call reset() before reuse."""
