// apex-vt-reflow — resize reflow (rewrap) without content loss.
//
// Android rotates / splits / folds screens daily; a naive resize crops the
// right edge (Screen::resize keeps the top-left newCols columns and drops
// the rest). This engine re-flows the logical lines assembled by
// lines::assemble into rows of the new width so no text is lost.
//
// Contract (all guaranteed; consumers may rely on every bullet):
//  * Each input logical line's BASE cells (wide trails excluded — they are
//    regenerated from their lead) flow left-to-right, top-to-bottom into
//    new rows of EXACTLY newCols cells. A logical line end closes the
//    current output row (endOfLine) — two logical lines never share a row,
//    and every line yields at least one row (a line with no base cells
//    renders one blank row). A line with zero visual rows produces nothing.
//  * Trailing blank cells of a logical line are right-padding (rowText
//    semantics) and are trimmed before flowing; an all-blank line keeps
//    ONE blank cell so it still renders exactly one styled row. Row-end
//    padding (and the orphan-column pad below) inherit the last placed
//    cell's style id — xterm-style background persistence.
//  * Wide chars never straddle rows: a width-2 cell that does not fit the
//    remaining width pads the row end with Cell{' ', wideLeadStyle, 0}
//    (xterm bg inheritance) and moves to the next row. newCols == 1
//    degrades a wide cell to narrow (width 2 can never fit one column —
//    the code point is kept, the wide flags dropped; documented downgrade
//    that keeps the engine total and bounded).
//  * Combining marks follow their base cell: an input mark on (globalRow,
//    col) whose base cell flows to new (row', col') is rewritten to
//    (row', col'). Marks attached to wide leads re-attach to the new lead
//    column; marks on trailing blanks trimmed above are dropped with their
//    cell; marks whose base cell fell beyond a truncation cut are dropped.
//  * Cell style ids pass through untouched; a regenerated wide trail
//    carries its lead's style (matches Screen::put).
//  * Cursor mapping: the cursor's CELL ORDINAL inside its logical line is
//    recorded (base cells strictly before the cursor position, in stream
//    order; a cursor column on a wide trail counts as "after the lead").
//    Reflow replays the ordinal: same ordinal → same cell → its new lead
//    (row, col). An ordinal at/past the line end (or beyond a truncation
//    cut, or past trimmed padding) maps to the END of that line — the
//    column just after the last placed cell. A cursorRow outside every
//    input line maps to (0, 0): cursorFirstAbsRow names an absolute row
//    that the output cannot synthesize (it holds only reflowed input
//    lines), so the cursor parks at the origin and the caller re-clamps.
//  * Bounds: at most 65535 output rows per logical line (keep the head,
//    drop the tail); newCols clamps to [1, 512]; rowCombs and lineOfRow
//    are parallel to rows (rowCombs[i] may be empty); rowsTotal ==
//    rows.size(); an empty input line set yields an empty output.
#pragma once

#include "vt_lines.h"
#include "vt_types.h"

#include <cstdint>
#include <vector>

namespace apex::vt {

// Combining-mark run anchored to a cell. In ReflowInput, row/col address
// the INPUT global visual row (rows of all lines counted 0..N-1 in order)
// and its column; in ReflowOutput.rowCombs they are rewritten to the new
// row/column of the base cell.
struct CombRun {
  int32_t row = 0;
  int32_t col = 0;
  std::vector<uint32_t> cps;
};

struct ReflowInput {
  std::vector<lines::LogicalLine> lines;  // screen content (visual row refs)
  std::vector<CombRun> combs;             // combining marks (input row numbering)
  int newCols = 80;                       // target width (clamped to [1, 512])
  int cursorRow = 0;                      // cursor's input global visual row
  int cursorCol = 0;                      // cursor's column on that row
  int64_t cursorFirstAbsRow = 0;          // see the header cursor contract
};

struct ReflowOutput {
  std::vector<std::vector<Cell>> rows;        // new visual rows (each exactly newCols)
  std::vector<std::vector<CombRun>> rowCombs; // per-row marks (row/col rewritten)
  int cursorRow = 0;                          // mapped cursor (see contract)
  int cursorCol = 0;
  std::vector<int32_t> lineOfRow;             // new row → logical line index
  int rowsTotal = 0;                          // == rows.size()
};

class ReflowEngine {
 public:
  static ReflowOutput rewrap(const ReflowInput& in);
};

}  // namespace apex::vt
