# InstaGene

[![CI](https://github.com/novelprotein/instagene/actions/workflows/ci.yml/badge.svg)](https://github.com/novelprotein/instagene/actions/workflows/ci.yml)
![Version](https://img.shields.io/badge/version-0.0.3-blue)
![Java](https://img.shields.io/badge/Java-21+-orange)
![License](https://img.shields.io/badge/license-MIT-green)

InstaGene is a suite of tools for reading, editing, and constructing nucleic
acid and protein sequences. It is built as a reusable engine wrapped in three
independent front-ends:

| Package      | What it is                                             | Run with                              |
|--------------|--------------------------------------------------------|---------------------------------------|
| `engine`     | reusable core library (`org.instagene.core`)           | dependency                            |
| `app-cli`    | command-line tool, good for scripting                  | `./gradlew :app-cli:runCli`           |
| `app-gui`    | desktop front-end for creating/editing sequences (Swing) | `./gradlew :app-gui:runGui`         |
| `app-web`    | GUI-lite, web interface (client and server)            | `./gradlew :app-web:runWeb`           |
| `tests`      | cross-module test suite; a test is added with every bug fix | `./gradlew test`                 |

## Quick start

Install the desktop app from the Linux `.deb` and open a file straight from
the terminal:

```bash
sudo apt install ./instagene_0.0.3_amd64.deb
instagene plasmid.gb
```

Or run the portable self-contained GUI JAR (Java 21+):

```bash
java -Xmx8g -jar instagene-gui.jar
```

Or build from source:

```bash
./gradlew run                    # desktop GUI (default front-end)
./gradlew :app-cli:runCli --args="help"
./gradlew :app-gui:runGui
./gradlew :app-web:runWeb --args="--port 8080"
```

## Project Status

**Current Version:** 0.0.3

**Status:** Work in Progress

### In Progress
- [x] HTML5 GUI
- [x] Graphical User Interface (GUI)
- [x] Test suite
- [ ] Make Cli production ready

### Planned
- [ ] Plugin system
- [ ] Installer icons & signing
- [ ] Performance optimization

## Project structure & engine separation

The core engine is kept separate from the modules that access its features,
separating computation from the human interface. Each platform front-end is its
own standalone application that shares the engine:

* `engine` — the reusable core library (`org.instagene.core`): sequence model, IO, digest, assembly, etc.
* `app-cli` — command-line platform (`./gradlew :app-cli:runCli`).
* `app-gui` — desktop platform, Swing (`./gradlew :app-gui:runGui`).
* `app-web` — web platform: embedded HTTP server + browser UI (`./gradlew :app-web:runWeb`).
* `tests` — integration tests across all modules.

The root `run` task picks exactly one front-end via `-Pplatform=cli|gui|web`
(default `gui`), so `./gradlew run` never launches all three at once. The web
server opens **only** when the web platform is run explicitly
(`:app-web:runWeb`); the desktop and CLI apps never start it. Each front-end
launches exactly once per process.

## Building & running with Gradle

This project uses [Gradle](https://gradle.org/). To build and run the application, use the *Gradle* tool window by
clicking the Gradle icon in the right-hand toolbar, or run it directly from the terminal:

* Run `./gradlew run` to run a single front-end (defaults to the desktop GUI; pick another with `-Pplatform=cli|gui|web`).
* Run `./gradlew :app-cli:runCli [--args="..."]` to run the command-line front-end directly.
* Run `./gradlew :app-gui:runGui [--args="..."]` to run the Swing desktop front-end directly.
* Run `./gradlew :app-web:runWeb --args="--port 8080"` to run the HTML5 web front-end directly
  (`--listen HOST` binds a specific address, `--share` binds all interfaces so other machines on the LAN can connect).
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`). This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

### Using instagene-engine

The engine is published to GitHub Packages as
`org.instagene:instagene-engine`. Installers must authenticate to GitHub
Packages, so add the repository to the consumer's `build.gradle.kts` (or
`settings.gradle.kts`):

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
    // The version is defined once in gradle.properties (instagene.version).
    implementation("org.instagene:instagene-engine:0.0.3")
}
```

Versions: tagged releases publish exactly the `instagene.version` from
`gradle.properties`; every other build publishes `0.0.3-<short-sha>` (with a
`-sources` jar alongside). The engine has a single runtime dependency
(kotlinx-serialization) and the front-end-free API lives in the
`org.instagene.core` package.

### Command-line interface

The CLI (`app-cli`) turns the engine into scriptable tools for gaining insights
from sequences. Run `instagene help` (or `app-cli help`) for the full list of
commands; the main groups are:

* **Inspecting** — `info`, `gc`, `tm`, `orf`, `find`, `align`, `gel`, `identity`, `digest`, `sites`, `enzymes`
* **Editing** — `revcomp`, `complement`, `transcribe`, `translate`, `edit`, `extract`, `annotate`, `topology`, `convert`
* **Building plasmids** — `plasmid`, `gibson`, `golden-gate`, `recombine`, `primers`
* **Extras** — `sample`, `dilute`, `mix`, `blast-url`, `ncbi-search`, `ncbi-fetch`, `tools`, `gui`

When distributed, the CLI's launcher script is named `app-cli` (inside the
`instagene-cli` distribution); symlink or rename it to `instagene` to match the
documented examples.

### Launching the GUI from the command line

`instagene gui [FILE ...]` hands off to the desktop GUI, opening the given
file(s) in the editor:

```bash
instagene gui plasmid.gb
instagene gui --launcher /path/to/InstaGene plasmid.gb
```

The launcher is resolved in this order: `--launcher`, then the
`$INSTAGENE_GUI` environment variable, then an `instagene`/`InstaGene`
executable on `PATH`, then the standard Linux install locations
(`/opt/instagene/bin/instagene`, `/opt/instagene/bin/InstaGene`). If the
launcher is a `.jar`, it is run with `java -jar`. The CLI forwards its own exit
code, so scripts can tell whether the GUI ran successfully.

### Portable GUI JAR

Build the self-contained desktop GUI JAR and run it on any operating system
with Java 21 or newer:

```bash
./gradlew :app-gui:verifyStandaloneJar
java -Xmx8g -jar app-gui/build/distributions/instagene-gui.jar
```

This runnable JAR includes the GUI, engine, Kotlin runtime, serialization, and
FlatLaf dependencies. The separate thin `instagene.jar` remains available for
developer fallback `jpackage` builds, but official platform installers use the
GraalVM native executable.

### Native installers

Official platform installers are built from the GraalVM native GUI executable,
so they do not require a user-installed JRE. Native images cannot
cross-compile, so each installer is built on its own OS:

```bash
./gradlew :app-gui:nativeDeb --no-configuration-cache          # Ubuntu: .deb
./gradlew :app-gui:nativeRpm --no-configuration-cache          # Fedora: .rpm
./gradlew :app-gui:nativeAppImageZip --no-configuration-cache  # portable native app-image zip
./gradlew :app-gui:nativeWindowsMsi --no-configuration-cache   # Windows (WiX): .msi
./gradlew :app-gui:nativeMacDmg --no-configuration-cache       # macOS: .dmg
```

- GraalVM 21 is required to build native packages. CI uses
  `graalvm/setup-graalvm` for all native package jobs.
- Native package outputs are written to `app-gui/build/native-package/dist/`.
- The portable GUI JAR remains JVM-based and is the only app artifact that
  expects Java 21+ on the user's machine.
- macOS app bundles and Linux desktop packages include metadata for common
  researcher file types, including FASTA, GenBank/ApE, GFF3, EMBL/ENA, ABI,
  and SCF files. Windows file association support is planned for a future MSI
  update.
- Windows installers use a stable upgrade UUID, per-user install scope, Start
  Menu group, and shortcuts so upgrades behave consistently.
- macOS builds use a macOS-compatible package version, bundle identifier,
  document metadata, and education app category. Official signing/notarization
  can be layered on top when release secrets are available.
- The Linux `.deb` adds a `/usr/bin/instagene` wrapper and the app image
  contains an `instagene` script, so the desktop app can be launched from a
  terminal (`instagene <file>`); both exec the GraalVM-native `InstaGene`
  binary.

### CI build artifacts

Successful pushes to `master` and manually dispatched CI runs publish
short-lived downloadable artifacts for Linux (`.deb`, `.rpm`, and app-image
zip), Windows (`.msi`), macOS (`.dmg`), and the portable runnable GUI JAR. Pull
requests run the full test suite without the native packaging jobs. Tagged
releases publish the same GUI JAR alongside the native installers and release
checksums.

To grab the latest builds without building anything yourself, open the
[Actions tab](https://github.com/novelprotein/instagene/actions), select the
most recent successful `CI` run, scroll to the bottom **Artifacts** section,
and download the archive that matches your platform: `instagene-gui-jar`,
`instagene-linux-deb`, `instagene-linux-rpm`, `instagene-linux-app-image`,
`instagene-windows-msi`, or `instagene-macos-dmg`. Note that GitHub wraps each
artifact in a `.zip` download.

> **Heads-up:** these are untested snapshots of the latest commit, not stable
> releases. They can be broken or half-finished at any time — use them for
> previews and feedback, and prefer tagged releases for anything you depend on.

## Development

### Automated testing (CI)

Every push and pull request to `master` is checked automatically by the GitHub
Actions workflow in `.github/workflows/ci.yml`: it builds **all** modules
(`engine`, `app-cli`, `app-gui`, `app-web`, `tests`) and runs the **entire test
suite** (including the headless Swing smoke tests) with `./gradlew build` on
JDK 26. Test reports are uploaded as a build artifact whenever a run finishes.

Run the same gate locally at any time:

* `./gradlew build` — compiles every module and runs every check and test.
* `./gradlew verifySeparation` — enforces the separation rules: the engine
  must never reference `org.instagene.app`, the front-ends must never reference
  each other, and the published engine jar must contain only core classes.

### Git rules (local hooks)

The repository ships git hooks under `.githooks/` that run automatically on every
commit, so broken code or sloppy messages cannot slip through:

* `pre-commit` — runs `./gradlew test` (the full suite across all modules).
* `commit-msg` — requires a non-empty, descriptive subject (placeholders such as
  `wip`/`fix`/`stuff` are rejected; generated `Merge`/`Revert` lines pass).

Install them once (they stay in version control, nothing is copied into
`.git/hooks`):

```bash
./scripts/install-hooks.sh
```

Skip the gates for a single commit with `git commit --no-verify`.

### Build & configuration notes

The version is defined once — `instagene.version` in `gradle.properties` — and
surfaces everywhere it is displayed: the CLI `version` command, the `About`
dialog and window titles of the desktop GUI, and the web-server banner.

The CLI accepts two global options: `--env FILE` applies defaults from a
`KEY=VALUE` file (command-line values win) and `--no-colors` forces plain
output (styling is otherwise only used when stdout is a real terminal;
`NO_COLOR` has the same effect).

The shared build logic was extracted to a convention plugin located in
`buildSrc`. Dependencies are declared in the version catalog
(`gradle/libs.versions.toml`); both a build cache and a configuration cache are
enabled (see `gradle.properties`).

## License

InstaGene is released under the [MIT License](LICENSE).
