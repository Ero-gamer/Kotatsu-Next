//! Animated PNG (APNG) on top of the `png` crate.
//!
//! The chunk layout is scanned once without decoding (frame count, delays, loop count); frames are
//! then decoded sequentially and composited onto a canvas following the APNG dispose / blend rules.
//! Playback is sequential, so each frame is decoded once per loop; asking for an earlier frame
//! restarts the stream.

use crate::common::{
    check_limits, Decoder, Error, FileRange, Format, Info, Rect, Resampler, Result, Sink,
    MAX_FRAME_DURATION_MS,
};
use std::io::{BufReader, Read, Seek, SeekFrom};

const SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
const MAX_PIXELS: u64 = 64 * 1024 * 1024;
const MAX_FRAMES: u32 = 10_000;

/// True when `head` (at least the first 8 KiB of a file) is a PNG with an `acTL` chunk before `IDAT`.
pub fn is_apng(head: &[u8]) -> bool {
    if !head.starts_with(&SIGNATURE) {
        return false;
    }
    let mut pos = 8usize;
    while pos + 8 <= head.len() {
        let len =
            u32::from_be_bytes([head[pos], head[pos + 1], head[pos + 2], head[pos + 3]]) as usize;
        match &head[pos + 4..pos + 8] {
            b"acTL" => return true,
            b"IDAT" | b"IEND" => return false,
            _ => {}
        }
        pos = match pos.checked_add(12 + len) {
            Some(p) => p,
            None => return false,
        };
    }
    false
}

#[derive(Clone, Copy, Debug)]
struct FrameCtl {
    width: u32,
    height: u32,
    x: u32,
    y: u32,
    dispose: u8,
    blend: u8,
}

struct Layout {
    width: u32,
    height: u32,
    has_alpha: bool,
    loops: u32,
    /// Delay of every animation frame in milliseconds.
    delays: Vec<u32>,
    /// The default image (IDAT) is not part of the animation and has to be skipped.
    skip_default: bool,
}

fn scan(mut src: impl Read + Seek) -> Result<Layout> {
    let mut sig = [0u8; 8];
    src.read_exact(&mut sig)?;
    if sig != SIGNATURE {
        return Err(Error::new("APNG: not a PNG file"));
    }
    let (mut width, mut height, mut color_type) = (0u32, 0u32, 0u8);
    let (mut frames, mut loops) = (0u32, 0u32);
    let mut has_trns = false;
    let mut seen_actl = false;
    let mut seen_idat = false;
    let mut default_in_animation = false;
    let mut delays: Vec<u32> = Vec::new();
    loop {
        let mut head = [0u8; 8];
        if src.read_exact(&mut head).is_err() {
            break;
        }
        let len = u32::from_be_bytes([head[0], head[1], head[2], head[3]]) as u64;
        let kind = [head[4], head[5], head[6], head[7]];
        match &kind {
            b"IHDR" => {
                let mut d = [0u8; 13];
                if len < 13 {
                    return Err(Error::new("APNG: bad IHDR"));
                }
                src.read_exact(&mut d)?;
                width = u32::from_be_bytes([d[0], d[1], d[2], d[3]]);
                height = u32::from_be_bytes([d[4], d[5], d[6], d[7]]);
                color_type = d[9];
                src.seek(SeekFrom::Current(len as i64 - 13 + 4))?;
            }
            b"tRNS" => {
                has_trns = true;
                src.seek(SeekFrom::Current(len as i64 + 4))?;
            }
            b"acTL" => {
                if len < 8 {
                    return Err(Error::new("APNG: bad acTL"));
                }
                let mut d = [0u8; 8];
                src.read_exact(&mut d)?;
                frames = u32::from_be_bytes([d[0], d[1], d[2], d[3]]);
                loops = u32::from_be_bytes([d[4], d[5], d[6], d[7]]);
                seen_actl = true;
                src.seek(SeekFrom::Current(len as i64 - 8 + 4))?;
            }
            b"fcTL" => {
                if len < 26 {
                    return Err(Error::new("APNG: bad fcTL"));
                }
                let mut d = [0u8; 26];
                src.read_exact(&mut d)?;
                if !seen_idat {
                    default_in_animation = true;
                }
                let num = u16::from_be_bytes([d[20], d[21]]) as u32;
                let den = match u16::from_be_bytes([d[22], d[23]]) {
                    0 => 100,
                    v => v as u32,
                };
                delays.push((num * 1000 / den).min(MAX_FRAME_DURATION_MS));
                src.seek(SeekFrom::Current(len as i64 - 26 + 4))?;
            }
            b"IDAT" => {
                seen_idat = true;
                src.seek(SeekFrom::Current(len as i64 + 4))?;
            }
            b"IEND" => break,
            _ => {
                src.seek(SeekFrom::Current(len as i64 + 4))?;
            }
        }
    }
    if !seen_actl || frames < 2 || delays.len() as u32 != frames || frames > MAX_FRAMES {
        return Err(Error::new("APNG: not an animation"));
    }
    if width == 0 || height == 0 || width as u64 * height as u64 > MAX_PIXELS {
        return Err(Error::new("APNG: unsupported image size"));
    }
    check_limits(width, height, frames).map_err(|e| Error::new(format!("APNG: {e}")))?;
    Ok(Layout {
        width,
        height,
        has_alpha: matches!(color_type, 4 | 6) || has_trns,
        loops,
        delays,
        skip_default: !default_in_animation,
    })
}

