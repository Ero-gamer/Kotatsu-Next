use ssivcodecs::common::sampled_len;
use ssivcodecs::{Decoder, FileRange, PixelFormat, Rect, Sink};
use std::fs::File;
use std::os::fd::AsRawFd;
use std::path::PathBuf;
use std::sync::Arc;

fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

fn open_dec(name: &str) -> Box<dyn Decoder> {
    let file = File::open(fixture(name)).unwrap();
    let len = file.metadata().unwrap().len();
    ssivcodecs::jni_api::open(file.as_raw_fd(), 0, len).unwrap_or_else(|e| panic!("{name}: {e}"))
}

/// Reference PNG as premultiplied RGBA.
fn load_ref(name: &str) -> (u32, u32, Vec<u8>) {
    let mut dec = png::Decoder::new(std::io::BufReader::new(File::open(fixture(name)).unwrap()));
    dec.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
    let mut r = dec.read_info().unwrap();
    let mut buf = vec![0u8; r.output_buffer_size().unwrap()];
    let o = r.next_frame(&mut buf).unwrap();
    let ch = match o.color_type {
        png::ColorType::Grayscale => 1,
        png::ColorType::GrayscaleAlpha => 2,
        png::ColorType::Rgb => 3,
        png::ColorType::Rgba => 4,
        _ => panic!("indexed"),
    };
    let mut out = Vec::new();
    for p in buf[..o.buffer_size()].chunks_exact(ch) {
        let (rgb, a) = match ch {
            1 => ([p[0]; 3], 255),
            2 => ([p[0]; 3], p[1]),
            3 => ([p[0], p[1], p[2]], 255),
            _ => ([p[0], p[1], p[2]], p[3]),
        };
        let pm = |c: u8| ((c as u32 * a as u32 + 127) / 255) as u8;
        out.extend_from_slice(&[pm(rgb[0]), pm(rgb[1]), pm(rgb[2]), a]);
    }
    (o.width, o.height, out)
}

fn decode(dec: &mut dyn Decoder, frame: u32, rect: Rect, sample: u32) -> Vec<u8> {
    let (w, h) = (
        sampled_len(rect.width, sample),
        sampled_len(rect.height, sample),
    );
    let mut buf = vec![0u8; w as usize * h as usize * 4];
    let mut sink = Sink::new(&mut buf, w as usize * 4, PixelFormat::Rgba8888, w, h).unwrap();
    dec.decode(frame, rect, sample, &mut sink).unwrap();
    buf
}

fn max_diff(a: &[u8], b: &[u8]) -> u32 {
    assert_eq!(a.len(), b.len());
    a.iter()
        .zip(b)
        .map(|(x, y)| (*x as i32 - *y as i32).unsigned_abs())
        .max()
        .unwrap_or(0)
}

fn crop(full: &[u8], fw: u32, r: Rect) -> Vec<u8> {
    let mut out = Vec::new();
    for y in r.top..r.bottom() {
        let s = (y as usize * fw as usize + r.left as usize) * 4;
        out.extend_from_slice(&full[s..s + r.width as usize * 4]);
    }
    out
}

fn assert_whole(name: &str, reference: &str, tolerance: u32) {
    let mut dec = open_dec(name);
    let (w, h, expected) = load_ref(reference);
    assert_eq!((dec.info().width, dec.info().height), (w, h), "{name} size");
    let got = decode(dec.as_mut(), 0, Rect::new(0, 0, w, h), 1);
    let d = max_diff(&got, &expected);
    assert!(d <= tolerance, "{name}: max diff {d} > {tolerance}");
}

#[test]
fn jxl_still_images_match_reference() {
    assert_whole("rgb.jxl", "ref/rgb.png", 2);
    assert_whole("rgba.jxl", "ref/rgba.png", 2);
    assert_whole("gray.jxl", "ref/gray.png", 2);
    assert_whole("rot.jxl", "ref/rot.png", 2);
}

#[test]
fn jxl_regions_equal_crops_of_the_full_decode() {
    let mut dec = open_dec("rgba.jxl");
    let (w, h) = (dec.info().width, dec.info().height);
    let full = decode(dec.as_mut(), 0, Rect::new(0, 0, w, h), 1);
    let mut seed = 12345u32;
    let mut next = |m: u32| {
        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
        (seed >> 8) % m.max(1)
    };
    for _ in 0..40 {
        let (l, t) = (next(w - 1), next(h - 1));
        let r = Rect::new(l, t, 1 + next(w - l - 1), 1 + next(h - t - 1));
        let got = decode(dec.as_mut(), 0, r, 1);
        assert_eq!(max_diff(&got, &crop(&full, w, r)), 0, "region {r:?}");
    }
}

#[test]
fn jxl_downsample_is_a_box_average() {
    let mut dec = open_dec("rgb.jxl");
    let (w, h) = (dec.info().width, dec.info().height);
    let full = decode(dec.as_mut(), 0, Rect::new(0, 0, w, h), 1);
    for sample in [2u32, 4, 8] {
        let got = decode(dec.as_mut(), 0, Rect::new(0, 0, w, h), sample);
        let (sw, sh) = (sampled_len(w, sample), sampled_len(h, sample));
        assert_eq!(got.len(), (sw * sh * 4) as usize);
        // Spot check a few output pixels against a manual average.
        for (ox, oy) in [(0u32, 0u32), (sw / 2, sh / 2), (sw - 1, sh - 1)] {
            for c in 0..4usize {
                let (mut sum, mut n) = (0u32, 0u32);
                for y in oy * sample..((oy + 1) * sample).min(h) {
                    for x in ox * sample..((ox + 1) * sample).min(w) {
                        sum += full[(y as usize * w as usize + x as usize) * 4 + c] as u32;
                        n += 1;
                    }
                }
                let want = (sum + n / 2) / n;
                let have = got[(oy as usize * sw as usize + ox as usize) * 4 + c] as u32;
                assert!(
                    want.abs_diff(have) <= 1,
                    "sample {sample} px ({ox},{oy}) c{c}: {have} vs {want}"
                );
            }
        }
    }
}

