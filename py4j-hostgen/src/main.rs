use clap::Parser;
use heck::{ToLowerCamelCase, ToUpperCamelCase};
use serde::Deserialize;
use std::fs;
use std::path::PathBuf;

#[derive(Parser)]
#[command(name = "py4j-hostgen", about = "Generate host function bindings from extension.toml")]
struct Cli {
    #[arg(help = "Path to extension.toml")]
    manifest: PathBuf,

    #[arg(long, help = "Output directory for generated Rust code")]
    rust_out: Option<PathBuf>,

    #[arg(long, help = "Output directory for generated Java code")]
    java_out: Option<PathBuf>,

    #[arg(long, help = "Java package for generated code")]
    java_package: Option<String>,
}

#[derive(Deserialize)]
struct Manifest {
    extension: Extension,
    #[serde(default)]
    functions: Vec<Function>,
}

#[derive(Deserialize)]
struct Extension {
    name: String,
    wasm_module: Option<String>,
    #[serde(default)]
    prewarm: Vec<String>,
}

#[derive(Deserialize)]
struct Function {
    name: String,
    #[serde(default)]
    params: Vec<Param>,
    #[serde(default)]
    returns: Option<String>,
}

#[derive(Deserialize)]
struct Param {
    name: String,
    #[serde(rename = "type")]
    ty: String,
}

fn main() {
    let cli = Cli::parse();
    let content = fs::read_to_string(&cli.manifest).expect("Failed to read manifest");
    let manifest: Manifest = toml::from_str(&content).expect("Failed to parse manifest");

    if let Some(rust_out) = &cli.rust_out {
        fs::create_dir_all(rust_out).unwrap();
        let code = generate_rust(&manifest);
        let filename = format!("ext_{}.rs", manifest.extension.name);
        fs::write(rust_out.join(&filename), code).unwrap();
        eprintln!("Generated {}/{}", rust_out.display(), filename);
    }

    if let Some(java_out) = &cli.java_out {
        let package = cli
            .java_package
            .as_deref()
            .unwrap_or("com.hubspot.python4j.extensions");
        let package_dir: PathBuf = java_out.join(package.replace('.', "/"));
        fs::create_dir_all(&package_dir).unwrap();
        let code = generate_java(&manifest, package);
        let classname = format!("{}HostFunctions", manifest.extension.name.to_upper_camel_case());
        fs::write(package_dir.join(format!("{}.java", classname)), code).unwrap();
        eprintln!(
            "Generated {}/{}.java",
            package_dir.display(),
            classname
        );
    }
}

// ── Rust codegen ──────────────────────────────────────────────

fn generate_rust(m: &Manifest) -> String {
    let mod_name = &m.extension.name;
    let wasm_mod = m.extension.wasm_module.as_deref().unwrap_or(mod_name);

    let mut out = String::new();
    out.push_str("use pyo3::prelude::*;\n\n");

    // WASM import declarations
    out.push_str(&format!(
        "#[link(wasm_import_module = \"{}\")]\n",
        wasm_mod
    ));
    out.push_str("extern \"C\" {\n");
    for f in &m.functions {
        out.push_str(&format!("    fn {}(\n", wasm_import_name(f)));
        for p in &f.params {
            for (wn, wt) in wasm_params(&p.name, &p.ty) {
                out.push_str(&format!("        {}: {},\n", wn, wt));
            }
        }
        if f.returns.as_deref() == Some("string") || f.returns.as_deref() == Some("bytes") {
            out.push_str("        result_ptr: *mut u8,\n");
            out.push_str("        result_max_len: i32,\n");
        }
        let ret = wasm_return_type(f.returns.as_deref());
        out.push_str(&format!("    ) -> {};\n", ret));
    }
    out.push_str("}\n\n");

    out.push_str("const MAX_RESULT: i32 = 1024 * 1024;\n\n");

    // PyO3 wrapper functions
    for f in &m.functions {
        out.push_str(&generate_rust_pyo3_wrapper(f));
        out.push_str("\n");
    }

    // Module registration
    let py_mod_name = format!("_{}", mod_name);
    out.push_str(&format!(
        "pub fn register() {{\n    unsafe {{\n        pyo3::ffi::PyImport_AppendInittab(\n            b\"{}\\0\".as_ptr() as *const i8,\n            Some(PyInit_{}),\n        );\n    }}\n}}\n\n",
        py_mod_name, py_mod_name
    ));

    out.push_str(&format!(
        "pub fn prewarm(py: Python) {{\n    let modules = {:?};\n    for name in modules {{\n        match py.import(name) {{\n            Ok(_) => eprintln!(\"[prewarm] OK: {{}}\", name),\n            Err(e) => eprintln!(\"[prewarm] FAILED: {{}} - {{:?}}\", name, e),\n        }}\n    }}\n}}\n\n",
        m.extension.prewarm
    ));

    // #[pymodule]
    out.push_str(&format!(
        "#[pymodule]\nfn {}(m: &Bound<'_, PyModule>) -> PyResult<()> {{\n",
        py_mod_name
    ));
    for f in &m.functions {
        out.push_str(&format!(
            "    m.add_function(wrap_pyfunction!(py_{}, m)?)?;\n",
            f.name
        ));
    }
    out.push_str("    Ok(())\n}\n");

    out
}

