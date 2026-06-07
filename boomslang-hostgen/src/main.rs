use clap::Parser;
use std::path::PathBuf;

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
    let abi_path = cli.abi.to_str().unwrap();

    if let Some(java_out) = &cli.java_out {
        let package = cli
            .java_package
            .as_deref()
            .unwrap_or("com.hubspot.boomslang.extensions");
        boomslang_hostgen::generate_java(abi_path, java_out.to_str().unwrap(), package)?;
        eprintln!("Generated Java to {}", java_out.display());
    }

    if let Some(rust_host_out) = &cli.rust_host_out {
        boomslang_hostgen::generate_rust_host(abi_path, rust_host_out.to_str().unwrap())?;
        eprintln!("Generated Rust host to {}", rust_host_out.display());
    }

    Ok(())
}
