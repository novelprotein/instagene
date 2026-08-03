plugins {
    kotlin("jvm")
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":utils"))
    // any other dependencies your CLI/GUI logic needs
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