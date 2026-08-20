plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    // Upgrade the Java plugin (applied by kotlin("jvm")) to java-library so
    // consumers get explicit `api`/`implementation` separation, a sources JAR, and
    // a Maven publication for GitHub Packages.
    `java-library`
    `maven-publish`
}

dependencies {
    // kotlinx-serialization for project persistence, coroutines for parallel engine computation.
    implementation(libs.kotlinxSerialization)
    api(libs.kotlinxCoroutines)
    testImplementation(kotlin("test"))
}

// ------------------------------------------------------------------ versioning
// The version is derived once from `instagene.version` (gradle.properties) plus
// the git state: tagged releases publish exactly `0.0.1`, every other build
// publishes `0.0.1-<sha>`. The generated `Version.VERSION` uses the SemVer
// build-metadata form (`0.0.1+<sha>`) for display.
//
// All closures below only capture provider parameters (never the build script
// itself), which keeps them serializable for the configuration cache.
val baseVersion = providers.gradleProperty("instagene.version").orElse("0.0.0")

val gitHead = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val tagsAtHead = providers.exec {
    commandLine("git", "tag", "--points-at", "HEAD")
}.standardOutput.asText.map { tags -> tags.lineSequence().map { it.trim() }.toList() }


val taggedAsRelease = providers.zip(tagsAtHead, baseVersion) { tags, base ->
    tags.any { it == base || it == "v$base" }
}

group = "org.instagene"
version = providers.zip(taggedAsRelease, baseVersion) { release, base ->
    if (release) {
        base
    } else {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.map { "$base-${it.trim().ifEmpty { "unknown" }}" }.get()
    }
}.get()

// Bake the version into `org.instagene.core.Version`, the single source that
// every front-end reads at runtime. Development builds carry the short git
// commit as SemVer build metadata (`0.0.1+abc1234`); a build whose HEAD is
// tagged `v<version>` (or `<version>`) is an official release and reports
// exactly `0.0.1`.
val generateVersion = tasks.register("generateVersion") {
    description = "Generates org.instagene.core.Version from instagene.version and the git state."
    val outDir = layout.buildDirectory.dir("generated/version")
    inputs.property("instagene.version", baseVersion)
    inputs.property("gitHead", gitHead)
    inputs.property("tagsAtHead", tagsAtHead)
    outputs.dir(outDir)
    doLast(Action<Task> {
        val base = this.inputs.properties["instagene.version"] as String
        val head = this.inputs.properties["gitHead"] as String
        val tags = (this.inputs.properties["tagsAtHead"] as List<*>).map { it.toString() }
        val release = tags.any { it == base || it == "v$base" }
        val version = if (release) base else "$base+$head"
        val source = outDir.get().dir("org/instagene/core").asFile.apply { mkdirs() }
        source.resolve("Version.kt").writeText(
            """
            package org.instagene.core

            /** The InstaGene version, set once in `gradle.properties` (`instagene.version`). */
            object Version {
                /** `$version` — see https://github.com/novelprotein/instagene. */
                const val VERSION: String = "$version"
            }
            """.trimIndent()
        )
    })
}

kotlin {
    sourceSets.main.get().kotlin.srcDir(generateVersion.map { it.outputs.files })
}

tasks.named("compileKotlin") {
    dependsOn(generateVersion)
}

// ------------------------------------------------------------ publishing
java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("engine") {
            from(components["java"])
            artifactId = "instagene-engine"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/novelprotein/instagene")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
