# Benchmarks

InstaGene includes a built-in performance benchmark suite that tracks
execution time across all major engine operations.

## Running Benchmarks

```bash
# Run with the built-in test sequence
./gradlew :app-cli:bench

# Run with your own sequence
./gradlew :app-cli:bench -Pinput=sequence.fa
```

Benchmarks are run automatically as part of the CI pipeline. Each operation is
measured 3 times and averaged.

## Dashboard

The interactive benchmark dashboard is hosted on GitHub Pages and shows
historical performance trends:

[View Benchmark Dashboard](https://novelprotein.github.io/instagene/benchmarks/)

The dashboard is automatically updated on every push to `master` via the CI
workflow.

## What Is Measured

| Category        | Operations                                         |
|-----------------|----------------------------------------------------|
| Sequence I/O    | FASTA read/write, GenBank read/write               |
| Digest          | Single enzyme, full catalog (48 enzymes)           |
| Alignment       | Pairwise NW, multiple query alignment              |
| Statistics      | GC content, CpG islands, Shannon entropy           |
| Search          | Forward/reverse pattern search, amino acid search  |
| Translation     | Codon table translation, ORF finding               |
| Primer          | Melting temperature, hairpin assessment, self-dimer |

## Interpreting Results

- **ms** — milliseconds (lower is better)
- **Regression flag** — operations that slow down by >15% are flagged
- **Baseline comparison** — each run is compared against the previous commit
