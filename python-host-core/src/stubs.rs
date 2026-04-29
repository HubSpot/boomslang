use std::ffi::{c_char, c_int, c_void};

#[unsafe(no_mangle)]
pub extern "C" fn dlopen(_filename: *const c_char, _flag: c_int) -> *mut c_void {
    std::ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "C" fn dlsym(_handle: *mut c_void, _symbol: *const c_char) -> *mut c_void {
    std::ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "C" fn dlerror() -> *mut c_char {
    std::ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "C" fn dlclose(_handle: *mut c_void) -> c_int {
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn __cxa_allocate_exception(_size: usize) -> *mut c_void {
    std::process::abort();
}

#[unsafe(no_mangle)]
pub extern "C" fn __cxa_throw(
    _thrown: *mut c_void,
    _tinfo: *mut c_void,
    _dest: Option<unsafe extern "C" fn(*mut c_void)>,
) -> ! {
    std::process::abort();
}
