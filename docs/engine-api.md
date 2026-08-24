# Engine API

`engine` is the Kotlin library used by the desktop, CLI, and web applications.
It contains sequence models, parsers, analysis algorithms, cloning workflows,
and reusable project data structures. It has no Swing dependency.

## Install the engine

The published artifact is available from GitHub Packages:

~~~kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/novelprotein/instagene")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("org.instagene:instagene-engine:0.0.4")
}
~~~

The version is defined by `gradle.properties` in this repository. Tagged
releases publish the tagged version; other builds include a commit suffix.

## Core data model

`Seq` is the central immutable sequence record. It carries bases, name, type
(`DNA`, `RNA`, or `PROTEIN`), topology (`LINEAR` or `CIRCULAR`), features,
primers, molecule properties, metadata, and related annotations.

~~~kotlin
val plasmid = Seq(
    name = "pExample",
    bases = "GAATTCACCGGTT",
    kind = SeqKind.DNA,
    topology = Topology.CIRCULAR,
)

val edited = plasmid.insertAt(6, "GCGC")
~~~

Sequence editing returns a new record and carries compatible annotations.
Validate the resulting features when an edit crosses an annotation boundary.

## Sequence I/O

`SeqIO` detects and reads FASTA, GenBank, GFF3, EMBL, Swiss-Prot, FASTA,
Clustal, Stockholm, and PHYLIP alignments, and supported ABI/SCF
chromatograms. It can read records and write native formats through
`SeqFormat`.

~~~kotlin
val seq = SeqIO.read(File("plasmid.gb"))
val records = SeqIO.readAll(File("multi.fa"))
val fasta = SeqIO.write(seq, SeqFormat.FASTA)
val genbank = SeqIO.write(seq, SeqFormat.GENBANK)
~~~

Use `preferredSaveFormat(seq)` when preserving annotations, topology, primers,
or molecule properties. Legacy proprietary format support is converter-backed:
register a command containing `{in}` and ensure that it emits FASTA, GenBank,
EMBL, or GFF3. Direct SnapGene `.dna` import is intentionally deferred pending
legal and format review; export a supported GenBank record before loading it.

## Analysis and design

The main engine services include:

| Area | APIs |
|---|---|
| Sequence operations | Alphabet, SeqOps, SequenceStatistics, SequenceProfiles |
| Restriction mapping | Enzymes, Digest, EnzymeAnalysis, SiteDomestication |
| Alignment | Alignment, MultipleAlignment, SangerAlignment, SequenceIdentity |
| Annotation | FeatureLibrary, AdvancedSearch, ProjectSearch |
| Primer design | PrimerDesign, PrimerThermodynamics, PcrWorkflows |
| Assembly | Assembly, AssemblyWorkflows, CloningWorkflows, Recombination |
| Other workflows | CrisprDesign, VirtualGel, SecondaryStructure, MolecularCalculators |
| Remote | NcbiClient, OnlineCache, and OnlineCacheMode for explicit NCBI retrieval and BLAST polling |

Examples:

~~~kotlin
val counts = Digest.cutCounts(seq)
val ecoSites = Digest.cutSites(seq, Enzymes.require("EcoRI"))
val uniqueCutters = Digest.enzymesCutting(seq, times = 1)

val stats = SequenceStatistics.computeStats(seq)
val gcWindows = SequenceStatistics.gcContentProfile(seq, windowSize = 500)
val candidates = PrimerDesign.candidates(seq, 100, 400)
val tm = PrimerThermodynamics.thermodynamicResult("ATCGATCGATCG").tm
~~~

Most APIs are synchronous functions. Front ends are responsible for placing
large or remote operations on appropriate background workers and for deciding
how to present errors and cancellation. The internal `Parallel` helper is not a
public scheduling contract.

### Responsive long-running scans

`PerformanceTargets` records the workload classes used by desktop regressions:
10 kb plasmids, 100 kb constructs, and progressive multi-megabase work.
`Digest.countSites` accepts optional cancellation and bounded progress callbacks.
For large feature libraries, use `FeatureLibrary.previewMatchesCancellable` or
`annotateCancellable`; their `FeatureScanProgress` callback advances once per
definition and throws `CancellationException` when the supplied cancellation
predicate becomes true.

### Explicit remote retrieval

`NcbiClient` is network-only by default. Pass an `OnlineCache` and an explicit
policy when a workflow should retain response data. `PREFER_CACHE` reuses a
verified matching response, `NETWORK_THEN_CACHE` uses a verified response only
after a network failure, and `CACHE_ONLY` is safe for offline replay. Every
`fetchGenBank` result records NCBI request, response hash, retrieval timestamp,
and origin in `Seq.metadata` and a `ProcedureRecord`.

~~~kotlin
val cache = OnlineCache(File(".instagene-cache"))
val client = NcbiClient(onlineCache = cache, onlineCacheMode = OnlineCacheMode.PREFER_CACHE)
val record = client.fetchGenBank("J01636.1")
~~~

## Reports and reproducibility

`Reports` produces text reports for selected workflows. Sequence records also
carry procedure records that capture a workflow's inputs, warnings, and
summary. Reports support review, but they do not replace source-record checks
or laboratory documentation.

## Generic ELN/LIMS exchange

`GenericZipElnAdapter` is the built-in local-first handoff adapter. It writes a
versioned ZIP with a manifest, SHA-256 hashes, standard FASTA/GenBank/primer
attachments for a sequence, optional reports and attachments, and embedded
procedure provenance. It does not perform network I/O or use an ELN vendor
credential.

~~~kotlin
val destination = File("plasmid-handoff.zip")
val manifest = GenericZipElnAdapter.export(
    destination,
    ElnBundleRequest(
        title = "pExample handoff",
        sequence = plasmid,
        reports = listOf(ElnReport("reports/review.md", "# Review\n\nReady for review.")),
        attachments = listOf(
            ElnAttachment(
                path = "maps/pexample.svg",
                bytes = File("pexample.svg").readBytes(),
                mediaType = "image/svg+xml",
                role = ElnArtifactRole.MAP_SVG,
            ),
        ),
        provenance = mapOf("projectFile" to "constructs/pexample.gb"),
    ),
)

check(GenericZipElnAdapter.verify(destination).valid)
println(manifest.artifacts.map { it.path })
~~~

Attachment paths are normalized and must be relative, unique bundle paths.
`ElnAdapters.AVAILABLE` currently contains only this generic adapter. Live
vendor adapters are intentionally deferred until an approved integration has
credentials, authorization, documented field mapping, and API terms.

## Compatibility expectations

The engine API is evolving before a stable 1.0 release. Pin the published
version in consumers, run the project tests when upgrading, and prefer the
documented public classes over internal helpers.
