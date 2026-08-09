# InstaGene


This project contains 5 packages:
- InstaGene-CLI is a tool for gaining insights from nucleic acid sequences or protein sequences, good for scripting.
- InstaGene-GUI is a tool for creating and manipulating nucleic acid sequences or protein sequences.
- InstaGene-WEB is a tool functioning as an InstaGene-GUI lite and uses the web as an interface. It is a client and server.
- InstaGene-Engine is a library which functions as the backbone for the project. It does not force the other projects as a dependency, allowing it to be used in other gene editing projects without the overhead of an entire program.
- InstaGene-Test is a unit testing suit which tests each project in this repo for bugs automatically. Tests are added each time a bug is discovered to prevent reintroduction of similar bugs.


## Project Status

**Current Version:** 0.0.0-alpha

**Status:** Work in Progress

### Completed
- [x] Core engine
- [x] IO
- [x] CodonTables
- [x] Sequence parser
- [x] Project structure
- [x] Documentation
- [x] Basic CLI

### In Progress
- [x] HTML5 GUI
- [x] Graphical User Interface (GUI)
- [x] Test suite
- [ ] Harden IO
- [ ] Editing Workflow
- [ ] Make Cli production ready
- [ ] Package and release
- [ ] Library integration (using the engine as a reusable library)
- [ ] Move 100% of features into respective modules

### Planned
- [ ] Plugin system
- [ ] Cross-platform installers
- [ ] Performance optimization
- [ ] Improved documentation
- [ ] Versioning system


This project uses [Gradle](https://gradle.org/). To build and run the application, use the *Gradle* tool window by
clicking the Gradle icon in the right-hand toolbar, or run it directly from the terminal:

* Run `./gradlew run` to run a single front-end (defaults to the desktop GUI; pick another with `-Pplatform=cli|gui|web`).
* Run `./gradlew :app-cli:runCli [--args="..."]` to run the command-line front-end directly.
* Run `./gradlew :app-gui:runGui [--args="..."]` to run the Swing desktop front-end directly.
* Run `./gradlew :app-web:runWeb --args="--port 8080"` to run the HTML5 web front-end directly.
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`). This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

### Automated testing (CI)

Every push and pull request to `master` is checked automatically by the GitHub
Actions workflow in `.github/workflows/ci.yml`: it builds **all** modules
(`engine`, `app-cli`, `app-gui`, `app-web`, `tests`) and runs the **entire test
suite** (including the headless Swing smoke tests) with `./gradlew build` on JDK
26. Test reports are uploaded as a build artifact whenever a run finishes.

Run the same gate locally at any time:

* `./gradlew build` — compiles every module and runs every check and test.

### Git rules (local hooks)

The repository ships git hooks under `.githooks/` that run automatically on every
commit, so broken code or sloppy messages cannot slip through:

* `pre-commit` — runs `./gradlew test` (the full suite across all modules) and
  rejects trailing-whitespace errors in the staged diff.
* `commit-msg` — requires a non-empty, descriptive subject (placeholders such as
  `wip`/`fix`/`stuff` are rejected; generated `Merge`/`Revert` lines pass).

Install them once (they stay in version control, nothing is copied into
`.git/hooks`):

```bash
./scripts/install-hooks.sh
```

Skip the gates for a single commit with `git commit --no-verify`.

This project has the core engine separated from the modules which access features from it. This separates the computation tasks from the human interface.
Each platform front-end is its own standalone application that shares
the engine:

* `engine` — the reusable core library (`org.instagene.core`): sequence model, IO, digest, assembly, etc.
* `app-cli` — command-line platform (`./gradlew :app-cli:runCli`).
* `app-gui` — desktop platform, Swing (`./gradlew :app-gui:runGui`).
* `app-web` — web platform: embedded HTTP server + browser UI (`./gradlew :app-web:runWeb`).
* `tests` — integration tests across all modules.

The root `run` task picks exactly one front-end via `-Pplatform=cli|gui|web` (default `gui`),
so `./gradlew run` never launches all three at once. The web server opens **only** when the web
platform is run explicitly (`:app-web:runWeb`); the desktop and CLI apps never start it. Each front-end launches exactly once per process.

The shared build logic was extracted to a convention plugin located in `buildSrc`.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies and both a
build cache and a configuration cache (see `gradle.properties`).