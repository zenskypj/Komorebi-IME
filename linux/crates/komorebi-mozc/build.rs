use std::env;
use std::path::PathBuf;

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let proto_root = manifest_dir
        .join("..")
        .join("..")
        .join("..")
        .join("mozc")
        .join("src")
        .join("main")
        .join("proto");

    let protoc = protoc_bin_vendored::protoc_bin_path().expect("vendored protoc");
    // SAFETY: build scripts run single-threaded and nothing else reads PROTOC concurrently.
    unsafe {
        env::set_var("PROTOC", protoc);
    }

    let protos = [
        "protocol/candidate_window.proto",
        "protocol/commands.proto",
        "protocol/config.proto",
        "protocol/engine_builder.proto",
        "protocol/renderer_command.proto",
        "protocol/renderer_style.proto",
        "protocol/segmenter_data.proto",
        "protocol/state.proto",
        "protocol/user_dictionary_storage.proto",
    ];

    for proto in protos {
        println!(
            "cargo:rerun-if-changed={}",
            proto_root.join(proto).display()
        );
    }

    prost_build::Config::new()
        .compile_protos(&protos, &[proto_root.as_path()])
        .expect("compile Mozc protocol buffers");

    link_bridge();
}

fn link_bridge() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let default_dir = manifest_dir.join("..").join("..").join("bridge");
    let bridge_dir = env::var_os("KOMOREBI_MOZC_DIR")
        .map(PathBuf::from)
        .unwrap_or(default_dir);
    let lib_dir = bridge_dir.join("lib");
    let lib_file = lib_dir.join("libkomorebi_mozc.so");

    println!("cargo:rerun-if-env-changed=KOMOREBI_MOZC_DIR");
    println!("cargo:rerun-if-changed={}", lib_file.display());
    if !lib_file.is_file() {
        println!(
            "cargo:warning=libkomorebi_mozc.so is missing; run scripts/build_mozc_linux.sh before linking a binary that uses the engine"
        );
        return;
    }
    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    println!("cargo:rustc-link-lib=dylib=komorebi_mozc");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{}", lib_dir.display());
}
