package org.koitharu.kotatsu.core.prefs.mmkv

import android.os.Handler
import android.os.Looper

/** Runs change notifications the way `SharedPreferencesImpl` does: inline on the main thread,
 *  otherwise posted to it — listeners routinely touch UI. */
internal object MainThreadDispatcher {
    private val handler by lazy(LazyThreadSafetyMode.PUBLICATION) { Handler(Looper.getMainLooper()) }

    fun dispatch(task: Runnable) {
        if (Looper.myLooper() === Looper.getMainLooper()) task.run() else handler.post(task)
    }
}
