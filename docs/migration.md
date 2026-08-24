# Migration and exchange guide

Move records into InstaGene through documented, inspectable formats rather than
assuming that every desktop application's private file is interchangeable.
Before a migration, keep the original file read-only, record its source tool
and version, and compare length, topology, key features, and primer sequences
after import.

## From SnapGene

Direct `.dna` import is deferred while format compatibility and legal terms are
reviewed. In SnapGene, export the record as GenBank, then open the `.gb` or
`.gbk` file in InstaGene. Compare feature locations, circular topology, primer
sequences, and any custom qualifiers before continuing work.

## From ApE

Open `.ape` files through InstaGene's GenBank-family reader. Save a copy as
GenBank after review so the project has an explicit, standard working record.

## From Benchling, Geneious, or another ELN/LIMS

Export standard files from the source system—usually GenBank for an annotated
sequence, FASTA for sequence-only data, and CSV or Markdown/PDF for supporting
materials. Do not provide a vendor API token to InstaGene: live vendor
connectors are intentionally deferred until an institution supplies
authorization, credentials, and accepted API terms.

For outgoing work, use **Project → ELN / Lab Notebook → Export Generic
ELN/LIMS Bundle**. The portable ZIP is vendor-neutral and offline, so it can be
attached to an approved ELN record without granting InstaGene account access.
It is an export-only handoff today; importing a vendor record from the bundle
would require a documented field mapping and remains future work.

## From a legacy proprietary editor

If a legacy format appears in the [format matrix](formats.md), configure a
trusted external converter that emits FASTA, GenBank, EMBL, or GFF3. Use a
small non-sensitive test record first. Save the conversion command and version
with the project, then validate:

1. sequence length and stable identity;
2. molecule kind and circular/linear topology;
3. feature count, ranges, strand, and qualifiers;
4. primer binding coordinates and extensions; and
5. any source hashes or procedure history needed for the next workflow.

If a format is not listed, prefer exporting it from the source application to
GenBank or FASTA rather than guessing its binary structure.

## From an older InstaGene project

An InstaGene project is a normal folder plus `.instagene/project.json` and
`.instagene/history.json`. Copy the whole folder, including these metadata
files, then open the copied folder with **Open Project**. Project manifests use
relative paths, so a complete project folder can move between machines.

After a Git merge or a cloud-sync change, select **Reload Project from Disk**.
Clean tabs refresh; dirty buffers stay open as conflicts, and missing source
files do not erase local unsaved work. Resolve the conflict by reviewing the
two versions and saving an intentional result.

## From scripts and notebooks

The CLI supports machine-readable output where a command offers `--json`, and
workflow recipes use a typed, backward-compatible JSON schema. Pin the
InstaGene version used by a pipeline, preserve the original inputs, and verify
record identities before replaying a recipe. External tools and online actions
require explicit authorization; a local deterministic fallback is reported
when one is used.
