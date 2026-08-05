plugins {
    kotlin("jvm")
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":utils"))
    implementation(libs.flatlaf)
    implementation(libs.flatlafIntelliJ)
}

application {
    // Kotlin compiles top-level `main()` in Main.kt into a class called MainKt
    mainClass.set("org.instagene.app.MainKt")
}

kotlin {
    // adjust to whatever JDK version you're targeting
}

tasks.test {
    useJUnitPlatform()
}