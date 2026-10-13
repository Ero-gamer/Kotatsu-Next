//! JNI surface: `org.koitharu.kotatsu.core.image.codecs.NativeCodecs`.
//!
//! Every entry point is wrapped in `catch_unwind`: a panic becomes a failure return (0 / null /
//! false) and never unwinds into the JVM; the Kotlin side then falls back to the system decoders.

use crate::common::{sampled_len, Decoder, Error, FileRange, PixelFormat, Rect, Result, Sink};
use jni::objects::{JClass, JObject};
use jni::sys::{jboolean, jint, jintArray, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::fs::File;
use std::io::Read;
use std::os::fd::{BorrowedFd, OwnedFd};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex};

type Handle = Mutex<Box<dyn Decoder>>;

/// Opens the image at `[offset, offset + length)` of `fd` (duplicated; the caller keeps its own).
pub fn open(fd: i32, offset: u64, length: u64) -> Result<Box<dyn Decoder>> {
    if fd < 0 || length < 16 {
        return Err(Error::new("invalid source"));
    }
    // SAFETY: the caller guarantees `fd` is an open descriptor for the duration of this call.
    let owned: OwnedFd = unsafe { BorrowedFd::borrow_raw(fd) }
        .try_clone_to_owned()
        .map_err(|e| Error::new(format!("dup failed: {e}")))?;
    let file = Arc::new(File::from(owned));
    let range = FileRange::new(file, offset, length);
    let mut head = vec![0u8; 8192.min(length as usize)];
    let n = read_full(&mut range.clone(), &mut head)?;
    head.truncate(n);
    open_range(range, &head)
}

fn read_full(src: &mut impl Read, buf: &mut [u8]) -> Result<usize> {
    let mut n = 0;
    while n < buf.len() {
        match src.read(&mut buf[n..])? {
            0 => break,
            k => n += k,
        }
    }
    Ok(n)
}

#[allow(unused_variables)]
fn open_range(range: FileRange, head: &[u8]) -> Result<Box<dyn Decoder>> {
    #[cfg(feature = "jxl")]
    if crate::jxl::is_jxl(head) {
        return Ok(Box::new(crate::jxl::JxlDecoder::from_reader(
            std::io::BufReader::new(range),
        )?));
    }
    #[cfg(feature = "apng")]
    if crate::apng::is_apng(head) {
        return Ok(Box::new(crate::apng::ApngDecoder::new(range)?));
    }
    Err(Error::new("unsupported format"))
}

fn guard<T>(default: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(default)
}

/// # Safety
/// `handle` must come from `nativeOpen` and not have been closed.
unsafe fn handle<'a>(handle: jlong) -> Option<&'a Handle> {
    (handle as *const Handle).as_ref()
}

#[no_mangle]
pub extern "system" fn Java_org_koitharu_kotatsu_core_image_codecs_NativeCodecs_nativeOpen(
    _env: JNIEnv,
    _class: JClass,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    guard(0, || {
        if offset < 0 || length <= 0 {
            return 0;
        }
        match open(fd, offset as u64, length as u64) {
            Ok(decoder) => Box::into_raw(Box::new(Mutex::new(decoder))) as jlong,
            Err(_) => 0,
        }
    })
}

/// `{format, width, height, frameCount, hasAlpha, loopCount}` or null.
#[no_mangle]
pub extern "system" fn Java_org_koitharu_kotatsu_core_image_codecs_NativeCodecs_nativeInfo(
    env: JNIEnv,
    _class: JClass,
    h: jlong,
) -> jintArray {
    guard(std::ptr::null_mut(), || {
        // SAFETY: Kotlin keeps the handle alive while calling.
        let Some(handle) = (unsafe { handle(h) }) else {
            return std::ptr::null_mut();
        };
        let Ok(dec) = handle.lock() else {
            return std::ptr::null_mut();
        };
        let i = dec.info();
        let values = [
            i.format as jint,
            i.width as jint,
            i.height as jint,
            i.frame_count as jint,
            i.has_alpha as jint,
            i.loop_count as jint,
        ];
        let Ok(arr) = env.new_int_array(values.len() as i32) else {
            return std::ptr::null_mut();
        };
        if env.set_int_array_region(&arr, 0, &values).is_err() {
            return std::ptr::null_mut();
        }
        arr.into_raw()
    })
}

#[no_mangle]
pub extern "system" fn Java_org_koitharu_kotatsu_core_image_codecs_NativeCodecs_nativeFrameDuration(
    _env: JNIEnv,
    _class: JClass,
    h: jlong,
    frame: jint,
) -> jint {
    guard(0, || {
        // SAFETY: see nativeInfo.
        let Some(handle) = (unsafe { handle(h) }) else {
            return 0;
        };
        let Ok(dec) = handle.lock() else { return 0 };
        dec.frame_duration_ms(frame.max(0) as u32)
            .min(i32::MAX as u32) as jint
    })
}

