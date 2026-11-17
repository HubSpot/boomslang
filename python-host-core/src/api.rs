use std::ffi::CString;

use pyo3::exceptions::PySystemExit;
use pyo3::prelude::*;
use pyo3::types::{PyCode, PyCodeMethods, PyDict, PyInt, PyTuple};
use pyo3::marshal;

use crate::clear_buffers;

pub const MARSHAL_VERSION: i32 = 5;

pub fn compile_source(py: Python, source: &str) -> Result<Vec<u8>, String> {
    let source_cstr = CString::new(source).map_err(|e| format!("Invalid source: {}", e))?;
    let filename_cstr = CString::new("<script>").unwrap();

    let code = PyCode::compile(
        py,
        &source_cstr,
        &filename_cstr,
        pyo3::types::PyCodeInput::File,
    )
    .map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("SyntaxError: {}", e)
    })?;

    let bytecode = marshal::dumps(&code, MARSHAL_VERSION).map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("Marshal error: {}", e)
    })?;

    Ok(bytecode.as_bytes().to_vec())
}

pub fn load_bytecode(py: Python, bytecode: &[u8]) -> Result<i32, String> {
    clear_buffers();

    let unmarshalled = marshal::loads(py, bytecode).map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("Unmarshal error: {}", e)
    })?;

    let code: &Bound<PyCode> = unmarshalled.cast().map_err(|_| {
        "Expected code object from bytecode".to_string()
    })?;

    let main = py.import("__main__").map_err(|e| format!("Failed to import __main__: {}", e))?;
    let globals = main.dict();

    match code.run(Some(&globals), None) {
        Ok(_) => Ok(0),
        Err(e) => exit_status_or_print(py, e, "Execution error"),
    }
}

pub fn execute_function(py: Python, func_name: &str, args_json: &str) -> Result<i32, String> {
    clear_buffers();

    if func_name == "__main__" {
        return Ok(0);
    }

    let main = py.import("__main__").map_err(|e| format!("Failed to import __main__: {}", e))?;
    let main_dict = main.dict();

    let func = main_dict.get_item(func_name).map_err(|e| format!("Dict error: {}", e))?;
    let func = func.ok_or_else(|| format!("Function '{}' not found in __main__", func_name))?;

    if !func.is_callable() {
        return Err(format!("'{}' is not callable", func_name));
    }

    let args_tuple = if args_json.is_empty() {
        PyTuple::empty(py)
    } else {
        let json_module = py.import("json").map_err(|e| format!("Failed to import json: {}", e))?;
        let loads = json_module.getattr("loads").map_err(|e| format!("Failed to get json.loads: {}", e))?;
        let args_list = loads.call1((args_json,)).map_err(|e| {
            e.print_and_set_sys_last_vars(py);
            format!("Failed to parse args JSON: {}", e)
        })?;

        if let Ok(list) = args_list.cast::<pyo3::types::PyList>() {
            PyTuple::new(py, list.iter()).map_err(|e| format!("Failed to create tuple: {}", e))?
        } else {
            return Err("Args must be a JSON array".to_string());
        }
    };

    match func.call1(args_tuple) {
        Ok(_) => Ok(0),
        Err(e) => exit_status_or_print(py, e, "Function call error"),
    }
}

pub fn execute_legacy(py: Python, script: &str) -> Result<i32, String> {
    clear_buffers();

    let script_cstr = CString::new(script).map_err(|e| format!("Invalid script: {}", e))?;

    let main = py.import("__main__").map_err(|e| format!("Failed to import __main__: {}", e))?;
    let main_dict = main.dict();

    match py.run(&script_cstr, Some(&main_dict), Some(&main_dict)) {
        Ok(()) => Ok(0),
        Err(e) => exit_status_or_print(py, e, "Execution error"),
    }
}

pub fn install_module(py: Python, module_name: &str, source: &str) -> Result<i32, String> {
    clear_buffers();

    let types = py.import("types").map_err(|e| format!("Failed to import types: {}", e))?;
    let module_type = types.getattr("ModuleType").map_err(|e| format!("Failed to get ModuleType: {}", e))?;

    let module = module_type.call1((module_name,)).map_err(|e| format!("Failed to create module: {}", e))?;

    let module_dict = module.getattr("__dict__").map_err(|e| format!("Failed to get module dict: {}", e))?;
    let module_dict = module_dict.cast::<pyo3::types::PyDict>().map_err(|_| "Module dict is not a dict")?;

    module_dict.set_item("__name__", module_name).map_err(|e| format!("Failed to set __name__: {}", e))?;
    module_dict.set_item("__file__", format!("<memory>/{}.py", module_name)).map_err(|e| format!("Failed to set __file__: {}", e))?;

    let source_cstr = CString::new(source).map_err(|e| format!("Invalid source: {}", e))?;
    if let Err(e) = py.run(&source_cstr, Some(module_dict), Some(module_dict)) {
        return exit_status_or_print(py, e, "Module execution error");
    }

    let sys = py.import("sys").map_err(|e| format!("Failed to import sys: {}", e))?;
    let sys_modules = sys.getattr("modules").map_err(|e| format!("Failed to get sys.modules: {}", e))?;
    sys_modules.set_item(module_name, module).map_err(|e| format!("Failed to register module: {}", e))?;

    Ok(0)
}

