@file:Suppress("UnstableApiUsage")

// buildSrc is a nested build: with an explicit settings file it no longer
// inherits anything from the parent build, so the three things it needs are
// declared here:
//  1. toolchain repositories (foojay) - instead of Gradle's deprecated
//     auto-provisioning, so the JDK pinned by jvmToolchain() is resolved via
//     an explicit resolver rather than silently downloaded;
//  2. a repository for its own plugin/dependency resolution (it used to
//     inherit mavenCentral() from the implicit settings file);
//  3. the version catalog (normally auto-detected, but only when the implicit
//     settings file exists).
plugins {
    // No version: it is already on the classpath through the parent build, and
    // declaring a version here would fail compatibility resolution.
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
