# Credits & Third-Party Licenses

The app lists all of this live under **Settings > About > Open source licenses** (generated at build time from the
resolved dependency graph by [AboutLibraries](https://github.com/mikepenz/AboutLibraries), Apache-2.0; extra entries
in `config/aboutlibraries/`). Keep that folder in sync when adding native code or upstream ports.

Kotatsu Next is licensed under **GPL-3.0** (see `LICENSE`). It builds on the projects below.
Permissive licenses (Apache-2.0, MIT, BSD) require keeping the copyright/licence notice, which this
file and each project's source link satisfy. Versions are in `gradle/libs.versions.toml` and
`app/src/main/rust/ssivcodecs/Cargo.lock`. Licences of the projects named here were checked against each repository's LICENSE file on 2026-11-25; rows still marked **(verify)** could not be confirmed.

## Upstream / forks
| Project | Source | License |
|---|---|---|
| Kotatsu | https://github.com/KotatsuApp/Kotatsu | GPL-3.0 |
| Kotatsu Redo | https://github.com/Kotatsu-Redo/Kotatsu-redo | GPL-3.0 |
| Kotatsu parsers (Redo) | https://github.com/Kotatsu-Redo/kotatsu-parsers-redo | GPL-3.0 |
| SubsamplingScaleImageView (Dave Morrissey) | https://github.com/davemorrissey/subsampling-scale-image-view | Apache-2.0 |
| DropSauce (HuzaifaKhalid1311) | https://github.com/HuzaifaKhalid1311/DropSauce | GPL-3.0 |
| Kotatsu SSIV fork / this SSIV fork | https://github.com/Ero-gamer/subsampling-scale-image-view | Apache-2.0 |

## Native code (app + SSIV)
| Library | Source | License |
|---|---|---|
| libjpeg-turbo 3.2.1 (SSIV JPEG decoder, built from source, SHA-256 pinned) | https://github.com/libjpeg-turbo/libjpeg-turbo | BSD-3-Clause, IJG, Zlib |
| libwebp 1.6.0 (WebP encoder + SharpYUV, vendored unmodified in `app/src/main/rust/ssivcodecs/vendor/libwebp`) | https://github.com/webmproject/libwebp | BSD-3-Clause |
| jxl-oxide 0.12.6 (JPEG XL) | https://github.com/tirr-c/jxl-oxide | MIT OR Apache-2.0 |
| png 0.18 (APNG) | https://github.com/image-rs/image-png | MIT OR Apache-2.0 |
| jni 0.22 | https://github.com/jni-rs/jni-rs | MIT OR Apache-2.0 |
| moxcms (ICC conversion) | https://github.com/awxkee/moxcms | BSD-3-Clause OR Apache-2.0 |
| rayon | https://github.com/rayon-rs/rayon | MIT OR Apache-2.0 |
| Other transitive Rust crates (see `Cargo.lock`) | crates.io | MIT / Apache-2.0 / BSD / Zlib / Unlicense / 0BSD / Unicode-3.0 |
| libavif (AOMedia Android binding) | https://github.com/AOMediaCodec/libavif | BSD-2-Clause |

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
| AboutLibraries (licenses screen) | https://github.com/mikepenz/AboutLibraries | Apache-2.0 |
| Coil 3 | https://github.com/coil-kt/coil | Apache-2.0 |
| MMKV | https://github.com/Tencent/MMKV | BSD-3-Clause |
| ACRA | https://github.com/ACRA/acra | Apache-2.0 |
| Conscrypt | https://github.com/google/conscrypt | Apache-2.0 |
| Markwon | https://github.com/noties/Markwon | Apache-2.0 |
| AdapterDelegates | https://github.com/sockeqwe/AdapterDelegates | Apache-2.0 |
| org.json | https://github.com/stleary/JSON-java | JSON License (public-domain style, "good not evil") |
| disk-lru-cache | https://github.com/solkin/disk-lru-cache | MIT |
| WorkInspector (debug builds only) | https://github.com/Koitharu/WorkInspector | GPL-3.0 |
| KizzyRPC | https://github.com/dead8309/KizzyRPC | Apache-2.0 |

## Build/quality tooling (not shipped)
| Tool | Source | License |
|---|---|---|
| ktlint | https://github.com/pinterest/ktlint | MIT |
| Spotless | https://github.com/diffplug/spotless | Apache-2.0 |
| detekt | https://github.com/detekt/detekt | Apache-2.0 |
| KSP | https://github.com/google/ksp | Apache-2.0 |

## Ideas adopted or studied (re-implemented here, see CHANGES.md)
| Project | Source | License | What was taken |
|---|---|---|---|
| Kotatsu Redo 9.8.5 | https://github.com/Kotatsu-Redo/Kotatsu-redo | GPL-3.0 | Suggestions ranking, related fallback, history chapter recovery, webtoon seam fix, source scope choice (code ported, community-server parts excluded) |
| DropSauce | https://github.com/HuzaifaKhalid1311/DropSauce | GPL-3.0 | Backup/restore fixes (settings preservation, per-section restore, upsert bookmarks, touch cancel after long press), extra backup sections, PDF import idea |
| APNG4Android | https://github.com/penfeizhou/APNG4Android | Apache-2.0 | Animated WebP: demux frames and decode each through the platform decoder |
| TachiyomiSY | https://github.com/jobobby04/TachiyomiSY | Apache-2.0 | Reader behaviour/design ideas |
| Telephoto | https://github.com/saket/telephoto | Apache-2.0 | Zoomable image design ideas |
| Mangayomi | https://github.com/kodjodevf/mangayomi | Apache-2.0 | Prefetch behind the visible page, black-border crop vote, scroll curve |

## Ideas studied (no code copied)
`mpreg-ca/imagedecoder` and `WebGPUViewer` were reviewed for link flags, hardening limits and color
handling; their code is not included.
