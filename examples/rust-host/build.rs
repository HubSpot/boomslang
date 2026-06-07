use std::env;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let abi_path = manifest_dir.join("abi/boomslang_host.abi.json");
    let out_dir = env::var("OUT_DIR").unwrap();

    boomslang_hostgen::generate_rust_host(abi_path.to_str().unwrap(), &out_dir)
        .expect("generate Rust host bindings from ABI JSON");

    println!("cargo:rerun-if-changed={}", abi_path.display());
}
