# Contributing

InstaGene is a Kotlin/Gradle project with a reusable engine and separate
front ends. Contributions should keep scientific behavior explicit, testable,
and honest about what the application supports.

## Development setup

Requirements:

- JDK 21;
- Python 3.12 with `python3 -m pip install -r docs/requirements.txt`;
- Bash (Git Bash on Windows).

~~~bash
git clone https://github.com/novelprotein/instagene.git
cd instagene
bash scripts/check-ci.sh
~~~

On Windows, run the shell scripts in Git Bash.

Install the repository hooks to enforce checks for Git and IDE commits:

~~~bash
bash scripts/install-hooks.sh
~~~

The pre-commit hook runs a fresh full test suite, the full Gradle build, strict
documentation validation, host-supported distribution checks, and hook integration
tests. All tracked
changes must be staged before committing. A failed check, missing prerequisite,
or change to tracked files or the staged snapshot during validation blocks the
commit. Merge, cherry-pick, and revert commits use the same gate.

For IDE commits that do not inherit your terminal environment, persist runtime
paths in this checkout (use your own installation paths):

~~~bash
git config --local instagene.javaHome "C:/path/to/jdk-21"
git config --local instagene.python "C:/path/to/python.exe"
~~~

`JAVA_HOME` and `INSTAGENE_PYTHON` environment variables override these settings.
Checks never install dependencies automatically. Git's explicit `--no-verify`
bypass remains available; normal commits run the gate.

Portable artifacts and file associations are checked on every host. Linux also
checks DEB and app-image packages. Native Windows/macOS installers and platform
differences, including Linux/GraalVM behavior, remain covered by CI. Opt-in
performance and memory tests keep their normal exclusions.

## Module boundaries

- engine/ contains front-end-free sequence and workflow logic.
- app-cli/ contains the scriptable command line.
- app-gui/ contains the Swing desktop application.
- app-web/ contains the embedded web front end.
- tests/ contains cross-module and integration coverage.

The engine must not reference org.instagene.app, and front ends must not
depend on one another. Check the rule with:

~~~bash
bash scripts/compile.sh all verifySeparation
~~~

## Tests and regression coverage

Run the complete gate before opening a pull request:

~~~bash
bash scripts/check-ci.sh
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
bash scripts/check-docs.sh
~~~

The screenshots in `docs/screenshots/` are documentation assets. Use
descriptive alt text and keep image references relative to the page.

## Code style and pull requests

Prefer clear Kotlin and small, testable functions. Keep imports focused and
avoid moving heavy computation onto Swing’s event dispatch thread.

Before opening a pull request:

1. run `bash scripts/check-ci.sh` for the complete local gate;
2. run `python3 scripts/test-hooks.py` when changing hook or gate behavior;
3. describe user-visible behavior and limitations in the pull request.

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
