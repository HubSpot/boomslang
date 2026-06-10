import base64
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
from ._async import AsyncHostRegistry
from ._engine import DISARMED_DEADLINE_TICKS, runtime
from ._trampolines import define_boomslang_imports
from .errors import (
    PythonCompilationError,
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

MAX_BYTECODE_SIZE = 10 * 1024 * 1024

# Reserved control names for fetching host-call results larger than the
# guest bridge's fixed 1 MiB buffer (see _GUEST_BOOTSTRAP below).
_RESULT_PENDING = "__result_pending__"
_RESULT_CHUNK = "__result_chunk__"
# Base64 chunk size; must stay comfortably under the guest's 1 MiB buffer.
_RESULT_CHUNK_SIZE = 768 * 1024

_EXPORT_NAMES = (
    "alloc",
    "dealloc",
    "execute",
    "compile_source",
    "load_bytecode",
    "execute_function",
    "reset_state",
    "get_stdout_len",
    "get_stderr_len",
    "get_stdout",
    "get_stderr",
    "get_heap_pages",
)

_LOG_LEVELS = {0: logging.DEBUG, 1: logging.DEBUG, 2: logging.INFO, 3: logging.WARNING}

# Wraps boomslang_host.call so results larger than the guest bridge's fixed
# 1 MiB native buffer are fetched in base64 chunks through the reserved
# __result_pending__/__result_chunk__ control calls. The wrapper is installed
# by monkeypatching because the prewarmed boomslang_host module (and the
# `call` reference captured by boomslang_host.asyncio) is frozen into the
# Wizer memory snapshot — overriding the .py file on disk would not be seen.
# Under hosts without the control calls (e.g. the Java host) the pending probe
# fails and the original error is re-raised, preserving stock behavior.
_GUEST_BOOTSTRAP = """
def __boomslang_install():
    import base64
    import boomslang_host
    if hasattr(boomslang_host, "_boomslang_native_call"):
        return
    native = boomslang_host.call
    boomslang_host._boomslang_native_call = native

    def patched(name, args=""):
        try:
            return native(name, args)
        except RuntimeError:
            try:
                header = native("__result_pending__", "")
            except RuntimeError:
                header = ""
            if not header:
                raise
            total = int(header)
            parts = []
            offset = 0
            while offset < total:
                part = native("__result_chunk__", str(offset))
                if not part:
                    raise RuntimeError("host call failed (truncated chunked result)")
                parts.append(part)
                offset += len(part)
            return base64.b64decode("".join(parts)).decode("utf-8")

    boomslang_host.call = patched
    try:
        import boomslang_host.asyncio
        boomslang_host.asyncio.call = patched
    except Exception:
        pass


__boomslang_install()
del __boomslang_install
"""

_CLEAR_STDIN_SCRIPT = (
    "import sys, io\nsys.stdin = io.TextIOWrapper(io.BytesIO(b''), encoding='utf-8')"
)


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

    Async host functions are awaited through the bundled event loop:

        import asyncio
        from boomslang_host.asyncio import async_call
        asyncio.run(async_call("my_async_function", '{"key": "value"}'))

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
        async_host_functions: Mapping[str, HostFunction] | None = None,
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
        self._stdin_armed = False
        self._deadline_at: float | None = None
        self._parked_result: str | None = None
        self._async_registry = AsyncHostRegistry(self._deadline_remaining)
        for name, fn in (async_host_functions or {}).items():
            self._register_async(name, fn)

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
        self._stdin_armed = False

        self._bootstrap()

    def _bootstrap(self) -> None:
        script = _GUEST_BOOTSTRAP
        if self._python_path:
            script += "\nimport sys"
            for entry in self._python_path:
                script += f"\nsys.path.insert(0, {entry!r})"
        status = self._invoke_script("execute", script)
        if status != 0:
            logger.warning(
                "sandbox bootstrap failed with code %s: %s",
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
            self._async_registry.close()
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
            exit_code = self._invoke_script("execute", code)
            return self._collect_result(exit_code, start)

    def execute_function(self, name: str, args_json: str = "") -> ExecutionResult:
        """Call a function defined in the guest's __main__. args_json must be a
        JSON array of positional arguments (e.g. "[2, 40]") or empty."""
        with self._lock:
            self._check_usable()
            start = time.perf_counter()
            name_data = name.encode("utf-8")
            args_data = args_json.encode("utf-8")
            name_ptr = self._alloc(len(name_data))
            args_ptr = self._alloc(len(args_data)) if args_data else 0
            try:
                self._memory.write(self._store, name_data, name_ptr)
                if args_data:
                    self._memory.write(self._store, args_data, args_ptr)
                exit_code = self._invoke(
                    "execute_function", name_ptr, len(name_data), args_ptr, len(args_data)
                )
            finally:
                self._dealloc(name_ptr, len(name_data))
                if args_ptr:
                    self._dealloc(args_ptr, len(args_data))
            return self._collect_result(exit_code, start)

    def compile(self, code: str) -> bytes:
        """Compile Python source to bytecode that can be re-run via load_bytecode()."""
        with self._lock:
            self._check_usable()
            data = code.encode("utf-8")
            source_ptr = self._alloc(len(data))
            output_ptr = self._alloc(MAX_BYTECODE_SIZE)
            try:
                self._memory.write(self._store, data, source_ptr)
                bytecode_len = self._invoke(
                    "compile_source", source_ptr, len(data), output_ptr, MAX_BYTECODE_SIZE
                )
                if bytecode_len < 0:
                    stderr = self._try_read_stderr()
                    message = stderr or f"compilation failed with code {bytecode_len}"
                    if bytecode_len == -3:
                        message = f"compiled bytecode exceeds {MAX_BYTECODE_SIZE} bytes"
                    raise PythonCompilationError(message)
                data_out = self._memory.read(
                    self._store, output_ptr, output_ptr + bytecode_len
                )
                return bytes(data_out)
            finally:
                self._dealloc(source_ptr, len(data))
                self._dealloc(output_ptr, MAX_BYTECODE_SIZE)

    def load_bytecode(self, bytecode: bytes) -> ExecutionResult:
        """Execute bytecode previously produced by compile()."""
        with self._lock:
            self._check_usable()
            start = time.perf_counter()
            ptr = self._alloc(len(bytecode))
            try:
                self._memory.write(self._store, bytecode, ptr)
                exit_code = self._invoke("load_bytecode", ptr, len(bytecode))
            finally:
                self._dealloc(ptr, len(bytecode))
            return self._collect_result(exit_code, start)

    # ------------------------------------------------------------------
    # Stdin

    def set_stdin(self, data: bytes | str) -> None:
        """Provide stdin for the next execute()/execute_function()/load_bytecode()
        call. Mirrors the Java host: stdin is cleared after each execution."""
        if isinstance(data, str):
            data = data.encode("utf-8")
        encoded = base64.b64encode(data).decode("ascii")
        script = (
            "import sys, io, base64\n"
            f"sys.stdin = io.TextIOWrapper(io.BytesIO(base64.b64decode('{encoded}')), "
            "encoding='utf-8')"
        )
        with self._lock:
            self._check_usable()
            status = self._invoke_script("execute", script)
            if status != 0:
                raise PythonExecutionError(
                    self._try_read_stderr() or "failed to set stdin"
                )
            self._stdin_armed = True

    def clear_stdin(self) -> None:
        with self._lock:
            self._check_usable()
            self._clear_stdin_locked()

    def _clear_stdin_locked(self) -> None:
        if not self._stdin_armed:
            return
        self._stdin_armed = False
        status = self._invoke_script("execute", _CLEAR_STDIN_SCRIPT)
        if status != 0:
            logger.warning("failed to clear sandbox stdin: %s", self._try_read_stderr())

    # ------------------------------------------------------------------
    # Guest invocation plumbing

    def _collect_result(self, exit_code: int, start: float) -> ExecutionResult:
        stdout = self._read_stream("stdout")
        stderr = self._read_stream("stderr")
        self._clear_stdin_locked()
        duration_ms = (time.perf_counter() - start) * 1000
        return ExecutionResult(
            stdout=stdout, stderr=stderr, exit_code=exit_code, duration_ms=duration_ms
        )

    def _invoke_script(self, fn_name: str, code: str) -> int:
        data = code.encode("utf-8")
        ptr = self._alloc(len(data))
        try:
            self._memory.write(self._store, data, ptr)
            return self._invoke(fn_name, ptr, len(data))
        finally:
            self._dealloc(ptr, len(data))

    def _invoke(self, fn_name: str, *args: int) -> int:
        """Call a guest export with the execute deadline armed, mapping traps."""
        self._arm_deadline()
        try:
            return int(self._fn[fn_name](self._store, *args))
        except wasmtime.Trap as trap:
            self._poisoned = True
            self._disarm_deadline()
            if trap.trap_code == wasmtime.TrapCode.INTERRUPT:
                raise PythonTimeoutError(
                    f"execution exceeded the {self._limits.timeout}s timeout; "
                    "the sandbox is poisoned until reset()"
                ) from trap
            stderr = self._try_read_stderr()
            raise PythonExecutionError(stderr or trap.message) from trap
        finally:
            self._disarm_deadline()

    def _arm_deadline(self) -> None:
        self._deadline_at = time.monotonic() + self._limits.timeout
        self._store.set_epoch_deadline(runtime().deadline_ticks(self._limits.timeout))

    def _disarm_deadline(self) -> None:
        self._deadline_at = None
        self._store.set_epoch_deadline(DISARMED_DEADLINE_TICKS)

    def _deadline_remaining(self) -> float | None:
        deadline = self._deadline_at
        if deadline is None:
            return None
        return deadline - time.monotonic()

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

    def async_host_function(self, name: str):
        """Decorator registering an async host function. The handler runs on a
        host thread pool; guest code awaits it via
        boomslang_host.asyncio.async_call(name, args_json) under asyncio.run()."""

        def decorator(fn: HostFunction) -> HostFunction:
            self._register_async(name, fn)
            return fn

        return decorator

    def _register_async(self, name: str, fn: HostFunction) -> None:
        def handler(payload: str) -> str:
            args = json.loads(payload) if payload else None
            return json.dumps(fn(args))

        self._async_registry.register(name, handler)

    def _dispatch_host_call(self, name: str, args_json: str) -> str:
        if name == _RESULT_PENDING:
            parked = self._parked_result
            return "" if parked is None else str(len(parked))
        if name == _RESULT_CHUNK:
            return self._read_parked_chunk(int(args_json.strip()))
        if self._async_registry.is_control_call(name):
            return self._async_registry.handle_control_call(name, args_json)

        # A new user-level call invalidates any unfetched oversized result.
        self._parked_result = None
        fn = self._host_functions.get(name)
        if fn is not None:
            args = json.loads(args_json) if args_json else None
            return json.dumps(fn(args))
        if self._call_handler is not None:
            return self._call_handler(name, args_json)
        raise KeyError(f"no host function registered for {name!r}")

    def _park_oversized_result(self, data: bytes) -> None:
        """Called by the trampoline when a result exceeds the guest's buffer;
        the guest fetches it back in chunks via __result_pending__/__result_chunk__."""
        self._parked_result = base64.b64encode(data).decode("ascii")

    def _read_parked_chunk(self, offset: int) -> str:
        parked = self._parked_result
        if parked is None:
            return ""
        chunk = parked[offset : offset + _RESULT_CHUNK_SIZE]
        if offset + _RESULT_CHUNK_SIZE >= len(parked):
            self._parked_result = None
        return chunk

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
