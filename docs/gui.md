# GUI Reference

The desktop GUI (`app-gui`) is a Swing application with FlatLaf theming. It
provides a visual interface for editing, analyzing, and constructing sequences.

## Launching

```bash
./gradlew :app-gui:runGui                           # from source
java -Xmx8g -jar instagene-gui.jar                  # portable JAR
instagene gui sequence.gb                            # via CLI launcher
```

Installed Windows, macOS, and Linux desktop packages use the GraalVM-native GUI
binary and do not require Java on the researcher's machine. The portable GUI
JAR remains available for environments where a Java 21+ runtime is preferred.

## Menus

### File

- **Open** — Load sequence, annotation, chromatogram, alignment, or lab-note files
- **Save / Save As** — Export the current sequence
- **Recent Files** — Quick access to recently opened files
- **Exit** — Close the application

Files can also be dragged from Finder, Explorer, or a Linux file manager onto
the InstaGene window. macOS app bundles and Linux desktop packages include
file-opening metadata for common sequence/chromatogram extensions. Windows MSI
packages currently install shortcuts; file association support is planned for a
future installer update.

### Edit

- **Undo / Redo** — Document editing history for reversible sequence changes
- **Cut / Copy / Paste** — Standard clipboard operations
- **Select All** — Select the entire sequence

### View

- **Zoom In / Out** — Adjust sequence display size
- **Panels / Theme** — Show or hide supporting panels and switch FlatLaf themes

### Tools

- **Restriction Digest** — Interactive enzyme selection and fragment display
- **Analysis Workspace** — Open analysis workflows, including ORFs, CpG, CRISPR, alignment, and calculators
- **Primer Design** — Design and screen primers with thermodynamic analysis
- **Alignment** — Align sequences with Needleman-Wunsch
- **CpG Analysis** — Detect CpG islands and methylation patterns
- **CRISPR Design** — Guide RNA design with Ruleset 3 scoring

### Graph

Generate interactive analysis charts:

| Chart              | Description                                    |
|--------------------|------------------------------------------------|
| GC Content         | Sliding-window GC percentage                   |
| GC Skew            | G-C skew profile                               |
| Cumulative GC Skew | Cumulative skew showing replication origins     |
| Melting Temperature | Per-position Tm profile                       |
| CpG Density        | Observed/expected CpG ratio per window         |
| CpG Islands        | Island detection with configurable parameters  |

### Theme

Switch between FlatLaf themes at runtime. Themes bundled with
`flatlaf-intellij-themes` are available out of the box.

## Panels

### Sequence View

Displays the nucleotide or amino acid sequence with syntax highlighting:

- **DNA** — A (green), T (red), G (yellow), C (blue)
- **RNA** — A, U, G, C with same coloring
- **Protein** — Amino acids colored by chemistry

### Annotation Panel

Shows annotated features (CDS, gene, promoter, etc.) with position, type, and
strand information. Selecting a feature coordinates with the sequence view.

### Analysis Panel

Orchestrates analysis tools. Each analysis runs in a background thread (SwingWorker)
to keep the UI responsive during computation.

## Keyboard Shortcuts

The app uses the platform menu shortcut key: `Ctrl` on Windows/Linux and
`Command` on macOS.

| Shortcut | Action |
|----------|--------|
| `Ctrl/Cmd+O` | Open file |
| `Ctrl/Cmd+S` | Save |
| `Ctrl/Cmd+Z` | Undo |
| `Ctrl/Cmd+Shift+Z` | Redo |
| `Ctrl/Cmd+Plus` | Zoom in |
| `Ctrl/Cmd+Minus` | Zoom out |

## Theming

The GUI uses [FlatLaf](https://www.formdev.com/flatlaf/) for native-looking
UI on all platforms. Themes are discovered at runtime by scanning the classpath
for FlatLaf theme classes. All IntelliJ IDEA themes bundled with
`flatlaf-intellij-themes` are available out of the box.

## Performance

The GUI uses multi-threaded computation:

- **File I/O** runs off the Swing event thread so large FASTA/GenBank files do
  not block the window while they parse
- **Analysis tasks** run in SwingWorker background threads with cancellation in
  long-running workflows such as alignment, NCBI/BLAST, graphing, and digest
  scans
- **Chart updates** are dispatched on the EDT
- **Digest cut counts** are computed asynchronously and stale results are
  discarded when the active sequence changes
