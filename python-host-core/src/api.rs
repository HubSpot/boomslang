use std::ffi::CString;

use pyo3::prelude::*;
use pyo3::types::{PyCode, PyCodeMethods, PyTuple};
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

pub fn load_bytecode(py: Python, bytecode: &[u8]) -> Result<(), String> {
    clear_buffers();

    let unmarshalled = marshal::loads(py, bytecode).map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("Unmarshal error: {}", e)
    })?;

    let code: &Bound<PyCode> = unmarshalled.downcast().map_err(|_| {
        "Expected code object from bytecode".to_string()
    })?;

    let main = py.import("__main__").map_err(|e| format!("Failed to import __main__: {}", e))?;
    let globals = main.dict();

    code.run(Some(&globals), None).map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("Execution error: {}", e)
    })?;

    Ok(())
}

pub fn execute_function(py: Python, func_name: &str, args_json: &str) -> Result<(), String> {
    clear_buffers();

    if func_name == "__main__" {
        return Ok(());
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

        if let Ok(list) = args_list.downcast::<pyo3::types::PyList>() {
            PyTuple::new(py, list.iter()).map_err(|e| format!("Failed to create tuple: {}", e))?
        } else {
            return Err("Args must be a JSON array".to_string());
        }
    };

    func.call1(args_tuple).map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("Function call error: {}", e)
    })?;

    Ok(())
}

pub fn execute_legacy(py: Python, script: &str) -> Result<(), String> {
    clear_buffers();

    let script_cstr = CString::new(script).map_err(|e| format!("Invalid script: {}", e))?;

    let main = py.import("__main__").map_err(|e| format!("Failed to import __main__: {}", e))?;
    let main_dict = main.dict();

    py.run(&script_cstr, Some(&main_dict), Some(&main_dict)).map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("Execution error: {}", e)
    })?;

    Ok(())
}

pub fn install_module(py: Python, module_name: &str, source: &str) -> Result<(), String> {
    clear_buffers();

    let types = py.import("types").map_err(|e| format!("Failed to import types: {}", e))?;
    let module_type = types.getattr("ModuleType").map_err(|e| format!("Failed to get ModuleType: {}", e))?;

    let module = module_type.call1((module_name,)).map_err(|e| format!("Failed to create module: {}", e))?;

    let module_dict = module.getattr("__dict__").map_err(|e| format!("Failed to get module dict: {}", e))?;
    let module_dict = module_dict.downcast::<pyo3::types::PyDict>().map_err(|_| "Module dict is not a dict")?;

    module_dict.set_item("__name__", module_name).map_err(|e| format!("Failed to set __name__: {}", e))?;
    module_dict.set_item("__file__", format!("<memory>/{}.py", module_name)).map_err(|e| format!("Failed to set __file__: {}", e))?;

    let source_cstr = CString::new(source).map_err(|e| format!("Invalid source: {}", e))?;
    py.run(&source_cstr, Some(module_dict), Some(module_dict)).map_err(|e| {
        e.print_and_set_sys_last_vars(py);
        format!("Module execution error: {}", e)
    })?;

    let sys = py.import("sys").map_err(|e| format!("Failed to import sys: {}", e))?;
    let sys_modules = sys.getattr("modules").map_err(|e| format!("Failed to get sys.modules: {}", e))?;
    sys_modules.set_item(module_name, module).map_err(|e| format!("Failed to register module: {}", e))?;

    Ok(())
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
    let sys_modules = sys_modules.downcast::<pyo3::types::PyDict>().map_err(|_| "sys.modules is not a dict")?;

    if sys_modules.contains(module_name).unwrap_or(false) {
        sys_modules.del_item(module_name).map_err(|e| format!("Failed to remove module: {}", e))?;
    }

    Ok(())
}
