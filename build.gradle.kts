import org.gradle.api.artifacts.ProjectDependency
import java.util.zip.ZipFile

val frontends = mapOf(
    "cli" to ":app-cli:runCli",
    "gui" to ":app-gui:runGui",
    "web" to ":app-web:runWeb",
)

plugins {
    base
}

tasks.register("run") {
    group = "application"
    description =
        "Runs a single InstaGene front-end selected by the 'platform' property " +
            "(cli|gui|web; default gui). Example: ./gradlew run -Pplatform=cli"
    val platform = providers.gradleProperty("platform").orElse("gui").get()
    require(frontends.containsKey(platform)) {
        "Unknown platform '$platform'. Choose one of: ${frontends.keys.sorted().joinToString()}"
    }
    dependsOn(frontends.getValue(platform))
}

// ------------------------------------------------------------- separation rules
// The engine is a reusable library and must stay 100% free of UI/app code; the
// three front-ends talk only to the engine, never to each other; and `:tests`
// is the only project wired to every front-end. These dependency rules are
// checked at configuration time, so any build (including CI) fails loudly.

val appModules = setOf(":app-cli", ":app-gui", ":app-web")

// Only inspect the standard user-facing configurations. Plugins like graalvmNative
// create internal configurations with self-dependencies that are not real violations.
private val userFacingConfigNames = setOf(
    "implementation", "api", "compileOnly", "compileOnlyApi",
    "runtimeOnly", "runtimeClasspath", "compileClasspath",
    // Test variants
    "testImplementation", "testCompileOnly", "testRuntimeOnly",
    "testRuntimeClasspath", "testCompileClasspath",
)

gradle.projectsEvaluated {
    appModules.forEach { app ->
        val badDeps = project(app).configurations
            .asSequence()
            .filter { it.name in userFacingConfigNames }
            .flatMap { it.dependencies }
            .filterIsInstance<ProjectDependency>()
            .map { it.path }
            .filter { it != ":engine" }
            .toList()
        check(badDeps.isEmpty()) {
            "Separation violation: $app depends on ${badDeps}; front-ends may depend only on :engine"
        }
    }

    val testsProjectDeps = project(":tests").configurations
        .asSequence()
        .filter { it.name in userFacingConfigNames }
        .flatMap { it.dependencies }
        .filterIsInstance<ProjectDependency>()
        .map { it.path }
        .toSet()
    check(appModules.all { it in testsProjectDeps }) {
        "Separation violation: :tests must depend on all front-ends (${appModules})"
    }
    println("verifySeparation: dependency rules OK (engine library, front-ends, :tests)")
}

// Source-level and jar-level scans, wired into `check` so `./gradlew check`
// (and therefore CI) runs them on every build.
// The action body deliberately references only its `task` parameter (never the
// build script object), which is what makes it serializable for the config cache.
tasks.register("verifySeparation") {
    group = "verification"
    description = "Enforces the engine/front-end separation: no app code in engine, " +
        "no cross-front-end references, engine jar contains only core classes."
    // Captured into the action below by value; `layout` here resolves to the
    // project layout because build scripts may not use `project` at execution
    // time with the configuration cache.
    val engineSource = layout.projectDirectory.dir("engine/src/main")
    val frontEndSource = mapOf(
        "app-cli" to layout.projectDirectory.dir("app-cli/src/main"),
        "app-gui" to layout.projectDirectory.dir("app-gui/src/main"),
        "app-web" to layout.projectDirectory.dir("app-web/src/main"),
    )
    val otherPackages = mapOf(
        "app-cli" to listOf("org.instagene.app.gui", "org.instagene.app.web"),
        "app-gui" to listOf("org.instagene.app.cli", "org.instagene.app.web"),
        "app-web" to listOf("org.instagene.app.cli", "org.instagene.app.gui"),
    )
    dependsOn(":engine:jar")
    inputs.files(project(":engine").tasks.named("jar").map { it.outputs.files })

    doLast(Action<Task> {
        val engineFiles = engineSource.asFileTree.matching { include("**/*.kt") }.files
        val engineOffenders = engineFiles.filter { it.readText().contains("org.instagene.app") }
        check(engineOffenders.isEmpty()) {
            "Separation violation: engine sources reference org.instagene.app " +
                "in ${engineOffenders.joinToString { it.path }}"
        }

        otherPackages.forEach { (app, banned) ->
            val hits = frontEndSource.getValue(app).asFileTree.matching { include("**/*.kt") }.files
                .filter { f -> banned.any { f.readText().contains(it) } }
            check(hits.isEmpty()) {
                "Separation violation: $app references $banned in ${hits.joinToString { it.path }}"
            }
        }

        val engineJar = ZipFile(this.inputs.files.singleFile)
        val appEntries = engineJar.entries().asSequence().map { it.name }
            .filter { it.startsWith("org/instagene/app/") }.toList()
        engineJar.close()
        check(appEntries.isEmpty()) {
            "Separation violation: engine jar contains app classes: $appEntries"
        }

        println("verifySeparation: OK (sources clean, engine jar contains only core classes)")
    })
}

tasks.named("check") {
    dependsOn("verifySeparation", "verifySourceHygiene")
}

/** Fails CI when unfinished review markers reach production sources. */
tasks.register("verifySourceHygiene") {
    group = "verification"
    description = "Rejects unfinished TODO/FIXME markers in production Kotlin sources."
    val productionSources = layout.projectDirectory.asFileTree.matching {
        include("engine/src/main/**/*.kt", "app-*/src/main/**/*.kt")
    }
    inputs.files(productionSources)
    doLast {
        val markers = listOf("TODO", "FIXME")
        val findings = productionSources.files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                markers.firstOrNull { marker -> line.contains(marker) }?.let { "$file:${index + 1}: $it" }
            }
        }
        check(findings.isEmpty()) { "Source hygiene failure:\n${findings.joinToString("\n")}" }
    }
}
