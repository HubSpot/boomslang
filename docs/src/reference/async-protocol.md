# Async Wire Protocol (v1)

`boomslang_host.asyncio` (the Python client) and the host-side `AsyncHostRegistry` talk over a small, versioned protocol invoked through the stock `boomslang_host.call(name, args)` bridge. This page is the wire-level specification; usage is in the [async guide](../guide/async.md).

The `__async_*` names are a **reserved control namespace** — extension host functions may not use them (hostgen validation rejects them).

| Control call | Args | Returns |
|---|---|---|
| `__async_protocol__` | — | integer protocol version (currently `1`) |
| `__async_start__` | `name\npayload` | decimal token for a registered named async handler |
| `__async_poll__` | timeout ms (`<0` blocks, `0` polls) | one header line per ready completion: `token\t{1\|0}\t<valueByteLength>` |
| `__async_result__` | token | base64 of that completion's value bytes (consumes it) |
| `__async_cancel__` | token | cancels the in-flight future |

Typed async extension functions bypass `__async_start__`: their WASM import returns the `i64` token directly from the shared registry. Polling, result retrieval, and cancellation still flow through the control calls above.

## Design rationale

- **Versioned.** The Python client is frozen into each consumer's WASM Wizer snapshot, so the host must stay compatible with already-shipped clients. `__async_protocol__` lets a client refuse a host older than the protocol it was built against; bump `AsyncHostRegistry.PROTOCOL_VERSION` only for breaking wire changes.
- **Poll and result are decoupled.** `__async_poll__` returns only headers (token, ok flag, length); values are fetched one at a time via `__async_result__`. A batch of completions therefore never exceeds the single host-call result buffer. (A single value larger than that buffer is still a limitation — chunked retrieval is a future protocol addition.)
- **Failures never hang.** Synchronous handler errors are recorded via `AsyncHostRegistry.startFailed` and surface as a failed completion (the coroutine raises `HostAsyncError`); the client also rejects any non-positive token immediately.
- **Binary-safe value channel.** Completion values are carried as base64 of raw bytes, so extending async returns to `bytes` later needs no wire change.

## Implementations

The protocol is implemented by the Java `AsyncHostRegistry` (`core/`), the generated Rust host registry (hostgen's `rust_host.rs` template), and the Python client (`boomslang_host/asyncio.py`). They must agree byte-for-byte; consolidation of the duplicated implementations is tracked in [issue #45](https://github.com/HubSpot/boomslang/issues/45).
