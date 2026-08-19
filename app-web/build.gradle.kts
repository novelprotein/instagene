plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.kotlinxSerialization)
}

tasks.register<JavaExec>("runWeb") {
    group = "application"
    description = "Runs the web front-end directly."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.instagene.app.web.WebMainKt")
}
