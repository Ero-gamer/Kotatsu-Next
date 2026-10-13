# Project memory (for future assistant sessions)

Keep this file current; contains no personal data.

## Targets
- App: Kotatsu Next (fork of Kotatsu Redo). SSIV fork consumed via JitPack (`ssiv = "7.00"`).
- Reference device: 2 GB RAM, Android 9, armeabi-v7a, 720x1600. No local Android build environment.

## Standing preferences
- Short answers; show results at the end. Compact the chat each new session.
- Deliver only changed/new files as a zip with folder structure intact (no wrapper folder).
- Only safe, stable, tested-where-possible code; double-check; run real ktlint + detekt (+ cargo test).
- Accurate task lists (done = done, pending first). Follow the Karpathy guidelines (simple, surgical,
  verifiable changes; state assumptions).
- Be honest about what was not compiled or run on Android.

## Decisions
- Stay on AGP 8.13.2/Gradle 9.0.0; Kotlin 2.4.21; KSP 2.3.11; no AGP 9 migration (user skipped it).
- Dagger 2.58 / androidx.hilt 1.3.0 until AGP 9. detekt 1.23.8 (2.x alpha). jni 0.21.
- No Rust/alt libs for WebP/AVIF/GIF region decode (none give cheap regions).
- Shell cannot reach Maven; use release pages/docs for versions.

## Open items
Native crop-borders scan (C++), linear-light downscale, license report in build, on-device benchmark,
forced-native-failure test. See `docs/TESTS.md`.

## Account memory
Not importable by the assistant: paste app-related items here when available.
