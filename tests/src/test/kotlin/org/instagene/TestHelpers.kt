package org.instagene

import java.awt.Component
import java.awt.Container
import java.io.File
import javax.swing.SwingUtilities

/**
 * Shared test utilities used across GUI and engine tests.
 */

/** Run [block] on the EDT and return its result. */
fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: T? = null
    var error: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (t: Throwable) {
            error = t
        }
    }
    error?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** Find all descendants of [root] matching [type]. */
fun <T : Component> descendants(root: Component, type: Class<T>): List<T> {
    val found = ArrayList<T>()
    fun visit(component: Component) {
        if (type.isInstance(component)) found += type.cast(component)
        if (component is Container) component.components.forEach(::visit)
    }
    visit(root)
    return found
}

/** Inline reified version of [descendants]. */
inline fun <reified T : Component> descendants(root: Component): List<T> =
    descendants(root, T::class.java)

/** Poll [condition] on the EDT until it returns true or [timeoutMs] elapses. */
fun awaitEdt(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        var met = false
        SwingUtilities.invokeAndWait { met = condition() }
        if (met) return true
        Thread.sleep(10)
    }
    return false
}

/** Create a temporary directory that is cleaned up after the test. */
fun tempRoot(): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "instagene-test-${System.nanoTime()}")
    check(dir.mkdirs()) { "Failed to create $dir" }
    dir.deleteOnExit()
    return dir
}
