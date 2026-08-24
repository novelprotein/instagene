package org.instagene.app.gui.file

import org.instagene.core.ChromatogramReader
import org.instagene.core.ChromatogramRecord
import org.instagene.core.Seq
import org.instagene.core.SequenceIdentity
import org.instagene.core.io.SeqIO
import java.io.File

/**
 * A parsed file ready to be applied by the Swing UI.
 *
 * Keeping parsing and routing decisions out of the window makes single-file
 * and batch opening behave identically, and lets all disk work stay off the
 * event-dispatch thread.
 */
sealed interface OpenedFile {
    val file: File

    data class Sequence(override val file: File, val sequence: Seq) : OpenedFile
    data class Alignment(override val file: File, val sequences: List<Seq>) : OpenedFile
    data class Chromatogram(override val file: File, val record: ChromatogramRecord) : OpenedFile
    data class Text(override val file: File, val text: String) : OpenedFile
    data class Project(override val file: File) : OpenedFile
    data class System(override val file: File) : OpenedFile
}

/** A file that could not be opened, without aborting the rest of a batch. */
data class FileOpenFailure(val file: File, val message: String)

/** Deterministic summary used by GUI progress reporting and regression tests. */
data class FileOpenBatch(
    val opened: List<OpenedFile>,
    val failures: List<FileOpenFailure>,
    val cancelled: Boolean = false,
) {
    val completed: Int get() = opened.size + failures.size
}

/**
 * Parses supported researcher files and gives a useful conversion-oriented
 * error for unsupported inputs. It has no Swing dependency and never opens a
 * system application itself.
 */
object FileOpenService {

    fun load(file: File): OpenedFile {
        require(file.exists()) { "'${file.path}' no longer exists." }
        return when (FileTypes.classify(file)) {
            FileType.SEQUENCE -> OpenedFile.Sequence(file, SequenceIdentity.withSourceFile(SeqIO.read(file), file))
            FileType.ALIGNMENT -> {
                val sequences = runCatching { SeqIO.readAll(file).map { SequenceIdentity.withSourceFile(it, file) } }
                    .getOrElse { error ->
                        if (error.message.orEmpty().contains("different lengths")) {
                            throw IllegalArgumentException(
                                "'${file.name}' is not an aligned file: its rows have different lengths. " +
                                    "Open the records individually and run Alignment, or convert it to aligned FASTA.",
                                error,
                            )
                        }
                        throw error
                    }
                require(sequences.size >= 2) {
                    "'${file.name}' contains one sequence; open it as a sequence instead of an alignment."
                }
                require(sequences.map { it.bases.length }.distinct().size == 1) {
                    "'${file.name}' is not an aligned file: its rows have different lengths. " +
                        "Open the records individually and run Alignment, or convert it to aligned FASTA."
                }
                OpenedFile.Alignment(file, sequences)
            }
            FileType.CHROMATOGRAM -> OpenedFile.Chromatogram(file, readChromatogram(file))
            FileType.TEXT -> OpenedFile.Text(file, file.readText())
            FileType.PROJECT -> OpenedFile.Project(file)
            FileType.IMAGE, FileType.PDF -> OpenedFile.System(file)
            FileType.UNKNOWN -> throw IllegalArgumentException(unsupportedMessage(file))
        }
    }

    /**
     * Opens each distinct existing path independently. One malformed file must
     * never prevent a researcher from opening the remaining selections.
     */
    fun loadAll(
        files: List<File>,
        cancellationRequested: () -> Boolean = { false },
        onResult: (OpenedFile) -> Unit = {},
        onFailure: (FileOpenFailure) -> Unit = {},
    ): FileOpenBatch {
        val opened = mutableListOf<OpenedFile>()
        val failures = mutableListOf<FileOpenFailure>()
        val unique = files.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        for (file in unique) {
            if (cancellationRequested()) return FileOpenBatch(opened, failures, cancelled = true)
            runCatching { load(file) }
                .onSuccess { result -> opened += result; onResult(result) }
                .onFailure { error ->
                    val failure = FileOpenFailure(file, error.message ?: "Unable to open '${file.name}'.")
                    failures += failure
                    onFailure(failure)
                }
        }
        return FileOpenBatch(opened, failures)
    }

    fun unsupportedMessage(file: File): String = buildString {
        append("InstaGene cannot open '${file.name}' as a supported research file.")
        if (file.isDirectory) {
            append(" Select a folder containing .instagene/project.json, or create a new project first.")
        } else {
            append(" Supported sequence inputs include FASTA, GenBank/ApE, GFF3, EMBL/ENA, Swiss-Prot, and Clustal/Stockholm/PHYLIP alignments.")
            if (file.extension.equals("dna", ignoreCase = true)) {
                append(" SnapGene .dna import is intentionally deferred pending a license and format review. Export the record as GenBank from SnapGene, or use an independently approved converter that emits GenBank.")
            }
            append(" For proprietary or legacy formats, configure a converter that emits FASTA, GenBank, EMBL, or GFF3.")
        }
    }

    private fun readChromatogram(file: File): ChromatogramRecord {
        val header = file.inputStream().use { it.readNBytes(4) }
        return when {
            ChromatogramReader.looksLikeAbi(header) -> ChromatogramReader.readAbi(file)
            ChromatogramReader.looksLikeScf(header) -> ChromatogramReader.readScf(file)
            else -> error("'${file.name}' is not a readable ABI/AB1 or SCF chromatogram.")
        }
    }
}
