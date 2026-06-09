use clap::Parser;
use std::path::{Path, PathBuf};

#[derive(Parser)]
#[command(
    name = "boomslang-hostgen",
    about = "Generate host function bindings from boomslang ABI JSON"
)]
struct Cli {
    #[arg(help = "Path to extension ABI JSON")]
    abi: PathBuf,

    #[arg(long, help = "Output directory for generated Java code")]
    java_out: Option<PathBuf>,

    #[arg(long, help = "Java package for generated code")]
    java_package: Option<String>,

    #[arg(long, help = "Output directory for generated Rust Wasmtime host code")]
    rust_host_out: Option<PathBuf>,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cli = Cli::parse();
    let abi_path = path_to_str(&cli.abi, "ABI path")?;

    if cli.java_out.is_none() && cli.rust_host_out.is_none() {
        boomslang_hostgen::read_abi(&cli.abi)?;
        return Err("no output requested; pass --java-out or --rust-host-out".into());
    }

    if let Some(java_out) = &cli.java_out {
        let package = cli
            .java_package
            .as_deref()
            .unwrap_or("com.hubspot.boomslang.extensions");
        boomslang_hostgen::generate_java(
            abi_path,
            path_to_str(java_out, "Java output path")?,
            package,
        )?;
        eprintln!("Generated Java to {}", java_out.display());
    }

    if let Some(rust_host_out) = &cli.rust_host_out {
        boomslang_hostgen::generate_rust_host(
            abi_path,
            path_to_str(rust_host_out, "Rust host output path")?,
        )?;
        eprintln!("Generated Rust host to {}", rust_host_out.display());
    }

    Ok(())
}

fn path_to_str<'a>(path: &'a Path, label: &str) -> Result<&'a str, Box<dyn std::error::Error>> {
    path.to_str()
        .ok_or_else(|| format!("{label} must be valid UTF-8").into())
}
