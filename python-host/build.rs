use std::env;

fn main() {
    let wasi_sdk_path = env::var("WASI_SDK_PATH").expect("WASI_SDK_PATH is not set");
    let python_path = env::var("PYTHON_PATH").expect("PYTHON_PATH is not set");

    println!("cargo:rustc-link-search=native={}", python_path);
    println!(
        "cargo:rustc-link-search=native={}/share/wasi-sysroot/lib/wasm32-wasi",
        wasi_sdk_path
    );

    // compiler-rt builtins (wasm32)
    let clang_version = find_clang_dir(&wasi_sdk_path);
    println!(
        "cargo:rustc-link-search=native={}/lib/clang/{}/lib/wasip1",
        wasi_sdk_path, clang_version
    );
    println!("cargo:rustc-link-lib=static=clang_rt.builtins-wasm32");

    println!("cargo:rustc-link-lib=static=python3.14");

    // matplotlib C extensions
    println!("cargo:rustc-link-lib=static=_matplotlib_ft2font");
    println!("cargo:rustc-link-lib=static=_matplotlib_image");
    println!("cargo:rustc-link-lib=static=_matplotlib_backend_agg");
    println!("cargo:rustc-link-lib=static=_matplotlib_path");
    println!("cargo:rustc-link-lib=static=_matplotlib_c_internal_utils");
    println!("cargo:rustc-link-lib=static=_matplotlib_agg");

    // C++ stdlib for numpy's pocketfft
    println!("cargo:rustc-link-lib=static=c++");
    println!("cargo:rustc-link-lib=static=c++abi");

    println!("cargo:rustc-link-lib=static=c-printscan-long-double");

    println!("cargo:rustc-link-lib=static=wasi-emulated-signal");
    println!("cargo:rustc-link-lib=static=wasi-emulated-getpid");
    println!("cargo:rustc-link-lib=static=wasi-emulated-process-clocks");

    println!("cargo:rustc-link-arg=--export=__wasm_call_ctors");

    println!("cargo:rustc-link-arg=-z");
    println!("cargo:rustc-link-arg=stack-size=4194304");

    println!("cargo:rustc-link-arg=--allow-multiple-definition");

    // Optional: link a custom C extension module if provided
    if let Ok(extension_module_obj) = env::var("PYTHON4J_EXTENSION_OBJ") {
        println!("cargo:rustc-link-arg={}", extension_module_obj);
    }

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=WASI_SDK_PATH");
    println!("cargo:rerun-if-env-changed=PYTHON_PATH");
    println!("cargo:rerun-if-env-changed=PYTHON4J_EXTENSION_OBJ");
}

fn find_clang_dir(wasi_sdk_path: &str) -> String {
    let clang_dir = format!("{}/lib/clang", wasi_sdk_path);
    let mut versions: Vec<(Vec<u32>, String)> = std::fs::read_dir(&clang_dir)
        .unwrap_or_else(|e| panic!("read_dir {}: {}", clang_dir, e))
        .filter_map(|entry| entry.ok())
        .map(|entry| entry.file_name().to_string_lossy().into_owned())
        .filter_map(|name| {
            let parts: Option<Vec<u32>> = name.split('.').map(|p| p.parse::<u32>().ok()).collect();
            parts.map(|p| (p, name))
        })
        .collect();
    versions.sort_by(|a, b| a.0.cmp(&b.0));
    versions
        .pop()
        .map(|(_, name)| name)
        .unwrap_or_else(|| panic!("no versioned clang dir under {}", clang_dir))
}
