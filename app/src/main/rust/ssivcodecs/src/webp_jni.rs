//! JNI surface: `org.koitharu.kotatsu.core.image.codecs.NativeWebp`.
//!
//! Like `jni_api`, every entry point is wrapped in `catch_unwind` and reports failure with a
//! null / -1 return instead of unwinding into the JVM.

use crate::quality::{estimate, SourceQuality};
use crate::webp::{encode_rgba, WebpParams};
use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JByteBuffer, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, JNI_TRUE};
use jni::EnvUnowned;
use std::fs::File;
use std::io::{Cursor, Read};
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Encodes straight RGBA from a direct `ByteBuffer`. Returns the WebP file bytes or null.
/// `lossless` ignores `quality`; `near_lossless` / `exact` only apply to lossless.
#[no_mangle]
pub extern "system" fn Java_org_koitharu_kotatsu_core_image_codecs_NativeWebp_nativeEncode(
    mut env: EnvUnowned,
    _class: JClass,
    buffer: JByteBuffer,
    width: jint,
    height: jint,
    stride: jint,
    has_alpha: jboolean,
    lossless: jboolean,
    quality: jfloat,
    near_lossless: jint,
    exact: jboolean,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        if width <= 0 || height <= 0 || stride <= 0 {
            return std::ptr::null_mut();
        }
        env.with_env_no_catch(|env| -> jni::errors::Result<Option<JByteArray>> {
            let ptr = env.get_direct_buffer_address(&buffer)?;
            let capacity = env.get_direct_buffer_capacity(&buffer)?;
            if ptr.is_null() {
                return Ok(None);
            }
            // SAFETY: the buffer is a live direct ByteBuffer owned by the caller for this call; the
            // slice is only read.
            let pixels = unsafe { std::slice::from_raw_parts(ptr as *const u8, capacity) };
            let params = if lossless == JNI_TRUE {
                WebpParams::lossless(near_lossless, exact == JNI_TRUE)
            } else {
                WebpParams::lossy(quality)
            };
            let Ok(data) = encode_rgba(
                pixels,
                width as u32,
                height as u32,
                stride as usize,
                has_alpha == JNI_TRUE,
                &params,
            ) else {
                return Ok(None);
            };
            Ok(Some(env.byte_array_from_slice(&data)?))
        })
        .resolve::<LogErrorAndDefault>()
        .map_or(std::ptr::null_mut(), |a| a.into_raw())
    }))
    .unwrap_or(std::ptr::null_mut())
}

/// Source quality of the image file at `path`: 1..=100 lossy, 101 lossless, -1 unknown.
#[no_mangle]
pub extern "system" fn Java_org_koitharu_kotatsu_core_image_codecs_NativeWebp_nativeEstimateQuality(
    mut env: EnvUnowned,
    _class: JClass,
    path: JString,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        // An unreadable string resolves to "" which cannot be opened: reported as unknown.
        let path = env
            .with_env_no_catch(|env| -> jni::errors::Result<String> { path.try_to_string(env) })
            .resolve::<LogErrorAndDefault>();
        match File::open(path) {
            Ok(mut file) => estimate_buffered(&mut file).to_code(),
            Err(_) => SourceQuality::Unknown.to_code(),
        }
    }))
    .unwrap_or(-1)
}

/// How much of the file start is read in one go for the estimate.
const HEAD_BYTES: u64 = 64 * 1024;

/// The marker walk reads a byte or a few at a time; on a raw `File` each of those is a syscall (hundreds
/// for a JPEG). The head is read once and walked in memory; only when that is not enough (a walk that
/// runs past the head) does the file itself get walked.
fn estimate_buffered(file: &mut File) -> SourceQuality {
    let mut head = Vec::new();
    if file
        .by_ref()
        .take(HEAD_BYTES)
        .read_to_end(&mut head)
        .is_err()
    {
        return SourceQuality::Unknown;
    }
    let result = estimate(&mut Cursor::new(&head));
    if result == SourceQuality::Unknown && head.len() as u64 >= HEAD_BYTES {
        return estimate(file);
    }
    result
}
