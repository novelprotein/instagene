# Benchmarks

InstaGene includes a built-in performance benchmark suite for representative
engine operations. Results are useful for comparing commits under similar
conditions; they are not a promise of fixed performance on every machine.

## Running benchmarks

```bash
# Run engine and desktop-viewport benchmarks with the built-in test sequence
./gradlew :app-cli:bench :app-gui:desktopBench

# Run with your own sequence
./gradlew :app-cli:bench :app-gui:desktopBench -Pinput=sequence.fa
```

The CLI benchmark command uses the bundled sample sequence unless an input is
provided. `desktopBench` measures first and scrolled Swing sequence viewports
at the 10 kb plasmid and 100 kb construct workload classes. The CI workflow
publishes both result groups to the same dashboard after a successful run.

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
| Desktop         | First and scrolled viewport paint for 10 kb/100 kb sequence views |

## Interpreting results

- **ms** — milliseconds (lower is better)
- **Regression flag** — the dashboard flags operations that slow down by >15%
- **Baseline comparison** — each run is compared against the previous commit

## Memory-profile runs

The normal test suite includes focused asynchronous large-FASTA and full-panel
genome regressions. For an intentional allocation/heap observation run across
large FASTA, GenBank, and SCF trace batches, use the opt-in profile test:

```bash
./gradlew :tests:test --tests '*MemoryProfileTest' \
  -Dinstagene.memoryProfile=true -Dinstagene.heap=2g
```

The profile prints retained-heap snapshots for the current JVM; compare runs on
the same machine and JVM. It is a diagnostic aid, not a cross-machine memory
guarantee.
