// Re-export all WASM exports from python4j-host-core.
// These are the functions Chicory calls: alloc, dealloc, execute, compile, etc.
pub use python4j_host_core::export::*;
pub use python4j_host_core::stubs::*;

#[unsafe(export_name = "wizer_initialize")]
pub extern "C" fn wizer_initialize() {
    python4j_host_core::init(
        || {
            // Register extensions BEFORE Python initializes.
            // Each extension's register() calls PyImport_AppendInittab to make
            // its Python module available as a builtin.
            python4j_ext_host_bridge::register();

            // Add your own extensions here:
            // my_extension::register();
        },
        |py| {
            // Prewarm extensions AFTER Python initializes.
            // This imports the extension's Python modules so they're baked into
            // the Wizer memory snapshot — zero import cost at runtime.
            python4j_ext_host_bridge::prewarm(py);

            // my_extension::prewarm(py);
        },
    );
}
