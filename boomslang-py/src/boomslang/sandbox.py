import json
import logging
import os
import tempfile
import threading
import time
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path
from typing import Any

import wasmtime
from wasmtime import DirPerms, FilePerms, Linker, Store, WasiConfig

from ._assets import usr_host_dir
from ._engine import DISARMED_DEADLINE_TICKS, runtime
from ._trampolines import define_boomslang_imports
from .errors import (
    PythonExecutionError,
    PythonTimeoutError,
    SandboxClosedError,
    SandboxPoisonedError,
)
from .limits import ResourceLimits
from .result import ExecutionResult

logger = logging.getLogger(__name__)
guest_logger = logging.getLogger("boomslang.guest")

HostFunction = Callable[[Any], Any]
"""Receives the JSON-decoded args; its return value is JSON-encoded back to the guest."""

GUEST_WORK_PATH = "/work"

_EXPORT_NAMES = (
    "alloc",
    "dealloc",
    "execute",
    "reset_state",
    "get_stdout_len",
    "get_stderr_len",
    "get_stdout",
    "get_stderr",
    "get_heap_pages",
)

_LOG_LEVELS = {0: logging.DEBUG, 1: logging.DEBUG, 2: logging.INFO, 3: logging.WARNING}


def _default_on_log(level: int, message: str) -> None:
    guest_logger.log(_LOG_LEVELS.get(level, logging.ERROR), "%s", message)


