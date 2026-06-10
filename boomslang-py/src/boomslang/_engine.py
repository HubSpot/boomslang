import threading

import wasmtime

from ._assets import wasm_path
from ._layout import Layout, probe_layout

EPOCH_TICK_SECONDS = 0.01

# Effectively "no deadline" for stores that are not currently executing.
DISARMED_DEADLINE_TICKS = 2**62

# Wasmtime caps max_wasm_stack at the (unexposed) 2 MiB async_stack_size.
# CPython's recursion mostly lives on the guest's 4 MiB linear-memory shadow
# stack, so 1.5 MiB of native wasm stack is comfortable headroom over the
# 512 KiB default.
_MAX_WASM_STACK = 1536 * 1024


class _Runtime:
    """Process-wide wasmtime Engine + compiled Module + epoch ticker thread.

    Compiling the ~100 MB module is expensive (minutes on a cold wasmtime
    cache), so it is shared across all sandboxes. Epoch interruption is
    engine-global: a single ticker thread increments the epoch on a fixed
    cadence and every Store arms its own deadline in ticks, so one ticker
    serves all sandboxes without cross-cancellation.
    """

    def __init__(self) -> None:
        config = wasmtime.Config()
        config.epoch_interruption = True
        config.cache = True
        config.max_wasm_stack = _MAX_WASM_STACK
        self.engine = wasmtime.Engine(config)
        self.module = wasmtime.Module.from_file(self.engine, str(wasm_path()))
        self._ticker_lock = threading.Lock()
        self._ticker_started = False
        self._layout: Layout | None = None
        self._layout_lock = threading.Lock()

    def layout(self) -> Layout:
        """The guest filesystem layout baked into this runtime image,
        discovered once per process via a throwaway probe instance."""
        with self._layout_lock:
            if self._layout is None:
                self._layout = probe_layout(
                    self.engine, self.module, DISARMED_DEADLINE_TICKS
                )
            return self._layout

    def ensure_ticker(self) -> None:
        with self._ticker_lock:
            if self._ticker_started:
                return
            thread = threading.Thread(
                target=self._tick_forever, name="boomslang-epoch-ticker", daemon=True
            )
            thread.start()
            self._ticker_started = True

    def _tick_forever(self) -> None:
        ticker = threading.Event()
        while True:
            ticker.wait(EPOCH_TICK_SECONDS)
            self.engine.increment_epoch()

    def deadline_ticks(self, timeout_seconds: float) -> int:
        return max(1, int(timeout_seconds / EPOCH_TICK_SECONDS) + 1)


_runtime_lock = threading.Lock()
_runtime_instance: _Runtime | None = None


def runtime() -> _Runtime:
    global _runtime_instance
    with _runtime_lock:
        if _runtime_instance is None:
            _runtime_instance = _Runtime()
        return _runtime_instance
