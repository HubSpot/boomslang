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

    Ok(())
}
