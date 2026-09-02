# Bundled example provenance

InstaGene bundles only complete, source-identifiable records for its desktop,
CLI, and web examples. The records are checked-in NCBI GenBank snapshots, so
they work offline while retaining their original sequence, feature table,
authors, and publication or submission references.

Each record carries an InstaGene-added provenance statement in
`IG_SAMPLE_SOURCE` metadata, plus the accession, NCBI record URL, retrieval
date, source checksum, and annotation source. This application metadata is
separate from the original GenBank `COMMENT`, `AUTHORS`, and `REFERENCE` fields;
those source fields are preserved in structured record metadata and shown by the
Info panel. The Info panel also exposes the explicit NCBI nuccore URL as a
separate source link; it is not appended to the original `COMMENT` text.

| Example | Where it appears | Original source |
|---|---|---|
| `pBR322_NCBI` | Welcome screen, CLI sample, pBR322 database entry | Complete NCBI GenBank record [`J01749.1`](https://www.ncbi.nlm.nih.gov/nuccore/J01749.1), “Cloning vector pBR322, complete sequence.” |
| `pUC19_NCBI_reference` | Welcome screen, CLI sample, pUC19 database entry | Complete NCBI GenBank record [`M77789.2`](https://www.ncbi.nlm.nih.gov/nuccore/M77789.2), “Cloning vector pUC19, complete sequence.” |
| `GFP_Aequorea_NCBI_reference` | Welcome screen, CLI sample | Complete NCBI GenBank record [`L29345.1`](https://www.ncbi.nlm.nih.gov/nuccore/L29345.1), *Aequorea victoria* GFP mRNA, complete cds. |
| `pGFPuv_NCBI_reference` | Welcome screen, CLI sample | Complete NCBI GenBank record [`U62636.1`](https://www.ncbi.nlm.nih.gov/nuccore/U62636.1), “Cloning vector pGFPuv, complete sequence.” |

There are no generated plasmid, gene, alignment, or chromatogram records in
the bundled example catalog. Alignment and chromatogram tools remain
available for user-provided files.

The test fixture corpus has its own versioned manifest at
`tests/src/test/resources/fixtures/manifest.json`. Those fixtures support
deterministic software tests and are not bundled product examples.
