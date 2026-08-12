dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":engine")
include(":app-cli")
include(":app-gui")
include(":app-web")
include(":tests")

rootProject.name = "instagene"
