//! Animated GIF on top of the `gif` crate.
//!
//! Frame headers are scanned once without decoding pixels (frame count, delays, transparency, loop
//! count); frames are then decoded sequentially and composited onto a straight-alpha canvas
//! following the GIF disposal rules. Asking for an earlier frame restarts the stream.

use crate::common::{
    check_limits, emit_canvas, reserve_vec, zeroed_vec, Decoder, Error, FileRange, Format, Info,
    Rect, Result, Sink, MAX_FRAME_DURATION_MS, MAX_STREAM_CANVAS_PIXELS,
};
use ::gif::{ColorOutput, DecodeOptions, DisposalMethod, MemoryLimit, Repeat};
use std::io::BufReader;
use std::num::NonZeroU64;

const MAX_PIXELS: u64 = MAX_STREAM_CANVAS_PIXELS;
const MAX_FRAMES: u32 = 10_000;
/// Per-frame decode buffer cap (bytes); a frame never exceeds the canvas, which `MAX_PIXELS` bounds.
const FRAME_MEMORY: u64 = MAX_PIXELS * 4;
/// Delays of 0 / 1 centiseconds are treated as 100 ms, like browsers do.
const DEFAULT_DELAY_MS: u32 = 100;

/// True when `head` starts with a GIF signature.
pub fn is_gif(head: &[u8]) -> bool {
    head.starts_with(b"GIF87a") || head.starts_with(b"GIF89a")
}

fn options() -> DecodeOptions {
    let mut o = DecodeOptions::new();
    o.set_color_output(ColorOutput::RGBA);
    if let Some(limit) = NonZeroU64::new(FRAME_MEMORY) {
        o.set_memory_limit(MemoryLimit::Bytes(limit));
    }
    o
}

fn err(e: ::gif::DecodingError) -> Error {
    Error::new(format!("GIF: {e}"))
}

fn delay_ms(centis: u16) -> u32 {
    if centis <= 1 {
        DEFAULT_DELAY_MS
    } else {
        (centis as u32 * 10).min(MAX_FRAME_DURATION_MS)
    }
}

#[derive(Clone, Copy)]
struct Placed {
    x: usize,
    y: usize,
    w: usize,
    h: usize,
}

struct Stream {
    decoder: ::gif::Decoder<BufReader<FileRange>>,
    /// Index of the next frame the stream will produce.
    next: u32,
    /// Placement and disposal of the previously drawn frame, plus the pixels it covered.
    prev: Option<(Placed, DisposalMethod, Vec<u8>)>,
}

pub struct GifDecoder {
    source: FileRange,
    info: Info,
    delays: Vec<u32>,
    stream: Option<Stream>,
    /// Straight (non-premultiplied) RGBA canvas holding frame `rendered`.
    canvas: Vec<u8>,
    rendered: Option<u32>,
}

impl GifDecoder {
    pub fn new(source: FileRange) -> Result<Self> {
        let mut dec = options()
            .read_info(BufReader::new(source.clone()))
            .map_err(err)?;
        let (width, height) = (dec.width() as u32, dec.height() as u32);
        if width == 0 || height == 0 || width as u64 * height as u64 > MAX_PIXELS {
            return Err(Error::new("GIF: unsupported image size"));
        }
        let mut delays = Vec::new();
        let mut has_alpha = false;
        while let Some(f) = dec.next_frame_info().map_err(err)? {
            has_alpha |= f.transparent.is_some();
            delays.push(delay_ms(f.delay));
            if delays.len() as u32 > MAX_FRAMES {
                return Err(Error::new("GIF: too many frames"));
            }
        }
        let frames = delays.len() as u32;
        if frames < 2 {
            return Err(Error::new("GIF: not an animation"));
        }
        check_limits(width, height, frames).map_err(|e| Error::new(format!("GIF: {e}")))?;
        // NETSCAPE count = extra repetitions; no extension = play once. Info: total plays, 0 = forever.
        let loop_count = match dec.repeat() {
            Repeat::Infinite => 0,
            Repeat::Finite(n) => n as u32 + 1,
        };
        Ok(GifDecoder {
            source,
            info: Info {
                format: Format::Gif,
                width,
                height,
                frame_count: frames,
                has_alpha,
                loop_count,
            },
            delays,
            stream: None,
            canvas: zeroed_vec(width as usize * height as usize * 4)?,
            rendered: None,
        })
    }

