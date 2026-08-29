package org.koitharu.kotatsu.alternatives.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.AlternativeSourceScope
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityAlternativesBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListStateHolderListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.adapter.buttonFooterAD
import org.koitharu.kotatsu.list.ui.adapter.loadingFooterAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@AndroidEntryPoint
class SourceReplacementActivity : BaseActivity<ActivityAlternativesBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<MangaAlternativeModel> {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<SourceReplacementViewModel>()
	private var sourceMenuItem: MenuItem? = null
	private var sourceScopeMenuItem: MenuItem? = null
	private var presetScopeMenuItem: MenuItem? = null
	private var enabledScopeMenuItem: MenuItem? = null
	private var searchMenuItem: MenuItem? = null
	private var nextSourceMenuItem: MenuItem? = null
	private var skipMenuItem: MenuItem? = null
	private var isShowingSummary = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityAlternativesBinding.inflate(layoutInflater))
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		viewBinding.progressSources.isVisible = false
		viewBinding.scrollViewSummary.isVisible = false
		val listAdapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.MANGA_LIST_DETAILED, alternativeAD(coil, this, this))
			.addDelegate(ListItemType.SOURCE_REPLACEMENT, sourceReplacementAD(viewModel::selectSource))
			.addDelegate(
				ListItemType.STATE_EMPTY,
				sourceReplacementEmptyStateAD(
					onEditTitle = { searchMenuItem?.expandActionView() },
					onSkip = viewModel::skipCurrent,
				),
			)			.addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
			.addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
			.addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(this))
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, addHorizontalPadding = false))
			adapter = listAdapter
		}

		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		viewModel.list.observe(this, listAdapter)
		viewModel.currentManga.observe(this) {
			updateHeader()
		}
		viewModel.queuePosition.observe(this) {
			updateHeader()
		}
		viewModel.queueSize.observe(this) {
			updateHeader()
		}
		viewModel.selectedSource.observe(this) {
			updateHeader()
		}
		viewModel.matchedCount.observe(this) {
			updateHeader()
		}
		viewModel.isPlanning.observe(this) {
			updateHeader()
			updatePlanningProgress()
		}
		viewModel.planningStep.observe(this) {
			updatePlanningProgress()
		}
		viewModel.planningProgress.observe(this) {
			updateHeader()
			updatePlanningProgress()
		}
		viewModel.planningTotal.observe(this) {
			updateHeader()
			updatePlanningProgress()
		}
		viewModel.planningSourcesRemaining.observe(this) {
			updatePlanningProgress()
		}
		viewModel.planningSampleSize.observe(this) {
			updatePlanningProgress()
		}
		viewModel.planningCurrentSource.observe(this) {
			updatePlanningProgress()
		}
		viewModel.isManualMode.observe(this) {
			updateHeader()
		}
		viewModel.isComparingSources.observe(this) {
			updateHeader()
		}
		viewModel.sourceScope.observe(this) {
			updateSourceScopeMenu()
		}
		viewModel.activePresetTitle.observe(this) {
			updateSourceScopeMenu()
		}
		viewModel.onMigrated.observeEvent(this) { result ->
			if (result.remaining > 0) {
				Toast.makeText(this, R.string.migration_completed, Toast.LENGTH_SHORT).show()
			}
		}
		viewModel.onBatchMigrated.observeEvent(this) { result ->
			if (result.remaining > 0) {
				val message = getString(
					R.string.source_replacement_batch_completed,
					result.replaced,
					result.remaining,
				)
				Toast.makeText(this, message, Toast.LENGTH_LONG).show()
			}
		}
		viewModel.replacementSummary.observe(this) { summary ->
			summary?.let(::showReplacementSummary)
		}
		viewModel.onPlanReady.observeEvent(this) {
			viewModel.showSourceComparison()
		}
		updateHeader()
		updatePlanningProgress()
	}

	override fun onStop() {
		if (isFinishing) {
			viewModel.cancelPendingChecks()
		}
		super.onStop()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_alternatives, menu)
		sourceMenuItem = menu.findItem(R.id.action_choose_source)
		searchMenuItem = menu.add(Menu.NONE, R.id.action_search, Menu.NONE, R.string.search).apply {
			setIcon(androidx.appcompat.R.drawable.abc_ic_search_api_material)
			setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
			actionView = SearchView(this@SourceReplacementActivity).apply {
				queryHint = getString(R.string.search_manga)
				setOnQueryTextListener(object : SearchView.OnQueryTextListener {
					override fun onQueryTextSubmit(query: String?): Boolean {
						viewModel.search(query.orEmpty())
						clearFocus()
						return true
					}

					override fun onQueryTextChange(newText: String?): Boolean = false
				})
			}
			setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
				override fun onMenuItemActionExpand(item: MenuItem): Boolean {
					(item.actionView as? SearchView)?.setQuery(viewModel.manga.title, false)
					return true
				}

				override fun onMenuItemActionCollapse(item: MenuItem): Boolean = true
			})
		}
		nextSourceMenuItem = menu.add(Menu.NONE, ACTION_NEXT_SOURCE, Menu.NONE, R.string.next).apply {
			setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
		}
		skipMenuItem = menu.add(Menu.NONE, ACTION_SKIP, Menu.NONE, R.string.skip).apply {
			setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
		}
		val scopeMenu = menu.addSubMenu(
			Menu.NONE,
			ACTION_SOURCE_SCOPE,
			Menu.NONE,
			R.string.preset_sources,
		)
		sourceScopeMenuItem = scopeMenu.item.apply {
			setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
		}
		presetScopeMenuItem = scopeMenu.add(
			SOURCE_SCOPE_GROUP,
			ACTION_SCOPE_PRESET,
			Menu.NONE,
			R.string.source_presets,
		)
		enabledScopeMenuItem = scopeMenu.add(
			SOURCE_SCOPE_GROUP,
			ACTION_SCOPE_ENABLED,
			Menu.NONE,
			R.string.enabled_sources,
		)
		scopeMenu.setGroupCheckable(SOURCE_SCOPE_GROUP, true, true)
		updateSourceScopeMenu()
		updateHeader()
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			finishAfterTransition()
			true
		}

		R.id.action_choose_source -> {
			viewModel.showSourceComparison()
			true
		}

		ACTION_NEXT_SOURCE -> {
			viewModel.selectNextSource()
			true
		}

		ACTION_SKIP -> {
			viewModel.skipCurrent()
			true
		}

		ACTION_SCOPE_PRESET -> {
			viewModel.selectSourceScope(AlternativeSourceScope.CURRENT_PRESET)
			true
		}

		ACTION_SCOPE_ENABLED -> {
			viewModel.selectSourceScope(AlternativeSourceScope.ENABLED)
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat,
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.recyclerView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.summaryContainer.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			top = barsInsets.top,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onItemClick(item: MangaAlternativeModel, view: View) {
		when (view.id) {
			R.id.chip_source -> viewModel.selectSource(item.manga.source)
			R.id.button_migrate -> confirmMigration(item.manga)
			else -> router.openDetails(item.manga)
		}
	}

	override fun onRetryClick(error: Throwable) = viewModel.retry()

	override fun onEmptyActionClick() {
		if (viewModel.selectedSource.value == null) {
			viewModel.showSourceComparison()
		} else {
			searchMenuItem?.expandActionView()
		}
	}

	override fun onFooterButtonClick() {
		if (viewModel.isComparingSources.value) {
			viewModel.skipRemaining()
		} else {
			viewModel.skipCurrent()
		}
	}
	private fun confirmMigration(target: Manga) {
		val source = viewModel.manga
		buildAlertDialog(this, isCentered = true) {
			setIcon(R.drawable.ic_replace)
			setTitle(R.string.replace_source)
			setMessage(
				getString(
					R.string.migrate_confirmation,
					source.title,
					source.source.getTitle(context),
					target.title,
					target.source.getTitle(context),
				),
			)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.replace) { _, _ ->
				viewModel.migrate(target)
			}
		}.show()
	}

	private fun updateHeader() {
		if (isShowingSummary) {
			supportActionBar?.subtitle = null
			sourceMenuItem?.isVisible = false
			sourceScopeMenuItem?.isVisible = false
			searchMenuItem?.isVisible = false
			nextSourceMenuItem?.isVisible = false
			skipMenuItem?.isVisible = false
			return
		}
		val total = viewModel.queueSize.value
		if (viewModel.isPlanning.value) {
			supportActionBar?.subtitle = null
			sourceMenuItem?.isEnabled = false
			sourceScopeMenuItem?.isEnabled = false
			searchMenuItem?.isVisible = false
			nextSourceMenuItem?.isVisible = false
			skipMenuItem?.isVisible = false
			return
		}
		if (viewModel.isComparingSources.value) {
			supportActionBar?.subtitle = getString(R.string.compare_source_matches)
			sourceMenuItem?.isVisible = false
			searchMenuItem?.isVisible = false
			nextSourceMenuItem?.isVisible = false
			skipMenuItem?.isVisible = viewModel.isManualMode.value
			return
		}
		sourceMenuItem?.isVisible = true
		sourceScopeMenuItem?.isVisible = true
		sourceScopeMenuItem?.isEnabled = true
		val manual = viewModel.isManualMode.value
		searchMenuItem?.isVisible = manual && viewModel.selectedSource.value != null
		nextSourceMenuItem?.isVisible = manual
		skipMenuItem?.isVisible = manual
		val target = viewModel.selectedSource.value?.getTitle(this) ?: getString(R.string.top_source_matches)
		val manga = viewModel.manga
		supportActionBar?.subtitle = if (total > 1) {
			getString(
				R.string.source_replacement_subtitle_batch,
				manga.title,
				viewModel.queuePosition.value,
				total,
				target,
			)
		} else {
			getString(R.string.source_replacement_subtitle, manga.title, target)
		}
		sourceMenuItem?.title = if (viewModel.selectedSource.value == null) {
			getString(R.string.choose_source)
		} else {
			getString(R.string.source_selected, target)
		}
		sourceMenuItem?.isEnabled = true
	}

	private fun updateSourceScopeMenu() {
		val presetTitle = viewModel.activePresetTitle.value
		presetScopeMenuItem?.apply {
			isVisible = presetTitle != null
			title = presetTitle ?: getString(R.string.source_presets)
			isChecked = viewModel.sourceScope.value == AlternativeSourceScope.CURRENT_PRESET
		}
		enabledScopeMenuItem?.isChecked = viewModel.sourceScope.value == AlternativeSourceScope.ENABLED
		val selectedTitle = when (viewModel.sourceScope.value) {
			AlternativeSourceScope.CURRENT_PRESET -> presetTitle ?: getString(R.string.source_presets)
			AlternativeSourceScope.ENABLED -> getString(R.string.enabled_sources)
		}
		sourceScopeMenuItem?.title = getString(R.string.preset_sources) + ": " + selectedTitle
	}

	private fun showReplacementSummary(summary: SourceReplacementViewModel.ReplacementSummary) {
		isShowingSummary = true
		viewBinding.recyclerView.isVisible = false
		viewBinding.planningProgressContainer.isVisible = false
		viewBinding.summaryContainer.isVisible = true
		supportActionBar?.title = getString(R.string.source_replacement_summary_title)
		supportActionBar?.subtitle = null
		sourceMenuItem?.isVisible = false

		viewBinding.textViewSummaryTitle.setText(
			when (summary.replaced) {
				0 -> R.string.source_replacement_summary_zero_title
				1 -> R.string.source_replacement_summary_one_title
				else -> R.string.source_replacement_summary_many_title
			},
		)
		viewBinding.textViewSummaryMessage.text = when (summary.replaced) {
			0 -> getString(R.string.source_replacement_summary_zero_message)
			1 -> getString(R.string.source_replacement_summary_one_message)
			else -> getString(R.string.source_replacement_summary_many_message, summary.replaced)
		}
		viewBinding.textViewSummaryReplaced.text = summary.replaced.toString()
		viewBinding.textViewSummarySources.text = summary.sources.size.toString()
		viewBinding.summaryMixContainer.isVisible = summary.sources.isNotEmpty()
		viewBinding.textViewSummaryMix.text = summary.sources.joinToString(separator = " • ") {
			getString(
				R.string.source_replacement_summary_source_item,
				it.source.getTitle(this),
				it.count,
			)
		}
		viewBinding.buttonSummaryDone.setOnClickListener {
			finishAfterTransition()
		}
	}

	private fun updatePlanningProgress() {
		val planning = viewModel.isPlanning.value
		viewBinding.planningProgressContainer.isVisible = planning
		if (!planning) {
			return
		}
		val completed = viewModel.planningProgress.value
		val total = viewModel.planningTotal.value
		val remaining = (total - completed).coerceAtLeast(0)
		viewBinding.textViewPlanningStep.text = when (viewModel.planningStep.value) {
			SourceReplacementStep.COMPARING_SOURCES -> if (total == 0) {
				getString(R.string.source_replacement_step_preparing)
			} else {
				getString(R.string.source_replacement_step_comparing, viewModel.planningSampleSize.value)
			}

			SourceReplacementStep.MATCHING_TITLES -> getString(
				R.string.source_replacement_step_matching,
				viewModel.selectedSource.value?.getTitle(this) ?: getString(R.string.automatic),
			)

			SourceReplacementStep.SMART_MIX -> getString(
				R.string.source_replacement_step_smart_mix,
				viewModel.planningCurrentSource.value?.getTitle(this) ?: getString(R.string.automatic),
			)

			SourceReplacementStep.APPLYING_MATCHES -> getString(R.string.source_replacement_step_applying)
		}
		viewBinding.textViewPlanningRemaining.text = when (viewModel.planningStep.value) {
			SourceReplacementStep.COMPARING_SOURCES -> if (total == 0) {
				null
			} else {
				getString(
					R.string.source_replacement_remaining_checks,
					viewModel.planningSourcesRemaining.value,
					remaining,
				)
			}

			SourceReplacementStep.MATCHING_TITLES -> getString(
				R.string.source_replacement_remaining_titles,
				remaining,
			)

			SourceReplacementStep.SMART_MIX -> getString(
				R.string.source_replacement_remaining_mix,
				viewModel.planningSourcesRemaining.value,
				remaining,
			)

			SourceReplacementStep.APPLYING_MATCHES -> getString(
				R.string.source_replacement_remaining_replacements,
				remaining,
			)
		}
		with(viewBinding.planningProgressBar) {
			if (total == 0) {
				isIndeterminate = true
			} else {
				isIndeterminate = false
				max = total
				setProgressCompat(completed.coerceIn(0, total), true)
			}
		}
	}

	private companion object {
		const val ACTION_SOURCE_SCOPE = Menu.FIRST + 1000
		const val ACTION_SCOPE_PRESET = Menu.FIRST + 1001
		const val ACTION_SCOPE_ENABLED = Menu.FIRST + 1002
		const val SOURCE_SCOPE_GROUP = Menu.FIRST + 1003
		const val ACTION_NEXT_SOURCE = Menu.FIRST + 1004
		const val ACTION_SKIP = Menu.FIRST + 1005
	}
}
