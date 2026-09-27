#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# check_terminal_tools.sh — Terminal tool-surface contract gate
# ═══════════════════════════════════════════════════════════════════════════
#
# Answers one question with machine-checked certainty:
#   "我做的 terminal 工具到底能不能被 Agent 调用？"
#
# Three sources of truth must agree (implementation = registration = docs):
#   1. IMPLEMENTATION — every `terminal.*` / `terminal_*` tool id declared in
#      platform/terminal tools (v2 + legacy), paired to its declaring class.
#   2. REGISTRATION  — every v2 tool class must be constructed in
#      app/di/ToolModule.kt (a tool that exists but is never registered is
#      DEAD CODE the Agent can never call — silent capability loss).
#   3. DOCS          — the authoritative tool-surface table in
#      docs/terminal-api.md must list exactly the implemented ids (no
#      ghosts, no missing entries).
#
# Failure modes this gate catches:
#   ❌ new tool class added to v2 but never wired into ToolModule
#   ❌ tool id renamed in code but docs table not updated
#   ❌ tool deleted but docs row (ghost) left behind
#   ❌ duplicate tool ids (registry collision at runtime)
#
# Registration status of LEGACY tools (terminal_xxx underscore ids) is
# reported for information only — some are intentionally unregistered
# (superseded by their v2 counterpart); the gate only requires the docs
# legacy section to match the implemented legacy set.
#
# Usage: ./scripts/check_terminal_tools.sh   (from repo root; exit 0 = pass, 1 = fail)
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail

V2_DIR="platform/terminal/src/main/kotlin/com/apex/agent/platform/terminal/tools/v2"
LEGACY_DIR="platform/terminal/src/main/kotlin/com/apex/agent/platform/terminal/tools/legacy"
TOOLMODULE="app/src/main/kotlin/com/apex/agent/di/ToolModule.kt"
DOC="docs/terminal-api.md"

FAIL=0

