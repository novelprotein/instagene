# Reproducible workflows

## Written protocols and procedures

Open **Project → Workflow Library…**, or search for **Workflow Library** in the
command palette. This personal library is available without an open sequence
or project, and is shared across projects on this computer.

Choose **New Protocol** for notes and an ordered list of steps, or **New Procedure**
for general written instructions. Both support a title and plain text, including
Markdown notation (shown as text). Protocol steps have a title and instructions;
use **Add step**, **Edit step**, **Remove step**, **Move up**, and **Move down** to
maintain them. Incomplete protocols can be saved as long as they have a title.

Click **Save** to keep changes. Switching entries or closing with unsaved work
offers Save, Discard, and Cancel. Search includes titles, notes, and step text;
the type filter narrows the list to protocols or procedures. **Duplicate** opens
a new copy to edit and save. **Delete** asks for confirmation.

Entries persist in `workflow-library.json` alongside application preferences.
Include this file in personal backups. A load or save error is reported without
silently replacing existing data. This library stores written instructions;
it does not run protocols or track their execution.

## Computational workflow recipes

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