    /// Decodes the next frame; a failure drops the stream so the next call restarts cleanly.
    fn advance(&mut self) -> Result<()> {
        let result = self.advance_inner();
        if result.is_err() {
            self.stream = None;
            self.rendered = None;
        }
        result
    }

    fn advance_inner(&mut self) -> Result<()> {
        if self.stream.is_none() {
            let decoder = options()
                .read_info(BufReader::new(self.source.clone()))
                .map_err(err)?;
            self.stream = Some(Stream {
                decoder,
                next: 0,
                prev: None,
            });
            self.canvas.fill(0);
            self.rendered = None;
        }
        let (cw, ch) = (self.info.width as usize, self.info.height as usize);
        let stream = self.stream.as_mut().expect("stream was just opened");
        let frame = stream
            .decoder
            .read_next_frame()
            .map_err(err)?
            .ok_or_else(|| Error::new("GIF: unexpected end of stream"))?;
        // Clip to the canvas; broken files may place frames partly outside of it.
        let x = (frame.left as usize).min(cw);
        let y = (frame.top as usize).min(ch);
        let w = (frame.width as usize).min(cw - x);
        let h = (frame.height as usize).min(ch - y);
        let src_stride = frame.width as usize * 4;
        if frame.buffer.len() < src_stride * frame.height as usize {
            return Err(Error::new("GIF: short frame data"));
        }
        let placed = Placed { x, y, w, h };
        // 1. Dispose of the previous frame.
        if let Some((p, disposal, saved)) = stream.prev.take() {
            match disposal {
                DisposalMethod::Background => {
                    for r in 0..p.h {
                        let s = ((p.y + r) * cw + p.x) * 4;
                        self.canvas[s..s + p.w * 4].fill(0);
                    }
                }
                DisposalMethod::Previous => {
                    for r in 0..p.h {
                        let s = ((p.y + r) * cw + p.x) * 4;
                        self.canvas[s..s + p.w * 4]
                            .copy_from_slice(&saved[r * p.w * 4..(r + 1) * p.w * 4]);
                    }
                }
                DisposalMethod::Any | DisposalMethod::Keep => {}
            }
        }
        // 2. Remember what a "restore to previous" has to bring back.
        let mut saved = Vec::new();
        if frame.dispose == DisposalMethod::Previous {
            reserve_vec(&mut saved, w * h * 4)?;
            for r in 0..h {
                let s = ((y + r) * cw + x) * 4;
                saved.extend_from_slice(&self.canvas[s..s + w * 4]);
            }
        }
        // 3. Draw; transparent pixels leave the canvas untouched.
        for r in 0..h {
            let src = &frame.buffer[r * src_stride..r * src_stride + w * 4];
            let s = ((y + r) * cw + x) * 4;
            for (sp, dp) in src
                .chunks_exact(4)
                .zip(self.canvas[s..s + w * 4].chunks_exact_mut(4))
            {
                if sp[3] != 0 {
                    dp.copy_from_slice(sp);
                }
            }
        }
        stream.prev = Some((placed, frame.dispose, saved));
        self.rendered = Some(stream.next);
        stream.next += 1;
        Ok(())
    }
}

impl Decoder for GifDecoder {
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
                // Going backwards: restart the stream (playback loops and seeking hit this).
                self.stream = None;
            }
            loop {
                self.advance()?;
                if self.rendered == Some(frame) {
                    break;
                }
            }
        }
        emit_canvas(&self.canvas, self.info.width, rect, sample, sink)
    }
}
