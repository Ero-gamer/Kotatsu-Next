# Changes compared to upstream (Kotatsu / Kotatsu Redo)

Exclusive headline features are in the README; everything else is listed here.

## Reader
- Auto contrast, Auto brightness and Auto saturation (color correction screen, each with (i) info; the matching manual slider is disabled while on). One measuring pass per page (pixel-budget thumbnail, so webtoon strips are sampled evenly) stores raw stats in a new `page_stats` table (DB v41, memory LRU + batched writes). Downloaded pages are keyed by their zip entry CRC32+size and kept until the chapter/manga is deleted; streamed pages expire after 24 h (20k-row cap), so each image is read once. All three fold into ONE color matrix (no extra redraw, no tile reload). Values are signed: contrast stretches flat pages and gently compresses harsh color pages; brightness lightens dark and darkens too-bright pages towards a per-type target (paper 248, color 128, grey 140) with dead bands; saturation only boosts. Prefetched pages are measured ahead on two low-priority threads. Stored per manga and globally.
- Page badges now also work for pages whose list preview is a thumbnail: the real page is probed when it is already on disk (local file/archive or cached download). Image info sheet shows whether an ICC profile is embedded.
- Animated covers on the details screen (setting in Reader > animations, off by default): the big cover plays while the screen is open; lists and grids stay static. Off in memory-saver mode or with system animations off.
- Auto contrast/brightness/saturation now also apply to animated images (GIF, WebP, APNG): the first frame is measured once and one constant matrix is used for the whole animation.
- Color overlay (color correction screen, new section with Overlay red/green/blue/opacity sliders, each with (i) info): mixes a flat color over the page as the last step of the single color matrix (CPU, also on animated images); off at opacity 0. Stored per manga (DB v42) and globally.
- Page stats housekeeping: deleting a downloaded chapter/manga drops the stats of the archives it removed, a sweep in the storage cleanup drops stats of archives deleted outside the app, and re-downloaded streamed pages keep their stats (keyed by page URL + length). DB v41.
- Color correction screen: collapsible sections (Color and tone / Sharpness and clarity / Cleanup) with an "N active" count per section; a slider at its default shows "OFF".
- Double-page centre margin setting (Settings > Reader); swipe down on a non-zoomed image closes the image viewer.
- New reading mode "Bottom to top" (vertical pager, first page at the bottom, swipe down for the next page).

## Image pipeline
- Download-time splitting: tall strips (Settings > Downloads > Split tall images) and double-page spreads
  (Split double-page spreads: left-to-right or right-to-left half order) into WebP pages; the Image optimizer
  screen runs the tall split / WebP conversion on already downloaded chapters.
- SSIV fork (JitPack `com.github.Ero-gamer:subsampling-scale-image-view`): ARGB_8888 decoding,
  libjpeg-turbo 3.2.1 region decoder (native, falls back to Skia), tile prefetch + memory budget,
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
- Kotlin 2.4.21, KSP 2.3.13, AGP 9.5.2 (key `agp`, built-in Kotlin: the `kotlin-android` plugin is not
  applied), Gradle 9.9.2, Dagger/Hilt 2.60.1, androidx.hilt 1.4.0. App and SSIV are on the same AGP.
- CI: `build` job, native lint job (`lint-ssiv`), manual "Dependency versions report" workflow.
- The Kotlin runtime pin in `app/build.gradle.kts` now follows the catalog Kotlin version.
- Spotless 8.12.0 + ktlint 1.8.0 + detekt 1.23.8 are version-catalog entries and applied via the
  root build; `./gradlew spotlessCheck detekt` also runs under `check`.
- Valid Dependabot config (gradle, cargo, github-actions) next to the existing Renovate config.

## Backup and restore
- New backup sections: per-manga reader settings, per-source settings, updates feed (tracks + track logs).
- Device-specific settings (storage paths, app lock, proxy credentials, periodic-backup/Telegram options,
  incognito, Discord token) are kept on restore; numbers are coerced to the type the key has now.
- Every section restores on its own: one damaged section or record no longer aborts the rest.
- Ideas for these fixes come from DropSauce (GPL-3.0), re-implemented against this code base.

