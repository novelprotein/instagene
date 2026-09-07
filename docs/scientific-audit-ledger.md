# Scientific audit ledger

This ledger records the evidence status of scientific features. Numerical
predictions are only exposed when the implementation and its assumptions are
identified; otherwise results are labelled as heuristics or unavailable.

| Feature | Engine entry point | GUI surface | Evidence status | Limitations / follow-up |
| --- | --- | --- | --- | --- |
| Genetic-code translation | `CodonTable`, `SeqOps.translate` | Translation and feature panels | Bundled NCBI tables corrected; regression coverage expanded | Independent comparison of every advertised codon and initiation set remains recommended |
| Primer thermodynamics | `PrimerThermodynamics` | Primer design panels | SantaLucia nearest-neighbor and single salt correction implemented | Conditions and concentration conventions must be shown |
| CRISPR guide discovery | `CrisprDesign` | CRISPR analysis panel | Heuristic discovery only | SpCas9 NGG sequence screening is not an activity or off-target predictor |
| Site domestication | `SiteDomestication` | Domestication analysis panel | Coding-context validation in progress | Silent changes require explicit CDS strand/frame/code context |
| Restriction and methylation | `Digest`, `MethylationRules`, `HostMethylationInferenceRules` | Digest and Info panels | Curated rule table | Context-sensitive methylation and overlapping sites remain source-dependent |
| Chromatogram parsing | `ChromatogramReader`, `SangerAlignment` | Sanger tools | SCF v2/v3 and ABIF layout corrections implemented | Independently produced ABI/SCF files must still cover versions and sample widths |
| Molecular calculations | `SeqOps.molecularWeight`, `MolecularCalculators` | Info and calculator panels | Strand-aware mass and absorbance-unit corrections implemented | Units, topology, strandedness, and end chemistry must be explicit |
| Sequence statistics | `SequenceStatistics` | Statistics panels | Algorithm correction in progress | Optimized windows require independent reference fixtures |
| Pairwise/multiple alignment | `Alignment`, `MultipleAlignment`, `AlignmentScoring` | Alignment panels | PAM250 reference matrix corrected | Multiple alignment column merging needs reference regressions |
| Golden Gate screening | `GoldenGateFidelity` | Golden Gate workflows | Qualitative screen | Experimental fidelity percentages are unavailable without conditions |
| Cloning workflows | `CloningWorkflows` | Workflow panels | Simulation / construction semantics | Generic operations must not be labelled as chemistry-specific protocols |

Infrastructure features such as ABI/SCF parsing and external-tool execution
require software-format or backend verification in addition to scientific
citations.