/// Decodes `[left, top, width, height]` of `frame`, box-downsampled by `sample`, into `bitmap`
/// (ARGB_8888 or RGB_565, exactly the sampled size). Returns true on success.
#[no_mangle]
pub extern "system" fn Java_org_koitharu_kotatsu_core_image_codecs_NativeCodecs_nativeDecode(
    env: JNIEnv,
    _class: JClass,
    h: jlong,
    frame: jint,
    left: jint,
    top: jint,
    width: jint,
    height: jint,
    sample: jint,
    bitmap: JObject,
) -> jboolean {
    let ok = guard(false, || {
        if frame < 0 || left < 0 || top < 0 || width <= 0 || height <= 0 || sample < 1 {
            return false;
        }
        // SAFETY: see nativeInfo.
        let Some(handle) = (unsafe { handle(h) }) else {
            return false;
        };
        let Ok(mut dec) = handle.lock() else {
            return false;
        };
        let rect = Rect::new(left as u32, top as u32, width as u32, height as u32);
        bitmap::with_pixels(&env, &bitmap, |data, stride, format, bw, bh| {
            let (sw, sh) = (
                sampled_len(rect.width, sample as u32),
                sampled_len(rect.height, sample as u32),
            );
            if bw != sw || bh != sh {
                return Err(Error::new("bitmap size does not match the sampled region"));
            }
            let mut sink = Sink::new(data, stride, format, bw, bh)?;
            dec.decode(frame as u32, rect, sample as u32, &mut sink)
        })
        .is_ok()
    });
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_org_koitharu_kotatsu_core_image_codecs_NativeCodecs_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    h: jlong,
) {
    guard((), || {
        if h != 0 {
            // SAFETY: the handle was produced by Box::into_raw in nativeOpen and is closed once.
            drop(unsafe { Box::from_raw(h as *mut Handle) });
        }
    });
}

// `ssiv_check` lets the host type-check the Android-only module: RUSTFLAGS="--cfg ssiv_check" cargo check
#[cfg(any(target_os = "android", ssiv_check))]
mod bitmap {
    use super::*;
    use std::ffi::c_void;

    #[repr(C)]
    struct BitmapInfo {
        width: u32,
        height: u32,
        stride: u32,
        format: i32,
        flags: u32,
    }

    const FORMAT_RGBA_8888: i32 = 1;
    const FORMAT_RGB_565: i32 = 4;

    #[link(name = "jnigraphics")]
    extern "C" {
        fn AndroidBitmap_getInfo(
            env: *mut jni::sys::JNIEnv,
            bitmap: jni::sys::jobject,
            info: *mut BitmapInfo,
        ) -> i32;
        fn AndroidBitmap_lockPixels(
            env: *mut jni::sys::JNIEnv,
            bitmap: jni::sys::jobject,
            addr: *mut *mut c_void,
        ) -> i32;
        fn AndroidBitmap_unlockPixels(env: *mut jni::sys::JNIEnv, bitmap: jni::sys::jobject)
            -> i32;
    }

    /// Locks the bitmap's pixels and hands them to `f` as `(data, stride, format, width, height)`.
    pub fn with_pixels(
        env: &JNIEnv,
        bitmap: &JObject,
        f: impl FnOnce(&mut [u8], usize, PixelFormat, u32, u32) -> Result<()>,
    ) -> Result<()> {
        let raw_env = env.get_raw();
        let raw_bmp = bitmap.as_raw();
        let mut info = BitmapInfo {
            width: 0,
            height: 0,
            stride: 0,
            format: 0,
            flags: 0,
        };
        // SAFETY: `raw_env` / `raw_bmp` are valid for the duration of this JNI call.
        if unsafe { AndroidBitmap_getInfo(raw_env, raw_bmp, &mut info) } < 0 {
            return Err(Error::new("AndroidBitmap_getInfo failed"));
        }
        let format = match info.format {
            FORMAT_RGBA_8888 => PixelFormat::Rgba8888,
            FORMAT_RGB_565 => PixelFormat::Rgb565,
            _ => return Err(Error::new("unsupported bitmap format")),
        };
        let mut addr: *mut c_void = std::ptr::null_mut();
        // SAFETY: as above; the pixels stay valid until the matching unlock.
        if unsafe { AndroidBitmap_lockPixels(raw_env, raw_bmp, &mut addr) } < 0 || addr.is_null() {
            return Err(Error::new("AndroidBitmap_lockPixels failed"));
        }
        let len = info.stride as usize * info.height as usize;
        // SAFETY: the bitmap owns `stride * height` bytes at `addr` while locked.
        let data = unsafe { std::slice::from_raw_parts_mut(addr as *mut u8, len) };
        let result = catch_unwind(AssertUnwindSafe(|| {
            f(data, info.stride as usize, format, info.width, info.height)
        }));
        // SAFETY: matches the successful lock above.
        unsafe { AndroidBitmap_unlockPixels(raw_env, raw_bmp) };
        result.unwrap_or_else(|_| Err(Error::new("decoder panicked")))
    }
}

#[cfg(not(any(target_os = "android", ssiv_check)))]
mod bitmap {
    use super::*;

    pub fn with_pixels(
        _env: &JNIEnv,
        _bitmap: &JObject,
        _f: impl FnOnce(&mut [u8], usize, PixelFormat, u32, u32) -> Result<()>,
    ) -> Result<()> {
        Err(Error::new("bitmaps are only available on Android"))
    }
}
