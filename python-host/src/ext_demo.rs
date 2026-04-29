use pyo3::prelude::*;

#[link(wasm_import_module = "demo")]
unsafe extern "C" {
    fn greet(
        name_ptr: *const u8,
        name_len: i32,
        result_ptr: *mut u8,
        result_max_len: i32,
    ) -> i32;
    fn log(
        level: i32,
        message_ptr: *const u8,
        message_len: i32,
    ) -> ();
}

const MAX_RESULT: i32 = 1024 * 1024;

#[pyfunction]
#[pyo3(name = "greet")]
fn py_greet(name: &str) -> PyResult<String> {
    unsafe {
        let name_bytes = name.as_bytes();
        let mut result_buf = vec![0u8; MAX_RESULT as usize];
        let ret = greet(
            name_bytes.as_ptr(),
            name_bytes.len() as i32,
            result_buf.as_mut_ptr(),
            MAX_RESULT,
        );
        if ret < 0 {
            return Err(PyErr::new::<pyo3::exceptions::PyRuntimeError, _>("host call failed"));
        }
        Ok(String::from_utf8_lossy(&result_buf[..ret as usize]).into_owned())
    }
}

#[pyfunction]
#[pyo3(name = "log")]
fn py_log(level: i32, message: &str) -> PyResult<()> {
    unsafe {
        let message_bytes = message.as_bytes();
        log(
            level,
            message_bytes.as_ptr(),
            message_bytes.len() as i32,
        );
        Ok(())
    }
}

unsafe extern "C" {
    #[allow(non_snake_case)]
    fn PyInit__demo() -> *mut pyo3::ffi::PyObject;
}

pub fn register() {
    unsafe {
        pyo3::ffi::PyImport_AppendInittab(
            b"_demo\0".as_ptr() as *const i8,
            Some(PyInit__demo),
        );
    }
}

pub fn prewarm(py: Python) {
    let modules = ["_demo", "demo"];
    for name in modules {
        match py.import(name) {
            Ok(_) => eprintln!("[prewarm] OK: {}", name),
            Err(e) => eprintln!("[prewarm] FAILED: {} - {:?}", name, e),
        }
    }
}

#[pymodule]
fn _demo(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(py_greet, m)?)?;
    m.add_function(wrap_pyfunction!(py_log, m)?)?;
    Ok(())
}
