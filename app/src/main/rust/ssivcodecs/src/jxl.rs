//! JPEG XL support on top of `jxl-oxide` (pure Rust).
//!
//! Regions are rendered through jxl-oxide's region-of-interest support, so only the groups that
//! cover the requested rectangle are decoded. Large regions are rendered in horizontal bands to
//! bound the peak memory of the f32 render buffers.

use crate::common::{
    check_limits, Decoder, Error, Format, Info, Rect, Resampler, Result, Sink,
    MAX_FRAME_DURATION_MS,
};
use jxl_oxide::color::{ColourEncoding, EnumColourEncoding, RenderingIntent};
use jxl_oxide::{CropInfo, JxlImage, JxlThreadPool, PixelFormat};
use std::io::Read;
use std::sync::OnceLock;

/// Upper bound for the pixels rendered in a single band.
const MAX_BAND_PIXELS: u32 = 1 << 20;
/// JPEG XL groups are 256 px (at most), band edges are aligned to avoid decoding a group twice.
const GROUP_SIZE: u32 = 256;

fn pool() -> JxlThreadPool {
    static POOL: OnceLock<JxlThreadPool> = OnceLock::new();
    POOL.get_or_init(|| {
        let cpus = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(1);
        JxlThreadPool::rayon(Some(cpus.clamp(1, 2)))
    })
    .clone()
}

pub struct JxlDecoder {
    image: JxlImage,
    info: Info,
    durations_ms: Vec<u32>,
    alpha_associated: bool,
    /// Output was switched to sRGB; undone if the color transform cannot be built.
    srgb_requested: bool,
}

pub fn is_jxl(data: &[u8]) -> bool {
    // Bare codestream or ISO BMFF container.
    data.starts_with(&[0xFF, 0x0A])
        || data.starts_with(&[
            0, 0, 0, 0x0C, b'J', b'X', b'L', b' ', 0x0D, 0x0A, 0x87, 0x0A,
        ])
}

impl JxlDecoder {
    pub fn from_reader(reader: impl Read) -> Result<Self> {
        let image = JxlImage::builder()
            .pool(pool())
            .read(reader)
            .map_err(|e| Error::new(format!("JPEG XL: {e}")))?;
        Self::from_image(image)
    }

    fn from_image(mut image: JxlImage) -> Result<Self> {
        let width = image.width();
        let height = image.height();
        // Android treats pixels as sRGB, so wide-gamut / HDR / linear files are converted.
        let srgb_requested = request_srgb(&mut image);
        let format = image.pixel_format();
        if matches!(format, PixelFormat::Cmyk | PixelFormat::Cmyka) {
            return Err(Error::new("JPEG XL: CMYK images are not supported"));
        }
        let frame_count = image.num_loaded_keyframes() as u32;
        if frame_count == 0 {
            return Err(Error::new("JPEG XL: image has no frames"));
        }
        check_limits(width, height, frame_count)
            .map_err(|e| Error::new(format!("JPEG XL: {e}")))?;
        let header = image.image_header();
        let (tps_num, tps_den, loops) = match &header.metadata.animation {
            Some(a) => (
                a.tps_numerator.max(1) as u64,
                a.tps_denominator.max(1) as u64,
                a.num_loops,
            ),
            None => (1, 1, 0),
        };
        let durations_ms = (0..frame_count as usize)
            .map(|i| {
                let ticks = image.frame_header(i).map(|h| h.duration).unwrap_or(0) as u64;
                (ticks * 1000 * tps_den / tps_num).min(MAX_FRAME_DURATION_MS as u64) as u32
            })
            .collect();
        let alpha_associated = header
            .metadata
            .ec_info
            .iter()
            .find_map(|ec| ec.alpha_associated())
            .unwrap_or(false);
        Ok(JxlDecoder {
            info: Info {
                format: Format::Jxl,
                width,
                height,
                frame_count,
                has_alpha: format.has_alpha(),
                loop_count: loops,
            },
            image,
            durations_ms,
            alpha_associated,
            srgb_requested,
        })
    }

