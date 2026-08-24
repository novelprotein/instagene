package org.instagene.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.instagene.core.io.SeqFormat
import org.instagene.core.io.SeqIO
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The role of an attachment in the portable ELN/LIMS exchange bundle. */
@Serializable
enum class ElnArtifactRole {
    SEQUENCE_FASTA,
    SEQUENCE_GENBANK,
    PRIMER_CSV,
    REPORT_MARKDOWN,
    MAP_SVG,
    MAP_PNG,
    ATTACHMENT,
}

/** An additional human-readable report to include in an ELN exchange bundle. */
data class ElnReport(
    val path: String,
    val markdown: String,
    val description: String = "",
)

/** An arbitrary, explicit attachment such as a plasmid-map SVG or a microscope image. */
data class ElnAttachment(
    val path: String,
    val bytes: ByteArray,
    val mediaType: String,
    val role: ElnArtifactRole = ElnArtifactRole.ATTACHMENT,
    val description: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ElnAttachment

        if (path != other.path) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (mediaType != other.mediaType) return false
        if (role != other.role) return false
        if (description != other.description) return false

        return true
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + description.hashCode()
        return result
    }
}

/** Inputs for a generic, local-first ELN/LIMS export. */
data class ElnBundleRequest(
    val title: String,
    val sequence: Seq? = null,
    val reports: List<ElnReport> = emptyList(),
    val attachments: List<ElnAttachment> = emptyList(),
    /** User-supplied source or workflow references that are safe to include in the manifest. */
    val provenance: Map<String, String> = emptyMap(),
    /** Injectable for reproducible tests and externally timestamped workflows. */
    val createdAt: String = Instant.now().toString(),
)

@Serializable
data class ElnBundleArtifact(
    val path: String,
    val role: ElnArtifactRole,
    val mediaType: String,
    val bytes: Long,
    val sha256: String,
    val description: String = "",
)

@Serializable
data class ElnBundleSequence(
    val name: String,
    val cdseguid: String,
    val length: Int,
    val kind: String,
    val topology: String,
    val featureCount: Int,
    val primerCount: Int,
    val sourceSha256: String? = null,
)

@Serializable
data class ElnBundleProcedure(
    val operation: String,
    val summary: String,
    val inputs: List<String>,
    val warnings: List<String>,
    val timestamp: Long,
)

/** Versioned, self-describing index stored as `manifest.json` in every bundle. */
@Serializable
data class ElnBundleManifest(
    val schemaVersion: Int = GenericZipElnAdapter.SCHEMA_VERSION,
    val bundleType: String = GenericZipElnAdapter.ID,
    val title: String,
    val createdAt: String,
    val generatedBy: String,
    val sequence: ElnBundleSequence? = null,
    val procedures: List<ElnBundleProcedure> = emptyList(),
    val provenance: Map<String, String> = emptyMap(),
    val artifacts: List<ElnBundleArtifact>,
)

data class ElnBundleVerification(
    val manifest: ElnBundleManifest,
    val valid: Boolean,
    val problems: List<String>,
)

/** A generic ELN/LIMS adapter boundary. Live vendor adapters are intentionally not included. */
interface ElnAdapter {
    val id: String
    val displayName: String
    val supportsLiveSync: Boolean get() = false

    fun export(destination: File, request: ElnBundleRequest): ElnBundleManifest
}

/**
 * Vendor-neutral ZIP adapter. It writes a stable schema, standard sequence
 * attachments, optional maps/reports, provenance, and SHA-256 checksums; it
 * never sends data over the network.
 */
object GenericZipElnAdapter : ElnAdapter {
    const val ID = "instagene-generic-eln-zip"
    const val SCHEMA_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"

