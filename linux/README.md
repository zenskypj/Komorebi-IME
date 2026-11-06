# Komorebi IME — Linux frontend

This workspace contains the platform-neutral Rust core and, in later milestones, the fcitx5,
IBus, and touch-screen frontends.

Current state: **M0**. The crate opens the patched Mozc engine through `libkomorebi_mozc.so`,
owns the Mozc profile key, and turns a hiragana reading into candidates. There is no fcitx5 addon
yet.

```bash
./scripts/build_mozc_linux.sh      # from the repository root
cd linux
cargo test -p komorebi-core
cargo run -p komorebictl -- convert きょうはいいてんき
cargo run -p komorebictl -- convert きょうはいいてんき --commit
```

The build script prefers the prepared `tools/mozc-linux` checkout and `tools/bazelisk` when they
exist. Override them with `MOZC_DIR` and `BAZEL`, and limit Bazel with `BAZEL_ARGS="--jobs=2"`.

See [docs/linux/build.md](../docs/linux/build.md) for prerequisites, outputs, storage paths, and
the current limitations.
