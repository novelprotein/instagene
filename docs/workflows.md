# Reproducible workflows

InstaGene records the history embedded in a sequence and can export reports for
assembly and Sanger verification. The engine also exposes a small JSON workflow
recipe format for local pipelines, ELN attachments, and project review.

A recipe records:

- operation name and schema version;
- each input's display name and CD-SEGUID identity;
- sorted user parameters;
- the product identity and embedded procedure history; and
- optional external-tool versions.

Recipes deliberately do not embed sequence contents or execute arbitrary
commands. To replay a recipe, a caller supplies the input sequences and first
checks that their CD-SEGUID identities match the recorded inputs. This keeps a
recipe portable and reviewable while avoiding a hidden copy of potentially large
or sensitive samples.

## External tools

External tools, including Primer3 and multiple-sequence aligners, remain
optional. A report or recipe should record the command/version when an external
backend was selected. The GUI and CLI preserve a built-in fallback for Primer3,
and surface that fallback as a warning rather than silently claiming an external
result.

## Alignment export

The Alignment tool computes a consensus and conservation guide after an
alignment. It renders ties as `N` for nucleotides or `X` for proteins, instead
of arbitrarily choosing a residue. Use **Export aligned FASTA** to save the
gapped alignment while retaining row names.
