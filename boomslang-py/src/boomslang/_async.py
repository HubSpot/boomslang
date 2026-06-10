"""Host side of the async bridge for boomslang_host.asyncio.

Mirrors the Java AsyncHostRegistry wire protocol (v1), spoken over the stock
boomslang_host.call function with reserved control names:

    __async_protocol__          -> "1"
    __async_start__  name\\nargs -> decimal token (always positive)
    __async_poll__   timeout_ms -> "token\\t{1|0}\\t<valueByteLength>\\n" per completion
    __async_result__ token      -> base64 of the completion's value bytes (consuming)
    __async_cancel__ token      -> cancels the in-flight call

Values are fetched one at a time so a batch of completions never exceeds the
single host-call result buffer.
"""

import base64
import queue
import threading
import time
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor

PROTOCOL_VERSION = 1

PROTOCOL = "__async_protocol__"
START = "__async_start__"
POLL = "__async_poll__"
RESULT = "__async_result__"
CANCEL = "__async_cancel__"

_CONTROL_NAMES = frozenset({PROTOCOL, START, POLL, RESULT, CANCEL})

# Blocking polls are sliced so the wasm thread regularly returns to guest code,
# where the epoch-deadline trap can fire if the execute timeout has passed.
_POLL_SLICE_SECONDS = 0.05


class _Completion:
    __slots__ = ("token", "ok", "value")

    def __init__(self, token: int, ok: bool, value: bytes):
        self.token = token
        self.ok = ok
        self.value = value

    @classmethod
    def from_result(cls, token: int, result) -> "_Completion":
        text = "" if result is None else str(result)
        return cls(token, True, text.encode("utf-8"))

    @classmethod
    def from_error(cls, token: int, error: BaseException) -> "_Completion":
        return cls(token, False, repr(error).encode("utf-8"))


class AsyncHostRegistry:
    """Runs async host handlers on a thread pool and queues their completions
    for the guest event loop to poll."""

    def __init__(self, deadline_remaining: Callable[[], float | None]):
        self._handlers: dict[str, Callable[[str], str]] = {}
        self._executor: ThreadPoolExecutor | None = None
        self._next_token = 1
        self._token_lock = threading.Lock()
        self._in_flight: dict[int, Future] = {}
        self._completions: queue.Queue[_Completion] = queue.Queue()
        self._ready: dict[int, _Completion] = {}
        # Returns seconds until the current execute deadline (None = no deadline);
        # blocking polls never sleep past it so the guest can hit its epoch trap.
        self._deadline_remaining = deadline_remaining

    def register(self, name: str, handler: Callable[[str], str]) -> None:
        if name in _CONTROL_NAMES or name.startswith("__"):
            raise ValueError(f"async host function name is reserved: {name!r}")
        self._handlers[name] = handler

    @property
    def has_handlers(self) -> bool:
        return bool(self._handlers)

    def is_control_call(self, name: str) -> bool:
        return name in _CONTROL_NAMES

    def handle_control_call(self, name: str, args: str) -> str:
        if name == PROTOCOL:
            return str(PROTOCOL_VERSION)
        if name == START:
            return self._start(args)
        if name == POLL:
            return self._poll(int(args.strip()))
        if name == RESULT:
            return self._result(int(args.strip()))
        if name == CANCEL:
            self._cancel(int(args.strip()))
            return ""
        raise RuntimeError(f"Unknown async control call: {name}")

    def close(self) -> None:
        for future in list(self._in_flight.values()):
            future.cancel()
        self._in_flight.clear()
        if self._executor is not None:
            self._executor.shutdown(wait=False, cancel_futures=True)
            self._executor = None

    # ------------------------------------------------------------------

    def _allocate_token(self) -> int:
        with self._token_lock:
            token = self._next_token
            self._next_token += 1
            return token

    def _start(self, args: str) -> str:
        name, _, payload = args.partition("\n")
        handler = self._handlers.get(name)
        token = self._allocate_token()
        if handler is None:
            self._completions.put(
                _Completion.from_error(
                    token, RuntimeError(f"No async handler registered for: {name}")
                )
            )
            return str(token)

        if self._executor is None:
            self._executor = ThreadPoolExecutor(
                max_workers=8, thread_name_prefix="boomslang-async"
            )
        future = self._executor.submit(handler, payload)
        self._in_flight[token] = future

        def on_done(done: Future, token: int = token) -> None:
            self._in_flight.pop(token, None)
            if done.cancelled():
                self._completions.put(
                    _Completion.from_error(token, RuntimeError("cancelled"))
                )
                return
            error = done.exception()
            if error is not None:
                self._completions.put(_Completion.from_error(token, error))
            else:
                self._completions.put(_Completion.from_result(token, done.result()))

        future.add_done_callback(on_done)
        return str(token)

    def _poll(self, timeout_ms: int) -> str:
        drained: list[_Completion] = []
        first = self._take_first(timeout_ms)
        if first is not None:
            drained.append(first)
            while True:
                try:
                    drained.append(self._completions.get_nowait())
                except queue.Empty:
                    break

        headers = []
        for completion in drained:
            self._ready[completion.token] = completion
            ok = "1" if completion.ok else "0"
            headers.append(f"{completion.token}\t{ok}\t{len(completion.value)}\n")
        return "".join(headers)

    def _take_first(self, timeout_ms: int) -> _Completion | None:
        if timeout_ms == 0:
            try:
                return self._completions.get_nowait()
            except queue.Empty:
                return None

        wait_forever = timeout_ms < 0
        deadline = None if wait_forever else time.monotonic() + timeout_ms / 1000
        while True:
            slice_s = _POLL_SLICE_SECONDS
            remaining_execute = self._deadline_remaining()
            if remaining_execute is not None:
                if remaining_execute <= 0:
                    # The execute timeout has passed; hand control back to the
                    # guest so the epoch trap can fire.
                    return None
                slice_s = min(slice_s, remaining_execute)
            if deadline is not None:
                remaining_poll = deadline - time.monotonic()
                if remaining_poll <= 0:
                    return None
                slice_s = min(slice_s, remaining_poll)
            try:
                return self._completions.get(timeout=slice_s)
            except queue.Empty:
                continue

    def _result(self, token: int) -> str:
        completion = self._ready.pop(token, None)
        if completion is None:
            return ""
        return base64.b64encode(completion.value).decode("ascii")

    def _cancel(self, token: int) -> None:
        self._ready.pop(token, None)
        future = self._in_flight.pop(token, None)
        if future is not None:
            future.cancel()
