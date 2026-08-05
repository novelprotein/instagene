# instagene
vibe coded gene editing cli and gui tool, which does not work, ran out of tokens and might never be done


## Project Status

**Current Version:** 0.1.0-alpha

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
- [ ] Graphical User Interface (GUI)
- [ ] Harden IO
- [ ] Editing Workflow
- [ ] Make Cli production ready
- [ ] Package and release
- [ ] Test suite
- [ ] Full application build
- [ ] Library integration (using the engine as a reusable library)

### Planned
- [ ] Plugin system
- [ ] HTML5 GUI
- [ ] Cross-platform installers
- [ ] Performance optimization
- [ ] Improved documentation


This project uses [Gradle](https://gradle.org/). To build and run the application, use the *Gradle* tool window by
clicking the Gradle icon in the right-hand toolbar, or run it directly from the terminal:

* Run `./gradlew run` to build and run the application.
* Run `./gradlew build` to only build the application.
* Run `./gradlew check` to run all checks, including tests.
* Run `./gradlew clean` to clean all build outputs.

Note the usage of the Gradle Wrapper (`./gradlew`). This is the suggested way to use Gradle in production projects.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

[Learn more about Gradle tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#common_tasks).

This project follows the suggested multi-module setup and consists of the `app` and `utils` subprojects. The shared
build logic was extracted to a convention plugin located in `buildSrc`.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies and both a
build cache and a configuration cache (see `gradle.properties`).