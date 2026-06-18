# Extension ABI JSON & Lowering

An extension declares its host functions once, in `build.rs`, with the [hostgen DSL](hostgen-dsl.md). The build emits an **ABI JSON** file — the language-neutral contract from which host adapters (Java, Rust, or hand-written for any runtime) are generated.

## Schema

```json
{
  "abi_version": 1,
  "extension": {
    "name": "boomslang_host",
    "wasm_module": "boomslang",
    "prewarm": ["_boomslang_host", "boomslang_host", "boomslang_host.asyncio"]
  },
  "functions": [
    {
      "name": "call",
      "params": [
        { "name": "name", "type": "string" },
        { "name": "args", "type": "string" }
      ],
      "returns": "string",
      "async": false
    },
    {
      "name": "log",
      "params": [
        { "name": "level", "type": "int" },
        { "name": "message", "type": "string" }
      ],
      "returns": null,
      "async": false
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `abi_version` | Schema version. Generators require an **exact match** (currently `1`) and fail with a clear error otherwise. If omitted, defaults to `1`. |
| `extension.name` | Extension identifier. Drives generated names: Python module `<name>`, guest file `ext_<name>.rs`, Java class `<Name>HostFunctions`, Rust host file `host_<name>.rs`. |
| `extension.wasm_module` | The WASM **import module** the functions live under (e.g. import `boomslang.call`). Defaults to the extension name when omitted. |
| `extension.prewarm` | Python modules imported during Wizer initialization, frozen into the golden snapshot. |
| `functions[].name` | Function name; becomes the import name and the Python-visible function. |
| `functions[].params` | Ordered typed parameters. |
| `functions[].returns` | Return type or `null` for none. Async functions must return `string`. |
| `functions[].async` | Whether the function is an async host call (see below). |

Types are a closed enum: `string`, `int`, `float`, `bytes`. Unknown type values fail parsing.

## Lowering to WASM signatures

The ABI JSON decides the import signatures and memory protocol. For a function with declared params and return:

| Declared | Lowered |
| --- | --- |
| `string` / `bytes` param | `i32 ptr, i32 len` (UTF-8 bytes for strings) |
| `int` param | `i32` |
| `float` param | `f64` |
| `string` / `bytes` return | caller appends `i32 result_ptr, i32 result_max_len`; host writes the value into that buffer and returns the written byte length as `i32` |
| no return | `i32` status return |
| async function | returns an `i64` host token instead of a value (see the [async wire protocol](async-protocol.md)) |

So declared `call(name: string, args: string) -> string` becomes the import:

```text
boomslang.call(name_ptr: i32, name_len: i32,
               args_ptr: i32, args_len: i32,
               result_ptr: i32, result_max_len: i32) -> i32
```

**Result buffer protocol:** the guest allocates the result buffer (currently capped at 1 MiB per call) and passes it to the host. A negative return signals failure: `-1` for a handler error, `-2` when the value did not fit in `result_max_len`. The guest surfaces any negative return as a Python exception.

Behavioral note: on malformed pointers the generated Java host traps the instance, while the generated Rust host returns `-1`; aligning these is tracked in [issue #44](https://github.com/HubSpot/boomslang/issues/44).

## Generated artifacts

From one declaration, hostgen produces:

- **Rust guest** (`ext_<name>.rs`, included via `include!` into your extension crate): the `extern` imports, a Python module exposing typed functions, and `register()` / `prewarm()` hooks for `boomslang_host_core::init`.
- **Java host adapter** (`<Name>HostFunctions.java`): typed functional interfaces + a builder producing a `BoomslangExtension` for `PythonExecutorFactory.addExtension`.
- **Rust host adapter** (`host_<name>.rs`): a typed builder with `register(&mut wasmtime::Linker<_>)`.

Function names prefixed `__async_` are reserved for the async control namespace and rejected by validation.
