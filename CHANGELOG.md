# Changelog

Release-style log of what changed in Kotatsu Next, newest first. Differences from upstream are described
in [CHANGES.md](CHANGES.md); third-party credits in [CREDITS.md](CREDITS.md).
Everything below was written without an Android build on the author's side: CI is the verifier, and items
marked "device test" still need a run on a real phone.

## Unreleased (2026-11)

### Added
- **PDF to CBZ import**: pick a `.pdf` in the local import screen and it becomes `<name>.cbz`. Pages are
  rendered with the platform `PdfRenderer` (long side 2048 px, 1600 px on low-RAM phones) and encoded as WebP.
  The WebP quality follows the quality of the JPEG images embedded in the PDF (median of the first ones); when
  it cannot be read the quality is 90. Progress is shown in the import notification. Password-protected PDFs
  are refused with a clear error.
- **Backup / restore sections**: per-manga reader settings, per-source settings and the updates feed
  (tracks + track logs) are now part of a backup. Device-specific settings (storage paths, app lock, proxy
  credentials, periodic-backup and Telegram options, incognito, Discord token) are never overwritten by a
  restore. A damaged section no longer stops the other sections from being restored.
- **Crop borders per manga** (Use global / Enabled / Disabled) with a button in the reader bar; toggling the
  global crop setting re-crops open pages immediately. Database v45.
- CI: `build` job (cargo test + `assembleDebug`), `lint-ssiv` native lint job (clang-format, cppcheck,
  glslang, ruff, rust), glslang pinned by version and SHA-256.
- **Dependency versions report** workflow (`dep-versions.yml`, manual, read-only) + `scripts/dep_versions.py`:
  lists the latest stable version of every dependency, plugin and wrapper of the app and SSIV.

- **Open source licenses** screen (Settings > About), AboutLibraries 15.2.0: lists every resolved dependency with its license,
  plus native libraries (libjpeg-turbo, libwebp, jxl-oxide, ...) and upstream projects from `config/aboutlibraries/`.

- **auto-unzip workflow rewritten, strict**: validates zips (CRC, unsafe paths, symlinks, wrapper folder, zips outside the root), verifies every
  file on disk by SHA-256, force-stages gitignored files, fails when a file is not staged / the zip changes nothing / the push is rejected,
  audits origin against the zip afterwards, writes a summary table.

### Changed
- **Build migration to AGP 9.5.2, Gradle 9.9.2, Dagger/Hilt 2.60.1, built-in Kotlin** (app and SSIV). The
  held AndroidX bumps went in with it: Compose BOM 2026.11.01, core-ktx 1.19.1, lifecycle 2.11.1,
  webkit 1.18.0, work 2.12.0. SSIV now compiles against SDK 37 (minSdk stays 23).
- JUnit corrected to 4.13.2 (`6.1.2` does not exist), Spotless 8.12.0, kotlinx.serialization 1.13.0,
  SSIV annotation 1.11.0.
- Reader/renderer: less allocation and lock contention in the GPU tile renderer, safer renderer release,
  bounded prefetch on low-RAM phones (offscreen page limit, tile cap while a GPU filter is on),
  observed bytes-per-pixel for tile budgeting, preview debounce in the colour-filter screen.
- Native (Rust): fallible allocation for every large buffer, canvas caps for GIF/APNG/JXL animation,
  panic-safe JNI handles, overflow checks in release, 64 KiB format sniffing, EINTR-safe file reads.
- Webtoon reader: Ctrl + wheel zoom is multiplicative; seam/misalignment fixes between pages.

### Fixed
- Release R8 failed with "Optimized resource shrinking requires non-final IDs": `android.r8.optimizedResourceShrinking=false` (classic resource shrinking; `res/raw/keep.xml` still applies). Debug/nightly: LeakCanary's adaptive icon cannot link at minSdk 24, so both build types carry a plain replacement `leak_canary_icon`; `TestMangaRepository` opts in to `InternalParsersApi`.
- Parsers dependency is `1.9.9` (fork synced with upstream; the old 1.9.8 tag had no `src/contract`, so the app could not compile against it). SSIV stays `9.0.0`.
- First Kotlin compile errors after the Stage B bump: `PageHolder` used `R.integer.config_shortAnimTime` (now `android.R`), `MangaGridItemAD` lacked the `View` import, `SyncController.getLastSync` was never defined (reads the value `setLastSync` stores). Next round: `updateList` in `PagesViewModel`/`PagePickerViewModel` is `suspend` (`ChaptersLoader.snapshot()` is), `toCompactListModel` takes `options`, `ExploreViewModel` imports the 2-flow `combine`, the reader overlays use `?.` (they exist only in some layouts). LeakCanary manifest override now covers all its library packages.
- **minSdk raised 23 to 24** (Android 7.0+): Compose Material3 1.5 declares minSdk 24. `uses-sdk tools:overrideLibrary` only for LeakCanary 3.0-alpha-9 (minSdk 26, debug/nightly builds only).
- Restoring a backup on another device no longer imports that device's storage paths.
- Re-bookmarking the same page no longer crashes (upsert).
- A long-press on the tap grid no longer scrolls the page afterwards.
- Suggestions kept the least relevant results; now diversified and most relevant first.
- A chapter with zero pages opened a blank reader; now shows an error with retry.
- Reading history follows a chapter whose id changed after the source refreshed its list.

## Earlier work

See [CHANGES.md](CHANGES.md) for the earlier feature batches (image pipeline, libjpeg-turbo, Rust codecs,
WebP/AVIF/JPEG XL handling, colour correction, downloads, image info, suggestions, alternatives).
