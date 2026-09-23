# InstaGene build scripts

Convenience wrappers around the Gradle wrapper for the five Gradle subprojects.
Every script picks a **target** with its first positional argument:

| Target   | Module      | Compile task(s)                    | Test filter (in `:tests`)            |
| -------- | ----------- | ---------------------------------- | ------------------------------------ |
| `engine` | `:engine`   | `:engine:compileKotlin`            | `org.instagene.core.*`               |
| `cli`    | `:app-cli`  | `:app-cli:compileKotlin`           | `org.instagene.app.cli.*`            |
| `gui`    | `:app-gui`  | `:app-gui:compileKotlin`           | `org.instagene.app.gui.*`            |
| `web`    | `:app-web`  | `:app-web:compileKotlin`           | `org.instagene.app.web.*`            |
| `all`    | every module | `compileKotlin compileTestKotlin`  | no filter (full suite)               |

Both a POSIX shell script (`.sh`, for macOS/Linux/WSL) and a Windows batch
script (`.bat`) are provided for each task; run them from anywhere in the repo
from the project root (they `cd` to the repo root themselves).

All scripts forward any extra arguments after the target straight to Gradle, so
flags like `--offline`, `--info` or `--tests "com.foo.BarTest"` keep working.

## Compile

```sh
scripts/compile.sh [target]      # macOS/Linux/WSL
scripts\compile.bat [target]     # Windows
```

Default target is `all`. Verbosity is reduced with `--quiet` and `--console=plain`
so only errors and warnings are printed.

```sh
scripts/compile.sh               # compile main + test sources of every module
scripts/compile.sh engine        # engine library only
scripts/compile.sh gui           # desktop GUI only
scripts/compile.sh cli           # CLI only
scripts/compile.sh web           # web front-end only
```

## Test

```sh
scripts/test.sh [target]         # macOS/Linux/WSL
scripts\test.bat [target]        # Windows
```

Default target is `all`, which runs the full suite of the `:tests` module. A
target selects the matching subset through Gradle `--tests` filters, so you get
a quick iteration loop for one front-end:

```sh
scripts/test.sh                  # full suite
scripts/test.sh engine           # engine (org.instagene.core.*)
scripts/test.sh gui              # GUI tests               (org.instagene.app.gui.*)
scripts/test.sh cli              # CLI tests               (org.instagene.app.cli.*)
scripts/test.sh web              # web-server tests        (org.instagene.app.web.*)
```

Extra test options are forwarded, for example:

```sh
scripts/test.sh engine --tests "org.instagene.core.SeqIOTest"
```

## Run

```sh
scripts/run.sh [target]          # macOS/Linux/WSL
scripts\run.bat [target]         # Windows
```

Launches one front-end. The default is `gui`. The **engine has no run task** —
it is a reusable library consumed by the front-ends, so it only appears in
compile/test, never in run.

```sh
scripts/run.sh                   # desktop GUI
scripts/run.sh cli               # command line
scripts/run.sh web               # local web server
```

Run output is quiet (`--quiet` + `--console=plain`), which suppresses Gradle's
own noise while the application's own stdout remains visible (it's logged at
QUIET level). Program arguments can be passed the Gradle way, e.g.
`scripts/run.sh cli --args="version --verbose"`.

## Other scripts in this directory

| Script                    | Purpose                                                            |
| ------------------------- | ------------------------------------------------------------------ |
| `install-hooks.sh`        | Installs the repo git hooks (`.githooks/`) via `core.hooksPath`.   |
| `check-docs.sh`           | Builds the MkDocs site in strict mode.                             |
| `check-distributions.sh`  | Verifies standalone JARs, CLI distribution ZIPs and file associations. |
