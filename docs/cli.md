# CLI reference

The `instagene` CLI is intended for scripts, repeatable checks, batch sequence
operations, and environments where a desktop window is not appropriate. Run
`instagene help` for version-specific help.

## Input and output

Most commands accept a file as the final argument. They can also read FASTA,
GenBank, or bare bases from standard input. Use `--in FILE` and `--out FILE`
where a command exposes explicit stream options. Output normally goes to
standard output.

~~~bash
instagene info plasmid.gb
cat sequence.fa | instagene translate --stop-at-stop
instagene convert --to genbank --out annotated.gb sequence.fa
~~~

Global options:

| Option | Purpose |
|---|---|
| --env FILE | Load default KEY=VALUE settings; command-line values win |
| --no-colors | Disable ANSI color output |
| --json | Request machine-readable JSON for commands that support it |
| --version | Print the InstaGene version |
| --circular | Treat input as a circular molecule where the command supports it |

## Inspecting sequences

~~~bash
instagene info sequence.gb
instagene gc sequence.gb
instagene tm primer.fa
instagene orf --min-aa 30 --table 11 sequence.gb
instagene find --pattern GGATCC --mismatches 1 sequence.gb
instagene align --query read.fa,read2.fa reference.fa
instagene identity aligned.fa
~~~

Additional inspection commands:

- `gel --enzymes EcoRI,BamHI sequence.fa` simulates a digest gel; use
  `--completion` for incomplete reaction estimates.
- `enzymes --filter eco` searches the built-in enzyme list.
- `sites sequence.gb` reports unique cutters and non-cutters.
- `blast-url sequence.fa` creates a BLAST URL without submitting a job.
- `ncbi-search --term "gene name"` searches NCBI records.
- `ncbi-fetch --accession ACCESSION` retrieves a GenBank record.
- `dilute` and `mix` calculate common preparation volumes.

NCBI commands are network-only by default. To deliberately keep and reuse
responses, pass `--cache-dir DIR`; this selects `prefer-cache` unless another
`--cache-mode` is chosen. The available modes are `network-only`,
`prefer-cache`, `network-then-cache`, and `cache-only`. Cached entries are
versioned and SHA-256 verified. A retrieved GenBank record includes the request,
response hash, timestamp, and cache/network origin in its metadata and procedure
history; `cache-only` never contacts NCBI.

~~~bash
instagene ncbi-fetch --accession J01636.1 --cache-dir .instagene-ncbi-cache
instagene ncbi-fetch --accession J01636.1 --cache-dir .instagene-ncbi-cache --cache-mode cache-only
~~~

## Restriction mapping and plasmids

~~~bash
instagene digest --enzymes EcoRI,HindIII plasmid.gb
instagene plasmid --backbone puc19.gb --insert gfp.fa \
  --enzymes EcoRI,HindIII --name pGFP
instagene gibson --parts backbone.fa,insert.fa --min-overlap 20
instagene golden-gate --parts a.fa,b.fa --overhangs A,G,A
instagene recombine --target target.fa --donor donor.fa --arm 20
~~~

The CLI reports computed products, cut sites, fragments, diagnostics, and
warnings. It does not replace validation of sequence identity, reaction
conditions, or the intended cloning protocol.

## Editing and conversion

~~~bash
instagene revcomp sequence.fa
instagene complement sequence.fa
instagene transcribe sequence.fa
instagene backtranscribe rna.fa
instagene translate --frame 1 sequence.fa
instagene edit --insert ACGT --at 10 sequence.fa
instagene edit --delete --from 5 --to 20 sequence.fa
instagene edit --replace ACGT --from 5 --to 8 sequence.fa
instagene extract --from 100 --to 400 --revcomp sequence.gb
instagene annotate --from 1 --to 60 --label promoter --type promoter sequence.gb
instagene topology --set circular --origin 500 sequence.gb
instagene convert --to gff3 sequence.gb
~~~

Coordinates in command help are the user-facing sequence coordinates. Check
the command output and the resulting record before using an edited file in a
downstream workflow.

## Generic ELN/LIMS handoff

`eln-bundle` creates a local, vendor-neutral ZIP rather than contacting an ELN
service. The bundle contains FASTA and GenBank sequence attachments, a primer
CSV, a Markdown sequence/provenance summary, supplied reports/attachments, and
a versioned `manifest.json` with SHA-256 hashes.

~~~bash
instagene eln-bundle --out plasmid-handoff.zip plasmid.gb
instagene eln-bundle --out plasmid-handoff.zip --report verification.md \
  --attachment plasmid-map.svg,trace.pdf plasmid.gb
instagene eln-bundle --out plasmid-handoff.zip --json plasmid.gb
~~~

The command does not upload to Benchling, an ELN, or another vendor. Vendor
connectors remain deferred until credentials, authorization, and API terms are
supplied.

## Primer design

~~~bash
instagene primers --from 100 --to 400 --tm 60 sequence.gb
instagene primers --from 100 --to 400 --advanced --backend primer3 sequence.gb
echo ATCGATCGATCG | instagene tm
~~~

The `primers` command designs an amplicon primer pair. `--advanced` prints the
ranked, explainable candidate set. `--backend primer3` uses an installed
`primer3_core` through Boulder-IO; if it is missing or fails, InstaGene records
the reason and falls back to the deterministic built-in search. The GUI provides
additional screening, library storage, and feature integration.

## Optional external tools

Use `tools` to see the external-tool catalog and availability:

~~~bash
instagene tools
instagene tools --run primer3 --preview
instagene tools --run seqkit-stats sequence.fa
instagene tools --run emboss-restrict sequence.fa
instagene tools --run seqkit-locate --pattern GGATCC sequence.fa
~~~

External tools are not bundled. Configure and install them separately, and
review their own licensing and output conventions. Add `--preview` to inspect
the resolved command and any missing required values without creating files or
running an executable.

## Desktop handoff and benchmarks

~~~bash
instagene gui plasmid.gb
instagene gui --launcher /path/to/InstaGene plasmid.gb
instagene bench
instagene benchmark sequence.gb
~~~

The `gui` command resolves a launcher from `--launcher`, the `INSTAGENE_GUI`
environment variable, the system `PATH`, and standard Linux install locations.
See [Benchmarks](benchmarks/info.md) for the published dashboard.
