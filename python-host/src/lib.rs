pub use python4j_host_core::export::*;
pub use python4j_host_core::stubs::*;

#[unsafe(export_name = "wizer_initialize")]
pub extern "C" fn wizer_initialize() {
    python4j_host_core::init(
        || {
            python4j_ext_host_bridge::register();
        },
        |py| {
            python4j_ext_host_bridge::prewarm(py);
        },
    );
}
