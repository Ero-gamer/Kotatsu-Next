//! WebP encoder on top of the vendored libwebp 1.6.0 (see `build.rs`, `src/webp_shim.c`).
//!
//! Defaults favour quality over speed: encoder `method` 6 (slowest / smallest), SharpYUV for lossy
//! (no chroma bleeding / washed-out colors), lossless effort 100, lossless alpha, best alpha
//! filtering and a worker thread. Pixel input is straight (non-premultiplied) RGBA.

use crate::common::{Error, Result};
use std::os::raw::{c_int, c_void};

/// Largest width / height WebP can store.
pub const MAX_DIMENSION: u32 = 16383;

/// Mirrors `SsivWebpParams` in `webp_shim.c` (32-bit fields only, so no padding).
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct WebpParams {
    pub lossless: i32,
    /// Lossy: 0..=100 quality. Lossless: 0..=100 compression effort.
    pub quality: f32,
    /// 0 (fast) ..= 6 (slowest, best compression).
    pub method: i32,
    pub sharp_yuv: i32,
    pub alpha_quality: i32,
    /// 100 = off. Lower values trade exactness for size and need `lossless`.
    pub near_lossless: i32,
    /// 1 = keep RGB values of fully transparent pixels.
    pub exact: i32,
    pub thread_level: i32,
    pub pass: i32,
    pub segments: i32,
    pub sns_strength: i32,
    pub filter_strength: i32,
    pub filter_sharpness: i32,
    pub filter_type: i32,
    pub autofilter: i32,
    pub alpha_filtering: i32,
    pub alpha_compression: i32,
    pub preprocessing: i32,
    pub partitions: i32,
    pub partition_limit: i32,
}

impl WebpParams {
    /// Lossy encode at `quality` (clamped to 0..=100).
    pub fn lossy(quality: f32) -> Self {
        WebpParams {
            lossless: 0,
            quality: quality.clamp(0.0, 100.0),
            method: 6,
            sharp_yuv: 1,
            alpha_quality: 100,
            near_lossless: 100,
            exact: 0,
            thread_level: 1,
            pass: 10,
            segments: 4,
            sns_strength: 50,
            filter_strength: 60,
            filter_sharpness: 0,
            filter_type: 1,
            autofilter: 1,
            alpha_filtering: 2,
            alpha_compression: 1,
            preprocessing: 0,
            partitions: 0,
            partition_limit: 0,
        }
    }

    /// Lossless encode at maximum effort. `near_lossless` 100 keeps it pixel-exact; `exact` keeps the
    /// RGB of fully transparent pixels.
    pub fn lossless(near_lossless: i32, exact: bool) -> Self {
        WebpParams {
            lossless: 1,
            quality: 100.0,
            near_lossless: near_lossless.clamp(0, 100),
            exact: exact as i32,
            ..Self::lossy(100.0)
        }
    }
}

#[cfg(feature = "webp")]
extern "C" {
    fn ssiv_webp_encode(
        rgba: *const u8,
        width: c_int,
        height: c_int,
        stride: c_int,
        has_alpha: c_int,
        params: *const WebpParams,
        out: *mut *mut u8,
        out_size: *mut usize,
    ) -> c_int;
    fn ssiv_webp_free(ptr: *mut c_void);
    fn ssiv_webp_decode_rgba(
        data: *const u8,
        size: usize,
        width: *mut c_int,
        height: *mut c_int,
        out: *mut *mut u8,
    ) -> c_int;
}

/// Encodes straight RGBA (`stride` bytes per row) to a WebP file.
#[cfg(feature = "webp")]
pub fn encode_rgba(
    rgba: &[u8],
    width: u32,
    height: u32,
    stride: usize,
    has_alpha: bool,
    params: &WebpParams,
) -> Result<Vec<u8>> {
    if width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION {
        return Err(Error::new("unsupported WebP dimensions"));
    }
    let row = width as usize * 4;
    let needed = stride
        .checked_mul(height as usize - 1)
        .and_then(|n| n.checked_add(row))
        .ok_or_else(|| Error::new("size overflow"))?;
    if stride < row || rgba.len() < needed || stride > c_int::MAX as usize {
        return Err(Error::new("pixel buffer too small"));
    }
    let mut out: *mut u8 = std::ptr::null_mut();
    let mut out_size: usize = 0;
    // SAFETY: `rgba` holds at least `needed` bytes (checked above), `params` is a valid repr(C)
    // struct, and the out pointers are valid for writes.
    let code = unsafe {
        ssiv_webp_encode(
            rgba.as_ptr(),
            width as c_int,
            height as c_int,
            stride as c_int,
            has_alpha as c_int,
            params,
            &mut out,
            &mut out_size,
        )
    };
    if code != 0 || out.is_null() {
        return Err(Error::new(format!("WebP encode failed (code {code})")));
    }
    // SAFETY: on success the shim hands over `out_size` valid bytes, which we copy and then free.
    let data = unsafe { std::slice::from_raw_parts(out, out_size) }.to_vec();
    unsafe { ssiv_webp_free(out as *mut c_void) };
    Ok(data)
}

/// Decodes any (non-animated) WebP to straight RGBA: `(width, height, pixels)`.
#[cfg(feature = "webp")]
pub fn decode_rgba(data: &[u8]) -> Result<(u32, u32, Vec<u8>)> {
    let (mut w, mut h): (c_int, c_int) = (0, 0);
    let mut out: *mut u8 = std::ptr::null_mut();
    // SAFETY: `data` is valid for `data.len()` bytes; outputs are valid for writes.
    let code =
        unsafe { ssiv_webp_decode_rgba(data.as_ptr(), data.len(), &mut w, &mut h, &mut out) };
    if code != 0 || out.is_null() || w <= 0 || h <= 0 {
        return Err(Error::new("WebP decode failed"));
    }
    let len = w as usize * h as usize * 4;
    // SAFETY: libwebp returned a `w * h * 4` byte RGBA buffer, copied then freed.
    let pixels = unsafe { std::slice::from_raw_parts(out, len) }.to_vec();
    unsafe { ssiv_webp_free(out as *mut c_void) };
    Ok((w as u32, h as u32, pixels))
}
