# Format matrix

This matrix distinguishes files that InstaGene parses itself from files that
need a separately configured converter. “Read/write” means that the engine has
a built-in serializer for the listed family; it does not mean every annotation
or vendor-specific extension has an identical representation in every format.
Keep an original copy and review a converted record before using it.

## Built-in sequence and alignment formats

| Family | Typical extensions | Read | Write | Practical guidance |
|---|---|---:|---:|---|
| FASTA / bare sequence | `.fa`, `.fasta`, `.fna`, `.fas`, `.seq`, `.txt` | Yes | Yes | Sequence-focused interchange. It does not retain the editable feature table, circular topology, primers, procedure history, or most record metadata. |
| GenBank / DDBJ / ApE | `.gb`, `.gbk`, `.genbank`, `.ape` | Yes | Yes | Preferred working format for annotated or circular records. ApE files use the GenBank-family path. |
| GFF3 | `.gff`, `.gff3` | Yes | Yes | Annotation-centric exchange; check the associated sequence and attribute mapping after exchange. |
| EMBL / ENA | `.embl`, `.ena` | Yes | Yes | Flat-file interchange. Review complex qualifiers after a cross-tool round trip. |
| Swiss-Prot | `.swiss`, `.sprot`, `.dat` | Yes | Yes | Protein-oriented flat-file records. |
| FASTA alignment | `.afa`, `.msa` | Yes | Yes | Multiple aligned rows; use `readAll`/the Alignment tool when every row is needed. |
| Clustal alignment | `.aln`, `.clustal` | Yes | Yes | Multiple aligned rows and alignment export. |
| Stockholm alignment | `.sto`, `.stockholm` | Yes | Yes | Multiple aligned rows and alignment export. |
| PHYLIP alignment | `.phy`, `.phylip`, `.ph` | Yes | Yes | Sequential PHYLIP alignment input/output. |
| ABI/AB1 chromatogram | `.ab1`, `.abi` | Yes | No | Imported as trace calls, quality data, and channels for chromatogram/Sanger workflows. It is not a sequence-file export target. |
| SCF chromatogram | `.scf` | Yes | No | Imported for chromatogram/Sanger workflows; not exported by InstaGene. |

## Interoperability contract

The supported import/export paths follow a three-level contract so research
workflows can distinguish safe exchange from lossy conversion:

| Contract level | Meaning | Typical examples | Handling expectation |
|---|---|---|---|
| Native fidelity | The format is parsed and written by InstaGene with explicit retention of sequence, features, topology, and provenance where the format is capable of representing them. | GenBank/ApE, GFF3, EMBL, Swiss-Prot, alignment families | Preferred working format for major desktop or scripting workflows. |
| Best-effort loss-aware | The format captures the core sequence but may lose some editable or display metadata. | FASTA, text-export variants | Acceptable for exchange or transcript-style use, but warnings should be surfaced before use in a critical workflow. |
| Converter-only / deferred | The format is not natively supported by InstaGene and must be exported through a reviewed converter or a documented import route. | SnapGene `.dna`, some vendor-specific or legacy formats | Treat as a migration path, not as native compatibility. |

The practical rule is simple:

- use GenBank/ApE or GFF3 when a feature table, origin, and annotation state
  matter;
- use FASTA or alignment formats when the sequence itself is the only required
  payload;
- treat all converter-backed formats as migration steps and verify the resulting
  record before using it in a decision-making workflow.

A record should be considered scientifically trustworthy only when its format,
annotation retention, and provenance are understood. When a conversion drops
annotation state or topology, the UI and CLI should make that loss explicit
instead of silently accepting it.

`SeqIO.read(file)` returns the first record of a multi-record sequence or
alignment file. Use `SeqIO.readAll(file)` or the alignment tools when all rows
are required.

## Desktop routing and file associations

The desktop open dialog routes native sequence, alignment, chromatogram, text,
image, PDF, and project files through one flow. Text can open in the in-app
editor; images and PDFs are handed to the operating system. Installed native
packages advertise the native sequence and alignment extensions. ABI/AB1 and
SCF remain unregistered with the operating system even though they can be
opened in InstaGene, because their direct analysis route is intentionally
separate from a default-file-handler claim.

Portable JAR and app-image downloads do not modify operating-system file
associations.

## Converter-backed legacy formats

The following families are catalogued but are not bundled parsers: CLC Bio,
Clone Manager, DNA Strider, DNADynamo, DNASIS, DNAssist, DNASTAR Lasergene,
DS Gene, EnzymeX, Gene Construction Kit, Geneious, GeneTool, Genome Compiler,
Jellyfish, MacVector, pDRAW32, Serial Cloner, Vector NTI, and Visual Cloning.

Configure a converter with the relevant `INSTAGENE_CONVERTER_*` environment
variable or desktop setting. Its command must include `{in}` and emit FASTA,
GenBank, EMBL, or GFF3 to `{out}` or standard output. The converter is a
separate dependency: inspect its license, version, command line, and output
before treating the converted record as equivalent to the source.

## Deliberately deferred direct formats

SnapGene `.dna` is not a built-in or converter-listed direct import target.
Until its format and legal terms have been reviewed, use SnapGene’s own export
to GenBank and open the resulting `.gb`/`.gbk` file. This documented conversion
path is different from claiming native SnapGene compatibility.

## Choosing a working format

- Use **GenBank** for annotated plasmids, circular topology, primers, procedure
  history, or molecule properties that should remain in an editable record.
- Use **FASTA** for a simple sequence or a tool that explicitly requires FASTA.
- Use an **alignment format** for an aligned collection rather than flattening
  it to a single sequence record.
- Use the [generic ELN/LIMS ZIP](tasks.md#hand-off-a-record-to-an-eln-or-lims)
  when handing off a sequence together with a map, primer table, report, and
  integrity manifest.
