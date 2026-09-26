// apex-vt-reflow — implementation (see vt_reflow.h for the full contract).
#include "vt_reflow.h"

#include <algorithm>

namespace apex::vt {

namespace {

constexpr int kMaxCols = 512;         // newCols upper bound (contract)
constexpr int kMaxRowsPerLine = 65535;  // per-logical-line output row bound

// Screen::rowText blank semantics: cp ' ' or 0 (style-agnostic padding).
inline bool isBlankCp(uint32_t cp) { return cp == uint32_t(' ') || cp == 0; }

// One base cell of a logical line's reflow stream, tagged with its INPUT
// (global row, column) so combining marks can follow it.
struct BaseCell {
  int32_t inRow;
  int32_t col;
  Cell cell;
};

// Gather the logical line's base cells (wide trails are regenerated from
// their lead, never copied) in stream order, then trim the trailing blank
// padding. An all-blank line keeps exactly one blank cell so it still
// renders one styled row; lines with rows but no base cells at all fall
// through empty (the emission loop still produces one blank row).
std::vector<BaseCell> gatherStream(const lines::LogicalLine& L, int64_t lineGlobal) {
  std::vector<BaseCell> stream;
  size_t total = 0;
  for (const lines::RowRef& rr : L.rows) {
    total += size_t(rr.count > 0 ? rr.count : 0);
  }
  stream.reserve(total);
  for (size_t r = 0; r < L.rows.size(); ++r) {
    const lines::RowRef& rr = L.rows[r];
    if (rr.cells == nullptr) continue;
    const int32_t inRow = int32_t(lineGlobal + int64_t(r));
    for (int c = 0; c < rr.count; ++c) {
      const Cell& cell = rr.cells[size_t(c)];
      if (cell.flags & kCellWideTrail) continue;  // trail flows via its lead
      stream.push_back(BaseCell{inRow, int32_t(c), cell});
    }
  }
  // Trim trailing blanks (rowText padding semantics). Lockstep trim: if
  // everything is blank, keep the LAST blank so its style (e.g. a full-row
  // background) survives.
  if (!stream.empty()) {
    const BaseCell kept = stream.back();
    while (!stream.empty() && isBlankCp(stream.back().cell.cp)) {
      stream.pop_back();
    }
    if (stream.empty()) stream.push_back(kept);
  }
  return stream;
}

// Copy every input mark anchored at (inRow, col) into [scratch], rewritten
// to the base cell's new (outRow, outCol). [order] holds indices into
// [combs] sorted by (row, col) — the input vector itself is untouched.
void attachCombs(const std::vector<int32_t>& order, const std::vector<CombRun>& combs,
                 int32_t inRow, int32_t col, int32_t outRow, int outCol,
                 std::vector<CombRun>* scratch) {
  // Binary search the first entry >= (inRow, col).
  size_t lo = 0, hi = order.size();
  while (lo < hi) {
    const size_t mid = lo + (hi - lo) / 2;
    const CombRun& cr = combs[size_t(order[mid])];
    if (cr.row < inRow || (cr.row == inRow && cr.col < col)) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  for (size_t k = lo; k < order.size(); ++k) {
    const CombRun& cr = combs[size_t(order[k])];
    if (cr.row != inRow || cr.col != col) break;  // sorted → past the run
    CombRun moved = cr;  // deep copy — combining marks are a rare path
    moved.row = outRow;
    moved.col = int32_t(outCol);
    scratch->push_back(std::move(moved));
  }
}

}  // namespace

ReflowOutput ReflowEngine::rewrap(const ReflowInput& in) {
  ReflowOutput out;

  // Clamp the target width (contract: [1, 512]).
  const int newCols = in.newCols < 1 ? 1 : (in.newCols > kMaxCols ? kMaxCols : in.newCols);

  // ── Pass 1: locate the cursor's logical line and its cell ordinal ──────
  // The ordinal counts base cells strictly before the cursor position in
  // stream order. A cursor column on a wide TRAIL counts as "after the
  // lead" (the lead is before it), which is the visually correct spot.
  int cursorLine = -1;
  int64_t cursorOrdinal = 0;
  {
    int64_t g = 0;  // global visual row index of the current line's row 0
    for (size_t li = 0; li < in.lines.size(); ++li) {
      const lines::LogicalLine& L = in.lines[li];
      const int64_t n = int64_t(L.rows.size());
      if (in.cursorRow >= 0 && int64_t(in.cursorRow) >= g && int64_t(in.cursorRow) < g + n) {
        cursorLine = int(li);
        const int localRow = int(int64_t(in.cursorRow) - g);
        for (int r = 0; r < localRow; ++r) {
          const lines::RowRef& rr = L.rows[size_t(r)];
          if (rr.cells == nullptr) continue;
          for (int c = 0; c < rr.count; ++c) {
            if (!(rr.cells[size_t(c)].flags & kCellWideTrail)) ++cursorOrdinal;
          }
        }
        const lines::RowRef& rr = L.rows[size_t(localRow)];
        int cCol = in.cursorCol < 0 ? 0 : in.cursorCol;
        if (rr.cells == nullptr || cCol > rr.count) {
          cCol = rr.cells == nullptr ? 0 : rr.count;  // clamp to the row
        }
        for (int c = 0; c < cCol; ++c) {
          if (!(rr.cells[size_t(c)].flags & kCellWideTrail)) ++cursorOrdinal;
        }
        break;
      }
      g += n;
    }
  }
  // cursorRow outside every input line: cursorFirstAbsRow names an absolute
  // row the output cannot synthesize (it only holds reflowed input lines),
  // so the cursor parks at the origin (0, 0) — the caller re-clamps.

  // ── Combining-mark lookup index (sorted by input global row, then col) ──
  std::vector<int32_t> combOrder;
  combOrder.reserve(in.combs.size());
  for (size_t i = 0; i < in.combs.size(); ++i) {
    if (in.combs[i].row >= 0) combOrder.push_back(int32_t(i));
  }
  std::sort(combOrder.begin(), combOrder.end(),
            [&in](int32_t a, int32_t b) {
              const CombRun& ca = in.combs[size_t(a)];
              const CombRun& cb = in.combs[size_t(b)];
              if (ca.row != cb.row) return ca.row < cb.row;
              return ca.col < cb.col;
            });

  // ── Pass 2: reflow every logical line ──────────────────────────────────
  std::vector<Cell> row;      // current output row under construction
  std::vector<CombRun> rowComb;  // marks of that row (flushed in parallel)
  int64_t globalRow = 0;     // running global visual row index (input)

  for (size_t li = 0; li < in.lines.size(); ++li) {
    const lines::LogicalLine& L = in.lines[li];
    const int64_t lineGlobal = globalRow;
    globalRow += int64_t(L.rows.size());
    if (L.rows.empty()) continue;  // a line without rows produces nothing

    const std::vector<BaseCell> stream = gatherStream(L, lineGlobal);
    const int lineFirstRow = int(out.rows.size());

    int w = 0;                   // filled width of the current row
    uint16_t padStyle = 0;       // style inherited by row-end padding
    int64_t ordinal = 0;         // base cells placed in this line so far
    bool cursorFound = false;
    int endRow = -1;             // end-of-line cursor fallback: after the
    int endCol = 0;              //   last placed cell (or the line's first row)
    int lineRows = 0;            // rows emitted for this line (bounded)

    row.assign(size_t(newCols), Cell{uint32_t(' '), 0, 0});
    rowComb.clear();

    size_t si = 0;
    while (true) {
      // Fill the current row as far as it goes.
      while (si < stream.size()) {
        Cell cell = stream[si].cell;
        int cw = (cell.flags & kCellWideLead) ? 2 : 1;
        if (cw == 2 && newCols < 2) {
          // Width 2 can never fit a 1-column row — degrade to narrow so
          // the code point is still rendered and the loop stays total.
          cell.flags = uint16_t(cell.flags & ~uint16_t(kCellWideLead));
          cw = 1;
        }
        if (w + cw > newCols) {
          if (cw == 2 && w + 1 == newCols) {
            // The wide char would straddle the edge: pad the orphan column
            // with the wide char's style (xterm bg inheritance) and move
            // the char to the next row.
            row[size_t(w)] = Cell{uint32_t(' '), cell.style, 0};
            w = newCols;
          }
          break;  // row full → flush below
        }
        // Cursor replay: this cell is the cursor's ordinal target.
        if (cursorLine == int(li) && !cursorFound && ordinal == cursorOrdinal) {
          cursorFound = true;
          out.cursorRow = int(out.rows.size());  // index of the row being built
          out.cursorCol = w;                     // lead column for wide cells
        }
        row[size_t(w)] = cell;
        if (cw == 2) {
          row[size_t(w + 1)] = Cell{uint32_t(0), cell.style, kCellWideTrail};
        }
        attachCombs(combOrder, in.combs, stream[si].inRow, stream[si].col,
                    int32_t(out.rows.size()), w, &rowComb);
        w += cw;
        endRow = int(out.rows.size());
        endCol = w;
        padStyle = cell.style;  // padding inherits the last placed cell's bg
        ++ordinal;
        ++si;
      }
      // Flush the row (padding included) — an empty row is only flushed
      // when the line has produced nothing yet (every line ≥ 1 row).
      if (w > 0 || lineRows == 0) {
        for (int c = w; c < newCols; ++c) {
          row[size_t(c)] = Cell{uint32_t(' '), padStyle, 0};
        }
        out.rows.push_back(std::move(row));
        out.rowCombs.push_back(std::move(rowComb));
        out.lineOfRow.push_back(int32_t(li));
        ++lineRows;
        row.assign(size_t(newCols), Cell{uint32_t(' '), 0, 0});  // recycle
        rowComb.clear();
        w = 0;
      }
      if (si >= stream.size()) break;  // logical line done (endOfLine)
      if (lineRows >= kMaxRowsPerLine) break;  // bounded: keep head, drop tail
    }

    // Cursor fallback: the ordinal sat at/past the line end, beyond trimmed
    // padding, or beyond a truncation cut → END of this line.
    if (cursorLine == int(li) && !cursorFound) {
      out.cursorRow = endRow < 0 ? lineFirstRow : endRow;
      out.cursorCol = endRow < 0 ? 0 : endCol;
    }
  }

  out.rowsTotal = int(out.rows.size());
  return out;
}

}  // namespace apex::vt
