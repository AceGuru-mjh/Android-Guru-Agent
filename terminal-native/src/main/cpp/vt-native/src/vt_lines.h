// apex-vt-lines — logical line assembly over wrapped visual rows.
//
// A terminal screen stores VISUAL rows: text wider than the screen is folded
// across several rows by the engine (the wrap flag marks a fold — the row was
// continued, not hard-newlined). Search / copy / reflow all need the original
// LOGICAL line, so this module stitches wrapped chains back together and
// projects a logical line to UTF-8 text plus a byte→(row,col) map.
//
// Contract:
//  * assemble() is greedy: rows[i].wrapped == true means rows[i] was folded
//    and rows[i+1] continues the SAME logical line. A chain longer than
//    maxRowsPerLine is force-cut at the bound (defense against runaway
//    wrapped streams, e.g. cat of a minified file): the cut line keeps
//    rows.back().wrapped == true, endsWithNewline == false, and
//    AssembleStats.truncatedLines counts it. An OPEN last line (wrapped, but
//    no further input row) is not truncated — the content simply continues
//    below the region the caller passed in.
//  * maxRowsPerLine clamps to [1, 65535]: row indices are packed into
//    16 bits by extractText's map.
//  * extractText() matches Screen::rowText semantics exactly: wide trail
//    cells are skipped (the lead renders for both), blank cells (cp 0 / ' ')
//    render ' ', and trailing blanks are trimmed (style-agnostic). Combining
//    marks occupy no cell and never enter the text.
//  * map contract (consumed by SearchEngine and tests):
//      map.size() == text.size() + 1
//      map[i] = (rowInLine << 16) | col   for EVERY byte i
//      map[text.size()] = one-past-end of the last cell (col + cell width)
//    Cluster START bytes carry the owning cell's position; UTF-8
//    continuation bytes repeat it, so map[i] == map[i-1] identifies a
//    continuation byte (search uses this to align hit ends to cluster
//    starts). A wide lead's bytes all map to its lead column; its trail
//    emits no byte and no map entry.
//  * RowRef only borrows memory — the caller owns the cells/extras and must
//    keep them alive while LogicalLine is used.
#pragma once

#include "vt_screen.h"

#include <cstdint>
#include <string>
#include <vector>

namespace apex::vt::lines {

// Visual row reference (cells owned by the caller — see header contract).
struct RowRef {
  const Cell* cells = nullptr;
  int count = 0;               // valid columns (== screen columns)
  bool wrapped = false;        // engine folded at the row end (no hard newline) → next row joins
  const RowExtras* extras = nullptr;  // combining mark table, may be null
};

// Logical line = a chain of wrapped visual rows.
struct LogicalLine {
  std::vector<RowRef> rows;
  int64_t firstAbsRow = 0;     // abs row number of rows[0] (caller-defined: 0 = oldest scrollback row)
  bool endsWithNewline = true; // last row ended with a hard newline (!wrapped)
};

// Greedy assembly stats: total logical lines and lines force-cut at
// maxRowsPerLine.
struct AssembleStats {
  int lineCount = 0;
  int truncatedLines = 0;
};

// Assemble rowCount visual rows (oldest first) into logical lines appended
// to *out (cleared first). firstAbsRow is the abs row number of rows[0].
AssembleStats assemble(const RowRef* rows, int rowCount, int64_t firstAbsRow,
                       int maxRowsPerLine, std::vector<LogicalLine>* out);

// Extract the logical line's UTF-8 text and byte→(rowInLine, col) map.
// See the file-header contract for the exact map encoding.
void extractText(const LogicalLine& line, std::string* text,
                 std::vector<uint32_t>* map);

}  // namespace apex::vt::lines
