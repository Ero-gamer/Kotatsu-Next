//! Estimates the encoder quality an image file was saved with, from its headers only.
//!
//! - JPEG: the DQT quantisation table is matched against the IJG (libjpeg) tables for quality 1..=100.
//!   Exact for libjpeg-style encoders, the nearest match otherwise.
//! - Lossy WebP: the VP8 frame header carries the quantiser index, which is mapped back through
//!   libwebp's quality curve. An estimate, good to a few points.
//! - PNG / GIF / BMP and lossless WebP are lossless.

use std::io::{Read, Seek, SeekFrom};

/// Result of [`estimate`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SourceQuality {
    /// Lossy source with an estimated quality 1..=100.
    Lossy(u8),
    Lossless,
    Unknown,
}

impl SourceQuality {
    /// Encoding for the JNI boundary: 1..=100 lossy, 101 lossless, -1 unknown.
    pub fn to_code(self) -> i32 {
        match self {
            SourceQuality::Lossy(q) => i32::from(q),
            SourceQuality::Lossless => 101,
            SourceQuality::Unknown => -1,
        }
    }
}

/// Standard IJG luminance table, natural (row-major) order.
const STD_LUMA: [i32; 64] = [
    16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113,
    92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
];

/// Zig-zag position -> natural index.
const ZIGZAG: [usize; 64] = [
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20,
    13, 6, 7, 14, 21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59,
    52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
];

/// Reads the head of `src` and estimates its source quality.
pub fn estimate<R: Read + Seek>(src: &mut R) -> SourceQuality {
    let mut magic = [0u8; 12];
    if src.seek(SeekFrom::Start(0)).is_err() || read_full(src, &mut magic) < 12 {
        return SourceQuality::Unknown;
    }
    if magic[0] == 0xFF && magic[1] == 0xD8 {
        return jpeg_quality(src).map_or(SourceQuality::Unknown, SourceQuality::Lossy);
    }
    if &magic[0..4] == b"RIFF" && &magic[8..12] == b"WEBP" {
        return webp_quality(src);
    }
    if magic.starts_with(&[0x89, b'P', b'N', b'G'])
        || magic.starts_with(b"GIF8")
        || magic.starts_with(b"BM")
    {
        return SourceQuality::Lossless;
    }
    SourceQuality::Unknown
}

fn read_full<R: Read>(src: &mut R, buf: &mut [u8]) -> usize {
    let mut n = 0;
    while n < buf.len() {
        match src.read(&mut buf[n..]) {
            Ok(0) | Err(_) => break,
            Ok(k) => n += k,
        }
    }
    n
}

// ── JPEG ─────────────────────────────────────────────────────────────────────

fn jpeg_quality<R: Read + Seek>(src: &mut R) -> Option<u8> {
    src.seek(SeekFrom::Start(2)).ok()?;
    let mut table: Option<[i32; 64]> = None;
    // Bounded walk: a sane JPEG has its DQT within a few dozen segments.
    for _ in 0..256 {
        let mut b = [0u8; 1];
        // Find the next marker prefix, skipping fill bytes.
        loop {
            if read_full(src, &mut b) != 1 {
                return table.and_then(best_quality);
            }
            if b[0] == 0xFF {
                break;
            }
        }
        let mut marker = 0xFF;
        while marker == 0xFF {
            if read_full(src, &mut b) != 1 {
                return table.and_then(best_quality);
            }
            marker = b[0];
        }
        match marker {
            0x00 | 0x01 | 0xD0..=0xD8 => continue,
            0xD9 | 0xDA => break,
            _ => {}
        }
        let mut len = [0u8; 2];
        if read_full(src, &mut len) != 2 {
            break;
        }
        let len = usize::from(u16::from_be_bytes(len));
        if len < 2 {
            break;
        }
        if marker == 0xDB {
            let mut data = vec![0u8; len - 2];
            if read_full(src, &mut data) != data.len() {
                break;
            }
            if let Some(t) = parse_dqt(&data) {
                // Table 0 is luminance; keep the first one found, but let table id 0 win.
                if table.is_none() || t.0 == 0 {
                    table = Some(t.1);
                }
            }
        } else if src.seek(SeekFrom::Current((len - 2) as i64)).is_err() {
            break;
        }
    }
    table.and_then(best_quality)
}

