//! Native image codecs for Kotatsu: JPEG XL (`jxl-oxide`) and animated PNG (`png`).
//!
//! The crate is built as a JNI library (`libssivcodecs.so`); everything except [`jni_api`] is plain
//! Rust and unit-testable on the host.

#[cfg(feature = "apng")]
pub mod apng;
pub mod common;
pub mod jni_api;
#[cfg(feature = "jxl")]
pub mod jxl;

pub use common::{Decoder, Error, FileRange, Format, Info, PixelFormat, Rect, Result, Sink};