# ── helpers ────────────────────────────────────────────────────────────────
# Extract "id<TAB>class" pairs from a directory of tool sources.
# State machine per file: the most recent `class XxxTool(` declaration owns
# the next `override val id: String = "..."`. POSIX awk (no gawk extensions).
extract_pairs() {
    local dir="$1"
    local f
    for f in "$dir"/*.kt; do
        [ -e "$f" ] || continue
        awk '
        {
            if (match($0, /class [A-Za-z0-9_]+/)) {
                cls = substr($0, RSTART + 6, RLENGTH - 6)
            } else if ($0 ~ /override val id/ && match($0, /"terminal[._][a-z._]+"/)) {
                id = substr($0, RSTART + 1, RLENGTH - 2)
                if (cls != "") print id "\t" cls
            }
        }' "$f"
    done
}

set_diff() { # $1 minus $2, both sorted files of lines
    comm -23 "$1" "$2"
}

# ── 1. Implementation side ─────────────────────────────────────────────────
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

extract_pairs "$V2_DIR"      > "$TMP/v2_pairs"
extract_pairs "$LEGACY_DIR"  > "$TMP/legacy_pairs"

cut -f1 "$TMP/v2_pairs"     | sort > "$TMP/v2_ids"
cut -f1 "$TMP/legacy_pairs" | sort > "$TMP/legacy_ids"

# 1a. duplicate v2 ids (runtime registry collision)
sort "$TMP/v2_ids" | uniq -d > "$TMP/v2_dupes"
if [ -s "$TMP/v2_dupes" ]; then
    echo "❌ DUPLICATE TERMINAL TOOL IDS (registry collision at startup):"
    while IFS= read -r id; do echo "   $id"; done < "$TMP/v2_dupes"
    FAIL=1
fi

V2_COUNT=$(wc -l < "$TMP/v2_ids" | tr -d ' ')
LEGACY_COUNT=$(wc -l < "$TMP/legacy_ids" | tr -d ' ')

# ── 2. Registration side (ToolModule) ──────────────────────────────────────
[ -f "$TOOLMODULE" ] || { echo "❌ ToolModule not found: $TOOLMODULE"; exit 1; }
# TerminalToolAdapter( itself never matches: the regex demands a literal
# "Tool(" terminator, and "TerminalToolAdapter(" ends with "Adapter(".
# Commented-out registration lines must NOT count as registration: strip
# line comments (leading //) and KDoc / block-comment continuation lines
# (leading star) before matching — otherwise "comment out the register call"
# would silently pass the gate.
sed -e '/^[[:space:]]*\/\//d' -e '/^[[:space:]]*\*/d' "$TOOLMODULE" \
    | grep -oE 'Terminal[A-Za-z0-9]+Tool\(|Legacy[A-Za-z0-9]+Tool\(' \
    | tr -d '(' | sort -u > "$TMP/registered_classes"

# 2a. every v2 tool class MUST be registered
sort "$TMP/v2_pairs" | cut -f2 | sort -u > "$TMP/v2_classes"
set_diff "$TMP/v2_classes" "$TMP/registered_classes" > "$TMP/unregistered_v2"
if [ -s "$TMP/unregistered_v2" ]; then
    echo "❌ V2 TERMINAL TOOLS IMPLEMENTED BUT NEVER REGISTERED (Agent cannot call them):"
    while IFS= read -r cls; do
        tool_id=$(awk -F'\t' -v c="$cls" '$2 == c {print $1}' "$TMP/v2_pairs" | head -1)
        echo "   $cls  ($tool_id)  — wire it in app/di/ToolModule.kt"
    done < "$TMP/unregistered_v2"
    FAIL=1
fi

# ── 3. Docs side (authoritative table in terminal-api.md) ──────────────────
[ -f "$DOC" ] || { echo "❌ terminal-api.md not found: $DOC"; exit 1; }
if ! grep -q 'terminal-tool-surface begin' "$DOC"; then
    echo "❌ docs/terminal-api.md is missing the authoritative tool-surface table"
    echo "   (delimited by the terminal-tool-surface HTML comment markers)."
    echo "   Add it — the table is the machine-verified answer to"
    echo "   \"which terminal tools can the Agent actually call?\""
    exit 1
fi
# The authoritative block is delimited by HTML comments so ordinary prose
# mentions of tool ids elsewhere in the doc never false-positive.
sed -n '/<!-- terminal-tool-surface begin/,/terminal-tool-surface end -->/p' "$DOC" > "$TMP/doc_block"
grep -oE '\| `terminal[._][a-z._]+`' "$TMP/doc_block" | sed 's/^| `//; s/`$//' | sort > "$TMP/doc_ids" || true
grep '^terminal\.' "$TMP/doc_ids"        > "$TMP/doc_v2" || true
grep '^terminal_' "$TMP/doc_ids"         > "$TMP/doc_legacy" || true

# 3a. implemented v2 ids missing from docs
set_diff "$TMP/v2_ids" "$TMP/doc_v2" > "$TMP/docs_missing"
if [ -s "$TMP/docs_missing" ]; then
    echo "❌ IMPLEMENTED V2 TOOLS MISSING FROM docs/terminal-api.md tool-surface table:"
    while IFS= read -r id; do echo "   $id"; done < "$TMP/docs_missing"
    echo "   → add a row to the authoritative table (search: terminal-tool-surface begin)"
    FAIL=1
fi

# 3b. ghost v2 ids in docs (documented but not implemented)
set_diff "$TMP/doc_v2" "$TMP/v2_ids" > "$TMP/docs_ghosts"
if [ -s "$TMP/docs_ghosts" ]; then
    echo "❌ GHOST TOOL IDS IN docs/terminal-api.md (documented, never implemented):"
    while IFS= read -r id; do echo "   $id"; done < "$TMP/docs_ghosts"
    FAIL=1
fi

# 3c. legacy set must match docs exactly (both directions)
set_diff "$TMP/legacy_ids" "$TMP/doc_legacy" > "$TMP/legacy_missing"
if [ -s "$TMP/legacy_missing" ]; then
    echo "❌ LEGACY TOOLS MISSING FROM docs legacy section:"
    while IFS= read -r id; do echo "   $id"; done < "$TMP/legacy_missing"
    FAIL=1
fi
set_diff "$TMP/doc_legacy" "$TMP/legacy_ids" > "$TMP/legacy_ghosts"
if [ -s "$TMP/legacy_ghosts" ]; then
    echo "❌ GHOST LEGACY IDS IN docs legacy section:"
    while IFS= read -r id; do echo "   $id"; done < "$TMP/legacy_ghosts"
    FAIL=1
fi

# ── Report (informational: legacy registration status) ─────────────────────
SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/null}"
{
    echo ""
    echo "## 🔌 Terminal tool-surface contract report"
    echo ""
    echo "- v2 tools implemented: **$V2_COUNT** (all registered: $([ -s "$TMP/unregistered_v2" ] && echo NO || echo yes))"
    echo "- legacy compat tools implemented: **$LEGACY_COUNT**"
    echo "- authoritative docs table: docs/terminal-api.md"
    echo ""
} >> "$SUMMARY_FILE" 2>/dev/null || true

echo "── Legacy registration status (informational) ──"
while IFS=$'\t' read -r id cls; do
    if grep -qE "(^|[^A-Za-z0-9])${cls}\(" "$TOOLMODULE"; then
        echo "  $id  → registered (compat alias)"
    else
        echo "  $id  → NOT registered (superseded by v2 counterpart)"
    fi
done < "$TMP/legacy_pairs" | sort

echo ""
if [ "$FAIL" -eq 0 ]; then
    echo "✅ Terminal tool surface verified: $V2_COUNT v2 + $LEGACY_COUNT legacy ids;"
    echo "   implementation = registration = docs (see docs/terminal-api.md table)."
fi
exit $FAIL
