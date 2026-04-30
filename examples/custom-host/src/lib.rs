// Re-export all WASM exports from boomslang-host-core.
// These are the functions Chicory calls: alloc, dealloc, execute, compile, etc.
pub use boomslang_host_core::export::*;
pub use boomslang_host_core::stubs::*;

#[unsafe(export_name = "wizer_initialize")]
pub extern "C" fn wizer_initialize() {
    boomslang_host_core::init(
        || {
            // Register extensions BEFORE Python initializes.
            // Each extension's register() calls PyImport_AppendInittab to make
            // its Python module available as a builtin.
            boomslang_ext_host_bridge::register();

            // Add your own extensions here:
            // my_extension::register();
        },
        |py| {
            // Prewarm extensions AFTER Python initializes.
            // This imports the extension's Python modules so they're baked into
            // the Wizer memory snapshot — zero import cost at runtime.
            boomslang_ext_host_bridge::prewarm(py);

            // my_extension::prewarm(py);
        },
    );
}
