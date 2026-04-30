use std::env;
use std::fs;
use std::path::{Path, PathBuf};

const CPYTHON_WASI_VERSION: &str = "0.1.0";
const CPYTHON_WASI_URL: &str = "https://github.com/HubSpot/boomslang/releases/download";

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let artifact_dir = resolve_cpython_wasi(&out_dir);

    // Export paths for downstream cdylib crates to use in their build.rs
    println!("cargo:ROOT={}", artifact_dir.display());
    println!(
        "cargo:LIB_DIR={}",
        artifact_dir.join("lib/wasm32-wasi").display()
    );
    println!(
        "cargo:STDLIB={}",
        artifact_dir.join("usr/local/lib/python3.14").display()
    );
    println!(
        "cargo:INCLUDE={}",
        artifact_dir.join("include/python3.14").display()
    );

    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-env-changed=CPYTHON_WASI_DIR");
}

fn resolve_cpython_wasi(out_dir: &Path) -> PathBuf {
    if let Ok(dir) = env::var("CPYTHON_WASI_DIR") {
        let path = PathBuf::from(dir);
        assert!(
            path.join("lib/wasm32-wasi/libpython3.14.a").exists(),
            "CPYTHON_WASI_DIR does not contain libpython3.14.a: {}",
            path.display()
        );
        eprintln!("Using CPYTHON_WASI_DIR: {}", path.display());
        return path;
    }

    let cached = out_dir.join("cpython-wasi");
    if cached.join("lib/wasm32-wasi/libpython3.14.a").exists() {
        eprintln!("Using cached cpython-wasi: {}", cached.display());
        return cached;
    }

    let url = format!(
        "{}/cpython-wasi-v{}/cpython-wasi.tgz",
        CPYTHON_WASI_URL, CPYTHON_WASI_VERSION
    );
    eprintln!("Downloading cpython-wasi from {}...", url);

    let tarball = out_dir.join("cpython-wasi.tgz");
    let status = std::process::Command::new("curl")
        .args(["-fSL", "-o"])
        .arg(&tarball)
        .arg(&url)
        .status()
        .expect("Failed to run curl");

    if !status.success() {
        panic!(
            "Failed to download cpython-wasi from {}. \
             Set CPYTHON_WASI_DIR to a local build instead.",
            url
        );
    }

    fs::create_dir_all(&cached).unwrap();
    let status = std::process::Command::new("tar")
        .args(["xzf"])
        .arg(&tarball)
        .arg("-C")
        .arg(&cached)
        .status()
        .expect("Failed to run tar");

    if !status.success() {
        panic!("Failed to extract cpython-wasi tarball");
    }

    eprintln!("Downloaded cpython-wasi to {}", cached.display());
    cached
}
