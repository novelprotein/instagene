# Scientific reference fixtures

Retrieved/generated 2026-09-06. These fixtures are independent of the Kotlin implementation and are read offline by `ScientificReferenceRegressionTest`.

| Fixture | Authority and version | Contract |
| --- | --- | --- |
| `PAM250.txt`, `BLOSUM62.txt` | [Biopython 1.86 matrix data](https://github.com/biopython/biopython/blob/biopython-186/Bio/Align/substitution_matrices/data/PAM250) | All 400 canonical amino-acid scores in each matrix, using the reference file's own column labels |
| `ncbi-codes.tsv` | [NCBI genetic codes](https://www.ncbi.nlm.nih.gov/Taxonomy/Utils/wprintgc.cgi), reference updated 2024-09-23 | Every codon and initiation flag for bundled tables 1, 2, 3, 4, 5, 9, 10, 11, 12; TCAG codon ordering |
| `primer-tm.tsv` | [Biopython 1.86 MeltingTemp](https://github.com/biopython/biopython/blob/biopython-186/Bio/SeqUtils/MeltingTemp.py) | DNA_NN3 terminal/initiation/symmetry parameters; saltcorr=7; concentrations in mol/L; Tm in Celsius |

Thermodynamic reference generation uses `biopython==1.86` outside the application. For each TSV row:

```python
from Bio.Seq import Seq
from Bio.SeqUtils import MeltingTemp as mt
selfcomp = str(Seq(sequence).reverse_complement()) == sequence
ct_nm = total_strand_concentration_m * 1e9
expected = mt.Tm_NN(
    sequence, nn_table=mt.DNA_NN3, saltcorr=7, selfcomp=selfcomp,
    dnac1=ct_nm if selfcomp else ct_nm / 2,
    dnac2=0 if selfcomp else ct_nm / 2,
    Na=na_m * 1000, Mg=mg_m * 1000, dNTPs=dntp_m * 1000,
)
```

Cases cover AT-rich, GC-rich, mixed composition, self-complementary and distinct complementary strands, sodium-only, mixed ions, magnesium-dominant conditions, dNTP chelation, and concentration changes. The numeric tolerance is 1e-8 Celsius. Agreement verifies implementation of this model, not experimental accuracy in every buffer or for modified bases.

`ChromatogramFormatRegressionTest` constructs multibase fixtures directly from the [Staden SCF layout](https://extras.csc.fi/staden/doc/manual/formats_unix_3.html) and [Applied Biosystems ABIF specification](https://archive.gfjc.fiu.edu/workshops/resources/articles/ABIF_File_Format.pdf). SCF cases cover versions 2/3 and 8/16-bit channels; ABI cases cover inline/external data and 1/2/4-byte numeric elements. These are specification fixtures, not instrument recordings.
