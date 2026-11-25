//! Still (non-animated, non-interlaced) PNG region decoder on top of the `png` crate.
//!
//! Rows are streamed: rows above the requested region are inflated and dropped, rows below it are never
//! read, and only the requested columns are converted and box-downsampled. The decoder keeps its stream
//! position, so tiles requested top to bottom (the usual scroll direction) continue where the previous one
//! stopped instead of inflating the image from the top again. A small band cache lets every tile of one
//! tile row share a single pass. Interlaced and animated files are not handled here (the caller falls back
//! to the system decoder / the APNG decoder).

use crate::common::{
    check_limits, premultiply, sampled_len, Decoder, Error, FileRange, Format, Info, PixelFormat,
    Rect, Resampler, Result, Sink,
};
use png::{ColorType, Transformations};
use std::io::BufReader;

const SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
/// Largest decoded (sampled, RGBA) tile-row band that is kept for the next tile of the same row.
const BAND_MAX_BYTES: usize = 8 * 1024 * 1024;
const READ_BUFFER: usize = 64 * 1024;

/// True when `head` (the first bytes of a file) is a non-interlaced PNG without an `acTL` chunk.
pub fn is_still_png(head: &[u8]) -> bool {
    if !head.starts_with(&SIGNATURE) || head.len() < 8 + 8 + 13 || &head[12..16] != b"IHDR" {
        return false;
    }
    // IHDR data: width(4) height(4) depth color compression filter interlace.
    if head[8 + 8 + 12] != 0 {
        return false;
    }
    let mut pos = 8usize;
    while pos + 8 <= head.len() {
        let len =
            u32::from_be_bytes([head[pos], head[pos + 1], head[pos + 2], head[pos + 3]]) as usize;
        match &head[pos + 4..pos + 8] {
            b"acTL" => return false,
            b"IDAT" => return true,
            b"IEND" => return false,
            _ => {}
        }
        // `12 + len` itself wraps on 32-bit targets for a hostile length
        pos = match len.checked_add(12).and_then(|n| pos.checked_add(n)) {
            Some(p) => p,
            None => return false,
        };
    }
    // `IDAT` not within the head (huge ancillary chunks): the `png` crate rejects what it cannot handle.
    true
}

type PngReader = png::Reader<BufReader<FileRange>>;

fn png_err(e: png::DecodingError) -> Error {
    Error::new(format!("PNG: {e}"))
}

struct Band {
    top: u32,
    height: u32,
    sample: u32,
    width_s: u32,
    /// Premultiplied RGBA, `width_s * sampled_len(height)` pixels.
    data: Vec<u8>,
}

pub struct PngStillDecoder {
    source: FileRange,
    info: Info,
    color: ColorType,
    reader: Option<PngReader>,
    next_row: u32,
    band: Option<Band>,
}

fn open_reader(source: &FileRange) -> Result<PngReader> {
    let mut decoder = png::Decoder::new(BufReader::with_capacity(READ_BUFFER, source.clone()));
    decoder.set_transformations(Transformations::EXPAND | Transformations::STRIP_16);
    let reader = decoder.read_info().map_err(png_err)?;
    if reader.info().interlaced {
        return Err(Error::new("PNG: interlaced"));
    }
    if reader.info().is_animated() {
        return Err(Error::new("PNG: animated"));
    }
    Ok(reader)
}

impl PngStillDecoder {
    pub fn new(source: FileRange) -> Result<Self> {
        let reader = open_reader(&source)?;
        let (width, height) = {
            let i = reader.info();
            (i.width, i.height)
        };
        check_limits(width, height, 1)?;
        let (color, _) = reader.output_color_type();
        let has_alpha = matches!(color, ColorType::Rgba | ColorType::GrayscaleAlpha);
        Ok(PngStillDecoder {
            source,
            info: Info {
                format: Format::Png,
                width,
                height,
                frame_count: 1,
                has_alpha,
                loop_count: 0,
            },
            color,
            reader: Some(reader),
            next_row: 0,
            band: None,
        })
    }

    /// Positions the stream so the next row read is `top`.
    fn seek_row(&mut self, top: u32) -> Result<()> {
        if self.reader.is_none() || self.next_row > top {
            self.reader = Some(open_reader(&self.source)?);
            self.next_row = 0;
        }
        let reader = self.reader.as_mut().expect("reader was just opened");
        while self.next_row < top {
            reader
                .next_row()
                .map_err(png_err)?
                .ok_or_else(|| Error::new("PNG: truncated"))?;
            self.next_row += 1;
        }
        Ok(())
    }

    /// Streams `rect` into `sink`, box-downsampled by `sample`.
    fn decode_rows(&mut self, rect: Rect, sample: u32, sink: &mut Sink<'_>) -> Result<()> {
        self.seek_row(rect.top)?;
        let mut resampler = Resampler::new(sink, rect.width, rect.height, sample)?;
        let mut out = vec![0u8; rect.width as usize * 4];
        let color = self.color;
        let reader = self.reader.as_mut().expect("seek_row opened the reader");
        for _ in 0..rect.height {
            let row = reader
                .next_row()
                .map_err(png_err)?
                .ok_or_else(|| Error::new("PNG: truncated"))?;
            self.next_row += 1;
            convert_row(row.data(), color, rect.left as usize, &mut out)?;
            resampler.push_row(&out);
        }
        Ok(())
    }

