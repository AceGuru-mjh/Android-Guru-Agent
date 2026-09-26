// apex-vt-lines — implementation (see vt_lines.h for the contract).
#include "vt_lines.h"

namespace apex::vt::lines {

namespace {

// UTF-8 encode [cp] and append to [out]; returns the byte count (1..4).
// Same encoder as Screen::renderCells — the two projections must agree.
int appendUtf8(std::string* out, uint32_t cp) {
  if (cp < 0x80) {
    out->push_back(char(cp));
    return 1;
  }
  if (cp < 0x800) {
    out->push_back(char(0xC0 | (cp >> 6)));
    out->push_back(char(0x80 | (cp & 0x3F)));
    return 2;
  }
  if (cp < 0x10000) {
    out->push_back(char(0xE0 | (cp >> 12)));
    out->push_back(char(0x80 | ((cp >> 6) & 0x3F)));
    out->push_back(char(0x80 | (cp & 0x3F)));
    return 3;
  }
  out->push_back(char(0xF0 | (cp >> 18)));
  out->push_back(char(0x80 | ((cp >> 12) & 0x3F)));
  out->push_back(char(0x80 | ((cp >> 6) & 0x3F)));
  out->push_back(char(0x80 | (cp & 0x3F)));
  return 4;
}

// Screen::rowText blank semantics: cp ' ' or 0 (style-agnostic).
inline bool isBlankCp(uint32_t cp) { return cp == uint32_t(' ') || cp == 0; }

}  // namespace

AssembleStats assemble(const RowRef* rows, int rowCount, int64_t firstAbsRow,
                       int maxRowsPerLine, std::vector<LogicalLine>* out) {
  AssembleStats stats{0, 0};
  if (out == nullptr) return stats;
  out->clear();
  if (rows == nullptr) rowCount = 0;
  if (rowCount <= 0) return stats;

  // Clamp the chain bound into [1, 65535]: extractText packs row-in-line
  // indices into 16 bits, so a logical line may never own more rows.
  int cap = maxRowsPerLine < 1 ? 1 : (maxRowsPerLine > 65535 ? 65535 : maxRowsPerLine);

  int i = 0;
  while (i < rowCount) {
    const int start = i;
    ++i;  // rows[start] always opens the line — a wrapped flag on row [start]
          // itself describes a fold INTO a previous line, not out of this one.
    // Greedily extend while the previous row folded and the chain stays
    // within the bound.
    while (i < rowCount && rows[i - 1].wrapped && (i - start) < cap) {
      ++i;
    }
    LogicalLine line;
    line.rows.assign(rows + start, rows + i);
    line.firstAbsRow = firstAbsRow + start;
    line.endsWithNewline = !line.rows.back().wrapped;

    // Force-cut: the last consumed row still folds AND more input rows
    // follow. The line keeps wrapped == true on its cut edge (the logical
    // line really does continue — the caller can detect truncation via
    // stats.truncatedLines or rows.size() == the bound + last row wrapped).
    if (i < rowCount && line.rows.back().wrapped && int(line.rows.size()) == cap) {
      ++stats.truncatedLines;
    }
    out->push_back(std::move(line));
    ++stats.lineCount;
  }
  return stats;
}

void extractText(const LogicalLine& line, std::string* text,
                 std::vector<uint32_t>* map) {
  if (text == nullptr || map == nullptr) return;
  text->clear();
  map->clear();

  // Trailing-blank trimming needs the last non-blank cell to build the
  // one-past-end entry, so track it while scanning.
  uint32_t lastPacked = 0;  // (rowInLine << 16) | col
  int lastWidth = 1;        // display width of that cell (lead = 2)

  for (size_t r = 0; r < line.rows.size(); ++r) {
    const RowRef& rr = line.rows[r];
    if (rr.cells == nullptr || rr.count <= 0) continue;
    const uint32_t packedRow = uint32_t(r) << 16;
    for (int c = 0; c < rr.count; ++c) {
      const Cell& cell = rr.cells[size_t(c)];
      if (cell.flags & kCellWideTrail) continue;  // trail renders via its lead
      const uint32_t cp = cell.cp == 0 ? uint32_t(' ') : cell.cp;
      const uint32_t packed = packedRow | uint32_t(c);
      const int bytes = appendUtf8(text, cp);
      // Every byte of the cluster carries the owning cell's position
      // (continuation bytes repeat it — see the header contract).
      for (int b = 0; b < bytes; ++b) map->push_back(packed);
      if (!isBlankCp(cell.cp)) {
        lastPacked = packed;
        lastWidth = (cell.flags & kCellWideLead) ? 2 : 1;
      }
    }
  }

  // Trim trailing blanks in lockstep with the text: a trailing ' ' byte is
  // always produced by a blank cell (non-blank cells cannot render ' '),
  // so popping text+map together keeps the 1:1 byte correspondence.
  while (!text->empty() && text->back() == ' ') {
    text->pop_back();
    map->pop_back();
  }

  // One-past-end entry: the column just past the last kept cell (a wide
  // lead consumes two columns). All-blank / empty line → (row 0, col 0).
  if (text->empty()) {
    map->push_back(0);
  } else {
    const uint32_t row = lastPacked >> 16;
    const uint32_t col = lastPacked & 0xFFFFu;
    map->push_back((row << 16) | (col + uint32_t(lastWidth)));
  }
}

}  // namespace apex::vt::lines
