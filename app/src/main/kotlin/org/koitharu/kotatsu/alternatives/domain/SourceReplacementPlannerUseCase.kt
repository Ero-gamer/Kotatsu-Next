package org.koitharu.kotatsu.alternatives.domain

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.roundToInt

private const val MAX_COMPARISON_SAMPLE = 25
private const val MAX_COMPARISON_PARALLELISM_PER_SOURCE = 2
private const val MAX_COMPARISON_TIMEOUTS_PER_SOURCE = 2
private const val COMPARISON_REQUEST_TIMEOUT_MS = 15_000L
private const val WEBVIEW_COMPARISON_REQUEST_TIMEOUT_MS = 30_000L

class SourceReplacementPlannerUseCase @Inject constructor(
	private val alternativesUseCase: AlternativesUseCase,
) {

	suspend operator fun invoke(
		manga: List<Manga>,
		sourceScope: AlternativeSourceScope,
		excludedSourceNames: Set<String> = emptySet(),
		onProgress: (SourceComparisonProgress) -> Unit = {},
		onSourceFailed: (MangaSource) -> Unit = {},
	): List<SourceReplacementPlan> = coroutineScope {
		val seeds = manga.distinctBy(Manga::id).representativeSample(MAX_COMPARISON_SAMPLE)
		if (seeds.isEmpty()) {
			return@coroutineScope emptyList()
		}
		val sources = alternativesUseCase.getCandidateSources(
			ref = seeds.first().source,
			sourceScope = sourceScope,
			sameLanguageOnly = true,
		).filter { it.name !in excludedSourceNames }
		val totalChecks = sources.size * seeds.size
		val progressLock = Any()
		var completedChecks = 0
		var completedSources = 0
		fun reportProgress(titleCompleted: Boolean = false, sourceCompleted: Boolean = false) {
			synchronized(progressLock) {
				if (titleCompleted) completedChecks++
				if (sourceCompleted) completedSources++
				onProgress(
					SourceComparisonProgress(
						completedChecks = completedChecks,
						totalChecks = totalChecks,
						remainingSources = sources.size - completedSources,
						totalSources = sources.size,
						sampleSize = seeds.size,
					),
				)
			}
		}
		reportProgress()
		val plans = sources.map { source ->
			async {
				val comparison = compareSource(source, seeds) {
					reportProgress(titleCompleted = true)
				}
				if (comparison.failed) {
					Log.w(
						TAG,
						"Excluding source after initial comparison failure: source=${source.name} partialMatches=${comparison.matches.size}/${seeds.size}",
					)
					synchronized(progressLock) {
						onSourceFailed(source)
					}
					null
				} else {
					SourceReplacementPlan(
						source = source,
						matches = comparison.matches.mapValues { it.value.manga },
						total = seeds.size,
						averageTitleSimilarity = comparison.matches.values
							.map(ScoredMatch::titleSimilarity)
							.takeIf(List<Float>::isNotEmpty)
							?.average()
							?.toFloat()
							?: 0f,
					)
				}.also { reportProgress(sourceCompleted = true) }
			}
		}.awaitAll().filterNotNull()
		plans.filter { it.matchedCount > 0 }.sortedWith(
			compareByDescending<SourceReplacementPlan> { it.matches.size }
				.thenByDescending(SourceReplacementPlan::averageTitleSimilarity),
		)
	}

	private suspend fun compareSource(
		source: MangaSource,
		seeds: List<Manga>,
		onTitleChecked: () -> Unit,
	): SourceComparisonResult {
		val isWebView = alternativesUseCase.isWebViewSource(source)
		val parallelism = if (isWebView) 1 else MAX_COMPARISON_PARALLELISM_PER_SOURCE
		val timeout = if (isWebView) {
			WEBVIEW_COMPARISON_REQUEST_TIMEOUT_MS
		} else {
			COMPARISON_REQUEST_TIMEOUT_MS
		}
		val matches = mutableMapOf<Long, ScoredMatch>()
		val initialSeed = seeds.first()
		val initialOutcome = compareTitle(
			source = source,
			seed = initialSeed,
			timeout = timeout,
			initialProbe = true,
		)
		onTitleChecked()
		initialOutcome.match?.let { match -> matches[match.seedId] = match }
		if (initialOutcome.failed) {
			repeat(seeds.size - 1) { onTitleChecked() }
			return SourceComparisonResult(matches = matches, failed = true)
		}
		var processed = 1
		var timeouts = 0
		for (batch in seeds.drop(1).chunked(parallelism)) {
			if (timeouts >= MAX_COMPARISON_TIMEOUTS_PER_SOURCE) {
				break
			}
			val outcomes = coroutineScope {
				batch.map { seed ->
					async {
						compareTitle(source, seed, timeout, initialProbe = false)
					}
				}.awaitAll()
			}
			processed += batch.size
			outcomes.forEach { outcome ->
				onTitleChecked()
				if (outcome.timedOut) {
					timeouts++
				}
				outcome.match?.let { match -> matches[match.seedId] = match }
			}
		}
		repeat(seeds.size - processed) { onTitleChecked() }
		return SourceComparisonResult(matches = matches, failed = false)
	}

	private suspend fun compareTitle(
		source: MangaSource,
		seed: Manga,
		timeout: Long,
		initialProbe: Boolean,
	): ComparisonOutcome {
		val requestFailed = AtomicBoolean(false)
		val completed = withTimeoutOrNull(timeout) {
			ComparisonOutcome(
				match = findMatch(
					seed = seed,
					source = source,
					loadDetails = false,
					onFailure = { requestFailed.set(true) },
				),
				timedOut = false,
				failed = requestFailed.get(),
			)
		}
		return completed ?: ComparisonOutcome(match = null, timedOut = true, failed = true).also {
			Log.w(
				TAG,
				(if (initialProbe) "Initial source probe timed out" else "Source comparison timed out") +
					": source=${source.name} title=\"${seed.title}\" id=${seed.id} timeoutMs=$timeout",
			)
		}
	}

	suspend fun resolve(
		manga: List<Manga>,
		source: MangaSource,
		knownMatches: Map<Long, Manga> = emptyMap(),
		onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
	): Map<Long, Manga> = coroutineScope {
		val seeds = manga.distinctBy(Manga::id)
		val seedIds = seeds.mapTo(hashSetOf(), Manga::id)
		val result = knownMatches.filterKeys(seedIds::contains).toMutableMap()
		val completed = AtomicInteger(result.size)
		onProgress(completed.get(), seeds.size)
		val resolved = seeds.filterNot { it.id in result }.map { seed ->
			async {
				findMatch(seed, source).also {
					onProgress(completed.incrementAndGet(), seeds.size)
				}
			}
		}.awaitAll()
		resolved.filterNotNull().forEach { match -> result[match.seedId] = match.manga }
		result
	}

	private suspend fun findMatch(
		seed: Manga,
		source: MangaSource,
		loadDetails: Boolean = true,
		onFailure: (() -> Unit)? = null,
	): ScoredMatch? {
		var best: ScoredMatch? = null
		alternativesUseCase.searchSource(
			manga = seed,
			source = source,
			loadDetails = loadDetails,
			onFailure = onFailure,
		).collect { candidate ->
			val scored = candidate.toScoredMatch(seed)
			if (best == null || scored.isBetterThan(checkNotNull(best))) {
				best = scored
			}
		}
		return best
	}

	private fun Manga.toScoredMatch(seed: Manga) = ScoredMatch(
		seedId = seed.id,
		manga = this,
		titleSimilarity = titleSimilarity(seed),
	)

	private fun ScoredMatch.isBetterThan(other: ScoredMatch): Boolean {
		return titleSimilarity > other.titleSimilarity ||
			(titleSimilarity == other.titleSimilarity && manga.chaptersCount() > other.manga.chaptersCount())
	}

	private fun Manga.titleSimilarity(reference: Manga): Float {
		val candidateTitles = (listOf(title) + altTitles).filter(String::isNotBlank)
		val referenceTitles = (listOf(reference.title) + reference.altTitles).filter(String::isNotBlank)
		return candidateTitles.maxOfOrNull { candidate ->
			referenceTitles.maxOfOrNull { ref -> normalizedSimilarity(candidate, ref) } ?: 0f
		} ?: 0f
	}

	private fun normalizedSimilarity(first: String, second: String): Float {
		val a = first.trim().lowercase(Locale.ROOT)
		val b = second.trim().lowercase(Locale.ROOT)
		val maxLength = maxOf(a.length, b.length)
		return if (maxLength == 0) 1f else 1f - a.levenshteinDistance(b).toFloat() / maxLength
	}

	private fun List<Manga>.representativeSample(limit: Int): List<Manga> {
		if (size <= limit) {
			return this
		}
		val sorted = sortedBy { it.title.lowercase(Locale.ROOT) }
		return List(limit) { index ->
			val position = index.toDouble() * sorted.lastIndex / (limit - 1)
			sorted[position.roundToInt()]
		}.distinctBy(Manga::id)
	}

	private data class ScoredMatch(
		val seedId: Long,
		val manga: Manga,
		val titleSimilarity: Float,
	)

	private data class ComparisonOutcome(
		val match: ScoredMatch?,
		val timedOut: Boolean,
		val failed: Boolean,
	)

	private data class SourceComparisonResult(
		val matches: Map<Long, ScoredMatch>,
		val failed: Boolean,
	)

	private companion object {
		const val TAG = "SourceReplacement"
	}
}

data class SourceReplacementPlan(
	val source: MangaSource,
	val matches: Map<Long, Manga>,
	val total: Int,
	val averageTitleSimilarity: Float,
) {
	val matchedCount: Int
		get() = matches.size

	val matchRate: Float
		get() = if (total == 0) 0f else matchedCount.toFloat() / total
}

data class SourceComparisonProgress(
	val completedChecks: Int,
	val totalChecks: Int,
	val remainingSources: Int,
	val totalSources: Int,
	val sampleSize: Int,
)
