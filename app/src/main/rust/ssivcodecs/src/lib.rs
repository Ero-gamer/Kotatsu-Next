//! Native image codecs for Kotatsu: JPEG XL (`jxl-oxide`), animated PNG (`png`), animated GIF (`gif`) and a
//! libwebp encoder (`webp`, vendored libwebp 1.6.0).
//!
//! The crate is built as a JNI library (`libssivcodecs.so`); everything except [`jni_api`] is plain
//! Rust and unit-testable on the host.

#[cfg(feature = "apng")]
pub mod apng;
pub mod common;
#[cfg(feature = "gif")]
pub mod gif;
pub mod jni_api;
#[cfg(feature = "jxl")]
pub mod jxl;
#[cfg(feature = "apng")]
pub mod pngstill;
pub mod quality;
#[cfg(feature = "webp")]
pub mod webp;
#[cfg(feature = "webp")]
pub mod webp_jni;

pub use common::{Decoder, Error, FileRange, Format, Info, PixelFormat, Rect, Result, Sink};
