package buildsrc.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

object MacPackageVersion {
    fun requireValid(version: String): String {
        require(Regex("^[1-9][0-9]*(\\.[0-9]+){0,2}$").matches(version)) {
            "macOS bundle version must contain one to three numeric components and start above zero: $version"
        }
        return version
    }

    fun defaultFor(marketingVersion: String): String {
        val components = marketingVersion.split('.').toMutableList()
        require(components.size in 1..3 && components.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit)
        }) {
            "instagene.version must contain one to three numeric components for macOS packaging: $marketingVersion"
        }
        if (components.first().toBigInteger().signum() == 0) {
            components[0] = "1"
        }
        return requireValid(components.joinToString("."))
    }
}

abstract class VerifyMacPackageMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val plistTemplate: RegularFileProperty

    @get:Input
    abstract val marketingVersion: Property<String>

    @get:Input
    abstract val bundleVersion: Property<String>

    @TaskAction
    fun verify() {
        check(MacPackageVersion.defaultFor("0.0.3") == "1.0.3")
        check(MacPackageVersion.defaultFor("1.2.3") == "1.2.3")

        val marketing = marketingVersion.get()
        val bundle = MacPackageVersion.requireValid(bundleVersion.get())
        val template = plistTemplate.get().asFile.readText()
        check("<string>$marketing</string>" in template) {
            "Generated macOS plist does not contain marketing version $marketing"
        }
        check("DEPLOY_BUNDLE_CFBUNDLE_VERSION" in template) {
            "Generated macOS plist does not retain the jpackage bundle-version placeholder"
        }
        check(!bundle.startsWith('0')) {
            "macOS bundle version must start above zero: $bundle"
        }
    }
}
