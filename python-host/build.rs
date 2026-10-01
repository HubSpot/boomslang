fn main() {
    println!("cargo:rustc-link-arg=--export=__wasm_call_ctors");
    println!("cargo:rustc-link-arg=-z");
    println!("cargo:rustc-link-arg=stack-size=4194304");
    println!("cargo:rerun-if-changed=build.rs");
}
