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

The GUI and CLI accept sequence files directly:

```bash
# GUI: open files in the editor
./gradlew :app-gui:runGui --args="plasmid.gb genes.fasta"

# CLI: inspect a sequence
./gradlew :app-cli:runCli --args="info plasmid.gb"
```

## Supported Formats

| Format    | Extension | Notes                        |
|-----------|-----------|------------------------------|
| FASTA     | `.fa`, `.fasta`, `.fna`, `.faa` | Nucleotide or protein |
| GenBank   | `.gb`, `.gbk`, `.genbank` | Features, annotations, topology |
| Plain     | `.txt`, `.seq` | Raw sequence text           |

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
