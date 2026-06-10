use std::env;
use std::error::Error;
use std::path::{Path, PathBuf};

fn main() -> Result<(), Box<dyn Error>> {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR")?);
    let stock_abi_path = manifest_dir.join("abi/boomslang_host.abi.json");
    let async_abi_path = manifest_dir.join("abi/demo_async.abi.json");
    let out_dir = env::var("OUT_DIR")?;

    boomslang_hostgen::generate_rust_host(
        path_to_str(&stock_abi_path, "stock ABI path")?,
        &out_dir,
    )?;
    boomslang_hostgen::generate_rust_host(
        path_to_str(&async_abi_path, "async ABI path")?,
        &out_dir,
    )?;

    println!("cargo:rerun-if-changed={}", stock_abi_path.display());
    println!("cargo:rerun-if-changed={}", async_abi_path.display());
    Ok(())
}

fn path_to_str<'a>(path: &'a Path, label: &str) -> Result<&'a str, Box<dyn Error>> {
    path.to_str()
        .ok_or_else(|| format!("{label} must be valid UTF-8").into())
}
