# Engine API

The engine (`org.instagene.core`) is a pure Kotlin library with no UI
dependencies. It provides the core bioinformatics operations used by all
front-ends.

## Module

```kotlin
dependencies {
    implementation("org.instagene:instagene-engine:0.0.3")
}
```

## Package Structure

```
org.instagene.core
├── Sequence I/O
│   ├── Seq                    # Core sequence model
│   ├── SeqIO                  # Format detection and read/write entry point
│   ├── Fasta                  # FASTA format parser/writer
│   ├── GenBank                # GenBank format parser/writer
│   └── ChromatogramReader     # ABI/AB1 and SCF chromatogram parser
├── Analysis
│   ├── SequenceStatistics     # GC content, entropy, CpG islands
│   ├── Digest                 # Restriction enzyme mapping
│   ├── Alignment              # Needleman-Wunsch alignment
│   ├── SangerAlignment        # Sanger read alignment
│   └── FeatureLibrary         # Feature annotation and search
├── Design
│   ├── PrimerThermodynamics   # Melting temperature, hairpin, dimer
│   ├── PrimerDesign           # Primer design with Tm targeting
│   ├── CrisprDesign           # CRISPR guide RNA design (Ruleset 3)
│   └── SiteDomestication      # Restriction site removal
├── Assembly
│   ├── Assembly               # Fragment assembly and overlap
│   ├── Recombination          # Homologous recombination
│   └── VirtualGel             # Gel electrophoresis simulation
└── Utilities
    ├── Alphabet               # IUPAC codes, complements, translations
    ├── CodonTable             # Codon translation tables
    ├── Enzymes                # Restriction enzyme catalog
    └── Parallel               # Coroutine-based parallel computation
```

## Key Classes

### Seq

The core sequence data class:

```kotlin
data class Seq(
    val name: String = "unnamed",
    val bases: String = "",
    val kind: SeqKind = SeqKind.DNA,
    val topology: Topology = Topology.LINEAR,
    val features: List<Feature> = emptyList(),
    val description: String = "",
    val metadata: Map<String, String> = emptyMap(),
)
```

### Digest

Restriction enzyme mapping:

```kotlin
// Count cut sites for all enzymes
val counts = Digest.cutCounts(seq)

// Find all cut sites for specific enzymes
val sites = Digest.cutSites(seq, listOf(Enzymes.ECORI, Enzymes.HINDIII))

// Find enzymes that cut exactly once
val unique = Digest.enzymesCutting(seq, times = 1)
```

### SequenceStatistics

Genome statistics:

```kotlin
val gc = SequenceStatistics.gcContent(seq)
val islands = SequenceStatistics.cpgIslands(seq)
val entropy = SequenceStatistics.computeStats(seq).shannonEntropy
```

### Alignment

Multiple sequence alignment:

```kotlin
val result = Alignment.align(reference, queries)
val discrepancies = result.discrepancyPositions()
```

### PrimerThermodynamics

Primer analysis:

```kotlin
val tm = PrimerThermodynamics.thermodynamicResult(primer).tm
val hairpin = PrimerThermodynamics.assessHairpin(primer)
val selfDimer = PrimerThermodynamics.assessSelfDimer(primer)
```

## Parallelization

The engine uses `kotlinx.coroutines` for CPU-bound parallel computation via
the `Parallel` utility class. Methods that iterate over large enzyme catalogs
or process multiple sequences are automatically parallelized when the input
size exceeds a threshold.

Key parallelized operations:

- `Digest.cutCounts` / `cutSites` — parallel over enzymes
- `Alignment.align` — parallel over query sequences
- `FeatureLibrary.previewMatches` — parallel over feature definitions
- `SequenceStatistics.cpgIslands` — parallel over window positions
- `AdvancedSearch.find` — parallel over strands

## Thread Safety

All engine methods are stateless and thread-safe. The `Parallel` utility
uses `Dispatchers.Default` for CPU-bound work and does not maintain shared
state between coroutines.
