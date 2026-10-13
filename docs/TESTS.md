# Tests

| Layer | Where | Run |
|---|---|---|
| Rust unit + integration (JXL region/downsample, APNG, limits, wide-gamut sRGB) | `app/src/main/rust/ssivcodecs/{src,tests}` | `cargo test --locked` (4 unit + 8 integration) |
| JVM unit tests | `app/src/test` (`MultiMutexTest`, `ChapterPagesTest`, `VersionIdTest`) | `./gradlew testDebugUnitTest` |
| Instrumented | `app/src/androidTest` (Room, backup agent, shortcuts) | `./gradlew connectedDebugAndroidTest` (device needed) |
| Lint/format | ktlint via Spotless, detekt | `./gradlew spotlessCheck detekt` |

## Status (honest)
- Rust crate: tests pass on the host. Fixtures are in `tests/fixtures` (incl. `p3.jxl`, Display P3).
- Android build and on-device behaviour (Oppo A11k class: 2 GB RAM, Android 9, armeabi-v7a) have **not**
  been verified by the maintainer's tooling here; CI must confirm.
- Not yet written: a test that forces native-library load failure and asserts the Skia fallback; an
  on-device benchmark of native vs Skia tile decode.