fn generate_rust_pyo3_wrapper(f: &Function) -> String {
    let mut out = String::new();

    // Function signature
    out.push_str("#[pyfunction]\n");
    let ret_type = match f.returns.as_deref() {
        Some("string") => "PyResult<String>",
        Some("int") => "PyResult<i32>",
        Some("float") => "PyResult<f64>",
        Some("bytes") => "PyResult<Vec<u8>>",
        None => "PyResult<()>",
        _ => "PyResult<String>",
    };
    out.push_str(&format!("fn py_{}(", f.name));
    let py_params: Vec<String> = f
        .params
        .iter()
        .map(|p| format!("{}: {}", &p.name, rust_py_type(&p.ty)))
        .collect();
    out.push_str(&py_params.join(", "));
    out.push_str(&format!(") -> {} {{\n", ret_type));

    // Body: marshal args, call WASM import, unmarshal result
    out.push_str("    unsafe {\n");

    // Prepare string args as byte slices
    for p in &f.params {
        if p.ty == "string" {
            out.push_str(&format!(
                "        let {name}_bytes = {name}.as_bytes();\n",
                name = p.name
            ));
        } else if p.ty == "bytes" {
            out.push_str(&format!(
                "        let {name}_bytes = {name};\n",
                name = p.name
            ));
        }
    }

    if f.returns.as_deref() == Some("string") || f.returns.as_deref() == Some("bytes") {
        out.push_str("        let mut result_buf = vec![0u8; MAX_RESULT as usize];\n");
    }

    // Call the WASM import
    out.push_str(&format!("        let ret = {}(\n", wasm_import_name(f)));
    for p in &f.params {
        match p.ty.as_str() {
            "string" => {
                out.push_str(&format!(
                    "            {name}_bytes.as_ptr(),\n            {name}_bytes.len() as i32,\n",
                    name = p.name
                ));
            }
            "bytes" => {
                out.push_str(&format!(
                    "            {name}_bytes.as_ptr(),\n            {name}_bytes.len() as i32,\n",
                    name = p.name
                ));
            }
            "int" => {
                out.push_str(&format!("            {},\n", p.name));
            }
            "float" => {
                out.push_str(&format!("            {},\n", p.name));
            }
            _ => {}
        }
    }
    if f.returns.as_deref() == Some("string") || f.returns.as_deref() == Some("bytes") {
        out.push_str("            result_buf.as_mut_ptr(),\n");
        out.push_str("            MAX_RESULT,\n");
    }
    out.push_str("        );\n");

    // Handle return
    match f.returns.as_deref() {
        Some("string") => {
            out.push_str("        if ret < 0 {\n");
            out.push_str("            return Err(PyErr::new::<pyo3::exceptions::PyRuntimeError, _>(\"host call failed\"));\n");
            out.push_str("        }\n");
            out.push_str(
                "        Ok(String::from_utf8_lossy(&result_buf[..ret as usize]).into_owned())\n",
            );
        }
        Some("bytes") => {
            out.push_str("        if ret < 0 {\n");
            out.push_str("            return Err(PyErr::new::<pyo3::exceptions::PyRuntimeError, _>(\"host call failed\"));\n");
            out.push_str("        }\n");
            out.push_str("        Ok(result_buf[..ret as usize].to_vec())\n");
        }
        Some("int") => {
            out.push_str("        Ok(ret)\n");
        }
        None => {
            out.push_str("        Ok(())\n");
        }
        _ => {
            out.push_str("        Ok(ret)\n");
        }
    }

    out.push_str("    }\n}\n");
    out
}

fn wasm_import_name(f: &Function) -> String {
    f.name.clone()
}

fn wasm_params(name: &str, ty: &str) -> Vec<(String, &'static str)> {
    match ty {
        "string" | "bytes" => vec![
            (format!("{}_ptr", name), "*const u8"),
            (format!("{}_len", name), "i32"),
        ],
        "int" => vec![(name.to_string(), "i32")],
        "float" => vec![(name.to_string(), "f64")],
        _ => vec![(name.to_string(), "i32")],
    }
}

