# InstaGene

[![CI](https://github.com/novelprotein/instagene/actions/workflows/ci.yml/badge.svg)](https://github.com/novelprotein/instagene/actions/workflows/ci.yml)
![Version](https://img.shields.io/badge/version-0.0.4-blue)
![Java](https://img.shields.io/badge/Java-21+-orange)
![License](https://img.shields.io/badge/license-MIT-green)

InstaGene is a toolkit for reading, editing and analyzing DNA, RNA, and protein sequences. It combines a researcher-focused
desktop GUI with a scriptable CLI, a local web front end, and a reusable engine all written in Kotlin.

![InstaGene sequence workspace](docs/screenshots/seq.png)

## Start here

- [Documentation site](https://novelprotein.github.io/instagene/)
- [Getting Started](docs/getting-started.md)
- [Desktop GUI guide](docs/gui.md)
- [CLI reference](docs/cli.md)
- [Engine API](docs/engine-api.md)

## Install

Tagged GitHub Releases provide native packages for:

| Platform | Package | Runtime |
|---|---|---|
| Windows | MSI | Bundled Java runtime |
| macOS | DMG | Bundled Java runtime |
| Linux | DEB, RPM, app-image archive | Bundled Java runtime |
| Any desktop OS | Portable GUI JAR | Java 21+ required |

CI artifacts are previews of a specific commit. Prefer tagged releases for
workflows that need a stable distribution.

## Build and run

The repository includes the Gradle wrapper. Java 21 or newer is required for
source builds.

~~~bash
git clone https://github.com/novelprotein/instagene.git
cd instagene
./gradlew build
~~~

Run the front ends directly:

~~~bash
./gradlew :app-gui:runGui
./gradlew :app-cli:runCli --args="help"
./gradlew :app-web:runWeb --args="--port 8080"
~~~

On Windows, use `gradlew.bat`.

## Project layout

| Module | Role |
|---|---|
| engine | Front-end-free sequence models, I/O, analysis, and workflows |
| app-gui | Swing desktop application |
| app-cli | Scriptable command-line application |
| app-web | Embedded local web application |
| tests | Cross-module and integration test suite |

The engine must not depend on the front ends, and front ends do not depend on
one another. Check the boundary with:

~~~bash
./gradlew verifySeparation
~~~

## Highlights

- FASTA, GenBank/ApE, GFF3, EMBL/ENA, Swiss-Prot, alignment, and chromatogram
  workflows;
- sequence editing, annotations, primers, molecule properties, and provenance;
- circular plasmid maps, restriction mapping, digest fragments, and virtual
  gels;
- primer design, alignment, Sanger review, CRISPR guides, ORFs, CpG, and
  sequence statistics;
- Gibson, Golden Gate, recombination, domestication, and other cloning
  workflows;
- optional external converters and command-line tool integrations.



## Useful commands

~~~bash
./gradlew :app-gui:verifyStandaloneJar
java -Xmx8g -jar app-gui/build/distributions/instagene-gui.jar
./gradlew :app-cli:bench
mkdocs build --strict --site-dir site
~~~

See [Contributing](docs/contributing.md) for testing, documentation, and pull
request requirements.

## License

InstaGene is released under the [MIT License](LICENSE).