    fn render_band(
        &mut self,
        frame: u32,
        band: Rect,
        resampler: &mut Resampler<'_, '_>,
    ) -> Result<()> {
        self.image.set_image_region(CropInfo {
            left: band.left,
            top: band.top,
            width: band.width,
            height: band.height,
        });
        let render = match self.image.render_frame(frame as usize) {
            Ok(render) => render,
            Err(_) if self.srgb_requested => {
                // The sRGB conversion failed (exotic profile): show the file's own colors instead.
                self.srgb_requested = false;
                revert_color(&mut self.image);
                self.image
                    .render_frame(frame as usize)
                    .map_err(|e| Error::new(format!("JPEG XL: {e}")))?
            }
            Err(e) => return Err(Error::new(format!("JPEG XL: {e}"))),
        };
        let mut stream = render.stream();
        let channels = stream.channels() as usize;
        let (w, h) = (stream.width(), stream.height());
        if w != band.width || h != band.height || !(1..=4).contains(&channels) {
            return Err(Error::new("JPEG XL: unexpected render size"));
        }
        let mut samples = vec![0u8; w as usize * h as usize * channels];
        let written = stream.write_to_buffer::<u8>(&mut samples);
        if written != samples.len() {
            return Err(Error::new("JPEG XL: short render"));
        }
        let mut row = vec![0u8; w as usize * 4];
        for src in samples.chunks_exact(w as usize * channels) {
            expand_row(src, channels, self.alpha_associated, &mut row);
            resampler.push_row(&row);
        }
        Ok(())
    }
}

const SRGB_CICP: [u8; 4] = [1, 13, 0, 1];

/// Switches the output to (gray) sRGB unless it already is. Returns whether it was switched.
fn request_srgb(image: &mut JxlImage) -> bool {
    if image.rendered_cicp() == Some(SRGB_CICP) {
        return false;
    }
    let encoding = match image.pixel_format() {
        PixelFormat::Gray | PixelFormat::Graya => {
            EnumColourEncoding::gray_srgb(RenderingIntent::Relative)
        }
        PixelFormat::Rgb | PixelFormat::Rgba => EnumColourEncoding::srgb(RenderingIntent::Relative),
        _ => return false,
    };
    image.request_color_encoding(encoding);
    true
}

/// Restores the file's own color encoding after [`request_srgb`].
fn revert_color(image: &mut JxlImage) {
    match image.image_header().metadata.colour_encoding.clone() {
        ColourEncoding::Enum(encoding) => image.request_color_encoding(encoding),
        ColourEncoding::IccProfile(_) => {
            if let Some(icc) = image.original_icc().map(<[u8]>::to_vec) {
                let _ = image.request_icc(&icc);
            }
        }
    }
}

/// Converts one row of 1..=4 interleaved channels to premultiplied RGBA.
fn expand_row(src: &[u8], channels: usize, already_premultiplied: bool, out: &mut [u8]) {
    match channels {
        1 => {
            for (s, o) in src.iter().zip(out.chunks_exact_mut(4)) {
                o.copy_from_slice(&[*s, *s, *s, 255]);
            }
        }
        2 => {
            for (s, o) in src.chunks_exact(2).zip(out.chunks_exact_mut(4)) {
                let a = s[1];
                let g = if already_premultiplied {
                    s[0]
                } else {
                    mul255(s[0], a)
                };
                o.copy_from_slice(&[g, g, g, a]);
            }
        }
        3 => {
            for (s, o) in src.chunks_exact(3).zip(out.chunks_exact_mut(4)) {
                o.copy_from_slice(&[s[0], s[1], s[2], 255]);
            }
        }
        _ => {
            for (s, o) in src.chunks_exact(4).zip(out.chunks_exact_mut(4)) {
                let a = s[3];
                if a == 255 || already_premultiplied {
                    o.copy_from_slice(s);
                } else {
                    o.copy_from_slice(&[mul255(s[0], a), mul255(s[1], a), mul255(s[2], a), a]);
                }
            }
        }
    }
}

#[inline]
fn mul255(c: u8, a: u8) -> u8 {
    let v = c as u32 * a as u32 + 128;
    ((v + (v >> 8)) >> 8) as u8
}

impl Decoder for JxlDecoder {
    fn info(&self) -> &Info {
        &self.info
    }

    fn frame_duration_ms(&self, frame: u32) -> u32 {
        self.durations_ms.get(frame as usize).copied().unwrap_or(0)
    }

    fn decode(&mut self, frame: u32, rect: Rect, sample: u32, sink: &mut Sink<'_>) -> Result<()> {
        if frame >= self.info.frame_count {
            return Err(Error::new("frame index out of range"));
        }
        let rect = rect
            .clamped(self.info.width, self.info.height)
            .ok_or_else(|| Error::new("region is outside of the image"))?;
        let mut resampler = Resampler::new(sink, rect.width, rect.height, sample)?;
        let rows_budget = (MAX_BAND_PIXELS / rect.width).max(GROUP_SIZE);
        let mut y = rect.top;
        while y < rect.bottom() {
            let aligned_end = (y + rows_budget) / GROUP_SIZE * GROUP_SIZE;
            let end = aligned_end
                .max((y / GROUP_SIZE + 1) * GROUP_SIZE)
                .min(rect.bottom());
            self.render_band(
                frame,
                Rect::new(rect.left, y, rect.width, end - y),
                &mut resampler,
            )?;
            y = end;
        }
        debug_assert_eq!(resampler.remaining_rows(), 0);
        Ok(())
    }
}
