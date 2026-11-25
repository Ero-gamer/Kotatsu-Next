//! Still-PNG region decoder: regions, sampling, stream continuation, band cache, color types.

use ssivcodecs::common::sampled_len;
use ssivcodecs::{Decoder, PixelFormat, Rect, Sink};
use std::fs::File;
use std::io::BufWriter;
use std::os::fd::AsRawFd;
use std::path::PathBuf;

fn temp(name: &str) -> PathBuf {
    let mut p = std::env::temp_dir();
    p.push(format!("ssiv_pngstill_{}_{}.png", std::process::id(), name));
    p
}

/// Writes a deterministic test image and returns the premultiplied RGBA reference (8-bit).
fn make_png(
    name: &str,
    w: u32,
    h: u32,
    color: png::ColorType,
    depth: png::BitDepth,
    interlaced: bool,
) -> (PathBuf, Vec<u8>) {
    let path = temp(name);
    let ch = color.samples();
    let bytes = if depth == png::BitDepth::Sixteen {
        2
    } else {
        1
    };
    let mut raw = vec![0u8; w as usize * h as usize * ch * bytes];
    let mut seed = 7u32;
    for (i, b) in raw.iter_mut().enumerate() {
        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
        // Smooth-ish with noise so filters get exercised.
        *b = ((i as u32 / 3).wrapping_mul(5) as u8).wrapping_add((seed >> 24) as u8 & 15);
    }
    let file = BufWriter::new(File::create(&path).unwrap());
    let mut enc = png::Encoder::new(file, w, h);
    enc.set_color(color);
    enc.set_depth(depth);
    if color == png::ColorType::Indexed {
        let palette: Vec<u8> = (0..256u32 * 3).map(|i| (i * 7) as u8).collect();
        enc.set_palette(palette);
    }
    let mut wr = enc.write_header().unwrap();
    wr.write_image_data(&raw).unwrap();
    wr.finish().unwrap();
    if interlaced {
        // The encoder cannot write Adam7: flip the IHDR flag and fix the chunk CRC.
        let mut data = std::fs::read(&path).unwrap();
        data[28] = 1;
        let crc = crc32(&data[12..29]);
        data[29..33].copy_from_slice(&crc.to_be_bytes());
        std::fs::write(&path, data).unwrap();
        return (path, Vec::new());
    }
    let reference = reference_rgba(&path);
    (path, reference)
}

fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for b in bytes {
        crc ^= *b as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                0xEDB8_8320 ^ (crc >> 1)
            } else {
                crc >> 1
            };
        }
    }
    !crc
}

fn reference_rgba(path: &PathBuf) -> Vec<u8> {
    let mut dec = png::Decoder::new(std::io::BufReader::new(File::open(path).unwrap()));
    dec.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut r = dec.read_info().unwrap();
    let mut buf = vec![0u8; r.output_buffer_size().unwrap()];
    let o = r.next_frame(&mut buf).unwrap();
    let ch = o.color_type.samples();
    let mut out = Vec::new();
    for p in buf[..o.buffer_size()].chunks_exact(ch) {
        let (rgb, a) = match o.color_type {
            png::ColorType::Grayscale => ([p[0]; 3], 255u8),
            png::ColorType::GrayscaleAlpha => ([p[0]; 3], p[1]),
            png::ColorType::Rgb => ([p[0], p[1], p[2]], 255),
            _ => ([p[0], p[1], p[2]], p[3]),
        };
        let pm = |c: u8| ((c as u32 * a as u32 + 127) / 255) as u8;
        out.extend_from_slice(&[pm(rgb[0]), pm(rgb[1]), pm(rgb[2]), a]);
    }
    out
}

fn open(path: &PathBuf) -> ssivcodecs::Result<Box<dyn Decoder>> {
    let file = File::open(path).unwrap();
    let len = file.metadata().unwrap().len();
    ssivcodecs::jni_api::open(file.as_raw_fd(), 0, len)
}

