# CLI Reference

The command-line interface (`app-cli`) provides scriptable access to all engine
features. Run `instagene help` for the full command list.

## Global Options

| Option           | Description                                      |
|------------------|--------------------------------------------------|
| `--env FILE`     | Load defaults from a `KEY=VALUE` environment file |
| `--no-colors`    | Disable ANSI color output                        |

## Inspecting Sequences

### info

Display sequence metadata: name, length, topology, base composition, GC content.

```bash
instagene info sequence.gb
instagene info --format fasta sequence.txt
```

### gc

Compute GC content, GC skew profile, and cumulative GC skew.

```bash
instagene gc sequence.gb
instagene gc --window 500 sequence.gb
```

### orf

Find open reading frames in all reading frames.

```bash
instagene orf --min-aa 30 sequence.gb
instagene orf --table STANDARD --both-strands sequence.gb
```

### find

Search for a degenerate or literal pattern on one or both strands.

```bash
instagene find --pattern "ATCGNR" sequence.gb
instagene find --pattern "MVLSPADK" --mode amino-acid protein.fa
```

### align

Align multiple sequences using Needleman-Wunsch with affine gap penalties.

```bash
instagene align reference.gb query1.gb query2.gb
```

### identity

Compute pairwise sequence identity between aligned sequences.

```bash
instagene identity aligned.fa
```

## Restriction Mapping

### digest

Perform restriction enzyme digestion and display fragments.

```bash
instagene digest --enzyme EcoRI sequence.gb
instagene digest --enzymes EcoRI,HindIII,BamHI sequence.gb
```

### sites

Show all recognition sites for one or more enzymes.

```bash
instagene sites --enzyme EcoRI sequence.gb
instagene sites --enzymes ALL --unique sequence.gb
```

### enzymes

Analyze enzyme cutting frequency across the full catalog.

```bash
instagene enzymes --times 1 sequence.gb
```

### gel

Simulate a restriction digest gel electrophoresis.

```bash
instagene gel --enzyme EcoRI --ladder ladder.gb sequence.gb
```

## Editing

### revcomp

Reverse complement a sequence.

```bash
instagene revcomp sequence.gb
```

### complement

Complement a sequence.

```bash
instagene complement sequence.gb
```

### transcribe

Transcribe DNA to RNA.

```bash
instagene transcribe sequence.gb
```

### translate

Translate nucleotide sequence to protein.

```bash
instagene translate --table STANDARD sequence.gb
```

### annotate

Annotate features on a sequence.

```bash
instagene annotate --region 100..500 --label "Promoter" sequence.gb
```

## Plasmid Construction

### plasmid

Design a circular plasmid map.

```bash
instagene plasmid --backbone backbone.gb --insert insert.gb
```

### gibson

Design a Gibson assembly.

```bash
instagene gibson --fragments frag1.gb,frag2.gb,frag3.gb
```

### golden-gate

Design a Golden Gate assembly.

```bash
instagene golden-gate --enzyme BsaI --fragments frag1.gb,frag2.gb
```

### primers

Design primers for PCR amplification.

```bash
instagene primers --target region.gb --tm 60
```

## Primer Analysis

### tm

Compute melting temperature using nearest-neighbor thermodynamics.

```bash
instagene tm "ATCGATCGATCG"
```

### screen

Full primer screening: hairpin, self-dimer, cross-dimer, delta-G stability.

```bash
instagene screen "ATCGATCGATCG"
```

## Performance

### bench

Run built-in performance benchmarks.

```bash
instagene bench                    # use built-in test sequence
instagene bench sequence.gb        # benchmark with your sequence
```

See the [Benchmarks](benchmarks/index.md) page for historical performance data.
