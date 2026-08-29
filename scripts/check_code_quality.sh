#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# check_code_quality.sh — targeted anti-pattern gates
# ═══════════════════════════════════════════════════════════════════════════
#
# Three targeted checks (NOT a full linter — Gradle handles compilation and
# tests; this catches the anti-patterns that compile fine but rot code):
#
#  1. GATE  `javaClass.getMethod(...)` in core modules — reflective dispatch
#     on an object you already hold a typed reference to. Legitimate uses of
#     reflection (optional runtime deps via Class.forName, hidden Android
#     APIs) are NOT flagged; this pattern is — it hides type errors until
#     runtime and breaks silently on rename. Fix: define an interface and
#     do a type-safe `as?` cast (see ConfirmationSink for the pattern).
#
#  2. GATE  `printStackTrace()` in main sources — stdout stack traces are
#     invisible in production (no logcat routing, no tag). Use the
#     structured logger (AppLogger in core, android.util.Log in app).
#
#  3. AUDIT empty catch blocks — reported (not gated): swallowing errors
#     silently is sometimes correct (best-effort logging) but should be
#     visible in review.
#
# Usage: ./scripts/check_code_quality.sh  (from repo root; exit 0 = pass, 1 = fail)
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/null}"
FAIL=0

# ── Gate 1: reflective dispatch on held references (core + app main) ────────
# grep -v ':[0-9]*: *\*' skips KDoc/block-comment continuation lines (docs may
# legitimately SHOW the anti-pattern, e.g. ConfirmationSink.kt's rationale)
REFLECT_HITS=$(grep -rn "javaClass\.getMethod" \
    --include="*.kt" \
    core/*/src/main app/src/main platform/*/src/main terminal-emulator/src/main 2>/dev/null \
    | grep -v ':[0-9]*: *\*' || true)

if [ -n "$REFLECT_HITS" ]; then
    echo "❌ GATE 1 — reflective method dispatch on held references (use a typed interface instead):"
    echo "$REFLECT_HITS"
    echo ""
    echo "   Pattern fix: extract the needed methods into an interface, have the"
    echo "   target implement it, then 'target as? MyInterface' (see ConfirmationSink.kt)."
    FAIL=1
else
    echo "✅ GATE 1 — no 'javaClass.getMethod' reflective dispatch in main sources"
fi

# ── Gate 2: printStackTrace in main sources ────────────────────────────────
STACK_HITS=$(grep -rn "printStackTrace" \
    --include="*.kt" \
    core/*/src/main app/src/main platform/*/src/main terminal-emulator/src/main plugin-sdk/*/src/main 2>/dev/null || true)

if [ -n "$STACK_HITS" ]; then
    echo "❌ GATE 2 — printStackTrace() in main sources (route through the logger instead):"
    echo "$STACK_HITS"
    FAIL=1
else
    echo "✅ GATE 2 — no printStackTrace() in main sources"
fi

# ── Audit: empty catch blocks (report-only) ────────────────────────────────
EMPTY_CATCH_COUNT=$(grep -rEn 'catch \([a-zA-Z. :]+\) \{ *\}|catch \([a-zA-Z. :]+\) \{\n *//' \
    --include="*.kt" \
    core/*/src/main app/src/main platform/*/src/main 2>/dev/null | wc -l || true)

TODO_COUNT=$(grep -rn "TODO\|FIXME\|XXX" --include="*.kt" \
    core/*/src/main app/src/main platform/*/src/main 2>/dev/null | wc -l || true)

echo "📋 AUDIT — empty catch blocks in main sources: $EMPTY_CATCH_COUNT (review-only, not gated)"
echo "📋 AUDIT — TODO/FIXME/XXX markers in main sources: $TODO_COUNT (review-only, not gated)"

{
    echo ""
    echo "## 🧹 Code-quality audit"
    echo ""
    echo "| Check | Result |"
    echo "|-------|--------|"
    echo "| Reflective dispatch (\`javaClass.getMethod\`) | $([ -z "$REFLECT_HITS" ] && echo '✅ none' || echo '❌ found') |"
    echo "| \`printStackTrace()\` in main sources | $([ -z "$STACK_HITS" ] && echo '✅ none' || echo '❌ found') |"
    echo "| Empty catch blocks (audit-only) | $EMPTY_CATCH_COUNT |"
    echo "| TODO/FIXME markers (audit-only) | $TODO_COUNT |"
} >> "$SUMMARY_FILE" 2>/dev/null || true

exit $FAIL
