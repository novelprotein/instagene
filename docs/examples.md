# Bundled example provenance

InstaGene includes small bundled examples so a new desktop install can open a
sequence, map, trace, or alignment without requiring a lab file. Most examples
are synthetic teaching data authored for InstaGene and distributed under the
repository MIT License. The real plasmid example is a checked-in NCBI GenBank
snapshot so it works offline and keeps its feature annotations. For that real
plasmid, BLAST is used as the record-selection check; the feature annotations
come from the selected GenBank record, because BLAST reports alignments rather
than complete feature tables.

Sequence examples carry the source statement in structured `IG_SAMPLE_SOURCE`
metadata. Synthetic sequence examples also put that statement in their record
description, so exported FASTA headers and GenBank `DEFINITION` fields retain
the citation. Do not treat bundled examples as substitutes for reviewing source
records before experimental use.

| Example | Where it appears | Description/source statement |
|---|---|---|
| `pUC19_MCS` | CLI sample, cloning tests | Synthetic teaching fragment manually authored for InstaGene; represents the pUC19 multiple cloning site region only, not a downloaded full pUC19 record. |
| `GFP_CDS` | Welcome screen gene example, CLI sample | Synthetic GFP-like teaching open reading frame authored for InstaGene examples; not copied from an external database record. |
| `pInstaGene_demo` | Welcome screen plasmid example, CLI sample | Synthetic circular construct authored for InstaGene tutorials from the bundled `pUC19_MCS` teaching fragment plus artificial filler and annotations. |
| `pBR322_NCBI` | Welcome screen real plasmid example, CLI sample, pBR322 database entry | Real plasmid example BLAST-verified against NCBI GenBank accession [`J01749.1`](https://www.ncbi.nlm.nih.gov/nuccore/J01749.1), "Cloning vector pBR322, complete sequence"; features are from that selected GenBank record; primary complete-sequence reference [PubMed 383387](https://pubmed.ncbi.nlm.nih.gov/383387/). |
| `alignment_reference`, `alignment_read_1`, `alignment_read_2` | Welcome screen alignment example | Synthetic three-sequence alignment authored for InstaGene examples, including one gap and one substitution for viewer testing. |
| `synthetic_chromatogram` | Welcome screen chromatogram example | Generated synthetic chromatogram trace authored for InstaGene examples; contains no lab, patient, proprietary, or downloaded trace data. |

The test fixture corpus has its own versioned manifest at
`tests/src/test/resources/fixtures/manifest.json`. That manifest records each
fixture file's source statement, format, license, and SHA-256 digest.
