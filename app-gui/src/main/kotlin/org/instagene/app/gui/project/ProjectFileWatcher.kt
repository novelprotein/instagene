package org.instagene.app.gui.project

import org.instagene.core.project.SeqProject
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Watches a project directory tree for file-system changes and triggers a
 * debounced [onChanged] callback on the EDT when anything changes.
 *
 * Subdirectories are registered as they appear so deeply nested changes are
 * caught without polling. The watcher ignores `.instagene/` entirely to avoid
 * refresh loops from our own saves.
 *
 * Usage: [start] / [stop]. The watcher is designed to be explicitly managed
 * (not auto-started from the constructor) so headless tests and short-lived
 * panels are unaffected.
 */
class ProjectFileWatcher(private val onChanged: () -> Unit) : AutoCloseable {

    private var watchService: WatchService? = null
    private var watcherThread: Thread? = null
    private var debounceTimer: Timer? = Timer(300) { fire() }.apply { isRepeats = false }

    /** Starts watching [root]. Idempotent — calling while already watching is a no-op. */
    fun start(root: File) {
        stop()
        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws
        registerRecursive(ws, root.toPath())
        val t = Thread({ pump(ws) }, "ProjectFileWatcher-${root.name}").apply {
            isDaemon = true
            start()
        }
        watcherThread = t
    }

    /** Stops watching. Safe to call multiple times. */
    fun stop() {
        watcherThread?.interrupt()
        watcherThread = null
        watchService?.close()
        watchService = null
    }

    override fun close() = stop()

    // --- internals ------------------------------------------------------------------

    private fun pump(ws: WatchService) {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val key: WatchKey = ws.take()
                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue
                    @Suppress("UNCHECKED_CAST")
                    val child = event.context() as? Path ?: continue
                    val dir = key.watchable() as? Path ?: continue
                    val changed = dir.resolve(child)
                    if (isIgnored(changed)) continue
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                        registerRecursive(ws, changed)
                    }
                    scheduleRefresh()
                }
                if (!key.reset()) break
            }
        } catch (_: InterruptedException) {
            // Shutting down — normal.
        } catch (_: java.nio.file.ClosedWatchServiceException) {
            // Shutting down — normal.
        }
    }

    private fun registerRecursive(ws: WatchService, dir: Path) {
        if (isIgnored(dir)) return
        try {
            dir.register(ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY,
            )
            if (Files.isDirectory(dir) && !Files.isSymbolicLink(dir)) {
                Files.list(dir).use { stream ->
                    stream.filter { Files.isDirectory(it) && !isIgnored(it) && !Files.isSymbolicLink(it) }
                        .forEach { registerRecursive(ws, it) }
                }
            }
        } catch (_: java.nio.file.ClosedWatchServiceException) {
            // Shutting down mid-registration — fine.
        }
    }

    private fun isIgnored(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        return name == SeqProject.MANIFEST_DIR || name.endsWith(".tmp")
    }

    /** Coalesce rapid-fire FS events into a single EDT refresh. */
    private fun scheduleRefresh() {
        SwingUtilities.invokeLater {
            debounceTimer?.let {
                it.stop()
                it.restart()
            }
        }
    }

    private fun fire() {
        if (SwingUtilities.isEventDispatchThread()) {
            onChanged()
        } else {
            SwingUtilities.invokeLater { onChanged() }
        }
    }
}
