from pathlib import Path

from .errors import RuntimeAssetsError

_RUNTIME_DIR = Path(__file__).resolve().parent / "_runtime"


def runtime_dir() -> Path:
    if not _RUNTIME_DIR.is_dir():
        raise RuntimeAssetsError(
            f"Runtime assets not found at {_RUNTIME_DIR}. "
            "If running from a source checkout, stage them first with "
            "'just python-stage' (after 'just fetch-main-wasm')."
        )
    return _RUNTIME_DIR


def wasm_path() -> Path:
    path = runtime_dir() / "bin" / "boomslang.wasm"
    if not path.is_file():
        raise RuntimeAssetsError(f"WASM binary not found at {path}")
    with path.open("rb") as f:
        if f.read(4) != b"\0asm":
            raise RuntimeAssetsError(f"{path} is not a WASM binary")
    return path


def usr_host_dir() -> Path:
    """Host directory bound to /usr in the guest (contains local/lib/python3.14)."""
    path = runtime_dir() / "usr"
    if not (path / "local" / "lib" / "python3.14").is_dir():
        raise RuntimeAssetsError(f"Python stdlib tree not found under {path}")
    return path
