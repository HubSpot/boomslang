pub use boomslang_host_core::export::*;
pub use boomslang_host_core::stubs::*;

#[unsafe(export_name = "wizer_initialize")]
pub extern "C" fn wizer_initialize() {
    boomslang_host_core::init(
        || {
            boomslang_ext_host_bridge::register();
        },
        |py| {
            boomslang_ext_host_bridge::prewarm(py);
        },
    );
}
