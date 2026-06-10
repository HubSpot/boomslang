from dataclasses import dataclass


@dataclass(frozen=True)
class ResourceLimits:
    """Execution limits for a Sandbox. Defaults mirror the Java ResourceLimits."""

    timeout: float = 120.0
    """Wall-clock seconds a single execute() may run before the sandbox is
    interrupted and poisoned."""

    max_memory_bytes: int | None = None
    """Cap on the sandbox's linear memory. None means the wasm32 4 GiB cap."""

    max_output_bytes: int = 10 * 1024 * 1024
    """Maximum bytes accepted from each of the guest's stdout/stderr buffers."""

    def __post_init__(self) -> None:
        if self.timeout <= 0:
            raise ValueError("timeout must be positive")
        if self.max_memory_bytes is not None and self.max_memory_bytes <= 0:
            raise ValueError("max_memory_bytes must be positive")
        if self.max_output_bytes <= 0:
            raise ValueError("max_output_bytes must be positive")
