# Audit notes

## Dependencies
- Rust: 59 transitive crates, all permissive except the crate itself (GPL-3.0-or-later). No AGPL/GPL
  dependencies (zenwebp/zenavif/rav1d-safe were rejected for AGPL and lack of region decode).
- libjpeg-turbo pinned by URL + SHA-256 (3.2.0).
- Held back versions and reasons: see `docs/DOCUMENTATION.md` (Version policy).

## Hardening
- Native decoders enforce dimension/frame/duration limits before allocating.
- JNI entry points are guarded (panics/`LinkageError` fall back to Skia).
- Native libs link with `--exclude-libs,ALL`, `--gc-sections`, `--icf=safe`, 16 KB page alignment.

## Known risks / open items
- Dagger 2.58 reading Kotlin 2.4 metadata is unverified; revert = `kotlin = "2.3.20"` in the catalog.
- Whole-image AVIF decode can be memory heavy on 2 GB devices.
- Gradle build files were updated without a local Android build; CI is the gate.
- Account-level "memory" items cannot be read by the assistant; see `MEMORY.md`.