    override val id: String = ID
    override val displayName: String = "Generic ELN/LIMS ZIP"

    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    override fun export(destination: File, request: ElnBundleRequest): ElnBundleManifest {
        require(request.title.isNotBlank()) { "ELN bundle title must not be blank" }
        val artifacts = buildArtifacts(request)
        val manifest = ElnBundleManifest(
            title = request.title.trim(),
            createdAt = request.createdAt,
            generatedBy = "InstaGene ${Version.VERSION}",
            sequence = request.sequence?.let(::sequenceSummary),
            procedures = request.sequence?.provenance.orEmpty().map {
                ElnBundleProcedure(it.operation, it.summary, it.inputs, it.warnings, it.timestamp)
            },
            provenance = request.provenance.toSortedMap(),
            artifacts = artifacts.map { artifact ->
                ElnBundleArtifact(
                    artifact.path,
                    artifact.role,
                    artifact.mediaType,
                    artifact.bytes.size.toLong(),
                    sha256(artifact.bytes),
                    artifact.description,
                )
            },
        )
        atomicWrite(destination) { output ->
            ZipOutputStream(output).use { zip ->
                writeZipEntry(zip, MANIFEST_NAME, json.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8))
                artifacts.forEach { artifact -> writeZipEntry(zip, artifact.path, artifact.bytes) }
            }
        }
        return manifest
    }

    /** Reads the versioned manifest without extracting any attachment. */
    fun readManifest(bundle: File): ElnBundleManifest = ZipFile(bundle).use(::readManifest)

    private fun readManifest(zip: ZipFile): ElnBundleManifest {
        val entry = zip.getEntry(MANIFEST_NAME) ?: throw IllegalArgumentException("ELN bundle has no $MANIFEST_NAME")
        return json.decodeFromString(zip.getInputStream(entry).readBytes().toString(StandardCharsets.UTF_8))
    }

    /** Verifies declared paths, byte sizes, and SHA-256 values against the ZIP contents. */
    fun verify(bundle: File): ElnBundleVerification = ZipFile(bundle).use { zip ->
        val manifest = readManifest(zip)
        val problems = ArrayList<String>()
        if (manifest.schemaVersion != SCHEMA_VERSION) problems += "Unsupported bundle schema ${manifest.schemaVersion}"
        if (manifest.bundleType != ID) problems += "Unexpected bundle type '${manifest.bundleType}'"
        val declaredPaths = HashSet<String>()
        manifest.artifacts.forEach { artifact ->
            if (safePath(artifact.path) != artifact.path) {
                problems += "Unsafe attachment path: ${artifact.path}"
                return@forEach
            }
            if (!declaredPaths.add(artifact.path)) {
                problems += "Duplicate attachment path: ${artifact.path}"
                return@forEach
            }
            val entry = zip.getEntry(artifact.path)
            if (entry == null) {
                problems += "Missing attachment: ${artifact.path}"
            } else {
                val bytes = zip.getInputStream(entry).readBytes()
                if (bytes.size.toLong() != artifact.bytes) problems += "Size mismatch: ${artifact.path}"
                if (sha256(bytes) != artifact.sha256) problems += "SHA-256 mismatch: ${artifact.path}"
            }
        }
        val actualPaths = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).map(ZipEntry::getName).toList()
        actualPaths.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { path ->
            problems += "Duplicate ZIP entry: $path"
        }
        (actualPaths.toSet() - declaredPaths - MANIFEST_NAME).forEach { path ->
            problems += "Undeclared attachment: $path"
        }
        ElnBundleVerification(manifest, problems.isEmpty(), problems)
    }

    private data class Artifact(
        val path: String,
        val bytes: ByteArray,
        val mediaType: String,
        val role: ElnArtifactRole,
        val description: String = "",
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Artifact

            if (path != other.path) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (mediaType != other.mediaType) return false
            if (role != other.role) return false
            if (description != other.description) return false

            return true
        }

        override fun hashCode(): Int {
            var result = path.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + mediaType.hashCode()
            result = 31 * result + role.hashCode()
            result = 31 * result + description.hashCode()
            return result
        }
    }

    private fun buildArtifacts(request: ElnBundleRequest): List<Artifact> {
        val artifacts = ArrayList<Artifact>()
        request.sequence?.let { seq ->
            val stem = safeStem(seq.name)
            artifacts += Artifact(
                "sequences/$stem.fasta",
                SeqIO.write(seq, SeqFormat.FASTA).toByteArray(StandardCharsets.UTF_8),
                "text/x-fasta",
                ElnArtifactRole.SEQUENCE_FASTA,
                "Sequence attachment in FASTA",
            )
            artifacts += Artifact(
                "sequences/$stem.gb",
                SeqIO.write(seq, SeqFormat.GENBANK).toByteArray(StandardCharsets.UTF_8),
                "chemical/x-genbank",
                ElnArtifactRole.SEQUENCE_GENBANK,
                "Sequence attachment in GenBank",
            )
            artifacts += Artifact(
                "primers/$stem-primers.csv",
                ElnCopy.primerCsv(seq).toByteArray(StandardCharsets.UTF_8),
                "text/csv",
                ElnArtifactRole.PRIMER_CSV,
                "Primer/order table",
            )
            artifacts += Artifact(
                "reports/$stem-summary.md",
                ElnCopy.sequenceSummaryMarkdown(seq).toByteArray(StandardCharsets.UTF_8),
                "text/markdown",
                ElnArtifactRole.REPORT_MARKDOWN,
                "Sequence and provenance summary",
            )
        }
        request.reports.forEach { report ->
            artifacts += Artifact(report.path, report.markdown.toByteArray(StandardCharsets.UTF_8), "text/markdown", ElnArtifactRole.REPORT_MARKDOWN, report.description)
        }
        request.attachments.forEach { attachment ->
            artifacts += Artifact(attachment.path, attachment.bytes, attachment.mediaType, attachment.role, attachment.description)
        }
        val normalized = artifacts.map { artifact -> artifact.copy(path = requireSafePath(artifact.path)) }
        require(normalized.map { it.path }.distinct().size == normalized.size) { "ELN bundle attachment paths must be unique" }
        return normalized.sortedBy { it.path }
    }

    private fun sequenceSummary(seq: Seq): ElnBundleSequence = ElnBundleSequence(
        name = seq.name,
        cdseguid = SequenceIdentity.cdseguid(seq),
        length = seq.length,
        kind = seq.kind.name,
        topology = seq.topology.name,
        featureCount = seq.features.size,
        primerCount = seq.primers.size,
        sourceSha256 = seq.metadata["SOURCE_SHA256"],
    )

    private fun requireSafePath(path: String): String =
        safePath(path) ?: throw IllegalArgumentException("Unsafe ELN attachment path '$path'")

    private fun safePath(path: String): String? {
        val normalized = path.replace('\\', '/').trim()
        return normalized.takeIf {
            it.isNotBlank() &&
                !it.startsWith('/') &&
                !it.contains("://") &&
                it.split('/').none { part -> part.isBlank() || part == "." || part == ".." || ':' in part }
        }
    }

    private fun safeStem(value: String): String {
        val stem = value.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.')
        return stem.ifBlank { "sequence" }
    }

    private fun writeZipEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun atomicWrite(destination: File, write: (java.io.OutputStream) -> Unit) {
        val parent = destination.absoluteFile.parentFile ?: File(".")
        require(parent.exists() || parent.mkdirs()) { "Cannot create ELN bundle directory: ${parent.path}" }
        val temp = Files.createTempFile(parent.toPath(), ".${destination.name}.", ".tmp")
        try {
            Files.newOutputStream(temp).use(write)
            try {
                Files.move(temp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** The currently supported, intentionally offline ELN adapter catalog. */
object ElnAdapters {
    val GENERIC_ZIP: ElnAdapter = GenericZipElnAdapter
    val AVAILABLE: List<ElnAdapter> = listOf(GENERIC_ZIP)
}

/** Copyable counterparts of the standard bundle attachments. */
object ElnCopy {
    fun sequenceSummaryMarkdown(seq: Seq): String = buildString {
        appendLine("# ${seq.name.ifBlank { "Unnamed sequence" }}")
        appendLine()
        appendLine("- Stable identity: `${SequenceIdentity.cdseguid(seq)}`")
        appendLine("- Length: ${seq.length} ${if (seq.kind == SeqKind.PROTEIN) "aa" else "bp"}")
        appendLine("- Molecule: ${seq.kind.name.lowercase()}, ${seq.topology.name.lowercase()}")
        appendLine("- Features: ${seq.features.size}")
        appendLine("- Primers: ${seq.primers.size}")
        seq.metadata["SOURCE_SHA256"]?.let { appendLine("- Source SHA-256: `$it`") }
        if (seq.provenance.isNotEmpty()) {
            appendLine()
            appendLine("## Recorded procedures")
            seq.provenance.forEach { procedure -> appendLine("- `${procedure.operation}` — ${procedure.summary}") }
        }
    }

    fun primerCsv(seq: Seq): String = buildString {
        appendLine("name,sequence,binding_start_1based,binding_end,strand,extension,description")
        seq.primers.forEach { primer ->
            appendLine(
                listOf(
                    primer.name,
                    primer.fullSequence,
                    (primer.bindingStart + 1).toString(),
                    primer.bindingEnd.toString(),
                    primer.strand.symbol,
                    primer.extension,
                    primer.description,
                ).joinToString(",") { value -> csv(value) },
            )
        }
    }

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        '"' + value.replace("\"", "\"\"") + '"'
    } else {
        value
    }
}