/// Returns `(table_id, values in zig-zag order)` of the first 8-bit table in a DQT segment.
fn parse_dqt(mut data: &[u8]) -> Option<(u8, [i32; 64])> {
    let mut found: Option<(u8, [i32; 64])> = None;
    while !data.is_empty() {
        let info = data[0];
        let precision16 = info >> 4 != 0;
        let id = info & 0x0F;
        let size = if precision16 { 128 } else { 64 };
        if data.len() < 1 + size {
            break;
        }
        if !precision16 {
            let mut t = [0i32; 64];
            for (dst, src) in t.iter_mut().zip(&data[1..65]) {
                *dst = i32::from(*src);
            }
            if found.is_none() || id == 0 {
                found = Some((id, t));
            }
        }
        data = &data[1 + size..];
    }
    found
}

/// IJG quality (1..=100) whose scaled standard table is closest to `table` (zig-zag order), or `None`
/// when no IJG table is close: mozjpeg, Photoshop and ImageMagick use other tables, and "nearest" would
/// then be off by 10-30 points and make the policy re-encode at the wrong target.
fn best_quality(table: [i32; 64]) -> Option<u8> {
    let mut best = (i32::MAX, 100u8);
    for q in 1..=100i32 {
        let scale = if q < 50 { 5000 / q } else { 200 - q * 2 };
        let mut dist = 0;
        for (zz, value) in table.iter().enumerate() {
            let std = STD_LUMA[ZIGZAG[zz]];
            let expected = ((std * scale + 50) / 100).clamp(1, 255);
            dist += (expected - value).abs();
        }
        // `<=`: on ties prefer the higher quality.
        if dist <= best.0 {
            best = (dist, q as u8);
        }
    }
    // Real libjpeg tables match exactly; a mean difference above 2 per coefficient is another family.
    (best.0 <= MAX_TABLE_DISTANCE).then_some(best.1)
}

/// Largest summed |expected - actual| over the 64 coefficients still accepted as an IJG table.
const MAX_TABLE_DISTANCE: i32 = 64 * 2;

// ── WebP ─────────────────────────────────────────────────────────────────────

fn webp_quality<R: Read + Seek>(src: &mut R) -> SourceQuality {
    let mut pos: u64 = 12;
    for _ in 0..64 {
        if src.seek(SeekFrom::Start(pos)).is_err() {
            break;
        }
        let mut hdr = [0u8; 8];
        if read_full(src, &mut hdr) != 8 {
            break;
        }
        let size = u64::from(u32::from_le_bytes([hdr[4], hdr[5], hdr[6], hdr[7]]));
        match &hdr[0..4] {
            b"VP8L" => return SourceQuality::Lossless,
            b"VP8 " => {
                let mut data = vec![0u8; size.min(512) as usize];
                let n = read_full(src, &mut data);
                data.truncate(n);
                return vp8_quality(&data).map_or(SourceQuality::Unknown, SourceQuality::Lossy);
            }
            b"VP8X" => {
                let mut flags = [0u8; 1];
                if read_full(src, &mut flags) == 1 && flags[0] & 0x02 != 0 {
                    return SourceQuality::Unknown; // animated
                }
            }
            b"ANIM" | b"ANMF" => return SourceQuality::Unknown,
            _ => {}
        }
        pos += 8 + size + (size & 1);
    }
    SourceQuality::Unknown
}

/// RFC 6386 boolean entropy decoder (just enough for the frame header).
struct BoolDecoder<'a> {
    data: &'a [u8],
    pos: usize,
    value: u32,
    range: u32,
    bit_count: u32,
}

impl<'a> BoolDecoder<'a> {
    fn new(data: &'a [u8]) -> Self {
        let b0 = u32::from(*data.first().unwrap_or(&0));
        let b1 = u32::from(*data.get(1).unwrap_or(&0));
        BoolDecoder {
            data,
            pos: 2,
            value: (b0 << 8) | b1,
            range: 255,
            bit_count: 0,
        }
    }

