import asyncio
import base64

from asyncio import base_events, events
from boomslang_host import call

# Async wire-protocol version this client speaks. Negotiated against the Java host's
# __async_protocol__ so a client frozen into a WASM image refuses a host too old to understand it.
PROTOCOL_VERSION = 1


class HostAsyncError(Exception):
    pass


class _HostSelector:
    def __init__(self, loop):
        self._loop = loop

    def select(self, timeout=None):
        timeout_ms = self._timeout_ms(timeout)
        headers = call("__async_poll__", str(timeout_ms))
        for token, ok, length in _decode_headers(headers):
            # Values are fetched one at a time so a batch of completions can never exceed the
            # single host-call result buffer; empty values skip the round trip entirely.
            if length > 0:
                value = base64.b64decode(call("__async_result__", str(token)))
            else:
                value = b""
            self._loop._complete_host_future(token, ok, value)
        return []

    def close(self):
        pass

    @staticmethod
    def _timeout_ms(timeout):
        if timeout is None:
            return -1
        if timeout <= 0:
            return 0
        return max(1, int(timeout * 1000))


def _decode_headers(headers):
    decoded = []
    for line in headers.splitlines():
        if not line:
            continue
        token, ok, length = line.split("\t", 2)
        decoded.append((int(token), ok == "1", int(length)))
    return decoded


def host_protocol_version():
    """Async wire-protocol version reported by the Java host (0 if unsupported/legacy)."""
    try:
        return int(call("__async_protocol__", ""))
    except Exception:
        return 0


class BoomslangEventLoop(base_events.BaseEventLoop):
    def __init__(self):
        super().__init__()
        self._selector = _HostSelector(self)
        self._host_futures = {}
        self._protocol_checked = False

    def _ensure_protocol(self):
        if self._protocol_checked:
            return
        self._protocol_checked = True
        host_version = host_protocol_version()
        if host_version < PROTOCOL_VERSION:
            raise RuntimeError(
                "boomslang_host async protocol mismatch: client requires host protocol "
                f">= {PROTOCOL_VERSION}, but host reports {host_version}. "
                "Rebuild the WASM host against a matching boomslang."
            )

    def create_host_future(self, name, args=""):
        self._ensure_protocol()
        token = int(call("__async_start__", f"{name}\n{args}"))
        return self.create_future_for_token(token)

    def create_future_for_token(self, token):
        self._ensure_protocol()
        future = self.create_future()
        if token <= 0:
            # The host could not even register the call (tokens are always positive). Fail the
            # future immediately rather than registering one that would never complete.
            future.set_exception(
                HostAsyncError(f"host failed to start async call (token={token})")
            )
            return future
        self._host_futures[token] = future
        future.add_done_callback(lambda done, token=token: self._cancel_host_future(token, done))
        return future

    def _complete_host_future(self, token, ok, value):
        future = self._host_futures.pop(token, None)
        if future is None or future.done():
            return
        text = value.decode("utf-8")
        if ok:
            future.set_result(text)
        else:
            future.set_exception(HostAsyncError(text or "host async call failed"))

    def _cancel_host_future(self, token, future):
        if future.cancelled() and self._host_futures.pop(token, None) is not None:
            call("__async_cancel__", str(token))

    def _process_events(self, event_list):
        pass

    def _write_to_self(self):
        pass

    def close(self):
        self._selector.close()
        # Cancel any still-pending host calls so their Java-side futures don't linger.
        for token, future in list(self._host_futures.items()):
            if not future.done():
                future.cancel()
                call("__async_cancel__", str(token))
        self._host_futures.clear()
        super().close()

    def add_reader(self, fd, callback, *args):
        raise NotImplementedError("Boomslang asyncio does not support file descriptor readers")

    def remove_reader(self, fd):
        return False

    def add_writer(self, fd, callback, *args):
        raise NotImplementedError("Boomslang asyncio does not support file descriptor writers")

    def remove_writer(self, fd):
        return False

    def run_in_executor(self, executor, func, *args):
        raise NotImplementedError("Boomslang asyncio does not support Python thread executors")


class BoomslangEventLoopPolicy(events._BaseDefaultEventLoopPolicy):
    _loop_factory = BoomslangEventLoop


def install():
    asyncio.set_event_loop_policy(BoomslangEventLoopPolicy())


def _running_boomslang_loop():
    loop = asyncio.get_running_loop()
    if not isinstance(loop, BoomslangEventLoop):
        raise RuntimeError("boomslang_host.asyncio.install() must be called before asyncio.run()")
    return loop


def async_call(name, args=""):
    return _running_boomslang_loop().create_host_future(name, args)


def from_host_token(token):
    return _running_boomslang_loop().create_future_for_token(token)


async def sleep(delay, result=None):
    await asyncio.sleep(delay)
    return result


__all__ = ["BoomslangEventLoop", "BoomslangEventLoopPolicy", "HostAsyncError", "async_call", "from_host_token", "host_protocol_version", "install", "sleep"]