fn decode(dec: &mut dyn Decoder, rect: Rect, sample: u32) -> Vec<u8> {
    let (w, h) = (
        sampled_len(rect.width, sample),
        sampled_len(rect.height, sample),
    );
    let mut buf = vec![0u8; w as usize * h as usize * 4];
    let mut sink = Sink::new(&mut buf, w as usize * 4, PixelFormat::Rgba8888, w, h).unwrap();
    dec.decode(0, rect, sample, &mut sink).unwrap();
    buf
}

fn box_ref(full: &[u8], fw: u32, r: Rect, s: u32) -> Vec<u8> {
    let (ow, oh) = (sampled_len(r.width, s), sampled_len(r.height, s));
    let mut out = vec![0u8; ow as usize * oh as usize * 4];
    for oy in 0..oh {
        for ox in 0..ow {
            for c in 0..4usize {
                let (mut sum, mut n) = (0u32, 0u32);
                for y in (oy * s)..((oy + 1) * s).min(r.height) {
                    for x in (ox * s)..((ox + 1) * s).min(r.width) {
                        let i = ((r.top + y) as usize * fw as usize + (r.left + x) as usize) * 4;
                        sum += full[i + c] as u32;
                        n += 1;
                    }
                }
                out[(oy as usize * ow as usize + ox as usize) * 4 + c] = ((sum + n / 2) / n) as u8;
            }
        }
    }
    out
}

fn max_diff(a: &[u8], b: &[u8]) -> u32 {
    assert_eq!(a.len(), b.len());
    a.iter()
        .zip(b)
        .map(|(x, y)| (*x as i32 - *y as i32).unsigned_abs())
        .max()
        .unwrap_or(0)
}

#[test]
fn all_color_types_decode_whole_image() {
    use png::{BitDepth::*, ColorType::*};
    for (name, ct, depth) in [
        ("gray8", Grayscale, Eight),
        ("gray16", Grayscale, Sixteen),
        ("ga8", GrayscaleAlpha, Eight),
        ("rgb8", Rgb, Eight),
        ("rgb16", Rgb, Sixteen),
        ("rgba8", Rgba, Eight),
        ("rgba16", Rgba, Sixteen),
        ("idx8", Indexed, Eight),
    ] {
        let (path, reference) = make_png(name, 37, 23, ct, depth, false);
        let mut dec = open(&path).unwrap();
        assert_eq!(dec.info().format as i32, 4, "{name}");
        assert_eq!((dec.info().width, dec.info().height), (37, 23));
        let got = decode(dec.as_mut(), Rect::new(0, 0, 37, 23), 1);
        assert!(max_diff(&got, &reference) <= 1, "{name}");
        std::fs::remove_file(path).ok();
    }
}

#[test]
fn regions_and_samples_match_reference_in_any_order() {
    let (w, h) = (130u32, 300u32);
    let (path, reference) = make_png(
        "regions",
        w,
        h,
        png::ColorType::Rgba,
        png::BitDepth::Eight,
        false,
    );
    let mut dec = open(&path).unwrap();
    let mut seed = 99u32;
    let mut next = |m: u32| {
        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
        (seed >> 8) % m.max(1)
    };
    for i in 0..60 {
        let (l, t) = (next(w - 1), next(h - 1));
        let r = Rect::new(l, t, 1 + next(w - l - 1), 1 + next(h - t - 1));
        let s = [1u32, 2, 3, 4, 8][(i % 5) as usize];
        let got = decode(dec.as_mut(), r, s);
        let want = box_ref(&reference, w, r, s);
        assert!(max_diff(&got, &want) <= 1, "region {r:?} sample {s}");
    }
    std::fs::remove_file(path).ok();
}

#[test]
fn tile_grid_scan_uses_bands_and_stays_exact() {
    let (w, h) = (256u32, 512u32);
    let (path, reference) = make_png(
        "grid",
        w,
        h,
        png::ColorType::Rgb,
        png::BitDepth::Eight,
        false,
    );
    let mut dec = open(&path).unwrap();
    for s in [1u32, 2, 4] {
        // Top to bottom, then bottom to top, tiles of 64 x 64 (aligned) incl. repeated tiles.
        let mut order: Vec<(u32, u32)> = Vec::new();
        for ty in 0..h / 64 {
            for tx in 0..w / 64 {
                order.push((tx * 64, ty * 64));
            }
        }
        let rev: Vec<_> = order.iter().rev().cloned().collect();
        for (x, y) in order.iter().chain(rev.iter()).chain(order.iter().take(5)) {
            let r = Rect::new(*x, *y, 64, 64);
            let got = decode(dec.as_mut(), r, s);
            assert!(
                max_diff(&got, &box_ref(&reference, w, r, s)) <= 1,
                "tile {r:?} s{s}"
            );
        }
    }
    std::fs::remove_file(path).ok();
}

