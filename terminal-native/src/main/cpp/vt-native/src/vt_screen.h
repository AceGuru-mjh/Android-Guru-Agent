// apex-vt-screen — screen buffer with flat POD cell storage + scrollback ring.
//
// Parity contract with the Kotlin ScreenBuffer (Spec §10/§15/§23 PR #53):
//  * erase writes blank cells carrying the GIVEN style (xterm bg-persistence);
//  * scroll blank lines use the DEFAULT style (unlike erase);
//  * only scrollUp with top==0 on the scrollback-enabled screen captures rows;
//  * linesEverScrolled is monotonic and never reset by clear()/clearScrollback();
//  * resize keeps the top-left content;
//  * wide-char put() repairs an overwritten trail by blanking the lead cell.
//
// Performance contract: zero per-cell heap allocation. Scrolling moves row
// spans with memmove; scrollback rows recycle their storage once the ring
// is full. Combining marks live in a sparse per-row side table (rare path).
#pragma once

#include "vt_types.h"

#include <memory>
#include <string>
#include <vector>

namespace apex::vt {

class StyleTable;

// Combining marks attached to base cells of one row (sparse, rare path).
struct CombiningMark {
  uint16_t col;
  std::vector<uint32_t> cps;
};

// ─── Hyperlink span (OSC 8) — per-row sparse storage in RowExtras ───────
// linkId 0 = none; ids index the engine's bounded URI table (1-based).
struct LinkSpan {
  uint16_t colStart;  // inclusive
  uint16_t colEnd;    // exclusive
  uint16_t linkId;
};

struct RowExtras {
  std::vector<CombiningMark> marks;  // kept sorted by col

  const std::vector<uint32_t>* find(uint16_t col) const;
  void add(uint16_t col, uint32_t cp);
  void removeCol(uint16_t col);
  void removeRange(int fromCol, int toCol);
  // Shift marks at cols >= fromCol by delta; drop out-of-bounds; keep sorted.
  void shiftCols(int fromCol, int delta, int cols);
  void clear() { marks.clear(); links.clear(); }
  bool empty() const { return marks.empty() && links.empty(); }

  // ── OSC 8 hyperlink spans (kept sorted, non-overlapping) ──
  std::vector<LinkSpan> links;
  // Attach [colStart, colEnd) to linkId; merges with a same-id neighbor.
  void addLink(uint16_t colStart, uint16_t colEnd, uint16_t linkId);
  // Drop or trim spans overlapping the given cell column (content overwrite).
  void invalidateLinkAt(uint16_t col);
  // Drop all spans (row structurally changed: ICH/DCH/EL/ED).
  void invalidateAllLinks() { links.clear(); }
  // Shift link spans on column insert/delete; drop out-of-bounds.
  void shiftLinks(int fromCol, int delta, int cols);
  const LinkSpan* linkAt(uint16_t col) const;
};

class Screen {
 public:
  Screen(int rows, int cols, int maxScrollbackLines, bool hasScrollback, StyleTable& styles);

  int rows() const { return rows_; }
  int cols() const { return cols_; }

  Cell cell(int r, int c) const {
    return (r >= 0 && r < rows_ && c >= 0 && c < cols_) ? cells_[idx(r, c)] : blankCell();
  }
  const Cell* rowCells(int r) const { return &cells_[idx(r, 0)]; }  // r must be valid

  void setCell(int r, int c, const Cell& cell);      // raw assignment
  void put(int r, int c, const Cell& cell);          // wide-trail aware
  void putCombining(int r, int c, uint32_t cp);
  const std::vector<uint32_t>* combiningAt(int r, int c) const;
  const RowExtras* combiningExtras(int r) const {
    return (r >= 0 && r < rows_) ? extras_[size_t(r)].get() : nullptr;
  }

  // ICH/IRM: insert [count] blank cells at fromCol, rest of the row shifts
  // right (cells past the right edge are dropped). Combining marks follow.
  void insertChars(int row, int fromCol, int count);
  // DCH: delete [count] cells at fromCol, rest of the row shifts left
  // (trailing cells blank). Combining marks follow.
  void deleteChars(int row, int fromCol, int count);

