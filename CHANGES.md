# Changes compared to upstream (Kotatsu / Kotatsu Redo)

Exclusive headline features are in the README; everything else is listed here.

## Image pipeline
- SSIV fork (JitPack `com.github.Ero-gamer:subsampling-scale-image-view`): ARGB_8888 decoding,
  libjpeg-turbo 3.2.0 region decoder (native, falls back to Skia), tile prefetch + memory budget,
  EXIF orientation at view level, `ImageRegionDecoder.isRegionDecodeCheap` (prefetch is skipped for
  formats without cheap region decode such as WebP/PNG/AVIF/GIF).
- Native Rust codecs `ssivcodecs` (JPEG XL region/downsample/animation via jxl-oxide, APNG): optional,
  built with cargo+NDK when available, otherwise skipped; the app falls back to Skia.
  - Wide-gamut JXL (e.g. Display P3) converted to sRGB with automatic fallback.
  - Decode limits (dimensions, frame count, frame duration) and thread clamp (1-2).
  - Animated drawables stop retrying after a decode failure.
- AVIF decoded with the AOMedia libavif Android binding (whole image, then downscale).
- Undecodable page formats are converted to PNG in `PageLoader`.
- Native link flags: 16 KB page size, `--gc-sections`, `--exclude-libs,ALL`, `--icf=safe`.

## Reader
- Vibrance and sharpening filters, 2x max zoom, 3-tap double-tap zoom cycle, vertical slider
  (page switches on release), auto-crop borders, optimized backup/restore, global switch to disable the
  automatic Cloudflare captcha solver.

## Build and tooling
- Kotlin 2.4.21, KSP 2.3.11, AGP 8.13.2 (key `agp`), Gradle 9.0.0. Dagger/Hilt stay on 2.58 and
  androidx.hilt on 1.3.0 because newer versions require AGP 9.
- The Kotlin runtime pin in `app/build.gradle.kts` now follows the catalog Kotlin version.
- Spotless 8.10.4 + ktlint 1.8.0 + detekt 1.23.8 are version-catalog entries and applied via the
  root build; `./gradlew spotlessCheck detekt` also runs under `check`.
- Valid Dependabot config (gradle, cargo, github-actions) next to the existing Renovate config.
