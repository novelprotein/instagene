package org.instagene.app.gui.theme

import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes
import java.io.File
import java.net.URLDecoder
import java.util.jar.JarFile
import javax.swing.LookAndFeel
import javax.swing.UIManager

/**
 * Registry of selectable FlatLaf themes and the runtime switching logic.
 *
 * All themes are discovered at startup from the classpath: core FlatLaf
 * themes are found by scanning for [FlatLaf] subclasses, and IntelliJ
 * community themes from [FlatAllIJThemes.INFOS].
 */
object ThemeManager {

    /** Default theme id, used until the user selects another. */
    const val DEFAULT_THEME = "FlatDarculaLaf"

    /** Previous app default, migrated so a fresh install does not stay on Dracula. */
    const val LEGACY_DEFAULT_THEME = "FlatDraculaIJTheme"

    /** A selectable theme and the factory for its look and feel. */
    data class Theme(
        val id: String,
        val displayName: String,
        val dark: Boolean,
        val create: () -> LookAndFeel,
    )

    /** All selectable themes, in menu order. */
    val themes: List<Theme> = buildThemes()

    private fun buildThemes(): List<Theme> {
        val seen = mutableSetOf<String>()
        val core = discoverCoreThemes(seen)
        val ij = discoverIJThemes(seen)
        return core + ij
    }

    private fun discoverCoreThemes(seen: MutableSet<String>): List<Theme> {
        val packages = listOf("com.formdev.flatlaf", "com.formdev.flatlaf.themes")
        val classNames = packages.flatMap { pkg ->
            classpathSubclasses(pkg, FlatLaf::class.java)
        }
        return classNames.mapNotNull { name ->
            runCatching {
                val cls = Class.forName(name)
                if (!FlatLaf::class.java.isAssignableFrom(cls)) return@runCatching null
                if (java.lang.reflect.Modifier.isAbstract(cls.modifiers)) return@runCatching null
                if (cls.getPackage()?.name?.startsWith("com.formdev.flatlaf.intellijthemes") == true) return@runCatching null
                val laf = cls.getConstructor().newInstance() as? FlatLaf ?: return@runCatching null
                val id = cls.simpleName
                if (!seen.add(id)) return@runCatching null
                val orig = UIManager.getLookAndFeel()
                UIManager.setLookAndFeel(laf)
                val dark = FlatLaf.isLafDark()
                UIManager.setLookAndFeel(orig)
                Theme(
                    id = id,
                    displayName = laf.name ?: id,
                    dark = dark,
                    create = { cls.getConstructor().newInstance() as LookAndFeel },
                )
            }.getOrNull()
        }.sortedWith(compareBy<Theme> { it.dark }.thenBy { it.displayName })
    }

    private fun discoverIJThemes(seen: MutableSet<String>): List<Theme> {
        val ijThemes = FlatAllIJThemes.INFOS.mapNotNull { info ->
            runCatching {
                val cls = Class.forName(info.className)
                val laf = cls.getConstructor().newInstance() as? LookAndFeel ?: return@runCatching null
                val simpleName = cls.simpleName
                val id = if (seen.add(simpleName)) simpleName
                else {
                    val pkg = cls.`package`?.name.orEmpty()
                    val suffix = pkg.substringAfterLast('.').ifEmpty { "extra" }
                    val disambiguated = "$simpleName ($suffix)"
                    seen.add(disambiguated)
                    disambiguated
                }
                Theme(
                    id = id,
                    displayName = info.name,
                    dark = info.isDark,
                    create = { laf },
                )
            }.getOrNull()
        }
        val light = ijThemes.filter { !it.dark }.sortedBy { it.displayName }
        val dark = ijThemes.filter { it.dark }.sortedBy { it.displayName }
        return light + dark
    }

    /** Scans the classpath for direct subclasses of [base] under [pkg]. */
    private fun <T : Any> classpathSubclasses(pkg: String, base: Class<T>): List<String> {
        val path = pkg.replace('.', '/')
        val result = mutableListOf<String>()
        val resources = Thread.currentThread().contextClassLoader.getResources(path)
        while (resources.hasMoreElements()) {
            val url = resources.nextElement()
            when (url.protocol) {
                "file" -> scanDir(File(URLDecoder.decode(url.path, "UTF-8")), path, base, result)
                "jar" -> scanJar(url, path, base, result)
            }
        }
        return result
    }

    private fun <T : Any> scanDir(dir: File, pkgPath: String, base: Class<T>, out: MutableList<String>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) {
                scanDir(f, "$pkgPath/${f.name}", base, out)
            } else if (f.name.endsWith(".class")) {
                val name = "$pkgPath/${f.name.removeSuffix(".class")}"
                    .replace('/', '.')
                runCatching { Class.forName(name) }
                    .getOrNull()?.let { if (base.isAssignableFrom(it)) out += name }
            }
        }
    }

    private fun <T : Any> scanJar(url: java.net.URL, pkgPath: String, base: Class<T>, out: MutableList<String>) {
        val jarPath = URLDecoder.decode(url.path.substringAfter("file:"), "UTF-8")
            .substringBeforeLast("!")
        val jar = runCatching { JarFile(File(jarPath)) }.getOrNull() ?: return
        jar.use { j ->
            j.entries().asIterator().forEach { entry ->
                val name = entry.name
                if (name.startsWith("$pkgPath/") && name.endsWith(".class")) {
                    val fqName = name.removeSuffix(".class").replace('/', '.')
                    runCatching { Class.forName(fqName) }
                        .getOrNull()?.let { if (base.isAssignableFrom(it)) out += fqName }
                }
            }
        }
    }

    private var applied: String? = null

    /** The id of the last successfully applied theme. */
    fun current(): String = applied ?: DEFAULT_THEME

    /** Maps the retired Dracula default to Darcula without changing any other user choice. */
    fun migrateLegacyDefault(id: String): String =
        if (id == LEGACY_DEFAULT_THEME) DEFAULT_THEME else id

    /** The theme with [id], or null when unknown. */
    fun theme(id: String): Theme? = themes.firstOrNull { it.id == id }

    /** Human-readable name for the theme with [id]; the id itself when unknown. */
    fun displayName(id: String): String = theme(id)?.displayName ?: id

    /** True when the theme with [id] is dark; false for unknown ids. */
    fun isDark(id: String): Boolean = theme(id)?.dark == true

    /**
     * Installs [id] as the active theme and updates every open window.
     * Returns false (leaving the current theme in place) if [id] is unknown
     * or the look-and-feel cannot be installed.
     */
    fun apply(id: String): Boolean {
        val target = theme(id) ?: return false
        val installed = runCatching { UIManager.setLookAndFeel(target.create()) }
        if (installed.isFailure) {
            System.err.println("instagene: failed to install theme '${target.displayName}': ${installed.exceptionOrNull()?.message}")
            return false
        }
        applied = id
        runCatching { FlatLaf.updateUI() }
            .onFailure { System.err.println("instagene: theme '${target.displayName}' installed but UI refresh failed: ${it.message}") }
        return true
    }
}
