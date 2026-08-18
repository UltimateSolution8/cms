#!/usr/bin/env bash
# The mechanical half of the phase gate: a session does not end over a red build or a shrinking suite.
#
# Those are the two failures this programme has actually had — not carelessness in the moment, but a
# session ending with something believed green that was not, and the belief surviving into the next
# one. A hook cannot read a checklist's meaning; it can run the build and count the tests, and those
# two facts are worth taking out of the realm of remembering.
#
# Registered as a Stop hook in .claude/settings.json. Exit 2 blocks and returns stderr to Claude;
# exit 0 is silent.

set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
state="$root/.claude/state"
marker="$state/last-verify"
baseline_file="$state/test-baseline"

payload="$(cat 2>/dev/null || true)"

# A blocked stop re-invokes this hook. Without this the pair loops forever, and the second run tells
# the operator nothing the first did not.
if printf '%s' "$payload" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then
    exit 0
fi

mkdir -p "$state"
[ -f "$baseline_file" ] || echo 495 > "$baseline_file"
baseline="$(tr -dc '0-9' < "$baseline_file")"
baseline="${baseline:-0}"

# Nothing the build could disagree with? Then do not spend twenty minutes proving it. A
# documentation-only session is the common case and taxing it would train the operator to disable
# this file.
if [ -f "$marker" ]; then
    changed="$(find "$root/platform" \
        \( -name '*.java' -o -name '*.sql' -o -name '*.yml' -o -name 'pom.xml' \) \
        -not -path '*/target/*' -newer "$marker" -print -quit 2>/dev/null)"
    if [ -z "$changed" ]; then
        exit 0
    fi
fi

command -v mvn >/dev/null 2>&1 || exit 0

# Testcontainers needs the daemon. With it down the build fails for a reason that is not about the
# change, and blocking on that would be a false gate — which is worse than no gate, because it gets
# switched off. Say so and let the stop through; the count is not verified and the operator knows it.
if ! docker info >/dev/null 2>&1; then
    echo "verify-gate: Docker is not running, so the suite was not verified. Start the daemon and re-run 'mvn -B verify' before treating this work as green." >&2
    exit 0
fi

log="$(mktemp "${TMPDIR:-/tmp}/uds-verify.XXXXXX")"
( cd "$root/platform" && mvn -B verify ) > "$log" 2>&1
status=$?

# The count comes from the XML. The failsafe .txt summaries are not usable for this: consent-ledger
# reports "Tests run: 0" in its own, which is how the total was once got wrong by 133.
count="$(find "$root/platform" -path '*-reports/TEST-*.xml' -print0 2>/dev/null \
    | xargs -0 sed -n 's/.*[^-]tests="\([0-9]*\)".*/\1/p' 2>/dev/null \
    | awk '{ total += $1 } END { print total + 0 }')"

if [ "$status" -ne 0 ]; then
    {
        echo "verify-gate: BLOCKED — 'mvn -B verify' failed. This phase is not done."
        echo
        grep -E '^\[ERROR\] +(Tests run|.*Test.*FAIL|Failures:)|^\[ERROR\].*\.java|FAILURE!' "$log" \
            | head -25
        echo
        echo "Full output: $log"
        echo "Fix the failures, or report the phase as incomplete with this output. Do not disable the gate."
    } >&2
    exit 2
fi

if [ "$count" -lt "$baseline" ]; then
    {
        echo "verify-gate: BLOCKED — the suite is green at $count tests but the baseline is $baseline."
        echo
        echo "A drop in the count is a deleted or silently skipped test, not a passing build. Find what"
        echo "stopped running. If a test was removed deliberately, say so and lower $baseline_file in the"
        echo "same change, with the reason — that is a decision, not housekeeping."
    } >&2
    exit 2
fi

if [ "$count" -gt "$baseline" ]; then
    echo "verify-gate: green at $count tests (baseline $baseline). Raise the baseline in $baseline_file when the phase is recorded — /phase-gate step 6." >&2
fi

touch "$marker"
rm -f "$log"
exit 0
