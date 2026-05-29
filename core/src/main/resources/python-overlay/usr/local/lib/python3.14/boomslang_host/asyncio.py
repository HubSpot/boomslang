import asyncio
import json

from asyncio import base_events, events
from boomslang_host import call


class HostAsyncError(Exception):
    pass


class _HostSelector:
    def __init__(self, loop):
        self._loop = loop

    def select(self, timeout=None):
        timeout_ms = self._timeout_ms(timeout)
        completions = json.loads(call("__async_poll__", str(timeout_ms)))
        for completion in completions:
            self._loop._complete_host_future(completion)
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


class BoomslangEventLoop(base_events.BaseEventLoop):
    def __init__(self):
        super().__init__()
        self._selector = _HostSelector(self)
        self._host_futures = {}

    def create_host_future(self, name, args=""):
        token = int(call("__async_start__", f"{name}\n{args}"))
        return self.create_future_for_token(token)

    def create_future_for_token(self, token):
        future = self.create_future()
        self._host_futures[token] = future
        future.add_done_callback(lambda done, token=token: self._cancel_host_future(token, done))
        return future

    def _complete_host_future(self, completion):
        token = int(completion["token"])
        future = self._host_futures.pop(token, None)
        if future is None or future.done():
            return
        if completion["ok"]:
            future.set_result(completion.get("result", ""))
        else:
            future.set_exception(HostAsyncError(completion.get("error", "host async call failed")))

    def _cancel_host_future(self, token, future):
        if future.cancelled() and self._host_futures.pop(token, None) is not None:
            call("__async_cancel__", str(token))

    def _process_events(self, event_list):
        pass

    def _write_to_self(self):
        pass

    def close(self):
        self._selector.close()
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


__all__ = ["BoomslangEventLoop", "BoomslangEventLoopPolicy", "HostAsyncError", "async_call", "from_host_token", "install", "sleep"]
