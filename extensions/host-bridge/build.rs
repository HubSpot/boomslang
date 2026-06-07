fn main() {
    let ext = boomslang_hostgen::ExtensionSpec::new("boomslang_host")
        .wasm_module("boomslang")
        .prewarm([
            "_boomslang_host",
            "boomslang_host",
            "boomslang_host.asyncio",
        ])
        .function("call", |f| {
            f.param("name", boomslang_hostgen::Type::String)
                .param("args", boomslang_hostgen::Type::String)
                .returns(boomslang_hostgen::Type::String)
        })
        .function("log", |f| {
            f.param("level", boomslang_hostgen::Type::Int)
                .param("message", boomslang_hostgen::Type::String)
        });

    boomslang_hostgen::Build::new(ext)
        .emit_rust_guest()
        .emit_abi_json()
        .generate()
        .expect("generate boomslang host extension");

    println!("cargo:rerun-if-changed=build.rs");
}
