use std::cell::RefCell;
use std::ffi::c_void;

use libmimalloc_sys as mi;
use pyo3::ffi::{PyMemAllocatorDomain, PyMemAllocatorEx, PyMem_SetAllocator};
use pyo3::prelude::*;

pub mod api;
pub mod export;
pub mod stubs;

mod builtins;


thread_local! {
    pub static STDOUT_BUFFER: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
    pub static STDERR_BUFFER: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

pub fn clear_buffers() {
    STDOUT_BUFFER.with(|buf| buf.borrow_mut().clear());
    STDERR_BUFFER.with(|buf| buf.borrow_mut().clear());
}

#[derive(Clone, Copy)]
enum StreamKind {
    Stdout,
    Stderr,
}

#[pyclass]
struct LoggingStream {
    kind: StreamKind,
}

#[pymethods]
impl LoggingStream {
    fn write(&self, data: &str) -> usize {
        match self.kind {
            StreamKind::Stdout => {
                STDOUT_BUFFER.with(|buf| buf.borrow_mut().extend(data.as_bytes()));
            }
            StreamKind::Stderr => {
                STDERR_BUFFER.with(|buf| buf.borrow_mut().extend(data.as_bytes()));
            }
        }
        data.len()
    }

    fn flush(&self) {}

    fn isatty(&self) -> bool {
        false
    }

    fn writable(&self) -> bool {
        true
    }
}

extern "C" fn mi_malloc_wrapper(_ctx: *mut c_void, size: usize) -> *mut c_void {
    unsafe { mi::mi_malloc(size) }
}

extern "C" fn mi_calloc_wrapper(_ctx: *mut c_void, nelem: usize, elsize: usize) -> *mut c_void {
    unsafe { mi::mi_zalloc(nelem * elsize) }
}

extern "C" fn mi_realloc_wrapper(
    _ctx: *mut c_void,
    ptr: *mut c_void,
    new_size: usize,
) -> *mut c_void {
    unsafe { mi::mi_realloc(ptr, new_size) }
}

extern "C" fn mi_free_wrapper(_ctx: *mut c_void, ptr: *mut c_void) {
    unsafe { mi::mi_free(ptr) }
}

fn install_stream_handlers(py: Python) -> PyResult<()> {
    let sys = py.import("sys")?;

    let stdout_handler = Py::new(py, LoggingStream { kind: StreamKind::Stdout })?;
    let stderr_handler = Py::new(py, LoggingStream { kind: StreamKind::Stderr })?;

    sys.setattr("stdout", stdout_handler)?;
    sys.setattr("stderr", stderr_handler)?;

    let path = sys.getattr("path")?;
    path.call_method1("insert", (0i32, "/lib"))?;

    Ok(())
}

fn prewarm_stdlib(py: Python) {
    let modules = [
        "sys", "io", "os", "pathlib", "json", "importlib",
        "typing", "collections", "collections.abc", "functools",
        "itertools", "dataclasses", "enum", "abc", "copy", "re",
        "datetime", "decimal", "traceback", "warnings", "inspect",
        "typing_extensions", "annotated_types",
        "pydantic_core", "pydantic", "pydantic.main", "pydantic.fields", "pydantic.config",
        "numpy", "numpy.linalg", "numpy.random", "numpy.fft",
        "pandas",
        "matplotlib", "matplotlib.pyplot",
        "ijson",
    ];

    py.run(
        c"import os; os.environ.setdefault('MPLCONFIGDIR', '/tmp/mplconfig')",
        None, None,
    ).ok();

    for name in modules {
        match py.import(name) {
            Ok(_) => eprintln!("[prewarm] OK: {}", name),
            Err(e) => eprintln!("[prewarm] FAILED: {} - {:?}", name, e),
        }
    }

    let warmup_code = c"
from pydantic import BaseModel
class _WizerWarmupModel(BaseModel):
    x: int
    y: str
_WizerWarmupModel(x=1, y='test')
";
    match py.run(warmup_code, None, None) {
        Ok(_) => eprintln!("[prewarm] OK: pydantic model creation"),
        Err(e) => eprintln!("[prewarm] FAILED: pydantic model creation - {:?}", e),
    }
}

/// Initialize the Python runtime. Call this from your cdylib's `wizer_initialize`.
///
/// 1. Installs mimalloc as Python's memory allocator
/// 2. Call `register_extensions` to register your extensions via `PyImport_AppendInittab`
/// 3. Initializes CPython
/// 4. Installs stream handlers, prewarns stdlib + libraries
/// 5. Calls `prewarm_extensions` for extension-specific prewarm
///
/// ```rust,ignore
/// #[unsafe(export_name = "wizer_initialize")]
/// pub extern "C" fn wizer_initialize() {
///     boomslang_host_core::init(
///         || {
///             my_extension::register();
///         },
///         |py| {
///             my_extension::prewarm(py);
///         },
///     );
/// }
/// ```
pub fn init<R, P>(register_extensions: R, prewarm_extensions: P)
where
    R: FnOnce(),
    P: FnOnce(Python),
{
    unsafe {
        let mut allocator = PyMemAllocatorEx {
            ctx: std::ptr::null_mut(),
            malloc: Some(mi_malloc_wrapper),
            calloc: Some(mi_calloc_wrapper),
            realloc: Some(mi_realloc_wrapper),
            free: Some(mi_free_wrapper),
        };
        PyMem_SetAllocator(PyMemAllocatorDomain::PYMEM_DOMAIN_RAW, &mut allocator);
        PyMem_SetAllocator(PyMemAllocatorDomain::PYMEM_DOMAIN_MEM, &mut allocator);
        PyMem_SetAllocator(PyMemAllocatorDomain::PYMEM_DOMAIN_OBJ, &mut allocator);
    }

    builtins::register_all();
    register_extensions();

    Python::initialize();
    Python::attach(|py| {
        install_stream_handlers(py).expect("Failed to install stream handlers");
        prewarm_stdlib(py);
        prewarm_extensions(py);
    });
}
