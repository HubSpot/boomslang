import pytest

from boomslang import (
    PythonExecutionError,
    PythonTimeoutError,
    ResourceLimits,
    Sandbox,
    SandboxPoisonedError,
)


def test_timeout_poisons_and_reset_revives():
    with Sandbox(limits=ResourceLimits(timeout=1.0)) as sandbox:
        with pytest.raises(PythonTimeoutError):
            sandbox.execute("while True: pass")
        assert sandbox.is_poisoned
        with pytest.raises(SandboxPoisonedError):
            sandbox.execute("print(1)")
        sandbox.reset()
        assert not sandbox.is_poisoned
        assert sandbox.execute("print('revived')").stdout == "revived\n"


def test_output_limit():
    with Sandbox(limits=ResourceLimits(max_output_bytes=1024)) as sandbox:
        with pytest.raises(PythonExecutionError, match="exceeds limit"):
            sandbox.execute("print('x' * 10000)")


def test_memory_limit_blocks_large_allocations():
    # The runtime image itself needs ~200 MB; cap total memory modestly above
    # that so a large allocation cannot be satisfied.
    baseline_pages = _baseline_pages()
    cap_bytes = (baseline_pages + 512) * 64 * 1024  # baseline + 32 MiB
    with Sandbox(limits=ResourceLimits(max_memory_bytes=cap_bytes)) as sandbox:
        result = sandbox.execute(
            "try:\n"
            "    data = bytearray(256 * 1024 * 1024)\n"
            "except MemoryError:\n"
            "    print('memory-error')"
        )
        assert result.stdout == "memory-error\n", result.stderr


def _baseline_pages() -> int:
    with Sandbox() as sandbox:
        return sandbox.heap_pages()


def test_invalid_limits_rejected():
    with pytest.raises(ValueError):
        ResourceLimits(timeout=0)
    with pytest.raises(ValueError):
        ResourceLimits(max_output_bytes=0)
