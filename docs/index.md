# InstaGene

[![CI](https://github.com/novelprotein/instagene/actions/workflows/ci.yml/badge.svg)](https://github.com/novelprotein/instagene/actions/workflows/ci.yml)
![Version](https://img.shields.io/badge/version-0.0.3-blue)
![Java](https://img.shields.io/badge/Java-21+-orange)
![License](https://img.shields.io/badge/license-MIT-green)

A suite of tools for reading, editing, and constructing nucleic acid and protein
sequences, built as a reusable engine with three independent front-ends.

## Quick Start

=== "Desktop GUI"

    Install the Linux `.deb` and open files from the terminal:

    ```bash
    sudo apt install ./instagene_0.0.3_amd64.deb
    instagene plasmid.gb
    ```

    Or run the portable JAR (Java 21+):

    ```bash
    java -Xmx8g -jar instagene-gui.jar
    ```

=== "CLI"

    Build from source and inspect a sequence:

    ```bash
    ./gradlew :app-cli:runCli --args="info sequence.gb"
    ./gradlew :app-cli:runCli --args="digest --enzyme EcoRI sequence.gb"
    ```

=== "Web"

    Start the embedded web server:

    ```bash
    ./gradlew :app-web:runWeb --args="--port 8080"
    ```

    Open `http://localhost:8080` in your browser.

## Architecture

| Module     | Description                                     | Run with                              |
|------------|------------------------------------------------|---------------------------------------|
| `engine`   | Reusable core library (`org.instagene.core`)   | dependency                            |
| `app-cli`  | Command-line tool, scriptable                  | `./gradlew :app-cli:runCli`           |
| `app-gui`  | Desktop front-end (Swing + FlatLaf)            | `./gradlew :app-gui:runGui`           |
| `app-web`  | Web interface (embedded HTTP server)           | `./gradlew :app-web:runWeb`           |
| `tests`    | Cross-module integration test suite            | `./gradlew test`                      |

## Key Features

- **Sequence I/O** — FASTA, GenBank/ApE, GFF3, EMBL/ENA, Swiss-Prot, alignments, and chromatograms
- **Restriction mapping** — enzyme catalog, CpG methylation analysis, virtual gel
- **Alignment** — Needleman-Wunsch with affine gap penalties, Sanger read alignment
- **Primer design** — melting temperature, thermodynamic screening, primer design
- **CRISPR** — Ruleset 3 guide RNA scoring and PAM site detection
- **ORF finding** — all six reading frames, customizable codon tables
- **Plasmid construction** — Golden Gate, Gibson assembly, recombination
- **Genome statistics** — GC content, CpG islands, Shannon entropy, tandem repeats
- **Theming** — runtime theme switching with bundled FlatLaf themes

## Installation

Pre-built installers are available from the
[GitHub Releases](https://github.com/novelprotein/instagene/releases) page:

| Platform | Format | Notes |
|----------|--------|-------|
| Linux    | `.deb` | GraalVM-built desktop app, Ubuntu/Debian, includes `/usr/bin/instagene` wrapper |
| Linux    | `.rpm` | GraalVM-built desktop app, Fedora/RHEL |
| Linux    | App-image zip | GraalVM-built desktop app, portable, no install required |
| Windows  | `.msi` | GraalVM-built desktop app, WiX-based installer with shortcuts |
| macOS    | `.dmg` | GraalVM-built desktop app bundle with file associations |
| Any      | GUI JAR | `java -Xmx8g -jar instagene-gui.jar` |
| Linux    | Native CLI | GraalVM-native `instagene` CLI executable |

## Using the Engine Library

The engine is published to GitHub Packages as `org.instagene:instagene-engine`.
Add it to your project:

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/novelprotein/instagene")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("org.instagene:instagene-engine:0.0.3")
}
```