    fn decode_inner(&mut self, rect: Rect, sample: u32, sink: &mut Sink<'_>) -> Result<()> {
        let (w, h) = (self.info.width, self.info.height);
        let aligned = rect.left % sample == 0
            && rect.top % sample == 0
            && (rect.width % sample == 0 || rect.right() == w)
            && (rect.height % sample == 0 || rect.bottom() == h);
        let band_w = sampled_len(w, sample);
        let band_h = sampled_len(rect.height, sample);
        let band_bytes = (band_w as usize)
            .checked_mul(band_h as usize)
            .and_then(|v| v.checked_mul(4));
        let use_band = aligned && band_bytes.is_some_and(|b| b <= BAND_MAX_BYTES);
        if !use_band {
            return self.decode_rows(rect, sample, sink);
        }
        let hit = matches!(
            &self.band,
            Some(b) if b.top == rect.top && b.height == rect.height && b.sample == sample
        );
        if !hit {
            self.band = None;
            let mut data = vec![0u8; band_bytes.unwrap_or(0)];
            {
                let mut band_sink = Sink::new(
                    &mut data,
                    band_w as usize * 4,
                    PixelFormat::Rgba8888,
                    band_w,
                    band_h,
                )?;
                self.decode_rows(
                    Rect::new(0, rect.top, w, rect.height),
                    sample,
                    &mut band_sink,
                )?;
            }
            self.band = Some(Band {
                top: rect.top,
                height: rect.height,
                sample,
                width_s: band_w,
                data,
            });
        }
        let band = self.band.as_ref().expect("band was just stored");
        let tile_w = sink.width as usize;
        let x0 = (rect.left / sample) as usize;
        if sink.height != band_h || x0 + tile_w > band.width_s as usize {
            return Err(Error::new("PNG: band does not cover the tile"));
        }
        let mut copy = Resampler::new(sink, tile_w as u32, band_h, 1)?;
        for y in 0..band_h as usize {
            let start = (y * band.width_s as usize + x0) * 4;
            copy.push_row(&band.data[start..start + tile_w * 4]);
        }
        Ok(())
    }
}

/// Converts the columns `left..left + out.len() / 4` of one decoded row to premultiplied RGBA.
fn convert_row(row: &[u8], color: ColorType, left: usize, out: &mut [u8]) -> Result<()> {
    let count = out.len() / 4;
    let channels = match color {
        ColorType::Grayscale => 1,
        ColorType::GrayscaleAlpha => 2,
        ColorType::Rgb => 3,
        ColorType::Rgba => 4,
        ColorType::Indexed => return Err(Error::new("PNG: palette was not expanded")),
    };
    let start = left * channels;
    let src = row
        .get(start..start + count * channels)
        .ok_or_else(|| Error::new("PNG: row is shorter than the image width"))?;
    match color {
        ColorType::Grayscale => {
            for (s, d) in src.iter().zip(out.chunks_exact_mut(4)) {
                d.copy_from_slice(&[*s, *s, *s, 255]);
            }
        }
        ColorType::GrayscaleAlpha => {
            for (s, d) in src.chunks_exact(2).zip(out.chunks_exact_mut(4)) {
                let g = premultiply(s[0], s[1]);
                d.copy_from_slice(&[g, g, g, s[1]]);
            }
        }
        ColorType::Rgb => {
            for (s, d) in src.chunks_exact(3).zip(out.chunks_exact_mut(4)) {
                d.copy_from_slice(&[s[0], s[1], s[2], 255]);
            }
        }
        _ => {
            for (s, d) in src.chunks_exact(4).zip(out.chunks_exact_mut(4)) {
                let a = s[3];
                d.copy_from_slice(&[
                    premultiply(s[0], a),
                    premultiply(s[1], a),
                    premultiply(s[2], a),
                    a,
                ]);
            }
        }
    }
    Ok(())
}

impl Decoder for PngStillDecoder {
    fn info(&self) -> &Info {
        &self.info
    }

    fn frame_duration_ms(&self, _frame: u32) -> u32 {
        0
    }

    fn decode(&mut self, frame: u32, rect: Rect, sample: u32, sink: &mut Sink<'_>) -> Result<()> {
        if frame != 0 {
            return Err(Error::new("PNG: single frame"));
        }
        if rect.width == 0
            || rect.height == 0
            || rect.right() > self.info.width
            || rect.bottom() > self.info.height
        {
            return Err(Error::new("PNG: region outside of the image"));
        }
        let result = self.decode_inner(rect, sample.max(1), sink);
        if result.is_err() {
            // A failed stream cannot be resumed: start over on the next call.
            self.reader = None;
            self.next_row = 0;
            self.band = None;
        }
        result
    }
}