struct Stream {
    reader: png::Reader<BufReader<FileRange>>,
    scratch: Vec<u8>,
    /// Index of the next animation frame the stream will produce.
    next: u32,
    /// Control data of the previously composited frame and the canvas before it was drawn.
    prev: Option<(FrameCtl, Option<Vec<u8>>)>,
    skipped_default: bool,
}

pub struct ApngDecoder {
    source: FileRange,
    info: Info,
    delays: Vec<u32>,
    skip_default: bool,
    stream: Option<Stream>,
    /// Straight (non-premultiplied) RGBA canvas after `rendered` frames.
    canvas: Vec<u8>,
    /// Index of the frame currently held by `canvas`.
    rendered: Option<u32>,
}

impl ApngDecoder {
    pub fn new(source: FileRange) -> Result<Self> {
        let layout = scan(source.clone())?;
        let frames = layout.delays.len() as u32;
        let canvas_len = layout.width as usize * layout.height as usize * 4;
        Ok(ApngDecoder {
            source,
            info: Info {
                format: Format::Apng,
                width: layout.width,
                height: layout.height,
                frame_count: frames,
                has_alpha: layout.has_alpha,
                loop_count: layout.loops,
            },
            delays: layout.delays,
            skip_default: layout.skip_default,
            stream: None,
            canvas: vec![0; canvas_len],
            rendered: None,
        })
    }

    fn open_stream(&self) -> Result<Stream> {
        let mut decoder = png::Decoder::new(BufReader::new(self.source.clone()));
        decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
        let reader = decoder
            .read_info()
            .map_err(|e| Error::new(format!("APNG: {e}")))?;
        let cap = reader
            .output_buffer_size()
            .ok_or_else(|| Error::new("APNG: frame too large"))?;
        Ok(Stream {
            reader,
            scratch: vec![0; cap],
            next: 0,
            prev: None,
            skipped_default: !self.skip_default,
        })
    }

    /// Decodes the next animation frame; a failure drops the stream so the next call restarts cleanly.
    fn advance(&mut self) -> Result<()> {
        let result = self.advance_inner();
        if result.is_err() {
            self.stream = None;
            self.rendered = None;
        }
        result
    }

    /// Decodes the next animation frame and composites it onto the canvas.
    fn advance_inner(&mut self) -> Result<()> {
        if self.stream.is_none() {
            self.stream = Some(self.open_stream()?);
            self.canvas.iter_mut().for_each(|b| *b = 0);
            self.rendered = None;
        }
        let (width, height) = (self.info.width, self.info.height);
        let stream = self.stream.as_mut().expect("stream was just opened");
        if !stream.skipped_default {
            stream
                .reader
                .next_frame(&mut stream.scratch)
                .map_err(|e| Error::new(format!("APNG: {e}")))?;
            stream.skipped_default = true;
        }
        let out = stream
            .reader
            .next_frame(&mut stream.scratch)
            .map_err(|e| Error::new(format!("APNG: {e}")))?;
        let fc = stream
            .reader
            .info()
            .frame_control()
            .ok_or_else(|| Error::new("APNG: missing frame control"))?;
        let ctl = FrameCtl {
            width: fc.width,
            height: fc.height,
            x: fc.x_offset,
            y: fc.y_offset,
            dispose: fc.dispose_op as u8,
            blend: fc.blend_op as u8,
        };
        if ctl.x as u64 + ctl.width as u64 > width as u64
            || ctl.y as u64 + ctl.height as u64 > height as u64
        {
            return Err(Error::new("APNG: frame is outside of the canvas"));
        }
        // 1. Dispose of the previous frame.
        if let Some((pc, saved)) = stream.prev.take() {
            match pc.dispose {
                1 => clear_rect(&mut self.canvas, width, &pc),
                2 => {
                    if let Some(saved) = saved {
                        restore_rect(&mut self.canvas, width, &pc, &saved);
                    } else {
                        clear_rect(&mut self.canvas, width, &pc);
                    }
                }
                _ => {}
            }
        }
        // 2. Remember what a "dispose to previous" would have to restore.
        let saved = if ctl.dispose == 2 && stream.next > 0 {
            Some(copy_rect(&self.canvas, width, &ctl))
        } else {
            None
        };
        // 3. Draw.
        let data = &stream.scratch[..out.buffer_size()];
        draw(&mut self.canvas, width, &ctl, data, out.color_type)?;
        stream.prev = Some((ctl, saved));
        self.rendered = Some(stream.next);
        stream.next += 1;
        Ok(())
    }
}