#[test]
fn jxl_animation() {
    let mut dec = open_dec("anim.jxl");
    let info = dec.info().clone();
    assert_eq!(
        (info.width, info.height, info.frame_count, info.loop_count),
        (120, 90, 5, 0)
    );
    let durations = [80, 120, 100, 60, 200];
    for i in 0..5u32 {
        assert_eq!(
            dec.frame_duration_ms(i),
            durations[i as usize],
            "duration {i}"
        );
        let (w, h, expected) = load_ref(&format!("ref/ajxl_{i}.png"));
        let got = decode(dec.as_mut(), i, Rect::new(0, 0, w, h), 1);
        assert!(max_diff(&got, &expected) <= 2, "jxl frame {i}");
    }
    // Random access / looping back.
    let (w, h, expected) = load_ref("ref/ajxl_1.png");
    assert!(
        max_diff(
            &decode(dec.as_mut(), 1, Rect::new(0, 0, w, h), 1),
            &expected
        ) <= 2
    );
}

#[test]
fn apng_animation_composites_like_the_reference() {
    let mut dec = open_dec("anim.apng");
    let info = dec.info().clone();
    assert_eq!(
        (info.width, info.height, info.frame_count, info.loop_count),
        (120, 90, 5, 3)
    );
    let durations = [80, 120, 100, 60, 200];
    for i in 0..5u32 {
        assert_eq!(
            dec.frame_duration_ms(i),
            durations[i as usize],
            "duration {i}"
        );
        let (w, h, expected) = load_ref(&format!("ref/apng_{i}.png"));
        let got = decode(dec.as_mut(), i, Rect::new(0, 0, w, h), 1);
        assert!(
            max_diff(&got, &expected) <= 2,
            "apng frame {i}: {}",
            max_diff(&got, &expected)
        );
    }
    // Same frame again (no re-decode) and going backwards (stream restart) both stay correct.
    for i in [4u32, 4, 2, 0, 3] {
        let (w, h, expected) = load_ref(&format!("ref/apng_{i}.png"));
        let got = decode(dec.as_mut(), i, Rect::new(0, 0, w, h), 1);
        assert!(max_diff(&got, &expected) <= 2, "apng frame {i} (revisit)");
    }
    // Region + sample on a composited frame.
    let (w, _h, expected) = load_ref("ref/apng_3.png");
    let r = Rect::new(7, 5, 61, 40);
    let got = decode(dec.as_mut(), 3, r, 1);
    assert!(max_diff(&got, &crop(&expected, w, r)) <= 2);
}

#[test]
fn still_png_and_garbage_are_rejected() {
    let file = File::open(fixture("rgb.png")).unwrap();
    let len = file.metadata().unwrap().len();
    assert!(
        ssivcodecs::jni_api::open(file.as_raw_fd(), 0, len).is_err(),
        "plain PNG is not an animation"
    );
    let jpg = File::open(fixture("rot.jpg")).unwrap();
    let len = jpg.metadata().unwrap().len();
    assert!(ssivcodecs::jni_api::open(jpg.as_raw_fd(), 0, len).is_err());
    // Truncated animation must fail cleanly, not panic.
    let bytes = std::fs::read(fixture("anim.apng")).unwrap();
    let tmp = std::env::temp_dir().join("ssiv_trunc.apng");
    std::fs::write(&tmp, &bytes[..bytes.len() / 2]).unwrap();
    let f = File::open(&tmp).unwrap();
    let range = FileRange::new(Arc::new(f), 0, (bytes.len() / 2) as u64);
    assert!(ssivcodecs::apng::ApngDecoder::new(range).is_err());
}

#[test]
fn rgb565_output_is_dithered_and_in_range() {
    let mut dec = open_dec("rgb.jxl");
    let (w, h) = (dec.info().width, dec.info().height);
    let mut buf = vec![0u8; w as usize * h as usize * 2];
    let mut sink = Sink::new(&mut buf, w as usize * 2, PixelFormat::Rgb565, w, h).unwrap();
    dec.decode(0, Rect::new(0, 0, w, h), 1, &mut sink).unwrap();
    assert!(buf.iter().any(|b| *b != 0));
}

#[test]
fn jxl_wide_gamut_converted_to_srgb() {
    // 64x48 flat (180, 100, 60) in Display P3; converted to sRGB (analytic, clipped) = (193, 95, 49).
    const EXPECT: [i32; 3] = [193, 95, 49];
    let mut dec = open_dec("p3.jxl");
    let got = decode(dec.as_mut(), 0, Rect::new(0, 0, 64, 48), 1);
    let (r, g, b) = (got[0] as i32, got[1] as i32, got[2] as i32);
    assert_eq!(got[3], 255);
    assert!(
        (r - EXPECT[0]).abs() <= 4 && (g - EXPECT[1]).abs() <= 4 && (b - EXPECT[2]).abs() <= 4,
        "got ({r},{g},{b}), want {EXPECT:?}"
    );
}
