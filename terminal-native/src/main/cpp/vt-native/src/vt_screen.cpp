#include "vt_screen.h"

#include "vt_style.h"

#include <algorithm>
#include <cstring>

namespace apex::vt {

// ─── RowExtras ────────────────────────────────────────────────────────────

const std::vector<uint32_t>* RowExtras::find(uint16_t col) const {
  // Linear scan — marks are rare and few per row.
  for (const CombiningMark& m : marks) {
    if (m.col == col) return &m.cps;
  }
  return nullptr;
}

void RowExtras::add(uint16_t col, uint32_t cp) {
  for (CombiningMark& m : marks) {
    if (m.col == col) {
      m.cps.push_back(cp);
      return;
    }
  }
  marks.push_back(CombiningMark{col, std::vector<uint32_t>{cp}});
  std::sort(marks.begin(), marks.end(),
            [](const CombiningMark& a, const CombiningMark& b) { return a.col < b.col; });
}

void RowExtras::removeCol(uint16_t col) {
  marks.erase(std::remove_if(marks.begin(), marks.end(),
                             [col](const CombiningMark& m) { return m.col == col; }),
              marks.end());
}

void RowExtras::removeRange(int fromCol, int toCol) {
  marks.erase(std::remove_if(marks.begin(), marks.end(), [&](const CombiningMark& m) {
                return m.col >= fromCol && m.col <= toCol;
              }),
              marks.end());
}

void RowExtras::shiftCols(int fromCol, int delta, int cols) {
  marks.erase(std::remove_if(marks.begin(), marks.end(), [&](const CombiningMark& m) {
                if (m.col < fromCol) return false;  // untouched
                int nc = int(m.col) + delta;
                if (nc < 0 || nc >= cols) return true;  // shifted off-screen → drop
                return false;
              }),
              marks.end());
  if (delta != 0) {
    for (CombiningMark& m : marks) {
      if (int(m.col) >= fromCol) m.col = uint16_t(int(m.col) + delta);
    }
    std::sort(marks.begin(), marks.end(),
              [](const CombiningMark& a, const CombiningMark& b) { return a.col < b.col; });
  }
}

// ── hyperlink spans ──

void RowExtras::addLink(uint16_t colStart, uint16_t colEnd, uint16_t linkId) {
  if (colEnd <= colStart || linkId == 0) return;
  // Merge with an adjacent/overlapping same-id span; otherwise append sorted.
  for (LinkSpan& s : links) {
    if (s.linkId == linkId && colStart <= s.colEnd && s.colStart <= colEnd) {
      s.colStart = std::min(s.colStart, colStart);
      s.colEnd = std::max(s.colEnd, colEnd);
      return;
    }
  }
  links.push_back(LinkSpan{colStart, colEnd, linkId});
  std::sort(links.begin(), links.end(),
            [](const LinkSpan& a, const LinkSpan& b) { return a.colStart < b.colStart; });
}

void RowExtras::invalidateLinkAt(uint16_t col) {
  // Trim or drop spans covering the cell — an overwritten cell is no longer
  // part of the link run.
  std::vector<LinkSpan> next;
  next.reserve(links.size());
  for (LinkSpan& s : links) {
    if (uint32_t(col) < s.colStart || uint32_t(col) >= s.colEnd) {
      next.push_back(s);
      continue;
    }
    if (s.colStart < col) next.push_back(LinkSpan{s.colStart, uint16_t(col), s.linkId});
    if (uint32_t(col) + 1 < s.colEnd) {
      next.push_back(LinkSpan{uint16_t(col + 1), s.colEnd, s.linkId});
    }
  }
  links = std::move(next);
}

void RowExtras::shiftLinks(int fromCol, int delta, int cols) {
  std::vector<LinkSpan> next;
  next.reserve(links.size());
  for (LinkSpan& s : links) {
    if (s.colEnd <= uint16_t(fromCol)) {
      next.push_back(s);  // entirely before the shift point
      continue;
    }
    int ns = int(s.colStart) + delta;
    int ne = int(s.colEnd) + delta;
    if (ne <= 0 || ns >= cols) continue;  // shifted off-screen
    if (ns < 0) ns = 0;
    if (ne > cols) ne = cols;
    next.push_back(LinkSpan{uint16_t(ns), uint16_t(ne), s.linkId});
  }
  links = std::move(next);
  std::sort(links.begin(), links.end(),
            [](const LinkSpan& a, const LinkSpan& b) { return a.colStart < b.colStart; });
}

const LinkSpan* RowExtras::linkAt(uint16_t col) const {
  for (const LinkSpan& s : links) {
    if (uint32_t(col) >= s.colStart && uint32_t(col) < s.colEnd) return &s;
  }
  return nullptr;
}

// ─── Screen ───────────────────────────────────────────────────────────────

Screen::Screen(int rows, int cols, int maxScrollbackLines, bool hasScrollback, StyleTable& styles)
    : rows_(rows < 1 ? 1 : rows),
      cols_(cols < 1 ? 1 : cols),
      hasScrollback_(hasScrollback),
      maxScrollback_(maxScrollbackLines < 0 ? 0 : size_t(maxScrollbackLines)),
      styles_(styles),
      blankStyleId_(styles_.defaultId()) {
  cells_.assign(size_t(rows_) * cols_, blankCell());
  extras_.resize(rows_);
  wrapFlags_.assign(rows_, 0);
}

void Screen::setCell(int r, int c, const Cell& cell) {
  if (r >= 0 && r < rows_ && c >= 0 && c < cols_) cells_[idx(r, c)] = cell;
}

void Screen::put(int r, int c, const Cell& cell) {
  if (r < 0 || r >= rows_ || c < 0 || c >= cols_) return;
  // Overwriting a wide trail blanks the lead first (Kotlin §9 parity).
  if (c > 0 && (cells_[idx(r, c)].flags & kCellWideTrail)) {
    cells_[idx(r, c - 1)] = blankCell();
    if (RowExtras* ex = extrasAt(r)) {
      ex->removeCol(uint16_t(c - 1));
      ex->invalidateLinkAt(uint16_t(c - 1));
    }
  }
  // A fresh cell has no combining marks — clear any stale mark at this col.
  if (RowExtras* ex = extrasAt(r)) {
    ex->removeCol(uint16_t(c));
    ex->invalidateLinkAt(uint16_t(c));
    if (c + 1 < cols_ && (cell.flags & kCellWideLead)) ex->invalidateLinkAt(uint16_t(c + 1));
    if (ex->empty()) extras_[size_t(r)].reset();
  }
  cells_[idx(r, c)] = cell;
  if ((cell.flags & kCellWideLead) && c + 1 < cols_) {
    Cell trail{};
    trail.cp = 0;
    trail.style = blankStyleId_;
    trail.flags = kCellWideTrail;
    cells_[idx(r, c + 1)] = trail;
    if (RowExtras* ex2 = extrasAt(r)) ex2->removeCol(uint16_t(c + 1));
  }
}

void Screen::putCombining(int r, int c, uint32_t cp) {
  if (r < 0 || r >= rows_ || c < 0 || c >= cols_) return;
  ensureExtras(r);
  extrasAt(r)->add(uint16_t(c), cp);
}

const std::vector<uint32_t>* Screen::combiningAt(int r, int c) const {
  if (r < 0 || r >= rows_ || c < 0 || c >= cols_) return nullptr;
  const RowExtras* ex = extras_[size_t(r)].get();
  return ex ? ex->find(uint16_t(c)) : nullptr;
}

void Screen::insertChars(int row, int fromCol, int count) {
  if (row < 0 || row >= rows_) return;
  count = count < 1 ? 1 : count;
  if (fromCol < 0) fromCol = 0;
  if (fromCol >= cols_) return;
  int srcStart = fromCol + count;
  if (srcStart < cols_) {
    std::memmove(&cells_[idx(row, srcStart)], &cells_[idx(row, fromCol)],
                 size_t(cols_ - srcStart) * sizeof(Cell));
  }
  Cell b = blankCell();
  for (int c = fromCol; c < fromCol + count && c < cols_; ++c) cells_[idx(row, c)] = b;
  if (RowExtras* ex = extrasAt(row)) {
    ex->shiftCols(fromCol, count, cols_);
    ex->shiftLinks(fromCol, count, cols_);
    if (ex->empty()) extras_[size_t(row)].reset();
  }
}

void Screen::deleteChars(int row, int fromCol, int count) {
  if (row < 0 || row >= rows_) return;
  count = count < 1 ? 1 : count;
  if (fromCol < 0) fromCol = 0;
  if (fromCol >= cols_) return;
  if (fromCol + count < cols_) {
    std::memmove(&cells_[idx(row, fromCol)], &cells_[idx(row, fromCol + count)],
                 size_t(cols_ - fromCol - count) * sizeof(Cell));
  }
  Cell b = blankCell();
  for (int c = std::max(fromCol, cols_ - count); c < cols_; ++c) cells_[idx(row, c)] = b;
  if (RowExtras* ex = extrasAt(row)) {
    ex->shiftCols(fromCol, -count, cols_);
    ex->shiftLinks(fromCol, -count, cols_);
    if (ex->empty()) extras_[size_t(row)].reset();
  }
}

void Screen::eraseRow(int row, int fromCol, int toCol, uint16_t styleId) {
  if (row < 0 || row >= rows_) return;
  int last = std::min(toCol, cols_ - 1);
  for (int c = std::max(fromCol, 0); c <= last; ++c) {
    Cell& dst = cells_[idx(row, c)];
    dst.cp = ' ';
    dst.style = styleId;  // erase keeps the given (current) style — bg persists
    dst.flags = 0;
  }
  if (RowExtras* ex = extrasAt(row)) {
    ex->removeRange(fromCol < 0 ? 0 : fromCol, last);
    ex->invalidateAllLinks();  // erased rows lose their span structure
    if (ex->empty()) extras_[size_t(row)].reset();
  }
}

void Screen::eraseRows(int fromRow, int toRow, uint16_t styleId) {
  for (int r = fromRow; r <= std::min(toRow, rows_ - 1); ++r) {
    for (int c = 0; c < cols_; ++c) {
      Cell& dst = cells_[idx(r, c)];
      dst.cp = ' ';
      dst.style = styleId;
      dst.flags = 0;
    }
    extras_[size_t(r)].reset();
    wrapFlags_[size_t(r)] = 0;  // fully erased row breaks any wrap chain
  }
}

void Screen::scrollUp(int n, int top, int bottom) {
  if (n <= 0 || top >= bottom || top < 0 || bottom >= rows_) return;
  int count = std::min(n, bottom - top + 1);
  if (hasScrollback_ && top == 0) {
    for (int i = 0; i < count; ++i) pushScrollbackRow(i);
  }
  int moveRows = bottom - top + 1 - count;
  if (moveRows > 0) {
    std::memmove(&cells_[idx(top, 0)], &cells_[idx(top + count, 0)],
                 size_t(moveRows) * cols_ * sizeof(Cell));
    // Rotate the sparse extras pointers to follow their rows.
    rotateScratch_.clear();
    rotateScratch_.insert(
        rotateScratch_.begin(),
        std::make_move_iterator(extras_.begin() + top),
        std::make_move_iterator(extras_.begin() + bottom + 1));
    auto& tmp = rotateScratch_;
    std::rotate(tmp.begin(), tmp.begin() + count, tmp.end());
    std::move(tmp.begin(), tmp.end(), extras_.begin() + top);
    // Wrap flags follow their rows through the rotation.
    std::rotate(wrapFlags_.begin() + top, wrapFlags_.begin() + top + count,
                wrapFlags_.begin() + bottom + 1);
  }
  for (int r = bottom - count + 1; r <= bottom; ++r) {
    fillRowBlank(r);
    extras_[size_t(r)].reset();
    wrapFlags_[size_t(r)] = 0;
  }
}

void Screen::scrollDown(int n, int top, int bottom) {
  if (n <= 0 || top >= bottom || top < 0 || bottom >= rows_) return;
  int count = std::min(n, bottom - top + 1);
  rotateScratch_.clear();
  rotateScratch_.insert(
      rotateScratch_.begin(),
      std::make_move_iterator(extras_.begin() + top),
      std::make_move_iterator(extras_.begin() + bottom + 1));
  auto& tmp = rotateScratch_;
  std::rotate(tmp.rbegin(), tmp.rbegin() + count, tmp.rend());
  if (bottom - top + 1 > count) {
    std::memmove(&cells_[idx(top + count, 0)], &cells_[idx(top, 0)],
                 size_t(bottom - top + 1 - count) * cols_ * sizeof(Cell));
  }
  std::move(tmp.begin(), tmp.end(), extras_.begin() + top);
  std::rotate(wrapFlags_.rbegin() + (rows_ - 1 - bottom),
              wrapFlags_.rbegin() + (rows_ - 1 - bottom) + count,
              wrapFlags_.rbegin() + (rows_ - top));
  for (int r = top; r < top + count; ++r) {
    fillRowBlank(r);
    extras_[size_t(r)].reset();
    wrapFlags_[size_t(r)] = 0;
  }
}

void Screen::insertLines(int row, int n, int top, int bottom) {
  if (row < top || row > bottom || row < 0 || row >= rows_) return;
  int count = std::min(n, bottom - row + 1);
  rotateScratch_.clear();
  rotateScratch_.insert(
      rotateScratch_.begin(),
      std::make_move_iterator(extras_.begin() + row),
      std::make_move_iterator(extras_.begin() + bottom + 1));
  auto& tmp = rotateScratch_;
  std::rotate(tmp.rbegin(), tmp.rbegin() + count, tmp.rend());
  if (bottom - row + 1 > count) {
    std::memmove(&cells_[idx(row + count, 0)], &cells_[idx(row, 0)],
                 size_t(bottom - row + 1 - count) * cols_ * sizeof(Cell));
  }
  std::move(tmp.begin(), tmp.end(), extras_.begin() + row);
  std::rotate(wrapFlags_.rbegin() + (rows_ - 1 - bottom),
              wrapFlags_.rbegin() + (rows_ - 1 - bottom) + count,
              wrapFlags_.rbegin() + (rows_ - row));
  for (int r = row; r < row + count; ++r) {
    fillRowBlank(r);
    extras_[size_t(r)].reset();
    wrapFlags_[size_t(r)] = 0;
  }
}

void Screen::deleteLines(int row, int n, int top, int bottom) {
  if (row < top || row > bottom || row < 0 || row >= rows_) return;
  int count = std::min(n, bottom - row + 1);
  rotateScratch_.clear();
  rotateScratch_.insert(
      rotateScratch_.begin(),
      std::make_move_iterator(extras_.begin() + row),
      std::make_move_iterator(extras_.begin() + bottom + 1));
  auto& tmp = rotateScratch_;
  std::rotate(tmp.begin(), tmp.begin() + count, tmp.end());
  if (bottom - row + 1 > count) {
    std::memmove(&cells_[idx(row, 0)], &cells_[idx(row + count, 0)],
                 size_t(bottom - row + 1 - count) * cols_ * sizeof(Cell));
  }
  std::move(tmp.begin(), tmp.end(), extras_.begin() + row);
  std::rotate(wrapFlags_.begin() + row, wrapFlags_.begin() + row + count,
              wrapFlags_.begin() + bottom + 1);
  for (int r = bottom - count + 1; r <= bottom; ++r) {
    fillRowBlank(r);
    extras_[size_t(r)].reset();
    wrapFlags_[size_t(r)] = 0;
  }
}

void Screen::resize(int newRows, int newCols) {
  newRows = newRows < 1 ? 1 : newRows;
  newCols = newCols < 1 ? 1 : newCols;
  if (newRows == rows_ && newCols == cols_) return;
  std::vector<Cell> newCells(size_t(newRows) * newCols, blankCell());
  std::vector<std::unique_ptr<RowExtras>> newExtras(newRows);
  std::vector<uint8_t> newWrap(newRows, 0);
  int copyRows = std::min(rows_, newRows);
  int copyCols = std::min(cols_, newCols);
  for (int r = 0; r < copyRows; ++r) {
    std::memcpy(&newCells[size_t(r) * newCols], &cells_[idx(r, 0)], size_t(copyCols) * sizeof(Cell));
    newExtras[size_t(r)] = std::move(extras_[size_t(r)]);
    newWrap[size_t(r)] = wrapFlags_[size_t(r)];
  }
  cells_ = std::move(newCells);
  extras_ = std::move(newExtras);
  wrapFlags_ = std::move(newWrap);
  rows_ = newRows;
  cols_ = newCols;
}

void Screen::clear() {
  for (auto& c : cells_) {
    c.cp = ' ';
    c.style = blankStyleId_;
    c.flags = 0;
  }
  for (auto& e : extras_) e.reset();
  std::fill(wrapFlags_.begin(), wrapFlags_.end(), 0);
  clearScrollback();
}

void Screen::clearScrollback() {
  ring_.clear();
  sbHead_ = 0;
  sbCount_ = 0;
  // linesEver_ is intentionally preserved (monotonic UI row-key baseline).
}

const Cell* Screen::scrollbackRowCells(int i) const {
  // i: oldest-first logical index; caller guarantees i < sbCount_.
  size_t phys = (sbHead_ + size_t(i)) % ring_.size();
  return ring_[phys].cells.data();
}

const RowExtras* Screen::scrollbackRowExtras(int i) const {
  size_t phys = (sbHead_ + size_t(i)) % ring_.size();
  return ring_[phys].extras.get();
}

// ── wrap flags ──

void Screen::setRowWrapped(int r, bool wrapped) {
  if (r >= 0 && r < rows_) wrapFlags_[size_t(r)] = wrapped ? 1 : 0;
}

bool Screen::rowWrapped(int r) const {
  return r >= 0 && r < rows_ ? wrapFlags_[size_t(r)] != 0 : false;
}

bool Screen::scrollbackRowWrapped(int i) const {
  if (i < 0 || size_t(i) >= sbCount_) return false;
  size_t phys = (sbHead_ + size_t(i)) % ring_.size();
  return ring_[phys].wrapped;
}

// ── reflow rebuild ──

void Screen::pushScrollbackRowCells(std::vector<Cell>&& cells,
                                     std::unique_ptr<RowExtras>&& extras, bool wrapped) {
  if (cells.size() != size_t(cols_)) return;  // caller contract violation
  ++linesEver_;
  if (maxScrollback_ == 0) return;
  SbRow* dst;
  if (ring_.size() < maxScrollback_) {
    ring_.emplace_back();
    dst = &ring_.back();
    sbCount_ = ring_.size();
    sbHead_ = 0;
  } else {
    dst = &ring_[sbHead_];
    sbHead_ = (sbHead_ + 1) % ring_.size();
  }
  dst->cells = std::move(cells);
  dst->extras = std::move(extras);
  dst->wrapped = wrapped;
  cells = std::vector<Cell>{};
  extras.reset();
}

void Screen::loadRow(int row, const Cell* cells, int cellCount,
                     std::unique_ptr<RowExtras>&& extras, bool wrapped) {
  if (row < 0 || row >= rows_) return;
  if (cells && cellCount > 0) {
    int n = std::min(cellCount, cols_);
    std::memcpy(&cells_[idx(row, 0)], cells, size_t(n) * sizeof(Cell));
  }
  extras_[size_t(row)] = std::move(extras);
  wrapFlags_[size_t(row)] = wrapped ? 1 : 0;
}

void Screen::resetTo(int newRows, int newCols) {
  newRows = newRows < 1 ? 1 : newRows;
  newCols = newCols < 1 ? 1 : newCols;
  rows_ = newRows;
  cols_ = newCols;
  cells_.assign(size_t(rows_) * cols_, blankCell());
  extras_.clear();
  extras_.resize(rows_);
  wrapFlags_.assign(rows_, 0);
  ring_.clear();
  sbHead_ = 0;
  sbCount_ = 0;
  // linesEver_ intentionally preserved (monotonic global row baseline).
}

std::string Screen::rowText(int r) const {
  if (r < 0 || r >= rows_) return std::string();
  return renderCells(&cells_[idx(r, 0)], extras_[size_t(r)].get());
}

std::string Screen::scrollbackRowText(int i) const {
  return renderCells(scrollbackRowCells(i), scrollbackRowExtras(i));
}

size_t Screen::cellFootprint() const {
  size_t total = cells_.size() * sizeof(Cell);
  for (const SbRow& row : ring_) total += row.cells.size() * sizeof(Cell);
  return total;
}

// ─── private helpers ──────────────────────────────────────────────────────

void Screen::fillRowBlank(int r) {
  Cell b = blankCell();
  Cell* dst = &cells_[idx(r, 0)];
  for (int c = 0; c < cols_; ++c) dst[c] = b;
}

void Screen::pushScrollbackRow(int srcRow) {
  ++linesEver_;  // monotonic even when the ring evicts (Kotlin M-2 parity)
  if (maxScrollback_ == 0) return;

  SbRow* dst;
  if (ring_.size() < maxScrollback_) {
    ring_.emplace_back();
    dst = &ring_.back();
    sbCount_ = ring_.size();
    sbHead_ = 0;
  } else {
    dst = &ring_[sbHead_];
    sbHead_ = (sbHead_ + 1) % ring_.size();
    // sbCount_ stays == ring_.size() (== maxScrollback_)
  }
  dst->cells.assign(&cells_[idx(srcRow, 0)], &cells_[idx(srcRow, 0)] + cols_);
  if (const RowExtras* ex = extras_[size_t(srcRow)].get()) {
    if (!dst->extras) dst->extras = std::make_unique<RowExtras>();
    *dst->extras = *ex;  // deep copy — scrollback is independent of live rows
  } else {
    dst->extras.reset();
  }
  dst->wrapped = wrapFlags_[size_t(srcRow)] != 0;
}

void Screen::ensureExtras(int r) {
  if (!extras_[size_t(r)]) extras_[size_t(r)] = std::make_unique<RowExtras>();
}

std::string Screen::renderCells(const Cell* cells, const RowExtras* extras) const {
  (void)extras;  // text projection renders base cps only (Kotlin parity)
  std::string out;
  out.reserve(size_t(cols_));
  int lastNonBlank = -1;  // index into [out]
  for (int c = 0; c < cols_; ++c) {
    const Cell& cell = cells[c];
    if (cell.flags & kCellWideTrail) continue;  // trail renders via its lead
    uint32_t cp = cell.cp == 0 ? uint32_t(' ') : cell.cp;
    // UTF-8 encode
    if (cp < 0x80) {
      out.push_back(char(cp));
    } else if (cp < 0x800) {
      out.push_back(char(0xC0 | (cp >> 6)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      out.push_back(char(0xE0 | (cp >> 12)));
      out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    } else {
      out.push_back(char(0xF0 | (cp >> 18)));
      out.push_back(char(0x80 | ((cp >> 12) & 0x3F)));
      out.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back(char(0x80 | (cp & 0x3F)));
    }
    if (cp != uint32_t(' ')) lastNonBlank = int(out.size()) - 1;
  }
  if (int(out.size()) > lastNonBlank + 1) out.resize(size_t(lastNonBlank + 1));
  return out;
}

}  // namespace apex::vt
