# Credits & Third-Party Licenses

Kotatsu Next is licensed under **GPL-3.0** (see `LICENSE`). It builds on the projects below.
Permissive licenses (Apache-2.0, MIT, BSD) require keeping the copyright/licence notice, which this
file and each project's source link satisfy. Versions are in `gradle/libs.versions.toml` and
`app/src/main/rust/ssivcodecs/Cargo.lock`. Rows marked **(verify)** have a licence I could not confirm
from this environment; check the linked repository before a release.

## Upstream / forks
| Project | Source | License |
|---|---|---|
| Kotatsu | https://github.com/KotatsuApp/Kotatsu | GPL-3.0 |
| Kotatsu Redo | https://github.com/Kotatsu-Redo/Kotatsu-redo | GPL-3.0 |
| Kotatsu parsers (Redo) | https://github.com/Kotatsu-Redo/kotatsu-parsers-redo | GPL-3.0 |
| SubsamplingScaleImageView (Dave Morrissey) | https://github.com/davemorrissey/subsampling-scale-image-view | Apache-2.0 |
| Kotatsu SSIV fork / this SSIV fork | https://github.com/Ero-gamer/subsampling-scale-image-view | Apache-2.0 |

## Native code (app + SSIV)
| Library | Source | License |
|---|---|---|
| libjpeg-turbo 3.2.0 (SSIV JPEG decoder, built from source, SHA-256 pinned) | https://github.com/libjpeg-turbo/libjpeg-turbo | BSD-3-Clause, IJG, Zlib |
| jxl-oxide 0.12.6 (JPEG XL) | https://github.com/tirr-c/jxl-oxide | MIT OR Apache-2.0 |
| png 0.18 (APNG) | https://github.com/image-rs/image-png | MIT OR Apache-2.0 |
| jni 0.21 | https://github.com/jni-rs/jni-rs | MIT OR Apache-2.0 |
| moxcms (ICC conversion) | https://github.com/awxkee/moxcms | BSD-3-Clause OR Apache-2.0 |
| rayon | https://github.com/rayon-rs/rayon | MIT OR Apache-2.0 |
| Other transitive Rust crates (see `Cargo.lock`) | crates.io | MIT / Apache-2.0 / BSD / Zlib / Unlicense / 0BSD / Unicode-3.0 |
| libavif (AOMedia Android binding) | https://github.com/AOMediaCodec/libavif | BSD-2-Clause (verify wrapper) |

## JVM / Android libraries (app)
| Library | Source | License |
|---|---|---|
| Kotlin, kotlinx.coroutines, kotlinx.serialization | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization | Apache-2.0 |
| AndroidX (core, appcompat, fragment, room, work, lifecycle, compose, ...) | https://cs.android.com/androidx, https://github.com/androidx/androidx | Apache-2.0 |
| Material Components | https://github.com/material-components/material-components-android | Apache-2.0 |
| Dagger / Hilt | https://github.com/google/dagger | Apache-2.0 |
| Guava | https://github.com/google/guava | Apache-2.0 |
| Desugar JDK libs | https://github.com/google/desugar_jdk_libs | GPL-2.0 w/ Classpath Exception (verify) |
| OkHttp, Okio, Moshi | https://github.com/square/okhttp, https://github.com/square/okio, https://github.com/square/moshi | Apache-2.0 |
| LeakCanary (debug only) | https://github.com/square/leakcanary | Apache-2.0 |
| Coil 3 | https://github.com/coil-kt/coil | Apache-2.0 |
| MMKV | https://github.com/Tencent/MMKV | BSD-3-Clause |
| ACRA | https://github.com/ACRA/acra | Apache-2.0 |
| Conscrypt | https://github.com/google/conscrypt | Apache-2.0 |
| Markwon | https://github.com/noties/Markwon | Apache-2.0 |
| AdapterDelegates | https://github.com/sockeqwe/AdapterDelegates | Apache-2.0 |
| org.json | https://github.com/stleary/JSON-java | JSON License (public-domain style, "good not evil") |
| disk-lru-cache | https://github.com/solkin/disk-lru-cache | Apache-2.0 (verify) |
| WorkInspector | https://github.com/Koitharu/WorkInspector | Apache-2.0 (verify) |
| KizzyRPC | https://github.com/dead8309/KizzyRPC | verify |

## Build/quality tooling (not shipped)
| Tool | Source | License |
|---|---|---|
| ktlint | https://github.com/pinterest/ktlint | MIT |
| Spotless | https://github.com/diffplug/spotless | Apache-2.0 |
| detekt | https://github.com/detekt/detekt | Apache-2.0 |
| KSP | https://github.com/google/ksp | Apache-2.0 |

## Ideas studied (no code copied)
`mpreg-ca/imagedecoder` and `WebGPUViewer` were reviewed for link flags, hardening limits and colour
handling; their code is not included.