## About
- Open source licenses screen (AboutLibraries 15.2.0, Compose M3) opened from Settings > About; data generated by the Gradle plugin at build
  time, extra entries in `config/aboutlibraries/libraries/*.json`; `@raw/aboutlibraries` is kept from resource shrinking (`res/raw/keep.xml`).

## Local import
- PDF files become CBZ (see CHANGELOG.md): PdfRenderer, WebP output, quality taken from embedded JPEGs.

## Reader (crop, input)
- Crop borders can be overridden per manga (Use global / Enabled / Disabled); DB v45.
- Tap inversion (Off / left-right / top-bottom / both).

## Suggestions, related, history, alternatives (ported from Kotatsu Redo 9.8.5, local parts only)
- Suggestions ranking (MMR diversification, dropped-series filter, TF-IDF tags), related row local fallback,
  history chapter-id recovery, source scope choice (enabled / disabled / preset) for alternatives.
- Community-server parts of Redo are not included.

## Image info: compression quality
- Image info sheet shows "Compression quality" for lossy images, read from the file header only (no decode), for downloaded and temporary pages alike.
- JPEG: quantization tables compared with the standard IJG tables; exact match shows "N / 100", otherwise "≈ N / 100" with an (i) button.
- WebP: VP8 quantizer index mapped to the libwebp scale (always an estimate, (i) shown); lossless WebP, PNG and BMP show "Lossless".
- AVIF: AV1 base_q_idx of still pictures mapped to the libavif scale (estimate, (i) shown); unsupported layouts show no row. JPEG XL stores no quality, so no row.

## Image info: pixel analysis
- "Analyze pixels" button in the image info sheet (JPEG, PNG, WebP, BMP). A 384×384 sample (best of up to 5 spots, 8×8-aligned) is recompressed at JPEG quality 20–97; the dip in luma error shows the quality it was last compressed with (works for PNG/WebP made from a JPEG, or when the header is unreadable). Flat blocks are ignored.
- Shows "Pixel analysis" (≈ N / 100, or no history / too flat / unavailable) with an (i) button; notes when the file is lossless but was JPEG-compressed, was re-encoded, or was saved again in another format. For JPEG files with estimated header quality a "Best estimate" row averages header and pixels (pixels weigh double on a very clear dip).

## ABIs
- Only `armeabi-v7a` and `arm64-v8a` are built and packaged, in the app and in SSIV (x86 / x86_64 removed: ABI filters, Rust x86_64 target, libwebp SSE2/SSE4.1/AVX2 sources, CMake emulator note). The `ssiv.abis` / `ssiv.splitAbi` build options are gone.
- `-Pssiv.legacyJni=false` stores native libs uncompressed (default stays compressed).

## Memory guards
- Still JPEG XL decode is downsampled so the bitmap never exceeds 16 MP (64 MB).
- Animations from files larger than 48 MB are not played (static first frame).

