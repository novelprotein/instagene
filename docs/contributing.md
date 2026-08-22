# Contributing

InstaGene is a Kotlin/Gradle project with a reusable engine and separate
front ends. Contributions should keep scientific behavior explicit, testable,
and honest about what the application supports.

## Development setup

Requirements:

- Java 21 or newer;
- a checkout of the repository; and
- the included Gradle wrapper.

~~~bash
git clone https://github.com/novelprotein/instagene.git
cd instagene
./gradlew build
~~~

On Windows, use `gradlew.bat`.

Install the repository hooks if you want the local commit checks:

~~~bash
./scripts/install-hooks.sh
~~~

## Module boundaries

- engine/ contains front-end-free sequence and workflow logic.
- app-cli/ contains the scriptable command line.
- app-gui/ contains the Swing desktop application.
- app-web/ contains the embedded web front end.
- tests/ contains cross-module and integration coverage.

The engine must not reference org.instagene.app, and front ends must not
depend on one another. Check the rule with:

~~~bash
./gradlew verifySeparation
~~~

## Tests and regression coverage

Run the complete gate before opening a pull request:

~~~bash
./gradlew build
./gradlew :tests:test
~~~

Every bug fix should include a focused regression test. Headless Swing tests
construct and exercise GUI panels without requiring a display. Long-running
large-sequence tests may have explicit timeouts; do not make the event
dispatch thread wait for background analysis.

## Documentation

Documentation should explain the application primarily from a researcher's
perspective while remaining accurate for contributors. Avoid claims such as
“all formats,” “publication quality,” or “validated experimentally” unless the
implementation and tests explicitly support them.

Build the documentation with:

~~~bash
mkdocs build --strict --site-dir site
~~~

The screenshots in `docs/screenshots/` are documentation assets. Use
descriptive alt text and keep image references relative to the page.

## Code style and pull requests

Prefer clear Kotlin and small, testable functions. Keep imports focused and
avoid moving heavy computation onto Swing’s event dispatch thread.

Before opening a pull request:

1. run the relevant tests;
2. run `./gradlew build`;
3. run the strict MkDocs build;
4. describe user-visible behavior and limitations in the pull request.

## Benchmarks

Run the CLI benchmark task locally:

~~~bash
./gradlew :app-cli:bench
./gradlew :app-cli:bench -Pinput=sequence.fa
~~~

The CI workflow records benchmark output. Benchmark numbers depend on hardware,
input size, JVM, and the current commit. Compare trends rather than treating a
single run as an absolute performance claim. GitHub Actions runners are broadly
similar, but relative performance should be compared only across similar
hardware.
