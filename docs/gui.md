# GUI Reference

The desktop GUI (`app-gui`) is a Swing application with FlatLaf theming. It
provides a visual interface for editing, analyzing, and constructing sequences.

## Launching

```bash
./gradlew :app-gui:runGui                           # from source
java -Xmx8g -jar instagene-gui.jar                  # portable JAR
instagene gui sequence.gb                            # via CLI launcher
```

## Menus

### File

- **Open** — Load a sequence file (FASTA, GenBank, plain text)
- **Save / Save As** — Export the current sequence
- **Recent Files** — Quick access to recently opened files
- **Exit** — Close the application

### Edit

- **Undo / Redo** — Full edit history with unlimited undo
- **Cut / Copy / Paste** — Standard clipboard operations
- **Select All** — Select the entire sequence

### View

- **Zoom In / Out** — Adjust sequence display size
- **Wrap Lines** — Toggle line wrapping in the sequence view

### Tools

- **Restriction Digest** — Interactive enzyme selection and fragment display
- **ORF Finder** — Find open reading frames with configurable parameters
- **Primer Design** — Design and screen primers with thermodynamic analysis
- **Alignment** — Align sequences with Needleman-Wunsch
- **CpG Analysis** — Detect CpG islands and methylation patterns
- **CRISPR Design** — Guide RNA design with Ruleset 3 scoring

### Graph

Generate publication-quality charts:

| Chart              | Description                                    |
|--------------------|------------------------------------------------|
| GC Content         | Sliding-window GC percentage                   |
| GC Skew            | G-C skew profile                               |
| Cumulative GC Skew | Cumulative skew showing replication origins     |
| Melting Temperature | Per-position Tm profile                       |
| CpG Density        | Observed/expected CpG ratio per window         |
| CpG Islands        | Island detection with configurable parameters  |

### Theme

Switch between installed FlatLaf themes at runtime. All IntelliJ themes
installed on the system are automatically discovered.

## Panels

### Sequence View

Displays the nucleotide or amino acid sequence with syntax highlighting:

- **DNA** — A (green), T (red), G (yellow), C (blue)
- **RNA** — A, U, G, C with same coloring
- **Protein** — Amino acids colored by chemistry

### Annotation Panel

Shows annotated features (CDS, gene, promoter, etc.) with position, type, and
strand information. Features are draggable for reordering.

### Analysis Panel

Orchestrates analysis tools. Each analysis runs in a background thread (SwingWorker)
to keep the UI responsive during computation.

## Keyboard Shortcuts

| Shortcut         | Action              |
|------------------|---------------------|
| `Ctrl+O`         | Open file           |
| `Ctrl+S`         | Save                |
| `Ctrl+Z`         | Undo                |
| `Ctrl+Shift+Z`   | Redo                |
| `Ctrl+Plus`      | Zoom in             |
| `Ctrl+Minus`     | Zoom out            |

## Theming

The GUI uses [FlatLaf](https://www.formdev.com/flatlaf/) for native-looking
UI on all platforms. Themes are discovered at runtime by scanning the classpath
for FlatLaf theme classes. All IntelliJ IDEA themes bundled with
`flatlaf-intellij-themes` are available out of the box.

## Performance

The GUI uses multi-threaded computation:

- **Analysis tasks** run in SwingWorker background threads
- **Chart updates** are dispatched on the EDT
- **Engine parallelism** uses coroutines for CPU-bound operations