## Reader: row-by-row tap-to-pan
- With tap-to-pan on, a zoomed page in a horizontal pager now moves on to the next row (one screen down, restarting at the row's start edge; up when going back) once the current row is finished, before the page turns. Tall pages are read fully by tapping.

## Reader prefetch: pages behind + failure memo
- Prefetch also queues 2 pages behind the visible one (1 in memory saver), after the pages ahead, so a back-swipe is instant. Imported from Mangayomi.
- A prefetched page that fails is not queued again for 2 minutes (no hammering a dead host). A user-requested load always retries and clears the memo.

## SSIV: first page of a chapter stayed pixelated until swiped away and back
Cause: detail tiles were requested once, while the view's scale was still 0 (the first scale is only set when the image is first drawn), so none were asked for and nothing re-requested them until a touch or a redraw of another page. Fix: the view refreshes its required tiles right after the first scale is known (SubsamplingScaleImageView.checkReady). Needs a new SSIV tag to reach the app.

## SSIV: page sometimes opens too small (bars around) or zoomed in after fast swipes (uncompiled)
- limitedScale: the fit scale now wins over a stale max scale (a reused view kept the previous page's max, which can be below this page's fit => drawn smaller than fit, permanently).
- reset(new image) clears a custom min scale left by the previous image.
- initTiles: decoder kept local until its init finished (was a field written from the background thread; a cancelled init of the previous page could overwrite or recycle the new page's decoder on fast swipes); a cancelled/failed init releases its decoder.
Needs a new SSIV tag.

## Download ahead while reading (setting, off by default)
Settings > Downloads > "Download ahead while reading": 1/2/3/5 chapters. While a saved chapter of a favorite series is read, the next chapters that are not saved are queued silently (cellular only if allowed). Nothing starts when the chapter read is not saved, the series is not a favorite, or it is a local-only manga. Imported idea from Mangayomi (selectDownloadAhead).

## Cover timeout + black-border crop
- Coil's HTTP client (covers, thumbnails, favicons) gets a 30 s whole-call limit; page downloads keep their own client.
- Crop borders now also handles pages with black margins: 8 sample points around the edge vote (>=5 black and <5 white => black margins), as in Mangayomi. White stays the default and behaves as before; near-black noise (<=12) counts as black.

## Cache keys ignore link signatures
LocalStorageCache (page cache etc.) now keys files by the link without signing parameters (Expires, Signature, Sig, Policy, Key-Pair-Id, X-Amz-*, X-Goog-*), so a re-signed link hits the file saved earlier instead of downloading the page again. Links without a query are untouched (no allocation). `token` is deliberately not stripped (it can identify the image). Existing entries with signed keys are simply re-fetched once.

### Tests: chapter navigation
`ChapterNavigationTest` covers `neighbourIndex`, `missingChaptersBetween`, `formatChapterNumber`.

### Fix: Retry-After handling
`RateLimitInterceptor.parseRetryAfter` returned an absolute epoch time for HTTP-date values (parsers expect a delay in ms, so waits looked years long and downloads gave up) and threw on malformed values. Now: delay from now, past/garbage = 0. `RetryAfterTest` added.

### Tests: double-page padding
`PadForDoublePageTest` (chapters never share a spread, cover-solo rule, order kept). Chapter-aware spread rules (M7) already existed in `padForDoublePage`; no code change.

### Downloads: max 2 at once
`DownloadWorker` now takes a process-wide permit (`MAX_CONCURRENT_DOWNLOADS = 2`) around the download body. Before, downloads of different manga (and silent download-ahead) all ran in parallel. Extra ones wait; cancel while waiting works.

### Downloads: smarter retries, bad-response check
- Retry delay: exponential backoff 2s/4s/8s (cap 16s) + jitter instead of flat 2s. A 429 without Retry-After now backs off and retries instead of pausing the whole download at once. Server Retry-After still wins. `RetryDelayTest` added.
- Pages: empty body or HTML (anti-bot/error page served with 200) is now treated as an error and retried, not saved as a broken page. Cover download unchanged.

### Downloads: 3-stage page pipeline, adaptive rate-limit gate, next-chapter prefetch
- `AdaptiveGate`: page network concurrency starts at 4, drops by one on each 429 (once per 2s), regains one per 20 successes, never below 1.
- Pages: network stage (gate) / split+store stage (max 2, no network permit held) / at most 8 pages in flight (bounds temp files).
- Next chapter's page list is requested while the current chapter downloads (failures fall back to the normal retry path).
- Tests: `AdaptiveGateTest`.

### Fixes: filter reset, AVIF handling
- Image filters: Reset + "This manga" now means defaults for that manga (filters off for it) instead of silently using the current (tweaked) global filter. The editor also opens on defaults when filters are off for the manga.
- AVIF: 10/12-bit AVIFs were decoded to RGB_565 for the reader (banding, tinted grays, weak GPU filters); now always 8 bits per channel.
- AVIF/other pages whose type could not be mapped to an extension got extensionless cbz entries; the extension now falls back to the file content. AVIF brand sniffing also accepts generic HEIF brands (mif1/miaf/msf1) when no HEIC brand is present.
- Saving a page from a downloaded chapter named AVIF bytes `.png`; the extension now comes from the entry's content.
- JPEG XL pages stored in a cbz without an extension are now recognised by content and decoded natively.
- Page conversion (formats the system decoder can't read, e.g. AVIF on older Android): the PNG copy now gets its own cache entry instead of overwriting the cached/downloaded file in place. Saving a streamed AVIF page now gives the original AVIF bytes, and loose downloaded files are no longer rewritten.

### AVIF read directly; WebP conversion for AVIF / JXL; auto quality; convert on download
- Reader: still AVIF pages (Android below 12) are decoded once by libavif into memory and served to the tiled viewer by cropping, instead of being re-encoded to a PNG file first. Pages too big for memory (decoded > 48 MB or low RAM) keep using the PNG copy. Works for plain files and cbz entries (also extensionless ones); filters apply as for any other format.
- Optimize / convert to WebP now also takes AVIF and JPEG XL pages (same resolution; for these a bigger WebP is accepted because WebP decodes faster and in regions).
- New setting "WebP quality: auto" (default on): re-encoded pages keep the source quality; lossless stays lossless; unknown / unreasonable (< 40) -> 95; PNG always lossy 98. AVIF quality is read from its AV1 header; JPEG XL quality is not readable yet (uses 95). Off: older fixed behaviour. Encoder: libwebp method 6 + SharpYUV.
- New setting "Convert pages to WebP when downloading" (default off): JPEG / PNG / AVIF / JXL pages are re-encoded as WebP during download (quality read per page from its header). Pages that do not get smaller are kept (except AVIF / JXL).
- Tests: `WebpPolicyTest`, `AvifBrandTest`.

## 2026-11-04g: auto mode: every truly lossless source (BMP, GIF, lossless WebP/AVIF/JXL) -> lossy q95; PNG stays q98; lossy keeps header/estimated quality (<40 or unknown -> 95). WebpPolicy.autoTarget + doc + strings.webp_quality_auto_summary + WebpPolicyTest updated. Fixed mode unchanged. Uncompiled.

## 2026-11-04h: T1 DONE: tap inversion (Off/L-R/T-B/Both) = pref reader_tap_inversion (AppSettings.readerTapInversion), TapGridArea.mirrored(), applied in ReaderControlDelegate.onGridTouch/onGridLongTouch (grid mirrored, actions untouched); pref_reader.xml ListPreference, arrays, strings; TapGridAreaTest. Finding: reader_taps_ltr only affects hardware keys/wheel (per its summary), taps in REVERSED mode follow PAGE_NEXT/PREV through fragment flip - unchanged. Uncompiled. Next: V1.

## 2026-11-04i: auto lossless->lossy already done (04g). V1 static audit OK: download flow DownloadWorker -> output.addPage(type=image/webp, part 0 single / i+1 split) -> LocalMangaZipOutput names base[_NN].ext (ext from MimeTypes or ImageFormatProbe, never extensionless); unconverted pages keep probed mime; temp files cleaned. No code change. Device-open test still pending (final tests). Next: N1 slider preview (optional), M3, M6, M12, reader_view lesson checks, remaining TESTS.

## 2026-11-04j: MANGAYOMI SCAN COMPLETE (100%): reader_view.dart lifecycle/dispose/retry/controller release, image_view_webtoon/paged (jump/post-frame/cacheExtent), page_states read; nothing new to import (wakelock/brightness/volume pause-resume = Android window flags in Next). Reader lessons vs Next: (1) live index OK (indexOfFirst by chapterId+index), (3) missing saved page -> snackbar, no crash, OK, (4) N/A on Android, (2) BUG FIXED: ReaderViewModel.loadImpl ignored loadSingleChapter()==false (chapter with 0 pages = blank reader, no error); now throws NoDataReceivedException -> error dialog/retry. Switch-chapter paths (lines ~361-420) already retried. Uncompiled. Next: remaining TESTS (ImageFormatProbe, WebpFrameSource, ZipEntryLocator, ZipOutput, PixelQuality), M3, M6, M12, N1.

## 2026-11-04k: TESTS added (uncompiled): core/image/ImageFormatProbeTest (JPEG baseline/progressive, PNG/APNG/alpha, GIF, WebP VP8L, JXL sniff, BMP, garbage, truncation no-throw, ext map, fileSize); SSIV library/src/test/.../ZipEntryLocatorTest (stored located+bytes equal, deflated/missing/empty/junk -> null) + library/build.gradle.kts testImplementation junit 4.13.2 (reaches app only with new SSIV tag). PixelQuality + WebpFrameSource need android.graphics (Bitmap/BitmapFactory) -> NOT JVM-testable, instrumented-only; ZipOutput needs Android too -> skipped. Next: M3 ReaderMotion curves, M6, M12, N1, then finish items.

## 2026-11-04l: M3 DONE (scoped): Android's RecyclerView/ViewPager2 already compute settle duration by distance/velocity (same formula as Mangayomi scroll(): (d/extent+1)*300 ms, max 2000), so only the CURVE differed: reader/ui/pager/ReaderMotion.quinticOut ((t-1)^5+1) now used for webtoon tap scroll (replaces DecelerateInterpolator) and double-page smooth scroll (replaces AccelerateDecelerate); ReaderMotionTest. Not changed: double-tap zoom durations (user setting doubleTapZoomDuration exists), snap helper, fling. Uncompiled. Next: M6 zoom-start verify, M12, N1.

## 2026-11-05a: M6 VERIFIED (ZoomStart AUTO/LEFT/RIGHT/CENTER via PageHolder.startX; used for open position, landscape auto-zoom; Reversed holder passes rtl=true). M12 AUDIT DONE: e-ink flash, webtoon side padding, reader-bar hide on scroll (onReaderScrolled), tap inversion (T1), keyboard zoom keys (+/-/ZOOM_IN/OUT in both scaling frames), auto-scroll (ScrollTimer) all exist -> nothing left. N1 slider thumbnail kept OPTIONAL/skipped (extra decode on 2 GB phone; ask user). No code change. Next: older items AUTO-6/7, INFO-EXTRAS, G3, MM, REDO, F1, AUD, CIYML(needs OK), NEON, SSIV-RELEASE, FINISH.

## 2026-11-05b: REDO part 1 (suggestions, LOCAL half only; uncompiled, parse-clean): FIXED BUG suggestions kept the LEAST relevant results (sortedBy asc + take(160) while DAO orders relevance DESC) -> now MMR diversify (descending). New suggestions/domain/SuggestionRanking.kt (isDropped: <=10% read + idle >21 d; distinctiveTags TF-IDF; weightedOverlap; diversify; idf), TagStatistics.kt (cached 10 min, @Singleton), TagsDao.countMangaPerTag/countTaggedManga + TagFrequency, HistoryRepository.isDropped(manga). Worker: seed = history minus dropped + favourites, minus NSFW when exclude-NSFW/NSFW disabled; tags = distinctiveTags (was takeMostFrequent); notification pick guarded for empty/small lists. SuggestionRankingTest. Community-server parts (SimilarWorkDto, CommunitySuggestionsSource, popularity damping, dropped penalty by server hits) SKIPPED per rule. REDO remaining: Related row local fallback, alternatives/source-replacement scope (preset/enabled/disabled), open exact suggested manga, webtoon seam/misalignment fixes (hand-merge with P9 edits), HistoryRepository chapter-id recovery (findSameChapter / findCachedChapter), MimeTypes/JXL verify. Local index (LocalMangaIndex) already present.

## 2026-11-05c: REDO part 2 (uncompiled, parse-clean): Related row local fallback = details/domain/RelatedMangaUseCase rewritten (source getRelated first; if <4 results: stored suggestions sharing tags (rarity-weighted overlap, min 2 shared unless <=2 tags), then ONE source search by rarest 1-2 tags, never popularity order; tags recovered from DB when details not loaded; NSFW filter; community server part dropped), SuggestionRepository.getTopList, RelatedListViewModel now uses the use case (full list = same as row). DetailsViewModel.doLoad default-branch fix (re-pick when stored-list branch missing from fresh list / not from history; replaces onEachWhile). REDO remaining: alternatives/source-replacement scope (preset/enabled/disabled), exact-manga open for suggestions, webtoon seam fixes (hand-merge with P9), HistoryRepository chapter-id recovery (findSameChapter/findCachedChapter), JXL verify (covers/downloads/local CBZ).

## 2026-11-05d: REDO part 3 WEBTOON SEAM FIX hand-merged (uncompiled, parse-clean, device-unverified): WebtoonFrameLayout.isRepinPending; WebtoonImageView (getScrollRange rounded like onMeasure = (sHeight/sWidth*width).toInt(); applyScroll() on onReady/onDownSamplingChanged/onSizeChanged; scrollToInternal keeps requested pos while not scalable + sets minimumScaleType CUSTOM); WebtoonHolder.onReady (restore -> scrollToAfterLayout, else recyclerView.onPageReady, no more 'top<0 -> end of page' jump, safe on settings change); WebtoonRecyclerView (consumeVerticalScroll walks ALL pages reached by scroll, scrollDirection tracking, onLayout->updateChildrenScroll, updateChildrenScroll pins start/end by position + skips pending repin pages, onPageReady/repinPage; old adjustScroll removed). Kept ours: canScale guard, recycle(), pendingScrollPos/scrollToAfterLayout, tile windowing, fast-scroll. RISK: setScaleAndCenter inside onSizeChanged/onLayout -> watch for layout loops / jitter on device (first thing to test: fling over short+tall pages). REDO remaining: alternatives/source-replacement scope, exact-manga open for suggestions, HistoryRepository chapter-id recovery (findSameChapter/findCachedChapter), JXL verify.

## 2026-11-05e: FMT matrix pass (all formats, one reusable guard): ImageFormatProbeTest +3 tests = table-driven over ImageFormat.entries (JPEG,PNG,GIF,WEBP,AVIF,JXL,BMP): sample header per format must sniff/probe to itself, ext+mime non-empty/unique/lowercase, ext round trip (case-insensitive), truncation never throws, name never decides; FAILS if a new ImageFormat is added without a sample. Static audit of the 4 surfaces (code read, no change needed): download name = content-probed mime then probe ext (never extensionless); BitmapDecoderCompat.probeMimeType content-first (ImageFormatProbe -> Files.probeContentType -> ext -> BitmapFactory); MimeTypes.EXTRA_TYPES covers jxl/avif/apng/bmp/heic/heif for ext->mime and mime->ext when Android/Coil tables lack them; local CBZ listing isImage() by extension via MimeTypes (extensionless entries rely on index.json page list; read path uses probe); reader chain Skia / libavif / JXL native; convert-to-WebP covers JPEG/PNG/BMP/GIF/AVIF/JXL; tall/wide split only JPEG/PNG/WEBP (by design). Not covered by JVM tests (Android classes): MimeTypes, decoders -> device test at FINISH with one real chapter per format. Uncompiled.

## 2026-11-05f: REDO part 4 (uncompiled, parse-clean): HISTORY CHAPTER-ID RECOVERY ported: core/model/Manga.kt findSameChapter (same url+branch, else unique volume+number in branch, else unique url; null when ambiguous), ChaptersDao.find, MangaDataRepository.findCachedChapter + keepHistoryChapter (runs inside storeManga/updateChapters before the chapter list is replaced: history moves to the same chapter under its new id), HistoryRepository.addOrUpdate resolves a replaced chapter id (older reader load) + recoverIfNeeded fixed: only recovers when the id is gone from the WHOLE list (old code looked only in the recovery branch and rewrote history of other-branch readers), tries findSameChapter first, then percent guess in the branch it was read in. 'Exact-manga open' = community-server only (resolveExact had only server callers) -> NOT APPLICABLE locally (our suggestions already open the stored Manga). REDO remaining: alternatives/source-replacement scope choice (preset/enabled/disabled sources), then FINISH.

## 2026-11-05g: REDO part 5 (uncompiled, parse-clean): SOURCE-SCOPE CHOICE ported. AlternativeSourceScope now sealed (Enabled / Disabled / Preset(id)); AlternativeSourceScopeOptions(defaultScope, presets, hasDisabledSources, isAvailable, presetTitle); AlternativesUseCase.getCandidateSources/getSourceScopeOptions (presets with >=1 candidate, default = active preset else enabled); new alternatives/ui/SourceScopeMenu.kt shared by AlternativesActivity/MenuProvider and SourceReplacementActivity; both ViewModels expose scopeOptions (activePresetTitle removed); menu XML submenu filled in code; string disabled_sources. REDO COMPLETE (all non-community Redo 9.8.5 items ported). Risk: uncompiled.
