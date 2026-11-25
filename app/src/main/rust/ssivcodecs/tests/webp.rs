//! Host tests for the libwebp encoder wrapper and the source-quality estimators.

use ssivcodecs::quality::{estimate, SourceQuality};
use ssivcodecs::webp::{decode_rgba, encode_rgba, WebpParams};
use std::io::Cursor;

/// Deterministic photo-ish test pattern (gradients + pseudo-noise).
fn pattern(w: u32, h: u32, alpha: bool) -> Vec<u8> {
    let mut state = 0x1234_5678u32;
    let mut out = Vec::with_capacity((w * h * 4) as usize);
    for y in 0..h {
        for x in 0..w {
            state = state.wrapping_mul(1_664_525).wrapping_add(1_013_904_223);
            let noise = (state >> 24) % 24;
            out.push(((x * 255 / w) + noise).min(255) as u8);
            out.push(((y * 255 / h) + noise).min(255) as u8);
            out.push((((x + y) * 255 / (w + h)) + noise).min(255) as u8);
            out.push(if alpha {
                ((x * 255 / w) as u8).max(1)
            } else {
                255
            });
        }
    }
    out
}

#[test]
fn lossless_roundtrip_is_pixel_exact() {
    let (w, h) = (64u32, 80u32);
    let rgba = pattern(w, h, true);
    let data = encode_rgba(
        &rgba,
        w,
        h,
        (w * 4) as usize,
        true,
        &WebpParams::lossless(100, true),
    )
    .unwrap();
    assert_eq!(&data[0..4], b"RIFF");
    let (dw, dh, px) = decode_rgba(&data).unwrap();
    assert_eq!((dw, dh), (w, h));
    assert_eq!(px, rgba);
}

#[test]
fn lossless_opaque_roundtrip_is_exact() {
    let (w, h) = (50u32, 33u32);
    let rgba = pattern(w, h, false);
    let data = encode_rgba(
        &rgba,
        w,
        h,
        (w * 4) as usize,
        false,
        &WebpParams::lossless(100, false),
    )
    .unwrap();
    let (_, _, px) = decode_rgba(&data).unwrap();
    assert_eq!(px, rgba);
}

#[test]
fn lossy_roundtrip_is_close_and_keeps_alpha() {
    let (w, h) = (64u32, 64u32);
    let rgba = pattern(w, h, true);
    let data = encode_rgba(
        &rgba,
        w,
        h,
        (w * 4) as usize,
        true,
        &WebpParams::lossy(95.0),
    )
    .unwrap();
    let (dw, dh, px) = decode_rgba(&data).unwrap();
    assert_eq!((dw, dh), (w, h));
    let mut worst_alpha = 0i32;
    let mut sum = 0u64;
    for (a, b) in rgba.chunks_exact(4).zip(px.chunks_exact(4)) {
        worst_alpha = worst_alpha.max((i32::from(a[3]) - i32::from(b[3])).abs());
        for c in 0..3 {
            sum += (i32::from(a[c]) - i32::from(b[c])).unsigned_abs() as u64;
        }
    }
    assert_eq!(worst_alpha, 0, "alpha is stored losslessly");
    let mean = sum as f64 / f64::from(w * h * 3);
    assert!(mean < 6.0, "mean color error {mean}");
}

#[test]
fn stride_larger_than_row_is_supported() {
    let (w, h) = (20u32, 30u32);
    let tight = pattern(w, h, false);
    let stride = (w * 4 + 16) as usize;
    let mut padded = vec![0xAAu8; stride * h as usize];
    for y in 0..h as usize {
        padded[y * stride..y * stride + (w * 4) as usize]
            .copy_from_slice(&tight[y * (w * 4) as usize..(y + 1) * (w * 4) as usize]);
    }
    let data = encode_rgba(
        &padded,
        w,
        h,
        stride,
        false,
        &WebpParams::lossless(100, false),
    )
    .unwrap();
    let (_, _, px) = decode_rgba(&data).unwrap();
    assert_eq!(px, tight);
}

#[test]
fn bad_input_is_rejected() {
    let p = WebpParams::lossy(80.0);
    assert!(encode_rgba(&[0; 16], 0, 4, 16, false, &p).is_err());
    assert!(
        encode_rgba(&[0; 16], 4, 4, 16, false, &p).is_err(),
        "buffer too small"
    );
    assert!(
        encode_rgba(&[0; 16], 4, 1, 8, false, &p).is_err(),
        "stride below row size"
    );
    assert!(
        encode_rgba(&vec![0; 16384 * 4], 16384, 1, 16384 * 4, false, &p).is_err(),
        "over 16383"
    );
    assert!(decode_rgba(b"not a webp").is_err());
}

#[test]
fn jpeg_quality_is_recovered_exactly() {
    for q in [30u8, 50, 75, 90, 95] {
        let bytes = std::fs::read(format!("tests/fixtures/q{q}.jpg")).unwrap();
        assert_eq!(
            estimate(&mut Cursor::new(bytes)),
            SourceQuality::Lossy(q),
            "jpeg q{q}"
        );
    }
}

#[test]
fn lossy_webp_quality_is_close() {
    let (w, h) = (96u32, 96u32);
    let rgba = pattern(w, h, false);
    for q in [30.0f32, 50.0, 75.0, 90.0, 95.0] {
        let data =
            encode_rgba(&rgba, w, h, (w * 4) as usize, false, &WebpParams::lossy(q)).unwrap();
        match estimate(&mut Cursor::new(data)) {
            SourceQuality::Lossy(est) => {
                let diff = (f32::from(est) - q).abs();
                assert!(diff <= 8.0, "requested {q}, estimated {est}");
            }
            other => panic!("expected lossy for q{q}, got {other:?}"),
        }
    }
}

#[test]
fn lossless_sources_are_detected() {
    let (w, h) = (16u32, 16u32);
    let rgba = pattern(w, h, true);
    let webp = encode_rgba(
        &rgba,
        w,
        h,
        (w * 4) as usize,
        true,
        &WebpParams::lossless(100, false),
    )
    .unwrap();
    assert_eq!(estimate(&mut Cursor::new(webp)), SourceQuality::Lossless);
    let png = std::fs::read("tests/fixtures/rgb.png").unwrap();
    assert_eq!(estimate(&mut Cursor::new(png)), SourceQuality::Lossless);
    assert_eq!(
        estimate(&mut Cursor::new(vec![0u8; 64])),
        SourceQuality::Unknown
    );
    assert_eq!(
        estimate(&mut Cursor::new(vec![1u8; 4])),
        SourceQuality::Unknown
    );
}

#[test]
fn quality_codes() {
    assert_eq!(SourceQuality::Lossy(88).to_code(), 88);
    assert_eq!(SourceQuality::Lossless.to_code(), 101);
    assert_eq!(SourceQuality::Unknown.to_code(), -1);
}
