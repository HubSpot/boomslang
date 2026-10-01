include!(concat!(env!("OUT_DIR"), "/image.rs"));

pub(crate) fn register_all() {
    for (name, init) in BUILTINS {
        unsafe {
            pyo3::ffi::PyImport_AppendInittab(name.as_ptr(), Some(*init));
        }
    }
}