fn wasm_return_type(ty: Option<&str>) -> &'static str {
    match ty {
        Some("string") | Some("bytes") => "i32",
        Some("int") => "i32",
        Some("float") => "f64",
        None => "()",
        _ => "i32",
    }
}

fn rust_py_type(ty: &str) -> &'static str {
    match ty {
        "string" => "&str",
        "int" => "i32",
        "float" => "f64",
        "bytes" => "&[u8]",
        _ => "&str",
    }
}

// ── Java codegen ──────────────────────────────────────────────

fn generate_java(m: &Manifest, package: &str) -> String {
    let ext_name = &m.extension.name;
    let class_name = format!("{}HostFunctions", ext_name.to_upper_camel_case());
    let wasm_mod = m.extension.wasm_module.as_deref().unwrap_or(ext_name);

    let mut out = String::new();
    out.push_str(&format!("package {};\n\n", package));
    out.push_str("import com.dylibso.chicory.runtime.HostFunction;\n");
    out.push_str("import com.dylibso.chicory.runtime.Instance;\n");
    out.push_str("import com.dylibso.chicory.runtime.Memory;\n");
    out.push_str("import com.dylibso.chicory.wasm.types.ValueType;\n");
    out.push_str("import java.nio.charset.StandardCharsets;\n");
    out.push_str("import java.util.ArrayList;\n");
    out.push_str("import java.util.List;\n\n");

    out.push_str(&format!("public class {} {{\n\n", class_name));
    out.push_str(&format!(
        "  private static final String MODULE = \"{}\";\n\n",
        wasm_mod
    ));

    // Functional interfaces
    for f in &m.functions {
        out.push_str(&generate_java_interface(f));
    }

    // Builder
    out.push_str("  public static Builder builder() {\n");
    out.push_str("    return new Builder();\n");
    out.push_str("  }\n\n");

    out.push_str("  public static class Builder {\n\n");
    for f in &m.functions {
        let handler_type = format!("{}Handler", f.name.to_upper_camel_case());
        let field = f.name.to_lower_camel_case();
        out.push_str(&format!("    private {} {};\n", handler_type, field));
    }
    out.push_str("\n");

    for f in &m.functions {
        let handler_type = format!("{}Handler", f.name.to_upper_camel_case());
        let field = f.name.to_lower_camel_case();
        let method = format!("with{}", f.name.to_upper_camel_case());
        out.push_str(&format!(
            "    public Builder {}({} handler) {{\n      this.{} = handler;\n      return this;\n    }}\n\n",
            method, handler_type, field
        ));
    }

    // build() -> HostFunction[]
    out.push_str("    public HostFunction[] build() {\n");
    out.push_str("      List<HostFunction> functions = new ArrayList<>();\n");
    for f in &m.functions {
        let field = f.name.to_lower_camel_case();
        out.push_str(&format!(
            "      if ({} != null) {{\n        functions.add(create{}Function());\n      }}\n",
            field,
            f.name.to_upper_camel_case()
        ));
    }
    out.push_str("      return functions.toArray(new HostFunction[0]);\n");
    out.push_str("    }\n\n");

    // Individual HostFunction creators
    for f in &m.functions {
        out.push_str(&generate_java_host_function(f, wasm_mod));
    }

    out.push_str("  }\n"); // end Builder
    out.push_str("}\n"); // end class

    out
}

fn generate_java_interface(f: &Function) -> String {
    let iface_name = format!("{}Handler", f.name.to_upper_camel_case());
    let ret = java_return_type(f.returns.as_deref());
    let params: Vec<String> = f
        .params
        .iter()
        .map(|p| format!("{} {}", java_type(&p.ty), p.name.to_lower_camel_case()))
        .collect();

    format!(
        "  @FunctionalInterface\n  public interface {} {{\n    {} handle({});\n  }}\n\n",
        iface_name,
        ret,
        params.join(", ")
    )
}

