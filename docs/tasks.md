# Researcher task guides

These guides describe the shortest safe route through common InstaGene tasks.
They are computational workflows: review the source record, the displayed
warnings, and the planned laboratory procedure before acting on an output.

## Open and inspect an unfamiliar record

1. Use **Open File** or drag the file into the window. The unified open flow
   reports files it could not route instead of silently dropping them.
2. Start on **Info**. Confirm the name, molecule type, length, topology, and
   source metadata before editing or designing against the record.
3. Use **Map** for an annotated plasmid and **Sequence** for base-level review.
4. Save an annotated or circular record as GenBank. FASTA is appropriate only
   when a downstream tool requires a sequence-only copy.

See the [format matrix](formats.md) before opening a legacy or proprietary
file.

## Build and review a plasmid

1. Open the backbone and insert records in separate tabs.
2. Inspect restriction sites in **Enzyme** and confirm the intended topology in
   **Info**.
3. Use **Analysis** to select PCR/mutagenesis, restriction cloning, Gibson,
   Golden Gate, recombination, or another appropriate assembly workflow.
4. Read every junction, orientation, warning, and product-coordinate result.
5. Export the workflow report and recipe with the product record. A recipe is
   an auditable reconstruction aid, not an executable laboratory protocol.

The [reproducible-workflow guide](workflows.md) explains identity checks and
the explicit authorization required for online or external-tool replay.

## Design primers with quality constraints

1. Open the target sequence, then open the relevant ABI/AB1 or SCF trace, or
   supply a FASTA-QUAL sidecar in the primer workflow.
2. In **Primers**, set a quality threshold and add manually excluded low-quality
   regions when there is laboratory knowledge not present in the trace.
3. Inspect each candidate's target coordinates, Tm, GC, and quality warnings.
4. Save the primer list or export a report; quality evidence and manual
   exclusions are included in the workflow provenance.

For verification, drag ABI/SCF reads onto the reference tab or open the
**Sanger Alignment** workflow. Uncovered bases are reported separately from
low-confidence calls.

## Work safely in a project folder

Projects are ordinary folders with `.instagene/project.json` and
`.instagene/history.json` metadata. Keep the metadata with the sequence files
when moving or backing up a project.

When a sync client, Git checkout, or another application changes a project:

1. Choose **Project → Reload Project from Disk**.
2. Clean tabs are refreshed from disk.
3. Dirty tabs are preserved and reported as local conflicts; InstaGene never
   silently overwrites their unsaved buffer.
4. Tabs whose source file has disappeared stay open so the researcher can copy
   or save their local work. Newly declared, supported files in a changed
   project manifest may be opened.

The project browser also notices filesystem changes and refreshes its listing.
Use explicit reload when you want to reconcile document contents, especially
after a Git or sync operation.

## Hand off a record to an ELN or LIMS

InstaGene provides a local, vendor-neutral handoff rather than live ELN
integration. With a sequence tab selected, choose **Project → ELN / Lab
Notebook** to:

- copy a Markdown sequence and recorded-procedure summary;
- export that summary, a GenBank or FASTA sequence attachment, a primer CSV,
  or an SVG map; or
- export a **Generic ELN/LIMS Bundle**.

The generic ZIP contains a versioned `manifest.json`, SHA-256 hashes, FASTA and
GenBank sequence attachments, a primer CSV, a Markdown summary, embedded
sequence procedure records, and (for a non-empty sequence) an SVG map. It
never uploads data or needs vendor credentials. Give the ZIP to the ELN/LIMS
workflow that your institution has approved and retain the source files with
it.

Bundle import is intentionally not implemented yet. This keeps the initial
exchange path reviewable and avoids pretending that a vendor-specific record
can be reconstructed without a documented mapping.

The same handoff is available to scripts through
[`instagene eln-bundle`](cli.md#generic-elnlims-handoff).

## Use online data deliberately

NCBI retrieval and BLAST are opt-in. The desktop's NCBI result-cache control
starts at **Network only**; no response is retained until a researcher selects
a cache mode. Cached NCBI records carry request, response hash, time, and
network/cache origin in their metadata and procedure history.

External tools are also optional. Use **Settings → External Tools** or
`instagene tools --health` to inspect an executable and its recovery guidance.
When a deterministic built-in fallback is used, the result reports that choice.

## Keep large records responsive

Opening and rendering a 10 kb plasmid, scrolling a 100 kb construct, and
running multi-megabase work are separate workload classes. Digest and feature
library scans show progress and expose cancellation where the operation can be
stopped safely. Avoid opening duplicate genome-scale records, give the portable
JAR enough heap, and wait for a background result before changing its inputs.

See [benchmarks](benchmarks/info.md) for the measured task definitions and
commands.
