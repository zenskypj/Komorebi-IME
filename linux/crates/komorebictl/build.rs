use std::env;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let default_lib = manifest_dir
        .join("..")
        .join("..")
        .join("bridge")
        .join("lib");
    let lib_dir = env::var_os("KOMOREBI_MOZC_DIR")
        .map(PathBuf::from)
        .map(|dir| dir.join("lib"))
        .unwrap_or(default_lib);

    println!("cargo:rerun-if-env-changed=KOMOREBI_MOZC_DIR");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{}", lib_dir.display());
}
