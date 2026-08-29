#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# check_file_size.sh — Single-Responsibility guard (God-file gate)
# ═══════════════════════════════════════════════════════════════════════════
#
# Fails the build when a Kotlin source file exceeds the per-sourceset line
# budget. Large files concentrate unrelated responsibilities (God classes),
# resist review, and are the #1 source of merge conflicts.
#
# Thresholds (deliberately generous — the goal is a hard ceiling, not bikeshed):
#   main sources:      MAX_MAIN_LINES (default 1200)
#   test sources:      MAX_TEST_LINES (default 1600)
#
# Raising a threshold is allowed ONLY with a justification comment in the PR;
# the preferred fix is splitting the file along responsibility seams.
#
# Usage: ./scripts/check_file_size.sh   (from repo root; exit 0 = pass, 1 = fail)
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

MAX_MAIN_LINES="${MAX_MAIN_LINES:-1200}"
MAX_TEST_LINES="${MAX_TEST_LINES:-1600}"

FAIL=0

# ── Main sources gate ──────────────────────────────────────────────────────
# shellcheck disable=SC2207
MAIN_FILES=($(find . -name "*.kt" -path "*/src/main/*" -not -path "./.git/*" -not -path "*/build/*" 2>/dev/null))
for f in "${MAIN_FILES[@]}"; do
    LINES=$(wc -l < "$f")
    if [ "$LINES" -gt "$MAX_MAIN_LINES" ]; then
        echo "❌ MAIN-SOURCE BUDGET EXCEEDED: $f is $LINES lines (max $MAX_MAIN_LINES)"
        echo "   Split it along responsibility seams (see orchestrator/ & AgentChat* splits for the pattern)."
        FAIL=1
    fi
done

# ── Test sources gate ──────────────────────────────────────────────────────
# shellcheck disable=SC2207
TEST_FILES=($(find . -name "*.kt" \( -path "*/src/test/*" -o -path "*/src/androidTest/*" \) -not -path "./.git/*" -not -path "*/build/*" 2>/dev/null))
for f in "${TEST_FILES[@]}"; do
    LINES=$(wc -l < "$f")
    if [ "$LINES" -gt "$MAX_TEST_LINES" ]; then
        echo "❌ TEST-SOURCE BUDGET EXCEEDED: $f is $LINES lines (max $MAX_TEST_LINES)"
        echo "   Split the suite by concern (e.g. classification / retry / parallel)."
        FAIL=1
    fi
done

# ── Report (always printed — feeds CI job summaries) ──────────────────────
SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/null}"
{
    echo ""
    echo "## 📐 File-size budget report"
    echo ""
    echo "Budgets — main: **$MAX_MAIN_LINES** lines, test: **$MAX_TEST_LINES** lines."
    echo ""
    echo "| Lines | File |"
    echo "|------:|------|"
} >> "$SUMMARY_FILE" 2>/dev/null || true

if [ ${#MAIN_FILES[@]} -gt 0 ]; then
    echo "── Top 10 largest MAIN sources (budget: $MAX_MAIN_LINES) ──"
    # NB: sort into a variable first — piping into `head` would SIGPIPE `sort`
    # and, under `set -o pipefail`, fail the whole script spuriously.
    ALL_SORTED=$(printf '%s\n' "${MAIN_FILES[@]}" | xargs wc -l 2>/dev/null | grep -v ' total$' | sort -rn || true)
    count=0
    while IFS= read -r line; do
        [ -z "$line" ] && continue
        count=$((count + 1))
        [ "$count" -gt 10 ] && break
        LINES=${line%% *}
        FILE=${line#* }
        echo "  $LINES  $FILE"
        echo "| $LINES | \`$FILE\` |" >> "$SUMMARY_FILE" 2>/dev/null || true
    done <<< "$ALL_SORTED"
fi

TOTAL_MAIN=${#MAIN_FILES[@]}
TOTAL_TEST=${#TEST_FILES[@]}
echo ""
echo "✅ Checked $TOTAL_MAIN main + $TOTAL_TEST test Kotlin files against line budgets (main: $MAX_MAIN_LINES, test: $MAX_TEST_LINES)"

exit $FAIL
