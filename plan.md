# InstaGene researcher-first roadmap

This is the approved sequential implementation plan. Complete and test one
feature group before starting the next one. Do not mark an item complete until
its focused tests and the applicable milestone gate pass.

## Foundation and release trust

- [x] Reconcile `todo.md` against implemented CI/package work.
- [x] Keep unsigned release behavior explicit and credential-gated.
- [ ] Add Windows signing verification when protected credentials are available.
- [ ] Add macOS signing, notarization, stapling, and assessment when credentials are available.
- [x] Preserve native installer and CLI smoke coverage.

## Researcher desktop adoption

- [x] Add bundled plasmid, gene, chromatogram, and alignment examples.
- [x] Add one typed file-open flow for sequence, chromatogram, alignment, annotation, text, and project files.
- [x] Add batch-open progress, cancellation, per-file reporting, and unsupported-format guidance.
- [x] Drive chooser filters from the shared format catalog.
- [x] Add a command palette for tools, files, projects, and common workflows.
- [ ] Complete macOS conventions, icons, installed-DMG checks, and Retina captures.
- [ ] Complete Windows metadata, association/open checks, SmartScreen guidance, filters, and DPI checks.
- [x] Add focused GUI and native-platform coverage.

## Plasmid construction and verification

- [x] Add a PCR-cloning wizard with primer design, product validation, report, and recipe output.
- [x] Extend cloning reports with normalized parameters and Markdown, JSON, HTML, and PDF export.
- [x] Persist user/lab feature libraries and enzyme sets as versioned plain files with import/export.
- [x] Add coordinate-linked reading-frame and translation validation.
- [x] Support drag-and-drop ABI/SCF reads onto a reference.
- [x] Add deterministic engine, GUI, report, and product-coordinate tests.

## Quality-aware primers and Sanger workflows

- [x] Add a quality-context model with source provenance, threshold, and manual exclusions.
- [x] Accept ABI/SCF traces, FASTA-QUAL Phred sidecars, and manual low-quality regions.
- [x] Combine trace evidence conservatively and keep uncovered positions distinct.
- [x] Carry effective quality constraints and Primer3 provenance into reports.
- [x] Add sequencing-primer selection mode.
- [x] Add synchronized quality overlays to chromatogram/mismatch views.
- [x] Expose equivalent CLI behavior and test fallback paths.

## Reproducible workflows and analysis

- [x] Migrate recipes to a typed, backward-compatible operation schema.
- [x] Replay every deterministic current workflow from GUI and CLI with identity-matched inputs.
- [x] Require explicit opt-in for online/external-tool recipe replay.
- [x] Add dot-plot and direct/inverted-repeat analysis with GUI and CLI export.
- [x] Add Clustal, Stockholm, and PHYLIP alignment I/O plus image export.
- [x] Add actionable external-tool health/version diagnostics.
- [x] Add deterministic engine, GUI, CLI/JSON, and fixture coverage.

## Data fidelity, provenance, and performance

- [x] Add a license-reviewed, versioned public fixture manifest and corpus.
- [x] Add ApE, EMBL, GFF3, Swiss-Prot, and alignment round-trip coverage.
- [x] Document a supported SnapGene conversion path while direct import remains deferred pending legal/format review.
- [x] Persist analysis settings and show/use stable identities consistently.
- [x] Add explicit online fetch caches with provenance and failure behavior.
- [x] Add performance targets, progress/cancellation, benchmarks, memory tests, and virtualization.

## Projects, ELN/LIMS, documentation, and extensibility

- [x] Add conflict-tolerant project reload behavior.
- [x] Add ELN copy/export actions for sequences, maps, reports, and primer CSV.
- [x] Ship a versioned generic ZIP bundle with manifest, hashes, provenance, and attachments.
- [x] Define and test a vendor-neutral ELN adapter; defer live vendors pending credentials and API terms.
- [x] Add headless engine API examples and preserve CLI JSON contracts.
- [x] Add task-based researcher documentation.
- [x] Add a built-in/converter/deferred format matrix.
- [x] Add migration and exchange guidance.
- [ ] Capture Windows and macOS installer screenshots after native release-owner checks.
- [x] Keep documentation claims tied to implementation and release checks.

## Program defaults

- [x] Keep online actions explicit and provenance-recorded.
- [x] Keep external tools optional with actionable diagnostics and deterministic fallbacks.
- [x] Defer direct vendor APIs until credentials and authorization are supplied.
- [x] Do not implement the local AI assistant in this program.

## External release gates still required

- [ ] Provide protected Windows signing credentials, choose the unsigned-release
  policy, and run Authenticode verification on the final MSI/executable.
- [ ] Provide Apple Developer/notarization credentials, sign the final app,
  notarize/staple the DMG, and run `spctl --assess`.
- [ ] Perform the native Windows high-DPI and macOS Retina/installed-DMG visual
  checks, then capture the corresponding release-version screenshots.
