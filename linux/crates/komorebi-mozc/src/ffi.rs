//! Raw C ABI exported by `libkomorebi_mozc`. See patches/0008.

use std::ffi::{CStr, CString, c_char, c_int};
use std::ptr;

#[repr(C)]
pub struct KmbMozc {
    _private: [u8; 0],
}

unsafe extern "C" {
    fn kmb_mozc_create(
        profile_dir: *const c_char,
        data_path: *const c_char,
        key: *const u8,
        key_len: usize,
        error: *mut *mut c_char,
    ) -> *mut KmbMozc;

    fn kmb_mozc_eval(
        engine: *mut KmbMozc,
        input: *const u8,
        input_len: usize,
        output: *mut *mut u8,
        output_len: *mut usize,
        error: *mut *mut c_char,
    ) -> c_int;

    fn kmb_mozc_data_version(engine: *mut KmbMozc) -> *const c_char;
    fn kmb_mozc_free_bytes(ptr: *mut u8);
    fn kmb_mozc_free_string(ptr: *mut c_char);
    fn kmb_mozc_destroy(engine: *mut KmbMozc);
}

/// Owned handle to the in-process Mozc engine.
pub struct Engine {
    raw: *mut KmbMozc,
}

impl Engine {
    pub fn new(
        profile_dir: &std::path::Path,
        data_path: &std::path::Path,
        key: &[u8],
    ) -> Result<Self, String> {
        let profile = CString::new(profile_dir.to_string_lossy().as_bytes())
            .map_err(|_| "profile path contains a NUL byte".to_string())?;
        let data = CString::new(data_path.to_string_lossy().as_bytes())
            .map_err(|_| "data path contains a NUL byte".to_string())?;
        let mut error = ptr::null_mut();
        let raw = unsafe {
            kmb_mozc_create(
                profile.as_ptr(),
                data.as_ptr(),
                key.as_ptr(),
                key.len(),
                &mut error,
            )
        };
        if raw.is_null() {
            return Err(take_error(error));
        }
        Ok(Self { raw })
    }

    pub fn eval(&self, command: &[u8]) -> Result<Vec<u8>, String> {
        let mut out: *mut u8 = ptr::null_mut();
        let mut out_len: usize = 0;
        let mut error = ptr::null_mut();
        let status = unsafe {
            kmb_mozc_eval(
                self.raw,
                command.as_ptr(),
                command.len(),
                &mut out,
                &mut out_len,
                &mut error,
            )
        };
        if status != 0 {
            return Err(take_error(error));
        }
        let bytes = unsafe { std::slice::from_raw_parts(out, out_len).to_vec() };
        unsafe { kmb_mozc_free_bytes(out) };
        Ok(bytes)
    }

    pub fn data_version(&self) -> String {
        let ptr = unsafe { kmb_mozc_data_version(self.raw) };
        if ptr.is_null() {
            return String::new();
        }
        unsafe { CStr::from_ptr(ptr) }
            .to_string_lossy()
            .into_owned()
    }
}

impl Drop for Engine {
    fn drop(&mut self) {
        unsafe { kmb_mozc_destroy(self.raw) };
    }
}

impl std::fmt::Debug for Engine {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Engine").finish_non_exhaustive()
    }
}

fn take_error(error: *mut c_char) -> String {
    if error.is_null() {
        return "Mozc engine failed without a message".to_string();
    }
    let message = unsafe { CStr::from_ptr(error) }
        .to_string_lossy()
        .into_owned();
    unsafe { kmb_mozc_free_string(error) };
    message
}
