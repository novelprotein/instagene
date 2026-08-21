# Getting Started

## Prerequisites

- **Java 21** or newer (for building from source)
- **Gradle** (via the included wrapper `./gradlew`)

## Building from Source

```bash
git clone https://github.com/novelprotein/instagene.git
cd instagene
./gradlew build
```

This compiles all modules and runs the full test suite.

## Running

=== "Native desktop installers"

    Download the installer for your operating system from the GitHub Actions
    artifacts or tagged GitHub Releases:

    | Platform | Artifact | Notes |
    |----------|----------|-------|
    | Windows  | `.msi` | GraalVM-native app; installs Start Menu and desktop shortcuts |
    | macOS    | `.dmg` | GraalVM-native app; drag `InstaGene.app` to Applications; registers common sequence/chromatogram file types |
    | Linux    | `.deb`, `.rpm`, native app-image zip | GraalVM-native app; the `.deb` also installs an `instagene` terminal launcher |

    Native installers do not require Java on the researcher's machine. The
    portable GUI JAR is the Java-based fallback and requires Java 21+.

=== "Desktop GUI"

    ```bash
    ./gradlew run                              # default front-end
    ./gradlew :app-gui:runGui                  # explicit
    ```

=== "CLI"

    ```bash
    ./gradlew :app-cli:runCli --args="help"    # list all commands
    ./gradlew :app-cli:runCli --args="info sequence.gb"
    ```

=== "Web"

    ```bash
    ./gradlew :app-web:runWeb --args="--port 8080"
    ```

## Opening Files

The GUI and CLI accept sequence files directly. macOS app bundles and Linux
desktop packages include metadata for common sequence and chromatogram
extensions, so researchers can open supported files from Finder or a Linux file
manager. Windows packages currently install shortcuts; file association support
is planned for a future MSI update.

```bash
# GUI: open files in the editor
./gradlew :app-gui:runGui --args="plasmid.gb genes.fasta"

# CLI: inspect a sequence
./gradlew :app-cli:runCli --args="info plasmid.gb"
```

The desktop window also accepts drag-and-drop files.

## Supported Formats

| Format | Extensions | Notes |
|--------|------------|-------|
| FASTA | `.fa`, `.fasta`, `.fna`, `.fas`, `.faa`, `.seq`, `.txt` | Nucleotide, RNA, protein, and bare pasted bases |
| FASTA alignment | `.aln`, `.afa`, `.msa` | Multi-sequence alignment input |
| GenBank / ApE | `.gb`, `.gbk`, `.genbank`, `.ape` | Features, annotations, topology, plasmid maps |
| GFF3 | `.gff`, `.gff3` | Annotation-centric sequence files |
| EMBL / ENA | `.embl`, `.ena` | EMBL flat-file records |
| Swiss-Prot | `.swiss`, `.sprot`, `.dat` | Protein records |
| Chromatograms | `.ab1`, `.abi`, `.scf` | Sanger trace files |
| Notes / lab documents | `.md`, `.markdown`, `.notes`, `.log`, images, PDFs | Opened in the in-app text editor or delegated to the OS |

## Gradle Tasks Reference

| Task                          | Description                          |
|-------------------------------|--------------------------------------|
| `./gradlew run`               | Run the default front-end (GUI)      |
| `./gradlew build`             | Build and test everything            |
| `./gradlew check`             | Run all checks including tests       |
| `./gradlew :app-cli:runCli`   | Run the CLI                          |
| `./gradlew :app-gui:runGui`   | Run the desktop GUI                  |
| `./gradlew :app-web:runWeb`   | Run the web server                   |
| `./gradlew :app-cli:bench`    | Run performance benchmarks           |
| `./gradlew :app-gui:nativeDeb` | Build the GraalVM-native Linux `.deb` |
| `./gradlew :app-gui:nativeWindowsMsi` | Build the GraalVM-native Windows `.msi` |
| `./gradlew :app-gui:nativeMacDmg` | Build the GraalVM-native macOS `.dmg` |
