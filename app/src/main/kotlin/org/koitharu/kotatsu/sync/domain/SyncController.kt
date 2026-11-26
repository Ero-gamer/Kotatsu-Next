package org.koitharu.kotatsu.sync.domain

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountManagerCallback
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SyncController
@Inject
constructor(
    @ApplicationContext context: Context,
    private val dbProvider: Provider<MangaDatabase>,
    private val settings: AppSettings,
) : InvalidationTracker.Observer(arrayOf(TABLE_HISTORY, TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES)) {
    private val authorityHistory = context.getString(R.string.sync_authority_history)
    private val authorityFavourites = context.getString(R.string.sync_authority_favourites)
    private val am = AccountManager.get(context)
    private val accountType = context.getString(R.string.account_type_sync)
    private val mutex = Mutex()
    private val pendingFavourites = AtomicBoolean(false)
    private val pendingHistory = AtomicBoolean(false)
    private val syncScheduled = AtomicBoolean(false)
    private val defaultGcPeriod = TimeUnit.DAYS.toMillis(2) // gc period if sync disabled

    override fun onInvalidated(tables: Set<String>) {
        val favourites =
            (TABLE_FAVOURITES in tables || TABLE_FAVOURITE_CATEGORIES in tables) &&
                !isSyncActiveOrPending(authorityFavourites)
        val history = TABLE_HISTORY in tables && !isSyncActiveOrPending(authorityHistory)
        if (favourites || history) {
            requestSync(favourites, history)
        }
    }

    fun addAccount(
        activity: Activity,
        callback: (account: Account?) -> Unit,
    ) {
        am.addAccount(accountType, accountType, null, null, activity, { _ -> onAccountUpdated(callback) }, null)
    }

    fun removeAccount(
        activity: Activity,
        account: Account,
        callback: (account: Account?) -> Unit,
    ) {
        val handler = AccountManagerCallback<Bundle> { _ -> onAccountUpdated(callback) }
        am.removeAccount(account, activity, handler, null)
    }

    private fun onAccountUpdated(callback: (account: Account?) -> Unit) {
        val account = am.getAccountsByType(accountType).firstOrNull()

        if (account != null) {
            setEnabled(account, syncFavorites = true, syncHistory = true)
            processLifecycleScope.launch(Dispatchers.IO) {
                requestFullSync()
            }
        }

        callback(account)
    }

    fun setEnabled(
        account: Account,
        syncFavorites: Boolean,
        syncHistory: Boolean,
    ) {
        ContentResolver.setSyncAutomatically(account, authorityFavourites, syncFavorites)
        ContentResolver.setSyncAutomatically(account, authorityHistory, syncHistory)
        updatePeriodicSync(account, authorityFavourites, syncFavorites)
        updatePeriodicSync(account, authorityHistory, syncHistory)
    }

    /**
     * Brings the registered periodic syncs in line with the current settings.
     *
     * Idempotent, and safe to call with no account. Needed on startup as well as on a settings change:
     * periodic syncs live in the system, not in the app, so an install that predates this - or one
     * whose account was added before it - would otherwise never register one.
     */
    fun updateSyncSchedule() {
        val account = peekAccount() ?: return
        for (authority in arrayOf(authorityFavourites, authorityHistory)) {
            updatePeriodicSync(account, authority, ContentResolver.getSyncAutomatically(account, authority))
        }
    }

    /**
     * Without this the app only ever syncs when it writes to a synced table, so changes made on another
     * device never arrive until something changes locally.
     *
     * The period is a hint: the system batches periodic syncs with other work and defers them under
     * Doze, so one runs roughly, not exactly, this often.
     */
    private fun updatePeriodicSync(
        account: Account,
        authority: String,
        isEnabled: Boolean,
    ) {
        val periodSeconds = TimeUnit.HOURS.toSeconds(settings.syncPeriodHours.toLong())
        // A fresh Bundle rather than Bundle.EMPTY: the periodic sync extras are retained by the system
        // and matched on later, and Bundle.EMPTY is an immutable shared instance. An empty bundle still
        // matches an empty bundle, so add and remove continue to refer to the same periodic sync.
        // addPeriodicSync replaces any existing entry with matching extras, so this doubles as an update.
        if (isEnabled && periodSeconds > 0L) {
            ContentResolver.addPeriodicSync(account, authority, Bundle(), periodSeconds)
        } else {
            ContentResolver.removePeriodicSync(account, authority, Bundle())
        }
    }

    fun isEnabled(account: Account): Boolean = ContentResolver.getMasterSyncAutomatically() && (
        ContentResolver.getSyncAutomatically(
            account,
            authorityFavourites,
        ) ||
            ContentResolver.getSyncAutomatically(
                account,
                authorityHistory,
            )
        )

    fun isFavouritesEnabled(account: Account): Boolean = ContentResolver.getMasterSyncAutomatically() &&
        ContentResolver.getSyncAutomatically(
            account,
            authorityFavourites,
        )

    fun isHistoryEnabled(account: Account): Boolean = ContentResolver.getMasterSyncAutomatically() &&
        ContentResolver.getSyncAutomatically(
            account,
            authorityHistory,
        )

    suspend fun requestFullSync() = withContext(Dispatchers.Default) {
        requestSyncImpl(favourites = true, history = true)
    }

    /**
     * Every history write (each page turn) invalidates the tables: requests are merged and sent once, after
     * a quiet [SYNC_DEBOUNCE_MS], instead of waking the sync adapter each time.
     */
    private fun requestSync(
        favourites: Boolean,
        history: Boolean,
    ) {
        if (favourites) pendingFavourites.set(true)
        if (history) pendingHistory.set(true)
        if (syncScheduled.compareAndSet(false, true)) {
            processLifecycleScope.launch(Dispatchers.Default) {
                delay(SYNC_DEBOUNCE_MS)
                // Clear before reading the flags: a request arriving now schedules the next run.
                syncScheduled.set(false)
                requestSyncImpl(
                    favourites = pendingFavourites.getAndSet(false),
                    history = pendingHistory.getAndSet(false),
                )
            }
        }
    }

    private suspend fun requestSyncImpl(
        favourites: Boolean,
        history: Boolean,
    ) = mutex.withLock {
        if (!favourites && !history) {
            return
        }
        val account = peekAccount()
        if (account == null) {
            dbProvider.get().gc(favourites, history)
            return
        }
        // With an account, what was removed is only cleared out by the sync that has sent it. A removal is a mark on
        // the row until then, and clearing marked rows out while syncing was switched off, for the phone or for this
        // account, left the server with the manga still on it: the next sync that did run brought it back, two days
        // after it was removed.
        if (!ContentResolver.getMasterSyncAutomatically()) {
            return
        }
        if (favourites && ContentResolver.getSyncAutomatically(account, authorityFavourites)) {
            ContentResolver.requestSync(account, authorityFavourites, Bundle.EMPTY)
        }
        if (history && ContentResolver.getSyncAutomatically(account, authorityHistory)) {
            ContentResolver.requestSync(account, authorityHistory, Bundle.EMPTY)
        }
    }

    /**
     * Up to when a removal from favourites may already have reached the server: the time of the last sync, or any
     * time at all while one is under way or waiting. Zero with no account, as nothing has gone anywhere.
     */
    fun getFavouritesSyncedUntil(): Long {
        val account = peekAccount() ?: return 0L
        return if (isSyncActiveOrPending(authorityFavourites)) {
            Long.MAX_VALUE
        } else {
            getLastSync(account, authorityFavourites)
        }
    }

    private fun getLastSync(account: Account, authority: String): Long = am.getUserData(account, lastSyncKey(authority))?.toLongOrNull() ?: 0L

    private fun peekAccount(): Account? = am.getAccountsByType(accountType).firstOrNull()

    private suspend fun MangaDatabase.gc(
        favourites: Boolean,
        history: Boolean,
    ) = withTransaction {
        val deletedAt = System.currentTimeMillis() - defaultGcPeriod
        if (history) {
            getHistoryDao().gc(deletedAt)
        }
        if (favourites) {
            getFavouritesDao().gc(deletedAt)
            getFavouriteCategoriesDao().gc(deletedAt)
        }
    }

    private fun isSyncActiveOrPending(authority: String): Boolean {
        val account = peekAccount() ?: return false
        return ContentResolver.isSyncActive(account, authority) || ContentResolver.isSyncPending(account, authority)
    }

    companion object {
        private const val SYNC_DEBOUNCE_MS = 30_000L

        @JvmStatic
        fun setLastSync(
            context: Context,
            account: Account,
            authority: String,
            time: Long,
        ) {
            val am = AccountManager.get(context)
            am.setUserData(account, lastSyncKey(authority), time.toString())
        }

        private fun lastSyncKey(authority: String) = "last_sync_" + authority.substringAfterLast('.')
    }
}
