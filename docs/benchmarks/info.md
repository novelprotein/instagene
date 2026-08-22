# Benchmarks

InstaGene includes a built-in performance benchmark suite for representative
engine operations. Results are useful for comparing commits under similar
conditions; they are not a promise of fixed performance on every machine.

## Running benchmarks

```bash
# Run with the built-in test sequence
./gradlew :app-cli:bench

# Run with your own sequence
./gradlew :app-cli:bench -Pinput=sequence.fa
```

The CLI benchmark command uses the bundled sample sequence unless an input is
provided. The CI workflow may publish results to the dashboard after a
successful benchmark run.

## Dashboard

The interactive benchmark dashboard is hosted on GitHub Pages and shows
historical performance trends:

[View Benchmark Dashboard](https://novelprotein.github.io/instagene/benchmarks/)

The dashboard is automatically updated on every push to `master` via the CI
workflow.

## What is measured

| Category        | Operations                                         |
|-----------------|----------------------------------------------------|
| Sequence I/O    | FASTA read/write, GenBank read/write               |
| Digest          | Single enzyme and catalog scans                    |
| Alignment       | Pairwise NW, multiple query alignment              |
| Statistics      | GC content, CpG islands, Shannon entropy           |
| Search          | Forward/reverse pattern search, amino acid search  |
| Translation     | Codon table translation, ORF finding               |
| Primer          | Melting temperature, hairpin assessment, self-dimer |

## Interpreting results

- **ms** — milliseconds (lower is better)
- **Regression flag** — the dashboard flags operations that slow down by >15%
- **Baseline comparison** — each run is compared against the previous commit
