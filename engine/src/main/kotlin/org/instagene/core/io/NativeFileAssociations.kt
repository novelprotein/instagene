package org.instagene.core.io

/** A file type that InstaGene can open directly without an external converter. */
data class NativeFileAssociation(
    val extension: String,
    val mimeType: String,
    val description: String,
)

/**
 * The single source of truth for native file associations.
 *
 * The same TSV is consumed by the runtime and by native-package validation,
 * so installer metadata cannot silently drift from the parser.
 */
object NativeFileAssociations {
    private const val RESOURCE = "/org/instagene/core/io/native-file-associations.tsv"

    val all: List<NativeFileAssociation> by lazy {
        NativeFileAssociations::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.useLines { lines ->
                lines.mapNotNull { line ->
                    val fields = line.split('\t')
                    if (fields.size != 3 || fields[0].startsWith("#")) null
                    else NativeFileAssociation(fields[0], fields[1], fields[2])
                }.toList()
            }
            ?: error("Missing native file association resource: $RESOURCE")
    }

    val extensions: Set<String> get() = all.mapTo(linkedSetOf()) { it.extension }
}
