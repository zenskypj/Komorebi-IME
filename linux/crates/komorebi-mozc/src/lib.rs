//! Safe Rust wrapper around the patched Mozc engine.
//!
//! The protobuf modules mirror the packages defined in `mozc/src/main/proto/protocol`.

#![allow(clippy::all)]

pub mod proto;

pub mod ffi;
