"""Host-function imports required by the WASM module.

The runtime imports exactly two functions from the "boomslang" module (see
extensions/host-bridge and examples/rust-host/abi/boomslang_host.abi.json):

    call(name_ptr, name_len, args_ptr, args_len, result_ptr, result_max_len) -> i32
        Returns bytes written into result_ptr, or -1 (handler error) /
        -2 (result larger than result_max_len). The guest-side bridge uses a
        fixed 1 MiB result buffer and does not retry on -2.

    log(level, msg_ptr, msg_len)

Exceptions must never escape a trampoline — that would trap the guest. Errors
are reported through the negative return codes, matching the Java host.
"""

import logging

from wasmtime import FuncType, Linker, ValType

logger = logging.getLogger(__name__)

_I32 = ValType.i32()
_CALL_TYPE = FuncType([_I32] * 6, [_I32])
_LOG_TYPE = FuncType([_I32] * 3, [])

CALL_ERROR = -1
CALL_RESULT_TOO_LARGE = -2


def define_boomslang_imports(linker: Linker, sandbox) -> None:
    def call(caller, name_ptr, name_len, args_ptr, args_len, result_ptr, result_max_len):
        try:
            memory = caller.get("memory")
            name = bytes(memory.read(caller, name_ptr, name_ptr + name_len)).decode("utf-8")
            args = bytes(memory.read(caller, args_ptr, args_ptr + args_len)).decode("utf-8")
            result = sandbox._dispatch_host_call(name, args)
            data = result.encode("utf-8")
            if len(data) > result_max_len:
                logger.error(
                    "host function %r result is %d bytes, exceeding the guest's %d-byte buffer",
                    name,
                    len(data),
                    result_max_len,
                )
                return CALL_RESULT_TOO_LARGE
            memory.write(caller, data, result_ptr)
            return len(data)
        except Exception:
            logger.exception("host function call failed")
            return CALL_ERROR

    def log(caller, level, msg_ptr, msg_len):
        try:
            memory = caller.get("memory")
            message = bytes(memory.read(caller, msg_ptr, msg_ptr + msg_len)).decode(
                "utf-8", errors="replace"
            )
            sandbox._on_log(level, message)
        except Exception:
            logger.exception("guest log handler failed")

    linker.define_func("boomslang", "call", _CALL_TYPE, call, access_caller=True)
    linker.define_func("boomslang", "log", _LOG_TYPE, log, access_caller=True)
