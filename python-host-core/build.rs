use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};

use serde::Deserialize;

const CPYTHON_WASI_VERSION: &str = "0.1.0";
const CPYTHON_WASI_URL: &str = "https://github.com/HubSpot/boomslang/releases/download";
const SYSROOT_CONTRACT: u32 = 1;

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct Sysroot {
    #[serde(default = "first_contract")]
    contract: u32,
    #[serde(default)]
    builtins: BTreeMap<String, String>,
    #[serde(default)]
    link_libs: Vec<String>,
    #[serde(default)]
    prewarm: Vec<String>,
    #[serde(default)]
    snapshot_required: BTreeMap<String, String>,
}

fn first_contract() -> u32 {
    1
}

fn main() {
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let artifact_dir = resolve_cpython_wasi(&out_dir);
    let lib_dir = artifact_dir.join("lib/wasm32-wasi");

    println!("cargo:ROOT={}", artifact_dir.display());
    println!("cargo:LIB_DIR={}", lib_dir.display());
    println!(
        "cargo:STDLIB={}",
        artifact_dir.join("usr/local/lib/python3.14").display()
    );
    println!(
        "cargo:INCLUDE={}",
        artifact_dir.join("include/python3.14").display()
    );

    let sysroot = read_sysroot(&artifact_dir);
    fs::write(out_dir.join("image.rs"), image_source(&sysroot)).unwrap();
    emit_link_lines(&sysroot, &lib_dir);

    println!("cargo:rerun-if-changed=build.rs");
    println!(
        "cargo:rerun-if-changed={}",
        artifact_dir.join("sysroot.json").display()
    );
    println!("cargo:rerun-if-env-changed=CPYTHON_WASI_DIR");
    println!("cargo:rerun-if-env-changed=WASI_SDK_PATH");
}

fn read_sysroot(artifact_dir: &Path) -> Sysroot {
    let path = artifact_dir.join("sysroot.json");
    let Ok(text) = fs::read_to_string(&path) else {
        println!(
            "cargo:warning=no {}: building a stdlib-only runtime",
            path.display()
        );
        return Sysroot::default();
    };
    let sysroot: Sysroot = serde_json::from_str(&text)
        .unwrap_or_else(|e| panic!("cannot parse {}: {}", path.display(), e));
    assert!(
        sysroot.contract == SYSROOT_CONTRACT,
        "{} declares sysroot contract version {}, but boomslang-host-core supports version {}",
        path.display(),
        sysroot.contract,
        SYSROOT_CONTRACT
    );
    for symbol in sysroot.builtins.values() {
        assert!(
            symbol.starts_with("PyInit_")
                && symbol
                    .chars()
                    .all(|c| c.is_ascii_alphanumeric() || c == '_'),
            "{}: builtin symbol {:?} is not a PyInit_ identifier",
            path.display(),
            symbol
        );
    }
    sysroot
}

fn image_source(sysroot: &Sysroot) -> String {
    let mut src = String::from("unsafe extern \"C\" {\n");
    let symbols: BTreeSet<&String> = sysroot.builtins.values().collect();
    for symbol in symbols {
        writeln!(src, "    fn {symbol}() -> *mut pyo3::ffi::PyObject;").unwrap();
    }
    src.push_str("}\n\n");

    src.push_str(
        "pub(crate) static BUILTINS: &[(&std::ffi::CStr, unsafe extern \"C\" fn() -> *mut pyo3::ffi::PyObject)] = &[\n",
    );
    for (module, symbol) in &sysroot.builtins {
        writeln!(src, "    (c{module:?}, {symbol}),").unwrap();
    }
    src.push_str("];\n\n");

    src.push_str("pub(crate) static PREWARM: &[&str] = &[\n");
    for module in &sysroot.prewarm {
        writeln!(src, "    {module:?},").unwrap();
    }
    src.push_str("];\n\n");

    src.push_str("pub(crate) static SNAPSHOT_REQUIRED: &[(&str, &str)] = &[\n");
    for (module, reason) in &sysroot.snapshot_required {
        writeln!(src, "    ({module:?}, {reason:?}),").unwrap();
    }
    src.push_str("];\n");
    src
}

fn emit_link_lines(sysroot: &Sysroot, lib_dir: &Path) {
    println!("cargo:rustc-link-search=native={}", lib_dir.display());

    let mut emitted = BTreeSet::new();
    let mut link = |lib: &str| {
        if emitted.insert(lib.to_string()) {
            println!("cargo:rustc-link-lib=static:-bundle={lib}");
        }
    };

    for lib in &sysroot.link_libs {
        link(lib);
    }
    for lib in core_libs(lib_dir) {
        link(&lib);
    }
    for lib in [
        "c++",
        "c++abi",
        "wasi-emulated-signal",
        "wasi-emulated-getpid",
        "wasi-emulated-process-clocks",
        "wasi-emulated-mman",
        "c-printscan-long-double",
    ] {
        link(lib);
    }

    let Ok(sdk) = env::var("WASI_SDK_PATH") else {
        return;
    };
    for triple in ["wasm32-wasip1", "wasm32-wasi"] {
        let dir = PathBuf::from(&sdk)
            .join("share/wasi-sysroot/lib")
            .join(triple);
        if dir.exists() {
            println!("cargo:rustc-link-search=native={}", dir.display());
        }
    }
    let clang = PathBuf::from(&sdk)
        .join("lib/clang")
        .join(latest_clang(&sdk));
    println!(
        "cargo:rustc-link-search=native={}",
        clang.join("lib/wasip1").display()
    );
    link("clang_rt.builtins-wasm32");
}

/// libpython and the archives its embed pkg-config names (zlib, sqlite3, HACL, ...). A bundled
/// cpython-wasi build has no embed .pc, because its libpython carries everything merged in.
fn core_libs(lib_dir: &Path) -> Vec<String> {
    let pkgconfig = lib_dir.join("pkgconfig");
    let Ok(embed) = fs::read_to_string(pkgconfig.join("python-3.14-embed.pc")) else {
        return vec!["python3.14".into()];
    };
    let mut libs = pc_libs(&embed);
    for required in pc_field(&embed, "Requires.private") {
        let pc = pkgconfig.join(format!("{required}.pc"));
        let text = fs::read_to_string(&pc)
            .unwrap_or_else(|e| panic!("python-3.14-embed.pc requires {}: {}", pc.display(), e));
        libs.extend(pc_libs(&text));
    }
    libs.retain(|lib| !lib.starts_with("wasi-emulated-"));
    libs
}

fn pc_libs(pc: &str) -> Vec<String> {
    ["Libs", "Libs.private"]
        .iter()
        .flat_map(|field| pc_field(pc, field))
        .filter_map(|flag| flag.strip_prefix("-l").map(str::to_string))
        .collect()
}

fn pc_field<'a>(pc: &'a str, field: &str) -> Vec<&'a str> {
    pc.lines()
        .find_map(|line| line.strip_prefix(field)?.strip_prefix(':'))
        .map(|value| value.split_whitespace().collect())
        .unwrap_or_default()
}

fn latest_clang(sdk: &str) -> String {
    let dir = format!("{sdk}/lib/clang");
    fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("read_dir {dir}: {e}"))
        .filter_map(|entry| entry.ok()?.file_name().into_string().ok())
        .filter_map(|name| {
            let version: Option<Vec<u32>> = name.split('.').map(|p| p.parse().ok()).collect();
            Some((version?, name))
        })
        .max()
        .map(|(_, name)| name)
        .unwrap_or_else(|| panic!("no versioned clang dir under {dir}"))
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