  void eraseRow(int row, int fromCol, int toCol, uint16_t styleId);
  void eraseRows(int fromRow, int toRow, uint16_t styleId);
  // P2 (Kotlin parity): fix orphan wide trails / edge-of-grid wide leads left
  // by raw shift ops (ICH/DCH/IRM). Idempotent; see vt_screen.cpp for details.
  void repairRow(int row);
  void scrollUp(int n, int top, int bottom);
  void scrollDown(int n, int top, int bottom);
  void insertLines(int row, int n, int top, int bottom);
  void deleteLines(int row, int n, int top, int bottom);
  void resize(int newRows, int newCols);
  void clear();
  void clearScrollback();

  int scrollbackCount() const { return static_cast<int>(sbCount_); }
  int64_t scrollbackLinesEver() const { return linesEver_; }
  const Cell* scrollbackRowCells(int i) const;            // i: oldest=0, must be < count
  const RowExtras* scrollbackRowExtras(int i) const;

  // ── Row wrap flags (reflow foundation) ──
  // wrapped(row) == true  → the row's content continues on the NEXT row
  // (engine autowrap at EOL, no hard newline). Maintained through scroll /
  // insert / delete / erase / clear. Full-row erase breaks the chain.
  void setRowWrapped(int r, bool wrapped);
  bool rowWrapped(int r) const;
  bool scrollbackRowWrapped(int i) const;

  // ── Reflow rebuild (engine-driven resize rewrap) ──
  // Push one fully-formed row into the scrollback ring (oldest-first order
  // is the CALLER's responsibility — rebuild feeds rows top-down). Bumps
  // linesEver_ so global row numbering stays monotonic.
  void pushScrollbackRowCells(std::vector<Cell>&& cells,
                              std::unique_ptr<RowExtras>&& extras, bool wrapped);
  // Copy a visible row into the scrollback ring (row-shrink resize path).
  void pushRowToScrollback(int srcRow) { pushScrollbackRow(srcRow); }
  // Continue the global row numbering at [v] after a restore/rebuild.
  void forceScrollbackBase(int64_t v) { linesEver_ = v; }
  // OSC 8 span attach (engine current-link state → row).
  void attachLink(int r, int colStart, int colEnd, uint16_t linkId) {
    if (r < 0 || r >= rows_) return;
    ensureExtras(r);
    extrasAt(r)->addLink(uint16_t(colStart), uint16_t(colEnd), linkId);
  }
  // Write a full visible row (row < rows). cols must match current width.
  void loadRow(int row, const Cell* cells, int cellCount,
               std::unique_ptr<RowExtras>&& extras, bool wrapped);
  // Reset dims + blank content + scrollback WITHOUT touching the style table
  // (used by rebuild-from-reflow and session restore).
  void resetTo(int newRows, int newCols);
  // The interned default style id (engine pre-interns before rebuild).
  uint16_t defaultStyleId() const { return blankStyleId_; }

  // Plain-text projection of one row: wide trails skipped, cp 0 → ' ',
  // trailing blank chars trimmed (blank = cp ' ' or 0 — style-agnostic).
  std::string rowText(int r) const;
  std::string scrollbackRowText(int i) const;

  // Internal: total bytes held in cell storage (test/fuzz leak guard).
  size_t cellFootprint() const;

 private:
  struct SbRow {
    std::vector<Cell> cells;
    std::unique_ptr<RowExtras> extras;
    bool wrapped = false;  // row's content continues on the next row
  };

  inline int idx(int r, int c) const { return r * cols_ + c; }
  Cell blankCell() const { return Cell{' ', blankStyleId_, 0}; }

  void fillRowBlank(int r);
  void pushScrollbackRow(int srcRow);
  void moveExtras(int dst, int src);         // unique_ptr move within extras_
  void ensureExtras(int r);
  RowExtras* extrasAt(int r) { return extras_[r].get(); }
  std::string renderCells(const Cell* cells, const RowExtras* extras) const;

  int rows_, cols_;
  bool hasScrollback_;
  size_t maxScrollback_;
  StyleTable& styles_;
  uint16_t blankStyleId_;  // interned DEFAULT style id

  std::vector<Cell> cells_;  // rows_ * cols_ flat
  std::vector<std::unique_ptr<RowExtras>> extras_;
  std::vector<uint8_t> wrapFlags_;  // per visible row, rides scrolls/inserts

  std::vector<SbRow> ring_;  // physical ring storage (grows to maxScrollback_)
  size_t sbHead_ = 0;        // oldest logical row index when ring is full
  size_t sbCount_ = 0;
  int64_t linesEver_ = 0;

  // Reusable scratch for row rotations (capacity retained — no per-scroll alloc).
  std::vector<std::unique_ptr<RowExtras>> rotateScratch_;
};

}  // namespace apex::vt
