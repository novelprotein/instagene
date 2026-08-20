# Contributing

## Development Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/novelprotein/instagene.git
   cd instagene
   ```

2. Install git hooks:
   ```bash
   ./scripts/install-hooks.sh
   ```

3. Build and test:
   ```bash
   ./gradlew build
   ```

## Architecture Rules

The project enforces strict separation between the engine and front-ends:

- **Engine** (`engine/`) must never reference `org.instagene.app`
- **Front-ends** must never reference each other
- The published engine jar must contain only core classes

Run `./gradlew verifySeparation` to check these rules.

## Running Tests

```bash
./gradlew test                   # all tests
./gradlew :tests:test            # cross-module tests only
./gradlew :engine:test           # engine tests only
```

## Adding Tests

Every bug fix should include a regression test. Tests live in the `tests`
module under `tests/src/test/kotlin/`. Shared test utilities are in
`tests/src/test/kotlin/org/instagene/TestHelpers.kt`.

## Code Style

- Kotlin with no comments unless complex logic requires explanation
- No wildcard imports
- Functions that do heavy computation should consider the `Parallel` utility
  for parallelization

## Pull Requests

1. Create a feature branch from `master`
2. Make your changes and ensure `./gradlew build` passes
3. Open a pull request against `master`
4. CI will run the full test suite automatically

## Benchmarks

Performance benchmarks run on every push to `master`. The benchmark suite
is opt-in via `-Dinstagene.perf=true` and requires a sequence file as input:

```bash
./gradlew :app-cli:bench -Dinstagene.perf=true -Pinput=sequence.fa
```

Results are published to the [Benchmarks](benchmarks/index.md) dashboard.
