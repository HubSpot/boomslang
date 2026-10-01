use pydantic_core as _;

pub use boomslang_host_core::export::*;
pub use boomslang_host_core::stubs::*;

unsafe extern "C" {
    fn PyInit__pydantic_core() -> *mut pyo3::ffi::PyObject;
}

const PYDANTIC_WARMUP: &std::ffi::CStr = c"
from pydantic import BaseModel
class _WizerWarmupModel(BaseModel):
    x: int
    y: str
_WizerWarmupModel(x=1, y='test')
";

#[unsafe(export_name = "wizer_initialize")]
pub extern "C" fn wizer_initialize() {
    boomslang_host_core::init(
        || {
            unsafe {
                pyo3::ffi::PyImport_AppendInittab(
                    c"_pydantic_core".as_ptr(),
                    Some(PyInit__pydantic_core),
                );
            }
            boomslang_ext_host_bridge::register();
        },
        |py| {
            match py.run(PYDANTIC_WARMUP, None, None) {
                Ok(_) => eprintln!("[prewarm] OK: pydantic model creation"),
                Err(e) => eprintln!("[prewarm] FAILED: pydantic model creation - {:?}", e),
            }
            boomslang_ext_host_bridge::prewarm(py);
        },
    );
}