/// Turns an error raised by user code into an exit status, the way CPython's
/// `_Py_HandleSystemExitAndKeyboardInterrupt` does for a `python` process, but without calling
/// `Py_Exit`. Printing a `SystemExit` through `PyErr_PrintEx` would finalize the interpreter and
/// issue WASI `proc_exit`, so the host would see a trap instead of the script's output.
///
/// `SystemExit` yields `Ok(status)`: `None` is 0, an int is masked to 0..=255 like a POSIX exit
/// status (ints outside the C `int` range become -1, as in CPython), and any other code is
/// written to `sys.stderr` followed by a newline and yields 1. Every other exception is printed
/// to `sys.stderr` and yields `Err`.
fn exit_status_or_print(py: Python, err: PyErr, context: &str) -> Result<i32, String> {
    if !err.is_instance_of::<PySystemExit>(py) {
        err.print_and_set_sys_last_vars(py);
        return Err(format!("{}: {}", context, err));
    }

    // site.Quitter (the exit()/quit() builtins) closes sys.stdin before raising SystemExit.
    reopen_closed_stdin(py);

    let value = err.value(py);
    let message = match value.getattr("code") {
        Ok(code) if code.is_none() => return Ok(0),
        Ok(code) if code.is_instance_of::<PyInt>() => {
            let status = code
                .extract::<i64>()
                .ok()
                .and_then(|n| i32::try_from(n).ok())
                .unwrap_or(-1);
            return Ok(status & 0xFF);
        }
        Ok(code) => code,
        Err(_) => value.clone().into_any(),
    };

    write_exit_message(py, &message);
    Ok(1)
}

fn write_exit_message(py: Python, message: &Bound<PyAny>) {
    let Ok(text) = message.str() else { return };
    let Ok(stderr) = py.import("sys").and_then(|sys| sys.getattr("stderr")) else { return };
    if stderr.is_none() {
        return;
    }
    let _ = stderr.call_method1("write", (text,));
    let _ = stderr.call_method1("write", ("\n",));
}

fn reopen_closed_stdin(py: Python) {
    let Ok(sys) = py.import("sys") else { return };
    let Ok(stdin) = sys.getattr("stdin") else { return };
    if stdin.is_none() {
        return;
    }
    let closed = stdin
        .getattr("closed")
        .and_then(|closed| closed.is_truthy())
        .unwrap_or(false);
    if !closed {
        return;
    }

    let kwargs = PyDict::new(py);
    let _ = kwargs.set_item("closefd", false);
    if let Ok(encoding) = stdin.getattr("encoding") {
        let _ = kwargs.set_item("encoding", encoding);
    }
    if let Ok(errors) = stdin.getattr("errors") {
        let _ = kwargs.set_item("errors", errors);
    }
    let reopened = py
        .import("builtins")
        .and_then(|builtins| builtins.getattr("open"))
        .and_then(|open| open.call((0, "r"), Some(&kwargs)));
    if let Ok(reopened) = reopened {
        let _ = sys.setattr("stdin", reopened);
    }
}

pub fn reset_main_namespace(py: Python) {
    let main = match py.import("__main__") {
        Ok(m) => m,
        Err(_) => return,
    };

    let main_dict = main.dict();
    let builtins = match py.eval(pyo3::ffi::c_str!("__builtins__"), None, None) {
        Ok(b) => b,
        Err(_) => return,
    };

    let keys: Vec<String> = main_dict
        .keys()
        .iter()
        .filter_map(|k| k.extract::<String>().ok())
        .filter(|name| !name.starts_with('_'))
        .collect();

    for key in keys {
        if builtins.hasattr(&*key).unwrap_or(false) {
            continue;
        }
        let _ = main_dict.del_item(&key);
    }
}

pub fn uninstall_module(py: Python, module_name: &str) -> Result<(), String> {
    let sys = py.import("sys").map_err(|e| format!("Failed to import sys: {}", e))?;
    let sys_modules = sys.getattr("modules").map_err(|e| format!("Failed to get sys.modules: {}", e))?;
    let sys_modules = sys_modules.cast::<pyo3::types::PyDict>().map_err(|_| "sys.modules is not a dict")?;

    if sys_modules.contains(module_name).unwrap_or(false) {
        sys_modules.del_item(module_name).map_err(|e| format!("Failed to remove module: {}", e))?;
    }

    Ok(())
}
