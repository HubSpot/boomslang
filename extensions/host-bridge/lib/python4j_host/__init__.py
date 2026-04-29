"""python4j host bridge — generic Java↔Python function calls.

Usage from Python:
    from python4j_host import call, log

    result = call("my_handler", '{"key": "value"}')
    log(2, "info message")
"""

from _python4j_host import call, log

__all__ = ["call", "log"]
