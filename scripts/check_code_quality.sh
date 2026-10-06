#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# check_code_quality.sh — targeted anti-pattern gates
# ═══════════════════════════════════════════════════════════════════════════
#
# Four targeted checks (NOT a full linter — Gradle handles compilation and
# tests; this catches the anti-patterns that compile fine but rot code):
#
#  1. GATE  `javaClass.getMethod(...)` in main sources — reflective dispatch
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
#  3. GATE  bare `collectAsState(` in main sources — lifecycle-unaware state
#     collection. All UI collection must use `collectAsStateWithLifecycle()`
#     (Google/NIA best practice: stops collection when UI is invisible).
#     The 2026-10 sweep (commit b33e4b7) brought the repo to 0 occurrences;
#     the browser kit re-introduced 2 in September before that — this gate
#     keeps it at 0. Comment lines are skipped (docs may show the pattern).
#
#  4. GATE empty catch blocks — swallowing errors silently is now a hard
#     failure (#260)：防御式 IO 纪律要求「异常折叠 + 留痕」，空 catch 连留痕都没有；
#     注释行（* 或 // 开头）豁免 —— KDoc 引述旧实现不算违规
#     silently is sometimes correct (best-effort logging) but should be
#     visible in review.
#
# Scope: ALL main sources in the repo (find-based, module-enumeration-free).
# The old hardcoded module list (core/* app platform/* terminal-emulator)
# left plugins/, plugin-sdk/, terminal-view and terminal-native outside the
# gates — the workflow plugin's fake-success stubs lived exactly in that
# blind spot. Any new module is now covered automatically.
#
# Usage: ./scripts/check_code_quality.sh  (from repo root; exit 0 = pass, 1 = fail)
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/null}"
FAIL=0

# ── Whole-repo main-source manifest (print0: safe for any path shape) ──────
MAIN_KT_LIST=$(find . -path "*/src/main/*" -name "*.kt" -not -path "*/build/*" -not -path "./.git/*" -print0 2>/dev/null | xargs -0 -r printf '%s\n' || true)

# ── Gate 1: reflective dispatch on held references (all main sources) ──────
# grep -v ':[0-9]*: *\*' skips KDoc/block-comment continuation lines (docs may
# legitimately SHOW the anti-pattern, e.g. ConfirmationSink.kt's rationale)
REFLECT_HITS=""
if [ -n "$MAIN_KT_LIST" ]; then
    REFLECT_HITS=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -n "javaClass\.getMethod" 2>/dev/null \
        | grep -v ':[0-9]*: *\*' || true)
fi

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
STACK_HITS=""
if [ -n "$MAIN_KT_LIST" ]; then
    STACK_HITS=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -n "printStackTrace" 2>/dev/null || true)
fi

if [ -n "$STACK_HITS" ]; then
    echo "❌ GATE 2 — printStackTrace() in main sources (route through the logger instead):"
    echo "$STACK_HITS"
    FAIL=1
else
    echo "✅ GATE 2 — no printStackTrace() in main sources"
fi

# ── Gate 3: lifecycle-unaware state collection in main sources ───────────
# `\.collectAsState\(` with a literal `(` — matches the bare overload only;
# `collectAsStateWithLifecycle(` does NOT match (its call site reads
# `.collectAsStateWithLifecycle(`). KDoc/line-comment continuations skipped
# via the same filter as Gate 1.
BARE_COLLECT_HITS=""
if [ -n "$MAIN_KT_LIST" ]; then
    # -H: force the file:line: prefix even when xargs' last batch holds a
    # single file — the comment filters below key on that prefix shape.
    BARE_COLLECT_HITS=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -HnE '\.collectAsState\(' 2>/dev/null \
        | grep -v ':[0-9]*: *\*' \
        | grep -v ':[0-9]*: *//' || true)
fi

if [ -n "$BARE_COLLECT_HITS" ]; then
    echo "❌ GATE 3 — bare collectAsState() in main sources (use collectAsStateWithLifecycle):"
    echo "$BARE_COLLECT_HITS"
    echo ""
    echo "   Fix: replace with androidx.lifecycle.compose.collectAsStateWithLifecycle"
    echo "   (dependency already declared: libs.lifecycle.runtime.compose)."
    FAIL=1
else
    echo "✅ GATE 3 — no bare collectAsState() in main sources (all lifecycle-aware)"
fi

# ── GATE 4: empty catch blocks（#260：普查升级为拦截）───────────────────────
EMPTY_CATCH_COUNT=0
EMPTY_CATCH_HITS=""
if [ -n "$MAIN_KT_LIST" ]; then
    # 注释行豁免：KDoc/行注释里引述旧实现（如「旧实现 catch(_){}」）不算违规。
    EMPTY_CATCH_HITS=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -nE 'catch \([a-zA-Z. :]+\) \{ *\}' 2>/dev/null \
        | grep -vE ':[0-9]+:[[:space:]]*(\*|//)' \
        || true)
    EMPTY_CATCH_COUNT=$(printf '%s\n' "$EMPTY_CATCH_HITS" | grep -c . || true)
fi

if [ "$EMPTY_CATCH_COUNT" -gt 0 ]; then
    echo "❌ GATE 4 — empty catch blocks in main sources: $EMPTY_CATCH_COUNT"
    echo ""
    echo "$EMPTY_CATCH_HITS"
    echo ""
    echo "   Fix: 防御式 IO 纪律 —— 异常至少要留痕（AppLogger / errorLog 回调）或"
    echo "   以注释说明为何可安全吞掉；CancellationException 必须重抛。"
    FAIL=1
else
    echo "✅ GATE 4 — no empty catch blocks in main sources"
fi

TODO_COUNT=0
if [ -n "$MAIN_KT_LIST" ]; then
    TODO_COUNT=$(printf '%s\n' "$MAIN_KT_LIST" \
        | xargs grep -n "TODO\|FIXME\|XXX" 2>/dev/null \
        | wc -l || true)
fi

echo "📋 AUDIT — TODO/FIXME/XXX markers in main sources: $TODO_COUNT (review-only, not gated)"

{
    echo ""
    echo "## 🧹 Code-quality audit"
    echo ""
    echo "| Check | Result |"
    echo "|-------|--------|"
    echo "| Reflective dispatch (\`javaClass.getMethod\`) | $([ -z "$REFLECT_HITS" ] && echo '✅ none' || echo '❌ found') |"
    echo "| \`printStackTrace()\` in main sources | $([ -z "$STACK_HITS" ] && echo '✅ none' || echo '❌ found') |"
    echo "| Bare \`collectAsState()\` in main sources | $([ -z "$BARE_COLLECT_HITS" ] && echo '✅ none' || echo '❌ found') |"
    echo "| Empty catch blocks (GATE 4, #260) | $([ "$EMPTY_CATCH_COUNT" -eq 0 ] && echo '✅ none' || echo "❌ $EMPTY_CATCH_COUNT") |"
    echo "| TODO/FIXME markers (audit-only) | $TODO_COUNT |"
} >> "$SUMMARY_FILE" 2>/dev/null || true

exit $FAIL