    fn read_bool(&mut self, prob: u32) -> bool {
        let split = 1 + (((self.range - 1) * prob) >> 8);
        let big_split = split << 8;
        let bit = if self.value >= big_split {
            self.range -= split;
            self.value -= big_split;
            true
        } else {
            self.range = split;
            false
        };
        while self.range < 128 {
            self.value <<= 1;
            self.range <<= 1;
            self.bit_count += 1;
            if self.bit_count == 8 {
                self.bit_count = 0;
                self.value |= u32::from(*self.data.get(self.pos).unwrap_or(&0));
                self.pos += 1;
            }
        }
        bit
    }

    fn flag(&mut self) -> bool {
        self.read_bool(128)
    }

    fn literal(&mut self, bits: u32) -> u32 {
        (0..bits).fold(0, |acc, _| (acc << 1) | u32::from(self.flag()))
    }

    /// `bits`-bit magnitude followed by a sign bit.
    fn signed(&mut self, bits: u32) -> i32 {
        let v = self.literal(bits) as i32;
        if self.flag() {
            -v
        } else {
            v
        }
    }

    /// Optional signed value: flag, then magnitude + sign when set (0 otherwise).
    fn opt_signed(&mut self, bits: u32) -> i32 {
        if self.flag() {
            self.signed(bits)
        } else {
            0
        }
    }
}

/// Estimated quality of a `VP8 ` (lossy) chunk payload.
fn vp8_quality(payload: &[u8]) -> Option<u8> {
    // 3-byte frame tag, 3-byte start code, 4 bytes of dimensions.
    if payload.len() < 12 || payload[0] & 1 != 0 || payload[3..6] != [0x9D, 0x01, 0x2A] {
        return None;
    }
    let mut d = BoolDecoder::new(&payload[10..]);
    d.flag(); // color space
    d.flag(); // clamping type
    let mut seg_quant: Option<(bool, [i32; 4])> = None;
    if d.flag() {
        // segmentation_enabled
        let update_map = d.flag();
        let update_data = d.flag();
        if update_data {
            let absolute = d.flag();
            let mut q = [0i32; 4];
            for v in q.iter_mut() {
                *v = d.opt_signed(7);
            }
            for _ in 0..4 {
                d.opt_signed(6); // loop filter strengths
            }
            seg_quant = Some((absolute, q));
        }
        if update_map {
            for _ in 0..3 {
                if d.flag() {
                    d.literal(8);
                }
            }
        }
    }
    d.flag(); // filter type
    d.literal(6); // loop filter level
    d.literal(3); // sharpness
    if d.flag() {
        // loop_filter_adj_enable
        if d.flag() {
            for _ in 0..8 {
                d.opt_signed(6);
            }
        }
    }
    d.literal(2); // partitions
    let y_ac_qi = d.literal(7) as i32;
    let qi = match seg_quant {
        Some((true, q)) => q.iter().sum::<i32>() / 4,
        Some((false, q)) => y_ac_qi + q.iter().sum::<i32>() / 4,
        None => y_ac_qi,
    }
    .clamp(0, 127);
    Some(quant_index_to_quality(qi))
}

/// Inverse of libwebp's `QualityToCompression` + `q = 127 * (1 - c)`.
fn quant_index_to_quality(qi: i32) -> u8 {
    let c = 1.0 - f64::from(qi) / 127.0;
    let linear = c * c * c;
    let quality = if linear < 0.5 {
        linear * 1.5
    } else {
        (linear + 1.0) / 2.0
    };
    ((quality * 100.0).round() as i32).clamp(1, 100) as u8
}

#[cfg(test)]
mod table_tests {
    use super::*;

    fn scaled(q: i32) -> [i32; 64] {
        let scale = if q < 50 { 5000 / q } else { 200 - q * 2 };
        let mut t = [0i32; 64];
        for (zz, v) in t.iter_mut().enumerate() {
            *v = ((STD_LUMA[ZIGZAG[zz]] * scale + 50) / 100).clamp(1, 255);
        }
        t
    }

    #[test]
    fn ijg_tables_are_matched_exactly() {
        for q in [10, 50, 75, 90, 100] {
            assert_eq!(best_quality(scaled(q)), Some(q as u8));
        }
    }

    #[test]
    fn foreign_tables_are_unknown() {
        // Flat table: no IJG quality looks like it
        assert_eq!(best_quality([40; 64]), None);
    }
}