class Sandbox:
    """A sandboxed CPython 3.14 interpreter running in WebAssembly.

    Each Sandbox is an isolated interpreter instantiated from the bundled
    Wizer-pre-initialized runtime image. Interpreter state persists across
    execute() calls; reset() restores the pristine image (files under the
    work dir persist).

    Guest code can call back into the host via the bundled bridge module:

        from boomslang_host import call, log
        call("my_function", '{"key": "value"}')   # JSON in, JSON out

    Host-function results are capped at 1 MiB by the guest-side bridge buffer.

    The guest filesystem layout is fixed by the runtime image (the libc
    preopen table is baked in at Wizer time, bound positionally to the host's
    preopen order): /usr (bundled runtime, read-only), /lib (extra Python
    libraries, on sys.path), /work (shared work dir), /tmp (ephemeral).
    Arbitrary additional mount points are not supported — share files through
    work_dir or lib_dir.

    Not thread-safe for concurrent calls on the same instance (calls are
    serialized internally); use one Sandbox per thread for parallelism.
    """

    def __init__(
        self,
        *,
        limits: ResourceLimits | None = None,
        work_dir: str | os.PathLike | None = None,
        lib_dir: str | os.PathLike | None = None,
        host_functions: Mapping[str, HostFunction] | None = None,
        call_handler: Callable[[str, str], str] | None = None,
        on_log: Callable[[int, str], None] | None = None,
        python_path: Sequence[str] = (GUEST_WORK_PATH,),
    ) -> None:
        self._limits = limits or ResourceLimits()
        self._host_functions: dict[str, HostFunction] = dict(host_functions or {})
        self._call_handler = call_handler
        self._on_log = on_log or _default_on_log
        self._python_path = tuple(python_path)
        self._lock = threading.RLock()
        self._closed = False
        self._poisoned = False

        self._scratch = tempfile.TemporaryDirectory(prefix="boomslang-scratch-")
        (Path(self._scratch.name) / "tmp").mkdir()
        (Path(self._scratch.name) / "lib").mkdir()
        if work_dir is None:
            self._managed_work = tempfile.TemporaryDirectory(prefix="boomslang-work-")
            self._work_dir = Path(self._managed_work.name)
        else:
            self._managed_work = None
            self._work_dir = Path(work_dir)
            self._work_dir.mkdir(parents=True, exist_ok=True)
        if lib_dir is None:
            self._lib_dir = Path(self._scratch.name) / "lib"
        else:
            self._lib_dir = Path(lib_dir)
            if not self._lib_dir.is_dir():
                raise ValueError(f"lib_dir is not a directory: {self._lib_dir}")

        self._instantiate()

    # ------------------------------------------------------------------
    # Lifecycle

    def _instantiate(self) -> None:
        rt = runtime()
        rt.ensure_ticker()

        store = Store(rt.engine)
        store.set_epoch_deadline(DISARMED_DEADLINE_TICKS)
        if self._limits.max_memory_bytes is not None:
            store.set_limits(memory_size=self._limits.max_memory_bytes)

        wasi = WasiConfig()
        wasi.env = [("PYTHONHOME", "/usr/local"), ("PYTHONDONTWRITEBYTECODE", "1")]
        # The guest libc's preopen table was baked in by Wizer and binds
        # positionally: fd 3 = /usr, fd 4 = /lib, fd 5 = /work, fd 6 = /tmp.
        # Registration order here is the contract; the guest_path strings are
        # ignored by the guest.
        wasi.preopen_dir(str(usr_host_dir()), "/usr", DirPerms.READ_ONLY, FilePerms.READ_ONLY)
        wasi.preopen_dir(str(self._lib_dir), "/lib")
        wasi.preopen_dir(str(self._work_dir), GUEST_WORK_PATH)
        wasi.preopen_dir(str(Path(self._scratch.name) / "tmp"), "/tmp")
        # Guest stdout/stderr are captured by in-guest buffers; WASI streams
        # only carry low-level runtime diagnostics.
        wasi.stdout_file = str(Path(self._scratch.name) / ".wasi-stdout.log")
        wasi.stderr_file = str(Path(self._scratch.name) / ".wasi-stderr.log")
        store.set_wasi(wasi)

        linker = Linker(rt.engine)
        linker.define_wasi()
        define_boomslang_imports(linker, self)

        instance = linker.instantiate(store, rt.module)
        exports = instance.exports(store)
        self._store = store
        self._memory = exports["memory"]
        self._fn = {name: exports[name] for name in _EXPORT_NAMES}

        self._bootstrap_python_path()

    def _bootstrap_python_path(self) -> None:
        if not self._python_path:
            return
        script = "import sys"
        for entry in self._python_path:
            script += f"\nsys.path.insert(0, {entry!r})"
        status = self._call_execute(script)
        if status != 0:
            logger.warning(
                "python_path injection failed with code %s: %s",
                status,
                self._read_stream("stderr"),
            )

    def reset(self) -> None:
        """Restore the pristine interpreter image. Files in the work dir persist."""
        with self._lock:
            if self._closed:
                raise SandboxClosedError("Sandbox has been closed")
            self._instantiate()
            self._poisoned = False

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._fn = {}
            self._memory = None
            self._store = None
            self._scratch.cleanup()
            if self._managed_work is not None:
                self._managed_work.cleanup()

    def __enter__(self) -> "Sandbox":
        return self

    def __exit__(self, *exc_info) -> None:
        self.close()

    # ------------------------------------------------------------------
    # Execution

    def execute(self, code: str) -> ExecutionResult:
        with self._lock:
            self._check_usable()
            start = time.perf_counter()
            exit_code = self._call_execute(code)
            stdout = self._read_stream("stdout")
            stderr = self._read_stream("stderr")
            duration_ms = (time.perf_counter() - start) * 1000
            return ExecutionResult(
                stdout=stdout, stderr=stderr, exit_code=exit_code, duration_ms=duration_ms
            )

    def _call_execute(self, code: str) -> int:
        data = code.encode("utf-8")
        ptr = self._alloc(len(data))
        try:
            self._memory.write(self._store, data, ptr)
            self._store.set_epoch_deadline(runtime().deadline_ticks(self._limits.timeout))
            try:
                return int(self._fn["execute"](self._store, ptr, len(data)))
            except wasmtime.Trap as trap:
                self._poisoned = True
                self._disarm_deadline()
                if trap.trap_code == wasmtime.TrapCode.INTERRUPT:
                    raise PythonTimeoutError(
                        f"execution exceeded the {self._limits.timeout}s timeout; "
                        "the sandbox is poisoned until reset()"
                    ) from trap
                stderr = self._try_read_stderr()
                message = stderr or trap.message
                raise PythonExecutionError(message) from trap
            finally:
                self._disarm_deadline()
        finally:
            self._dealloc(ptr, len(data))

    def _disarm_deadline(self) -> None:
        self._store.set_epoch_deadline(DISARMED_DEADLINE_TICKS)

    def _alloc(self, size: int) -> int:
        return int(self._fn["alloc"](self._store, size)) & 0xFFFFFFFF

    def _dealloc(self, ptr: int, size: int) -> None:
        try:
            self._fn["dealloc"](self._store, ptr, size)
        except Exception:
            logger.debug("dealloc failed", exc_info=True)

    def _read_stream(self, name: str) -> str:
        length = int(self._fn[f"get_{name}_len"](self._store))
        if length <= 0:
            return ""
        if length > self._limits.max_output_bytes:
            raise PythonExecutionError(
                f"{name} size {length} bytes exceeds limit of "
                f"{self._limits.max_output_bytes} bytes"
            )
        ptr = self._alloc(length)
        try:
            self._fn[f"get_{name}"](self._store, ptr, length)
            data = self._memory.read(self._store, ptr, ptr + length)
            return bytes(data).decode("utf-8", errors="replace")
        finally:
            self._dealloc(ptr, length)

    def _try_read_stderr(self) -> str:
        try:
            return self._read_stream("stderr")
        except Exception:
            return ""

    # ------------------------------------------------------------------
    # Host functions

    def host_function(self, name: str):
        """Decorator registering a host function callable from guest code via
        boomslang_host.call(name, args_json)."""

        def decorator(fn: HostFunction) -> HostFunction:
            self._host_functions[name] = fn
            return fn

        return decorator

    def _dispatch_host_call(self, name: str, args_json: str) -> str:
        fn = self._host_functions.get(name)
        if fn is not None:
            args = json.loads(args_json) if args_json else None
            return json.dumps(fn(args))
        if self._call_handler is not None:
            return self._call_handler(name, args_json)
        raise KeyError(f"no host function registered for {name!r}")

    # ------------------------------------------------------------------
    # Introspection

    @property
    def work_dir(self) -> Path:
        """Host path mounted at /work inside the guest."""
        return self._work_dir

    @property
    def lib_dir(self) -> Path:
        """Host path mounted at /lib inside the guest (on the guest's sys.path)."""
        return self._lib_dir

    @property
    def guest_work_path(self) -> str:
        return GUEST_WORK_PATH

    @property
    def is_closed(self) -> bool:
        return self._closed

    @property
    def is_poisoned(self) -> bool:
        return self._poisoned

    @property
    def limits(self) -> ResourceLimits:
        return self._limits

    def heap_pages(self) -> int:
        """Current size of the guest linear memory in 64 KiB pages."""
        with self._lock:
            self._check_usable()
            return int(self._fn["get_heap_pages"](self._store))

    def _check_usable(self) -> None:
        if self._closed:
            raise SandboxClosedError("Sandbox has been closed")
        if self._poisoned:
            raise SandboxPoisonedError(
                "Sandbox has been poisoned after a timeout or trap — call reset() before reuse"
            )
