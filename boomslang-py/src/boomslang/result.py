from dataclasses import dataclass


@dataclass(frozen=True)
class ExecutionResult:
    stdout: str
    stderr: str
    exit_code: int
    duration_ms: float

    @property
    def ok(self) -> bool:
        return self.exit_code == 0
