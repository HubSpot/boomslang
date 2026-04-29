use std::ffi::c_void;
use std::slice;

use libmimalloc_sys as mi;
use pyo3::prelude::*;

use crate::{api, clear_buffers, STDERR_BUFFER, STDOUT_BUFFER};

#[unsafe(no_mangle)]
pub extern "C" fn alloc(size: i32) -> *mut u8 {
    unsafe { mi::mi_malloc(size as usize) as *mut u8 }
}

#[unsafe(no_mangle)]
pub extern "C" fn dealloc(ptr: *mut u8, _size: i32) {
    unsafe { mi::mi_free(ptr as *mut c_void) }
}

#[unsafe(no_mangle)]
pub extern "C" fn compile_source(
    source_ptr: *const u8,
    source_len: i32,
    output_ptr: *mut u8,
    output_max_len: i32,
) -> i32 {
    clear_buffers();

    let source = unsafe {
        let bytes = slice::from_raw_parts(source_ptr, source_len as usize);
        match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    let result = Python::attach(|py| api::compile_source(py, source));

    match result {
        Ok(bytecode) => {
            let len = bytecode.len();
            if len > output_max_len as usize {
                return -3;
            }
            unsafe {
                std::ptr::copy_nonoverlapping(bytecode.as_ptr(), output_ptr, len);
            }
            len as i32
        }
        Err(_) => -1,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn load_bytecode(bytecode_ptr: *const u8, bytecode_len: i32) -> i32 {
    clear_buffers();

    let bytecode = unsafe { slice::from_raw_parts(bytecode_ptr, bytecode_len as usize) };

    Python::attach(|py| match api::load_bytecode(py, bytecode) {
        Ok(()) => 0,
        Err(_) => 1,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn execute_function(
    name_ptr: *const u8,
    name_len: i32,
    args_ptr: *const u8,
    args_len: i32,
) -> i32 {
    clear_buffers();

    let name = unsafe {
        let bytes = slice::from_raw_parts(name_ptr, name_len as usize);
        match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    let args = if args_len > 0 {
        unsafe {
            let bytes = slice::from_raw_parts(args_ptr, args_len as usize);
            match std::str::from_utf8(bytes) {
                Ok(s) => s,
                Err(_) => return -1,
            }
        }
    } else {
        ""
    };

    Python::attach(|py| match api::execute_function(py, name, args) {
        Ok(()) => 0,
        Err(_) => 1,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn execute(script_ptr: *const u8, script_len: i32) -> i32 {
    clear_buffers();

    let script = unsafe {
        let bytes = slice::from_raw_parts(script_ptr, script_len as usize);
        match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    Python::attach(|py| match api::execute_legacy(py, script) {
        Ok(()) => 0,
        Err(_) => 1,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn get_stdout_len() -> i32 {
    STDOUT_BUFFER.with(|buf| buf.borrow().len() as i32)
}

#[unsafe(no_mangle)]
pub extern "C" fn get_stderr_len() -> i32 {
    STDERR_BUFFER.with(|buf| buf.borrow().len() as i32)
}

#[unsafe(no_mangle)]
pub extern "C" fn get_stdout(ptr: *mut u8, max_len: i32) -> i32 {
    STDOUT_BUFFER.with(|buf| {
        let buf = buf.borrow();
        let len = buf.len().min(max_len as usize);
        unsafe {
            std::ptr::copy_nonoverlapping(buf.as_ptr(), ptr, len);
        }
        len as i32
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn get_stderr(ptr: *mut u8, max_len: i32) -> i32 {
    STDERR_BUFFER.with(|buf| {
        let buf = buf.borrow();
        let len = buf.len().min(max_len as usize);
        unsafe {
            std::ptr::copy_nonoverlapping(buf.as_ptr(), ptr, len);
        }
        len as i32
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn install_module(
    name_ptr: *const u8,
    name_len: i32,
    source_ptr: *const u8,
    source_len: i32,
) -> i32 {
    clear_buffers();

    let name = unsafe {
        let bytes = slice::from_raw_parts(name_ptr, name_len as usize);
        match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    let source = unsafe {
        let bytes = slice::from_raw_parts(source_ptr, source_len as usize);
        match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    Python::attach(|py| match api::install_module(py, name, source) {
        Ok(()) => 0,
        Err(_) => 1,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn uninstall_module(name_ptr: *const u8, name_len: i32) -> i32 {
    clear_buffers();

    let name = unsafe {
        let bytes = slice::from_raw_parts(name_ptr, name_len as usize);
        match std::str::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => return -1,
        }
    };

    Python::attach(|py| match api::uninstall_module(py, name) {
        Ok(()) => 0,
        Err(_) => 1,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn reset_state() {
    clear_buffers();
    Python::attach(|py| api::reset_main_namespace(py));
}

#[unsafe(no_mangle)]
pub extern "C" fn get_heap_pages() -> i32 {
    let memory_size = core::arch::wasm32::memory_size(0);
    memory_size as i32
}
