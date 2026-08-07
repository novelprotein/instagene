val frontends = mapOf(
    "cli" to ":app-cli:runCli",
    "gui" to ":app-gui:runGui",
    "web" to ":app-web:runWeb",
)

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