#[test]
fn edge_tiles_with_partial_blocks() {
    let (w, h) = (101u32, 77u32);
    let (path, reference) = make_png(
        "edge",
        w,
        h,
        png::ColorType::GrayscaleAlpha,
        png::BitDepth::Eight,
        false,
    );
    let mut dec = open(&path).unwrap();
    for s in [2u32, 4, 8] {
        let r = Rect::new(0, 0, w, h);
        assert!(max_diff(&decode(dec.as_mut(), r, s), &box_ref(&reference, w, r, s)) <= 1);
        let r = Rect::new(64, 64, w - 64, h - 64);
        assert!(max_diff(&decode(dec.as_mut(), r, s), &box_ref(&reference, w, r, s)) <= 1);
    }
    std::fs::remove_file(path).ok();
}

#[test]
fn rgb565_output_is_produced() {
    let (path, _) = make_png(
        "565",
        40,
        40,
        png::ColorType::Rgb,
        png::BitDepth::Eight,
        false,
    );
    let mut dec = open(&path).unwrap();
    let mut buf = vec![0u8; 20 * 20 * 2];
    let mut sink = Sink::new(&mut buf, 40, PixelFormat::Rgb565, 20, 20).unwrap();
    dec.decode(0, Rect::new(0, 0, 40, 40), 2, &mut sink)
        .unwrap();
    assert!(buf.iter().any(|b| *b != 0));
    std::fs::remove_file(path).ok();
}

#[test]
fn interlaced_and_garbage_are_rejected() {
    let (path, _) = make_png(
        "adam7",
        20,
        20,
        png::ColorType::Rgb,
        png::BitDepth::Eight,
        true,
    );
    assert!(
        open(&path).is_err(),
        "interlaced must fall back to the system decoder"
    );
    std::fs::remove_file(&path).ok();
    let bad = temp("bad");
    std::fs::write(
        &bad,
        [
            0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, b'I', b'H', b'D', b'R', 1,
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 0, 0, 0, 0,
        ],
    )
    .unwrap();
    assert!(open(&bad).is_err());
    std::fs::remove_file(bad).ok();
}

#[test]
fn truncated_file_fails_cleanly_then_recovers_nothing_panics() {
    let (path, _) = make_png(
        "trunc",
        64,
        200,
        png::ColorType::Rgb,
        png::BitDepth::Eight,
        false,
    );
    let data = std::fs::read(&path).unwrap();
    std::fs::write(&path, &data[..data.len() / 2]).unwrap();
    if let Ok(mut dec) = open(&path) {
        let (w, h) = (64u32, 200u32);
        let mut buf = vec![0u8; w as usize * h as usize * 4];
        let mut sink = Sink::new(&mut buf, w as usize * 4, PixelFormat::Rgba8888, w, h).unwrap();
        assert!(dec.decode(0, Rect::new(0, 0, w, h), 1, &mut sink).is_err());
        // An early region still works after the failed stream restarted.
        let top = decode(dec.as_mut(), Rect::new(0, 0, 64, 10), 1);
        assert_eq!(top.len(), 64 * 10 * 4);
    }
    std::fs::remove_file(path).ok();
}

#[test]
fn out_of_range_region_is_an_error() {
    let (path, _) = make_png(
        "oob",
        30,
        30,
        png::ColorType::Rgb,
        png::BitDepth::Eight,
        false,
    );
    let mut dec = open(&path).unwrap();
    let mut buf = vec![0u8; 10 * 10 * 4];
    let mut sink = Sink::new(&mut buf, 40, PixelFormat::Rgba8888, 10, 10).unwrap();
    assert!(dec
        .decode(0, Rect::new(25, 25, 10, 10), 1, &mut sink)
        .is_err());
    std::fs::remove_file(path).ok();
}
