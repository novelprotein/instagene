#!/usr/bin/env bash
# Shared runtime selection for the local checks. Source this file.
# Environment overrides take precedence over repository-local Git settings.
JAVA_HOME="${JAVA_HOME:-$(git config --get instagene.javaHome || true)}"
INSTAGENE_PYTHON="${INSTAGENE_PYTHON:-$(git config --get instagene.python || true)}"
INSTAGENE_PYTHON="${INSTAGENE_PYTHON:-python3}"
if command -v cygpath >/dev/null 2>&1; then
    if [[ "$JAVA_HOME" == [A-Za-z]:* ]]; then JAVA_HOME="$(cygpath -u "$JAVA_HOME")"; fi
    if [[ "$INSTAGENE_PYTHON" == [A-Za-z]:* ]]; then INSTAGENE_PYTHON="$(cygpath -u "$INSTAGENE_PYTHON")"; fi
fi
if [[ -n "$JAVA_HOME" ]]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
else
    unset JAVA_HOME
fi
export INSTAGENE_PYTHON
