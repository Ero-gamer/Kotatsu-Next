//! Types and helpers shared by the JPEG XL and APNG decoders.

use std::fmt;
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::sync::Arc;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Clone)]
pub struct Error(pub String);

impl Error {
    pub fn new(message: impl Into<String>) -> Self {
        Error(message.into())
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error(format!("I/O error: {e}"))
    }
}

/// 2^28 pixels: past any real page, stops absurd headers early.
pub const MAX_IMAGE_PIXELS: u64 = 1 << 28;
/// Frames of one animation.
pub const MAX_FRAMES: u32 = 10_000;
/// Frames x canvas pixels: every frame re-composites the canvas.
pub const MAX_ANIMATION_PIXELS: u64 = 1 << 34;
/// A minute per frame is already absurd; past that a player just hangs.
pub const MAX_FRAME_DURATION_MS: u32 = 60_000;

/// Rejects images whose declared size would exhaust memory or time.
pub fn check_limits(width: u32, height: u32, frames: u32) -> Result<()> {
    let pixels = width as u64 * height as u64;
    if width == 0 || height == 0 || pixels > MAX_IMAGE_PIXELS {
        return Err(Error::new(format!(
            "image size {width}x{height} not supported"
        )));
    }
    if frames > MAX_FRAMES || pixels * frames.max(1) as u64 > MAX_ANIMATION_PIXELS {
        return Err(Error::new(format!("animation too large: {frames} frames")));
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    Jxl = 1,
    Apng = 2,
}

/// Static properties of an opened image.
#[derive(Clone, Debug)]
pub struct Info {
    pub format: Format,
    pub width: u32,
    pub height: u32,
    pub frame_count: u32,
    pub has_alpha: bool,
    /// Number of times an animation repeats, `0` = forever.
    pub loop_count: u32,
}

impl Info {
    pub fn is_animated(&self) -> bool {
        self.frame_count > 1
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rect {
    pub left: u32,
    pub top: u32,
    pub width: u32,
    pub height: u32,
}

impl Rect {
    pub fn new(left: u32, top: u32, width: u32, height: u32) -> Self {
        Rect {
            left,
            top,
            width,
            height,
        }
    }

    pub fn right(&self) -> u32 {
        self.left + self.width
    }

    pub fn bottom(&self) -> u32 {
        self.top + self.height
    }

    /// Intersection with `0..width x 0..height`, `None` when empty.
    pub fn clamped(&self, width: u32, height: u32) -> Option<Rect> {
        let l = self.left.min(width);
        let t = self.top.min(height);
        let r = self.left.saturating_add(self.width).min(width);
        let b = self.top.saturating_add(self.height).min(height);
        if r > l && b > t {
            Some(Rect::new(l, t, r - l, b - t))
        } else {
            None
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PixelFormat {
    /// `R, G, B, A` bytes, alpha premultiplied (Android `ARGB_8888`).
    Rgba8888,
    /// Little-endian `RRRRRGGGGGGBBBBB`, alpha dropped (Android `RGB_565`).
    Rgb565,
}

impl PixelFormat {
    pub fn bytes_per_pixel(self) -> usize {
        match self {
            PixelFormat::Rgba8888 => 4,
            PixelFormat::Rgb565 => 2,
        }
    }
}

/// Destination buffer for decoded pixels.
pub struct Sink<'a> {
    pub data: &'a mut [u8],
    pub stride: usize,
    pub format: PixelFormat,
    pub width: u32,
    pub height: u32,
}

impl<'a> Sink<'a> {
    pub fn new(
        data: &'a mut [u8],
        stride: usize,
        format: PixelFormat,
        width: u32,
        height: u32,
    ) -> Result<Self> {
        let row_bytes = width as usize * format.bytes_per_pixel();
        if stride < row_bytes {
            return Err(Error::new("destination stride is smaller than a row"));
        }
        if height > 0 {
            let need = stride
                .checked_mul(height as usize - 1)
                .and_then(|v| v.checked_add(row_bytes))
                .ok_or_else(|| Error::new("destination size overflow"))?;
            if data.len() < need {
                return Err(Error::new("destination buffer is too small"));
            }
        }
        Ok(Sink {
            data,
            stride,
            format,
            width,
            height,
        })
    }
}

/// Size of `len` source pixels after a box downsample by `sample`.
pub fn sampled_len(len: u32, sample: u32) -> u32 {
    len.div_ceil(sample.max(1))
}

const BAYER4: [[u8; 4]; 4] = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];

#[inline]
fn pack565(r: u8, g: u8, b: u8, a: u8, x: u32, y: u32) -> u16 {
    // Premultiplied colour over a white background, then ordered dither before truncation.
    let inv = 255 - a as u32;
    let r = (r as u32 + inv).min(255);
    let g = (g as u32 + inv).min(255);
    let b = (b as u32 + inv).min(255);
    let d = BAYER4[(y & 3) as usize][(x & 3) as usize] as u32;
    let r5 = (r + (d >> 1)).min(255) >> 3;
    let g6 = (g + (d >> 2)).min(255) >> 2;
    let b5 = (b + (d >> 1)).min(255) >> 3;
    ((r5 << 11) | (g6 << 5) | b5) as u16
}

/// Box-filter downsampler fed with full-resolution premultiplied RGBA rows, top to bottom.
///
/// With `sample == 1` rows are copied (or packed to RGB565) directly.
pub struct Resampler<'s, 'a> {
    sink: &'s mut Sink<'a>,
    sample: u32,
    src_w: u32,
    src_h: u32,
    next_row: u32,
    acc: Vec<u32>,
    acc_rows: u32,
    cur_out: u32,
}

impl<'s, 'a> Resampler<'s, 'a> {
    pub fn new(sink: &'s mut Sink<'a>, src_w: u32, src_h: u32, sample: u32) -> Result<Self> {
        let sample = sample.max(1);
        if sink.width != sampled_len(src_w, sample) || sink.height != sampled_len(src_h, sample) {
            return Err(Error::new(
                "destination size does not match the sampled region",
            ));
        }
        let acc = if sample > 1 {
            vec![0u32; sink.width as usize * 4]
        } else {
            Vec::new()
        };
        Ok(Resampler {
            sink,
            sample,
            src_w,
            src_h,
            next_row: 0,
            acc,
            acc_rows: 0,
            cur_out: 0,
        })
    }

    /// Number of source rows still expected.
    pub fn remaining_rows(&self) -> u32 {
        self.src_h - self.next_row
    }

    /// Pushes the next source row: `src_w * 4` bytes of premultiplied RGBA.
    pub fn push_row(&mut self, row: &[u8]) {
        debug_assert_eq!(row.len(), self.src_w as usize * 4);
        if self.next_row >= self.src_h {
            return;
        }
        let y = self.next_row;
        self.next_row += 1;
        if self.sample == 1 {
            self.write_row(y, row);
            return;
        }
        let out_row = y / self.sample;
        if out_row != self.cur_out {
            self.flush();
            self.cur_out = out_row;
        }
        let s = self.sample as usize;
        for (ox, chunk) in row.chunks(s * 4).enumerate() {
            let a = &mut self.acc[ox * 4..ox * 4 + 4];
            for px in chunk.chunks_exact(4) {
                a[0] += px[0] as u32;
                a[1] += px[1] as u32;
                a[2] += px[2] as u32;
                a[3] += px[3] as u32;
            }
        }
        self.acc_rows += 1;
        if self.next_row == self.src_h {
            self.flush();
        }
    }

    fn flush(&mut self) {
        if self.acc_rows == 0 {
            return;
        }
        let s = self.sample;
        let out_w = self.sink.width as usize;
        let mut tmp = vec![0u8; out_w * 4];
        for ox in 0..out_w {
            let cols = s.min(self.src_w - ox as u32 * s);
            let n = cols * self.acc_rows;
            let half = n / 2;
            for c in 0..4 {
                tmp[ox * 4 + c] = ((self.acc[ox * 4 + c] + half) / n) as u8;
            }
        }
        self.acc.iter_mut().for_each(|v| *v = 0);
        self.acc_rows = 0;
        let y = self.cur_out;
        self.write_row(y, &tmp);
    }

    fn write_row(&mut self, y: u32, rgba: &[u8]) {
        let stride = self.sink.stride;
        let w = self.sink.width as usize;
        let start = y as usize * stride;
        match self.sink.format {
            PixelFormat::Rgba8888 => {
                self.sink.data[start..start + w * 4].copy_from_slice(&rgba[..w * 4]);
            }
            PixelFormat::Rgb565 => {
                let dst = &mut self.sink.data[start..start + w * 2];
                for (x, (px, out)) in rgba
                    .chunks_exact(4)
                    .zip(dst.chunks_exact_mut(2))
                    .enumerate()
                {
                    let v = pack565(px[0], px[1], px[2], px[3], x as u32, y);
                    out.copy_from_slice(&v.to_le_bytes());
                }
            }
        }
    }
}

/// A read-only window `[offset, offset + len)` of a shared file, read with positional reads (no shared
/// cursor), so any number of readers can be created cheaply, e.g. to restart an animation.
#[derive(Clone)]
pub struct FileRange {
    file: Arc<File>,
    offset: u64,
    len: u64,
    pos: u64,
}

impl FileRange {
    pub fn new(file: Arc<File>, offset: u64, len: u64) -> Self {
        FileRange {
            file,
            offset,
            len,
            pos: 0,
        }
    }

    pub fn len(&self) -> u64 {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

impl Read for FileRange {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.pos >= self.len || buf.is_empty() {
            return Ok(0);
        }
        let want = buf.len().min((self.len - self.pos) as usize);
        let n = read_at(&self.file, &mut buf[..want], self.offset + self.pos)?;
        self.pos += n as u64;
        Ok(n)
    }
}

impl Seek for FileRange {
    fn seek(&mut self, from: SeekFrom) -> std::io::Result<u64> {
        let target = match from {
            SeekFrom::Start(n) => n as i128,
            SeekFrom::End(d) => self.len as i128 + d as i128,
            SeekFrom::Current(d) => self.pos as i128 + d as i128,
        };
        if target < 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "seek before start",
            ));
        }
        self.pos = target as u64;
        Ok(self.pos)
    }
}

#[cfg(unix)]
fn read_at(file: &File, buf: &mut [u8], offset: u64) -> std::io::Result<usize> {
    use std::os::unix::fs::FileExt;
    file.read_at(buf, offset)
}

#[cfg(not(unix))]
fn read_at(_file: &File, _buf: &mut [u8], _offset: u64) -> std::io::Result<usize> {
    Err(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "positional reads need a unix target",
    ))
}

/// A decoder for one opened image file.
pub trait Decoder: Send {
    fn info(&self) -> &Info;

    /// Display time of a frame in milliseconds.
    fn frame_duration_ms(&self, frame: u32) -> u32;

    /// Decodes `rect` (in image coordinates) of `frame`, box-downsampled by `sample`, into `sink`.
    /// `sink` must be `sampled_len(rect.width) x sampled_len(rect.height)` pixels.
    fn decode(&mut self, frame: u32, rect: Rect, sample: u32, sink: &mut Sink<'_>) -> Result<()>;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sampled_len_rounds_up() {
        assert_eq!(sampled_len(10, 1), 10);
        assert_eq!(sampled_len(10, 4), 3);
        assert_eq!(sampled_len(8, 4), 2);
        assert_eq!(sampled_len(1, 8), 1);
    }

    #[test]
    fn resampler_averages_blocks_and_handles_edges() {
        // 5x3 image, sample 2 -> 3x2
        let (w, h) = (5u32, 3u32);
        let mut out = vec![0u8; 3 * 2 * 4];
        let mut sink = Sink::new(&mut out, 12, PixelFormat::Rgba8888, 3, 2).unwrap();
        let mut rs = Resampler::new(&mut sink, w, h, 2).unwrap();
        for y in 0..h {
            let mut row = vec![0u8; w as usize * 4];
            for x in 0..w {
                let v = (x + y * 10) as u8;
                row[x as usize * 4..x as usize * 4 + 4].copy_from_slice(&[v, v, v, 255]);
            }
            rs.push_row(&row);
        }
        // block (0,0): values 0,1,10,11 -> avg 5.5 -> 6 (rounded half up)
        assert_eq!(out[0], 6);
        // block (2,0): column 4 only, rows 0..2: values 4 and 14 -> 9
        assert_eq!(out[2 * 4], 9);
        // block (0,1): row 2 only, cols 0..2: values 20,21 -> 20.5 -> 21
        assert_eq!(out[12], 21);
        assert_eq!(out[3], 255);
    }

    #[test]
    fn rect_clamp() {
        assert_eq!(
            Rect::new(5, 5, 100, 100).clamped(10, 10),
            Some(Rect::new(5, 5, 5, 5))
        );
        assert_eq!(Rect::new(20, 0, 5, 5).clamped(10, 10), None);
    }

    #[test]
    fn limits() {
        assert!(check_limits(1000, 20000, 1).is_ok());
        assert!(check_limits(0, 10, 1).is_err());
        assert!(check_limits(1 << 15, 1 << 14, 1).is_err()); // 2^29 px
        assert!(check_limits(10, 10, MAX_FRAMES + 1).is_err());
        assert!(check_limits(1 << 14, 1 << 13, 200).is_err()); // 2^27 px x 200 > 2^34
        assert!(check_limits(1000, 1000, 100).is_ok());
    }
}
