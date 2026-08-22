package org.instagene.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

abstract class VerifyNativeFileAssociationsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val manifestEntries = manifestFile.get().asFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split('\t')
                check(fields.size == 3) { "Invalid association manifest line: $line" }
                fields[0] to fields.drop(1)
            }
            .toMap()

        val packageEntries = packageFiles.files.associate { file ->
            val properties = Properties().apply { file.inputStream().use(::load) }
            val extension = properties.getProperty("extension")
                ?: error("Missing extension in ${file.name}")
            extension to listOf(
                properties.getProperty("mime-type") ?: error("Missing mime-type in ${file.name}"),
                properties.getProperty("description") ?: error("Missing description in ${file.name}"),
            )
        }

        check(packageEntries == manifestEntries) {
            "Native association metadata differs from runtime manifest. " +
                "Manifest-only: ${manifestEntries.keys - packageEntries.keys}; " +
                "Package-only: ${packageEntries.keys - manifestEntries.keys}"
        }
    }
}