fn generate_java_host_function(f: &Function, wasm_mod: &str) -> String {
    let method_name = format!("create{}Function", f.name.to_upper_camel_case());
    let field = f.name.to_lower_camel_case();
    let has_string_return =
        f.returns.as_deref() == Some("string") || f.returns.as_deref() == Some("bytes");

    // Build WASM param types list
    let mut wasm_params = Vec::new();
    for p in &f.params {
        match p.ty.as_str() {
            "string" | "bytes" => {
                wasm_params.push("ValueType.I32");
                wasm_params.push("ValueType.I32");
            }
            "int" => wasm_params.push("ValueType.I32"),
            "float" => wasm_params.push("ValueType.F64"),
            _ => wasm_params.push("ValueType.I32"),
        }
    }
    if has_string_return {
        wasm_params.push("ValueType.I32"); // result_ptr
        wasm_params.push("ValueType.I32"); // result_max_len
    }

    let wasm_returns = match f.returns.as_deref() {
        Some("string") | Some("bytes") | Some("int") => vec!["ValueType.I32"],
        Some("float") => vec!["ValueType.F64"],
        None => vec![],
        _ => vec!["ValueType.I32"],
    };

    let mut out = String::new();
    out.push_str(&format!("    private HostFunction {}() {{\n", method_name));
    out.push_str(&format!(
        "      return new HostFunction(\n          MODULE,\n          \"{}\",\n          List.of({}),\n          List.of({}),\n          (Instance instance, long... args) -> {{\n",
        f.name,
        wasm_params.join(", "),
        wasm_returns.join(", ")
    ));

    // Extract args from WASM memory
    out.push_str("            Memory memory = instance.memory();\n");
    let mut arg_idx = 0;
    for p in &f.params {
        let java_name = p.name.to_lower_camel_case();
        match p.ty.as_str() {
            "string" => {
                out.push_str(&format!(
                    "            int {name}Ptr = Math.toIntExact(args[{i}]);\n            int {name}Len = Math.toIntExact(args[{j}]);\n            String {name} = memory.readString({name}Ptr, {name}Len, StandardCharsets.UTF_8);\n",
                    name = java_name, i = arg_idx, j = arg_idx + 1
                ));
                arg_idx += 2;
            }
            "bytes" => {
                out.push_str(&format!(
                    "            int {name}Ptr = Math.toIntExact(args[{i}]);\n            int {name}Len = Math.toIntExact(args[{j}]);\n            byte[] {name} = memory.readBytes({name}Ptr, {name}Len);\n",
                    name = java_name, i = arg_idx, j = arg_idx + 1
                ));
                arg_idx += 2;
            }
            "int" => {
                out.push_str(&format!(
                    "            int {} = Math.toIntExact(args[{}]);\n",
                    java_name, arg_idx
                ));
                arg_idx += 1;
            }
            "float" => {
                out.push_str(&format!(
                    "            double {} = Double.longBitsToDouble(args[{}]);\n",
                    java_name, arg_idx
                ));
                arg_idx += 1;
            }
            _ => {
                arg_idx += 1;
            }
        }
    }

    if has_string_return {
        out.push_str(&format!(
            "            int resultPtr = Math.toIntExact(args[{}]);\n            int resultMaxLen = Math.toIntExact(args[{}]);\n",
            arg_idx, arg_idx + 1
        ));
    }

    // Call handler
    let call_args: Vec<String> = f
        .params
        .iter()
        .map(|p| p.name.to_lower_camel_case())
        .collect();
    let call_expr = format!("{}.handle({})", field, call_args.join(", "));

    match f.returns.as_deref() {
        Some("string") => {
            out.push_str(&format!(
                "            String result = {};\n",
                call_expr
            ));
            out.push_str("            byte[] resultBytes = result.getBytes(StandardCharsets.UTF_8);\n");
            out.push_str("            if (resultBytes.length > resultMaxLen) {\n");
            out.push_str("              return new long[] { -2 };\n");
            out.push_str("            }\n");
            out.push_str("            memory.write(resultPtr, resultBytes);\n");
            out.push_str("            return new long[] { resultBytes.length };\n");
        }
        Some("int") => {
            out.push_str(&format!(
                "            int result = {};\n",
                call_expr
            ));
            out.push_str("            return new long[] { result };\n");
        }
        None => {
            out.push_str(&format!("            {};\n", call_expr));
            out.push_str("            return null;\n");
        }
        _ => {
            out.push_str(&format!(
                "            return new long[] {{ {} }};\n",
                call_expr
            ));
        }
    }

    out.push_str("          });\n");
    out.push_str("    }\n\n");

    out
}

fn java_type(ty: &str) -> &'static str {
    match ty {
        "string" => "String",
        "int" => "int",
        "float" => "double",
        "bytes" => "byte[]",
        _ => "String",
    }
}

fn java_return_type(ty: Option<&str>) -> &'static str {
    match ty {
        Some("string") => "String",
        Some("int") => "int",
        Some("float") => "double",
        Some("bytes") => "byte[]",
        None => "void",
        _ => "String",
    }
}