fn clear_rect(canvas: &mut [u8], width: u32, c: &FrameCtl) {
    for row in 0..c.height as usize {
        let start = ((c.y as usize + row) * width as usize + c.x as usize) * 4;
        canvas[start..start + c.width as usize * 4]
            .iter_mut()
            .for_each(|b| *b = 0);
    }
}

fn copy_rect(canvas: &[u8], width: u32, c: &FrameCtl) -> Vec<u8> {
    let mut out = Vec::with_capacity(c.width as usize * c.height as usize * 4);
    for row in 0..c.height as usize {
        let start = ((c.y as usize + row) * width as usize + c.x as usize) * 4;
        out.extend_from_slice(&canvas[start..start + c.width as usize * 4]);
    }
    out
}

fn restore_rect(canvas: &mut [u8], width: u32, c: &FrameCtl, saved: &[u8]) {
    let row_len = c.width as usize * 4;
    for row in 0..c.height as usize {
        let start = ((c.y as usize + row) * width as usize + c.x as usize) * 4;
        canvas[start..start + row_len].copy_from_slice(&saved[row * row_len..(row + 1) * row_len]);
    }
}

fn draw(
    canvas: &mut [u8],
    width: u32,
    c: &FrameCtl,
    data: &[u8],
    color: png::ColorType,
) -> Result<()> {
    let channels = match color {
        png::ColorType::Grayscale => 1,
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Rgb => 3,
        png::ColorType::Rgba => 4,
        png::ColorType::Indexed => return Err(Error::new("APNG: unexpanded palette")),
    };
    let row_len = c.width as usize * channels;
    if data.len() < row_len * c.height as usize {
        return Err(Error::new("APNG: short frame data"));
    }
    for row in 0..c.height as usize {
        let src = &data[row * row_len..(row + 1) * row_len];
        let start = ((c.y as usize + row) * width as usize + c.x as usize) * 4;
        let dst = &mut canvas[start..start + c.width as usize * 4];
        for (s, d) in src.chunks_exact(channels).zip(dst.chunks_exact_mut(4)) {
            let px = match channels {
                1 => [s[0], s[0], s[0], 255],
                2 => [s[0], s[0], s[0], s[1]],
                3 => [s[0], s[1], s[2], 255],
                _ => [s[0], s[1], s[2], s[3]],
            };
            if c.blend == 0 || px[3] == 255 {
                d.copy_from_slice(&px);
            } else if px[3] != 0 {
                over(d, &px);
            }
        }
    }
    Ok(())
}

/// Straight-alpha "source over destination" for one pixel.
fn over(dst: &mut [u8], src: &[u8; 4]) {
    let sa = src[3] as u32;
    let da = dst[3] as u32;
    let out_a = sa * 255 + da * (255 - sa);
    if out_a == 0 {
        dst.copy_from_slice(&[0, 0, 0, 0]);
        return;
    }
    for i in 0..3 {
        let num = src[i] as u32 * sa * 255 + dst[i] as u32 * da * (255 - sa);
        dst[i] = ((num + out_a / 2) / out_a) as u8;
    }
    dst[3] = ((out_a + 127) / 255) as u8;
}

#[inline]
fn premultiply(c: u8, a: u8) -> u8 {
    let v = c as u32 * a as u32 + 128;
    ((v + (v >> 8)) >> 8) as u8
}

impl Decoder for ApngDecoder {
    fn info(&self) -> &Info {
        &self.info
    }

    fn frame_duration_ms(&self, frame: u32) -> u32 {
        self.delays.get(frame as usize).copied().unwrap_or(0)
    }

    fn decode(&mut self, frame: u32, rect: Rect, sample: u32, sink: &mut Sink<'_>) -> Result<()> {
        if frame >= self.info.frame_count {
            return Err(Error::new("frame index out of range"));
        }
        let rect = rect
            .clamped(self.info.width, self.info.height)
            .ok_or_else(|| Error::new("region is outside of the image"))?;
        if self.rendered != Some(frame) {
            if self.rendered.is_some_and(|r| r > frame) {
                // Going backwards: restart the stream (playback loops hit this once per loop).
                self.stream = None;
            }
            loop {
                self.advance()?;
                if self.rendered == Some(frame) {
                    break;
                }
            }
        }
        let mut resampler = Resampler::new(sink, rect.width, rect.height, sample)?;
        let width = self.info.width as usize;
        let mut row = vec![0u8; rect.width as usize * 4];
        for y in rect.top..rect.bottom() {
            let start = (y as usize * width + rect.left as usize) * 4;
            for (s, d) in self.canvas[start..start + rect.width as usize * 4]
                .chunks_exact(4)
                .zip(row.chunks_exact_mut(4))
            {
                let a = s[3];
                d.copy_from_slice(&[
                    premultiply(s[0], a),
                    premultiply(s[1], a),
                    premultiply(s[2], a),
                    a,
                ]);
            }
            resampler.push_row(&row);
        }
        Ok(())
    }
}
