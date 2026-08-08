package org.instagene.app.gui

import org.instagene.core.prefs.PrefsStore
import org.instagene.core.prefs.UserPrefs

/**
 * Mutable, app-wide prefs facade: a [UserPrefs] snapshot that panels read from
 * and write to through [update], which persists and notifies listeners.
 *
 * With a null [store] (the default used by tests and by panels constructed on
 * their own) updates are kept in memory only.
 */
class Prefs(private val store: PrefsStore? = null) {

    var value: UserPrefs = store?.load() ?: UserPrefs()
        private set

    private val listeners = ArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    /** Applies [transform], persists the result and notifies listeners. */
    fun update(transform: (UserPrefs) -> UserPrefs) {
        val next = transform(value)
        if (next == value) return
        value = next
        store?.save(next)
        listeners.toList().forEach { it() }
    }

    /** Persists the current snapshot (used on window close). */
    fun save() {
        store?.save(value)
    }
}
