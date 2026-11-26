# Documentation

## Layout
- `app/` - Android app (Kotlin, Hilt, Room, Coil, Compose + Views).
- `app/src/main/rust/ssivcodecs/` - optional native codecs (JPEG XL, APNG), Rust `cdylib` via JNI.
- SSIV (separate repo, consumed from JitPack as `libs.ssiv`) - tiled image view with libjpeg-turbo.
- `config/detekt/detekt.yml`, `.editorconfig` - lint/format rules.
- `gradle/libs.versions.toml` - single version catalog (`agp`, `kotlin`, `ksp`, `spotless`, `detekt`, `ktlint`).

## Build
`./gradlew assembleDebug`. The Rust task runs only when cargo and the NDK are found; otherwise it logs
a skip and the app uses Skia decoders. `cargo build --locked` is used when `Cargo.lock` exists.
Requirements: JDK 17+ (CI uses 21), Gradle 9.9.2 wrapper, AGP 9.5.2 (built-in Kotlin).

## Image pipeline
1. `PageLoader` fetches a page (network, cache or CBZ) and detects the format (`ImageFormatProbe`).
2. SSIV picks a region decoder: libjpeg-turbo (JPEG) -> native Rust (JXL/APNG) -> Skia fallback.
3. `isRegionDecodeCheap` is false for WebP/PNG/AVIF/GIF: SSIV then loads only visible tiles and skips
   prefetch; the low-resolution base layer keeps off-screen content cheap.
4. Formats Skia cannot decode (e.g. AVIF on old devices) are converted to PNG first.
Region decode is cheap only where blocks are independent (JPEG, JXL groups). mmap is a byte-access
technique and does not make other formats region-decodable; stored (uncompressed) zip entries can be
read in place, deflated ones need an extracted cache.

## Code quality
`./gradlew spotlessCheck detekt` (also part of `check`); `./gradlew spotlessApply` formats.
Manual-only GitHub workflows (app and SSIV): `ktlint-fix.yml`, `spotless-fix.yml` (layout-only rules from
`config/ktlint/safe.editorconfig`, verified by `scripts/verify-safe-format.py`, compile-checked, delivered as a PR),
`detekt.yml` (report; SSIV can also update its baseline). detekt has no safe auto-fix. SSIV also has `lint.yml`
(report only; configs in `.editorconfig` and `config/detekt/`). `ci.yml` in the app still auto-runs full
`spotlessApply` on push and auto-commits.
Rust licenses: `python3 scripts/rust-licenses.py` (generated table, 59 crates, none unlicensed).
Rust: `cargo test --locked` in `app/src/main/rust/ssivcodecs`.

## Version policy
Latest stable versions only. Run the manual "Dependency versions report" workflow
(`scripts/dep_versions.py`) to see what is newer. Held back on purpose: detekt 1.23.8 (2.x is alpha),
MMKV 1.3.17 (2.x is a major: needs API check + 16 KB check), Conscrypt 2.8.0 (upstream issue 1268),
LeakCanary 3.0-alpha-9 (no 3.x stable), biometric 1.4.0-alpha05 (newer is rc only).
Planned: Room 2.8.5 modernization, then Room 3 (`androidx.room3` 3.0.3).

## CI
`ci.yml` (spotless auto-fix, detekt, `build`: cargo test + assembleDebug), `native-lint.yml` (SSIV C++/GLSL/
Rust/Python lint), `dep-versions.yml` (manual report), `auto-unzip.yml` (upload a zip to the repo root: strict extract, stage, commit, push, byte audit; any problem fails the run and commits nothing; optional secret `UNZIP_PAT` for workflow-file changes and to let the bot commit trigger CI).
