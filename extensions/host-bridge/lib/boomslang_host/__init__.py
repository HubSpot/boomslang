"""boomslang host bridge — generic Java↔Python function calls.

Usage from Python:
    from boomslang_host import call, log

    result = call("my_handler", '{"key": "value"}')
    log(2, "info message")
"""

from _boomslang_host import call, log

__all__ = ["call", "log"]
