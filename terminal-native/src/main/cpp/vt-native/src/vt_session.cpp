// apex-vt-session — binary session codec implementation (save/load).
//
// Wire format (all integers fixed-width little-endian):
//
//   header (14 B) : magic u32 | version u16 | payloadLen u32 | crc32 u32
//                   (crc32 covers exactly the payload bytes)
//   payload       : 13 sections in fixed order, each framed as
//                   `u32 bodyLen | body`; the payload is exactly consumed.
//
//   #  section     body
//   0  dims        rows i32 | cols i32
//   1  cursor      cursorRow i32 | cursorCol i32 | wrapPending u8 |
//                  cursorShape u8 | scrollTop i32 | scrollBottom i32 |
//                  savedCursorRow i32 | savedCursorCol i32 |
//                  savedWrapPending u8 | savedOriginMode u8        (28 B)
//   2  modes       cursorVisible | applicationCursor | bracketedPaste |
//                  alternateScreen | reverseVideo | originMode     (6 × u8)
//   3  styles      count u32 | count × {fg kind,idx,r,g,b | bg … | attr u16} (12 B)
//   4  grid        count u32 | count × {cp u32 | style u16 | flags u16}
//   5  marks       count u32 | count × {row i32 | col i32 | n u8 | n × cp u32}
//                  (combining marks on the visible screen)
//   6  scrollback  lineCount u32 | lineCount × {
//                    cellCount u32 (== cols) | cells | markCount u32 | marks }
//   7  tabs        count u32 (== cols) | count × u8 (0/1)
//   8  title       len u32 | len bytes (UTF-8)
//   9  charsets    g0DecGraphics u8 | g1DecGraphics u8 | shiftOutG1 u8
//  10  mouse       mouseMode u8 | mouseEncoding u8
//  11  misc        lastPrintable u32 | scrollbackLinesEver i64
//  12  sgr         count u32 | count × style (12 B, same layout as #3)
//
// The per-line cellCount in #6 is stored explicitly so load() can assert it
// equals cols (a mismatch is a corruption signal, not a parse guess).
//
// load() validation rules (tagged [Rn] at each check site):
//  R1  magic == kMagic                       R2  version == kFormatVersion
//  R3  payloadLen == len - 14                R4  crc32(payload) == stored crc
//  R5  every section framed + fully consumed; payload has no trailing bytes
//  R6  0 <= rows <= kMaxRows, 0 <= cols <= kMaxCols, rows==0 iff cols==0;
//      the canonical empty state (rows==cols==0) carries no scrollback lines
//  R7  booleans (wrapPending, modes, saved*, charsets) are 0 or 1
//  R8  enums in range: CursorShape <= 2, ColorKind <= 2 (fg+bg of styles and
//      sgrStack), Underline <= 5, mouseMode <= 4, mouseEncoding <= 3
//  R9  styles count <= kMaxStyles            R10 grid cell count == rows*cols
//  R11 every cell style id (grid + scrollback) < styles.size()
//  R12 gridMarks: row in [0, rows), col in [0, cols), cps <= 16
//  R13 scrollback: lineCount <= kMaxScrollbackSave; per-line cellCount ==
//      cols; line marks row in [0, lineCount), col in [0, cols), cps <= 16
//  R14 tabStops count == cols, values 0/1    R15 title bytes <= kMaxTitleBytes
//  R16 sgrStack count <= kMaxSgrStack
// Any violation or truncation → false, *out untouched. Allocations are
// bounded by (payload size × small constant) via pre-read bounds checks,
// so hostile files cannot force outsized resize() calls.

#include "vt_session.h"

#include <cstddef>
#include <cstring>
#include <utility>

namespace apex::vt::session {
namespace {

constexpr size_t kHeaderSize = 14;    // magic u32 | version u16 | payloadLen u32 | crc u32
constexpr size_t kSectionCount = 13;  // dims … sgr, fixed order
constexpr size_t kStyleBytes = 12;    // fg 5 | bg 5 | attr u16
constexpr size_t kCellBytes = 8;      // cp u32 | style u16 | flags u16
constexpr size_t kMarkMinBytes = 9;   // row i32 | col i32 | count u8
constexpr size_t kRowMinBytes = 8;    // cellCount u32 | markCount u32
constexpr uint8_t kCpsPerMarkMax = 16;

// ─── Writer — little-endian appending serializer ──────────────────────────
struct Writer {
  std::vector<uint8_t> buf;

  void putU8(uint8_t v) { buf.push_back(v); }
  void putU16(uint16_t v) {
    buf.push_back(uint8_t(v));
    buf.push_back(uint8_t(v >> 8));
  }
  void putU32(uint32_t v) {
    buf.push_back(uint8_t(v));
    buf.push_back(uint8_t(v >> 8));
    buf.push_back(uint8_t(v >> 16));
    buf.push_back(uint8_t(v >> 24));
  }
  void putU64(uint64_t v) {
    for (int i = 0; i < 8; ++i) buf.push_back(uint8_t(v >> (8 * i)));
  }
  // int→unsigned via memcpy: fully portable, no implementation-defined casts.
  void putI32(int32_t v) {
    uint32_t u = 0;
    std::memcpy(&u, &v, sizeof(u));
    putU32(u);
  }
  void putI64(int64_t v) {
    uint64_t u = 0;
    std::memcpy(&u, &v, sizeof(u));
    putU64(u);
  }
  void putBytes(const uint8_t* p, size_t n) {
    if (n != 0) buf.insert(buf.end(), p, p + n);
  }
  void putString(const std::string& s) {  // len u32 + bytes
    putU32(uint32_t(s.size()));
    putBytes(reinterpret_cast<const uint8_t*>(s.data()), s.size());
  }
};

// ─── Reader — bounds-checked little-endian deserializer ───────────────────
// Every read checks the remaining length first and returns false on
// underrun; callers abort on the first false (no partial state escapes).
struct Reader {
  const uint8_t* p = nullptr;
  size_t n = 0;
  size_t pos = 0;

  Reader() = default;
  Reader(const uint8_t* data, size_t size) : p(data), n(size) {}

  size_t remaining() const { return n - pos; }
  const uint8_t* cur() const { return p + pos; }

  bool readU8(uint8_t* v) {
    if (remaining() < 1) return false;
    *v = p[pos];
    ++pos;
    return true;
  }
  bool readU16(uint16_t* v) {
    if (remaining() < 2) return false;
    *v = uint16_t(uint32_t(p[pos]) | (uint32_t(p[pos + 1]) << 8));
    pos += 2;
    return true;
  }
  bool readU32(uint32_t* v) {
    if (remaining() < 4) return false;
    *v = uint32_t(p[pos]) | (uint32_t(p[pos + 1]) << 8) |
         (uint32_t(p[pos + 2]) << 16) | (uint32_t(p[pos + 3]) << 24);
    pos += 4;
    return true;
  }
  bool readU64(uint64_t* v) {
    if (remaining() < 8) return false;
    uint64_t u = 0;
    for (int i = 0; i < 8; ++i) u |= uint64_t(p[pos + i]) << (8 * i);
    *v = u;
    pos += 8;
    return true;
  }
  bool readI32(int32_t* v) {
    uint32_t u = 0;
    if (!readU32(&u)) return false;
    std::memcpy(v, &u, sizeof(*v));
    return true;
  }
  bool readI64(int64_t* v) {
    uint64_t u = 0;
    if (!readU64(&u)) return false;
    std::memcpy(v, &u, sizeof(*v));
    return true;
  }
  bool readBytes(uint8_t* dst, size_t cnt) {
    if (remaining() < cnt) return false;
    if (cnt != 0) std::memcpy(dst, cur(), cnt);
    pos += cnt;
    return true;
  }
  bool readStringN(std::string* dst, size_t cnt) {  // length already validated
    if (remaining() < cnt) return false;
    if (cnt != 0) dst->assign(reinterpret_cast<const char*>(cur()), cnt);
    pos += cnt;
    return true;
  }
  bool skip(size_t cnt) {
    if (remaining() < cnt) return false;
    pos += cnt;
    return true;
  }
};

// Appends `u32 bodyLen | body` to the payload under construction.
void appendSection(std::vector<uint8_t>* payload, const Writer& body) {
  Writer head;
  head.putU32(uint32_t(body.buf.size()));
  payload->insert(payload->end(), head.buf.begin(), head.buf.end());
  payload->insert(payload->end(), body.buf.begin(), body.buf.end());
}

// Frames the next section: reads the u32 body length, checks it fits in the
// remaining bytes and hands out a sub-reader over the body. The caller must
// parse the body fully (checked as [R5]).
bool frameSection(Reader* r, Reader* body) {
  uint32_t len = 0;
  if (!r->readU32(&len)) return false;  // [R5] 截断：长度前缀不足
  if (r->remaining() < len) return false;  // [R5] 截断：节体不足
  *body = Reader(r->cur(), len);
  return r->skip(len);
}

// Style wire layout: fg(5) | bg(5) | attr u16 → 12 bytes.
void putStyle(Writer* w, const Style& s) {
  w->putU8(s.fg.kind);
  w->putU8(s.fg.index);
  w->putU8(s.fg.r);
  w->putU8(s.fg.g);
  w->putU8(s.fg.b);
  w->putU8(s.bg.kind);
  w->putU8(s.bg.index);
  w->putU8(s.bg.r);
  w->putU8(s.bg.g);
  w->putU8(s.bg.b);
  w->putU16(s.attr);
}

bool readStyle(Reader* r, Style* s) {
  if (!r->readU8(&s->fg.kind) || !r->readU8(&s->fg.index) ||
      !r->readU8(&s->fg.r) || !r->readU8(&s->fg.g) || !r->readU8(&s->fg.b) ||
      !r->readU8(&s->bg.kind) || !r->readU8(&s->bg.index) ||
      !r->readU8(&s->bg.r) || !r->readU8(&s->bg.g) || !r->readU8(&s->bg.b) ||
      !r->readU16(&s->attr)) {
    return false;  // [R5] 截断
  }
  if (s->fg.kind > kColorRgb || s->bg.kind > kColorRgb) return false;  // [R8]
  if (ulOf(s->attr) > kUlDashed) return false;                         // [R8]
  return true;
}

// Mark wire layout: row i32 | col i32 | n u8 | n × cp u32.
void putMark(Writer* w, const Mark& m) {
  w->putI32(m.row);
  w->putI32(m.col);
  w->putU8(uint8_t(m.cps.size()));  // contract: caller keeps cps <= 16
  for (uint32_t cp : m.cps) w->putU32(cp);
}

// maxRow/maxCol bound the mark coordinates ([R12]/[R13] 行列越界).
bool readMark(Reader* r, int32_t maxRow, int32_t maxCol, Mark* m) {
  if (!r->readI32(&m->row) || !r->readI32(&m->col)) return false;
  uint8_t n = 0;
  if (!r->readU8(&n)) return false;
  if (n > kCpsPerMarkMax) return false;  // [R12]/[R13] cps 数每 mark <= 16
  if (r->remaining() < size_t(n) * 4u) return false;  // 截断预检
  m->cps.resize(n);
  for (uint32_t& cp : m->cps) {
    if (!r->readU32(&cp)) return false;
  }
  if (m->row < 0 || m->row >= maxRow) return false;  // 行越界
  if (m->col < 0 || m->col >= maxCol) return false;  // 列越界
  return true;
}

// SessionRow wire layout: cellCount u32 | cells | markCount u32 | marks.
// The cell count is stored per line so load can assert it equals cols.
void putRow(Writer* w, const SessionRow& row) {
  w->putU32(uint32_t(row.cells.size()));
  for (const Cell& c : row.cells) {
    w->putU32(c.cp);
    w->putU16(c.style);
    w->putU16(c.flags);
  }
  w->putU32(uint32_t(row.marks.size()));
  for (const Mark& m : row.marks) putMark(w, m);
}

// markMaxRow bounds line marks against the scrollback line count ([R13]).
bool readRow(Reader* r, int32_t cols, size_t styleCount, int32_t markMaxRow,
             SessionRow* row) {
  uint32_t cellCount = 0;
  if (!r->readU32(&cellCount)) return false;
  if (cellCount != uint32_t(cols)) return false;  // [R13] 行 cells == cols
  if (r->remaining() < size_t(cellCount) * kCellBytes) return false;  // 截断预检
  row->cells.resize(cellCount);
  for (Cell& c : row->cells) {
    uint32_t cp = 0;
    uint16_t style = 0, flags = 0;
    if (!r->readU32(&cp) || !r->readU16(&style) || !r->readU16(&flags)) return false;
    if (style >= styleCount) return false;  // [R11] cell style 下标越界
    c.cp = cp;
    c.style = style;
    c.flags = flags;
  }
  uint32_t markCount = 0;
  if (!r->readU32(&markCount)) return false;
  if (r->remaining() / kMarkMinBytes < markCount) return false;  // 截断预检
  row->marks.resize(markCount);
  for (Mark& m : row->marks) {
    if (!readMark(r, markMaxRow, cols, &m)) return false;
  }
  return true;
}

// Cell wire layout (grid section): cp u32 | style u16 | flags u16.
bool readCell(Reader* r, size_t styleCount, Cell* c) {
  uint32_t cp = 0;
  uint16_t style = 0, flags = 0;
  if (!r->readU32(&cp) || !r->readU16(&style) || !r->readU16(&flags)) return false;
  if (style >= styleCount) return false;  // [R11] cell style 下标越界
  c->cp = cp;
  c->style = style;
  c->flags = flags;
  return true;
}

// ─── CRC-32 (IEEE 802.3, reflected, poly 0xEDB88320) ──────────────────────
constexpr uint32_t kCrcPoly = 0xEDB88320u;

constexpr uint32_t crcTableEntry(uint32_t index) {
  uint32_t c = index;
  for (int bit = 0; bit < 8; ++bit) {
    c = (c & 1u) != 0u ? (kCrcPoly ^ (c >> 1)) : (c >> 1);
  }
  return c;
}

// 256-entry table, computed at compile time.
struct CrcTable {
  uint32_t entry[256];
  constexpr CrcTable() : entry{} {
    for (uint32_t i = 0; i < 256; ++i) {
      entry[i] = crcTableEntry(i);
    }
  }
};
constexpr CrcTable kCrcTable{};

}  // namespace

uint32_t crc32(const uint8_t* data, size_t len) {
  uint32_t c = 0xFFFFFFFFu;
  for (size_t i = 0; i < len; ++i) {
    c = kCrcTable.entry[(c ^ data[i]) & 0xFFu] ^ (c >> 8);
  }
  return c ^ 0xFFFFFFFFu;
}

bool save(const SessionState& s, std::vector<uint8_t>* out) {
  if (out == nullptr) return false;
  std::vector<uint8_t> payload;

  {  // 0 dims — first after the header so bad sizes fail early on load.
    Writer sec;
    sec.putI32(s.rows);
    sec.putI32(s.cols);
    appendSection(&payload, sec);
  }
  {  // 1 cursor
    Writer sec;
    sec.putI32(s.cursorRow);
    sec.putI32(s.cursorCol);
    sec.putU8(s.wrapPending ? 1 : 0);
    sec.putU8(uint8_t(s.cursorShape));
    sec.putI32(s.scrollTop);
    sec.putI32(s.scrollBottom);
    sec.putI32(s.savedCursorRow);
    sec.putI32(s.savedCursorCol);
    sec.putU8(s.savedWrapPending ? 1 : 0);
    sec.putU8(s.savedOriginMode ? 1 : 0);
    appendSection(&payload, sec);
  }
  {  // 2 modes
    Writer sec;
    sec.putU8(s.cursorVisible ? 1 : 0);
    sec.putU8(s.applicationCursor ? 1 : 0);
    sec.putU8(s.bracketedPaste ? 1 : 0);
    sec.putU8(s.alternateScreen ? 1 : 0);
    sec.putU8(s.reverseVideo ? 1 : 0);
    sec.putU8(s.originMode ? 1 : 0);
    appendSection(&payload, sec);
  }
  {  // 3 styles — before grid so cell style ids can be bounds-checked.
    Writer sec;
    sec.putU32(uint32_t(s.styles.size()));
    for (const Style& st : s.styles) putStyle(&sec, st);
    appendSection(&payload, sec);
  }
  {  // 4 grid
    Writer sec;
    sec.putU32(uint32_t(s.grid.size()));
    for (const Cell& c : s.grid) {
      sec.putU32(c.cp);
      sec.putU16(c.style);
      sec.putU16(c.flags);
    }
    appendSection(&payload, sec);
  }
  {  // 5 marks — combining marks on the visible screen.
    Writer sec;
    sec.putU32(uint32_t(s.gridMarks.size()));
    for (const Mark& m : s.gridMarks) putMark(&sec, m);
    appendSection(&payload, sec);
  }
  {  // 6 scrollback
    Writer sec;
    sec.putU32(uint32_t(s.scrollback.size()));
    for (const SessionRow& row : s.scrollback) putRow(&sec, row);
    appendSection(&payload, sec);
  }
  {  // 7 tabs
    Writer sec;
    sec.putU32(uint32_t(s.tabStops.size()));
    sec.putBytes(s.tabStops.data(), s.tabStops.size());
    appendSection(&payload, sec);
  }
  {  // 8 title
    Writer sec;
    sec.putString(s.title);
    appendSection(&payload, sec);
  }
  {  // 9 charsets
    Writer sec;
    sec.putU8(s.g0DecGraphics ? 1 : 0);
    sec.putU8(s.g1DecGraphics ? 1 : 0);
    sec.putU8(s.shiftOutG1 ? 1 : 0);
    appendSection(&payload, sec);
  }
  {  // 10 mouse
    Writer sec;
    sec.putU8(s.mouseMode);
    sec.putU8(s.mouseEncoding);
    appendSection(&payload, sec);
  }
  {  // 11 misc
    Writer sec;
    sec.putU32(s.lastPrintable);
    sec.putI64(s.scrollbackLinesEver);
    appendSection(&payload, sec);
  }
  {  // 12 sgr — XTPUSHSGR stack.
    Writer sec;
    sec.putU32(uint32_t(s.sgrStack.size()));
    for (const Style& st : s.sgrStack) putStyle(&sec, st);
    appendSection(&payload, sec);
  }

  Writer head;
  head.putU32(kMagic);
  head.putU16(kFormatVersion);
  head.putU32(uint32_t(payload.size()));
  head.putU32(crc32(payload.data(), payload.size()));

  std::vector<uint8_t> full;
  full.reserve(head.buf.size() + payload.size());
  full.insert(full.end(), head.buf.begin(), head.buf.end());
  full.insert(full.end(), payload.begin(), payload.end());
  out->swap(full);  // commit: only reached when everything succeeded
  return true;
}

bool load(const uint8_t* data, size_t len, SessionState* out) {
  if (data == nullptr || out == nullptr) return false;
  if (len < kHeaderSize) return false;  // [R5] 连 header 都不完整（截断）

  Reader hr(data, len);
  uint32_t magic = 0, payloadLen = 0, crcStored = 0;
  uint16_t version = 0;
  if (!hr.readU32(&magic) || !hr.readU16(&version) ||
      !hr.readU32(&payloadLen) || !hr.readU32(&crcStored)) {
    return false;  // defensive: unreachable after the length check above
  }
  if (magic != kMagic) return false;          // [R1] magic
  if (version != kFormatVersion) return false;  // [R2] 版本
  if (payloadLen != len - kHeaderSize) return false;  // [R3] payloadLen 与实际一致
  const uint8_t* payload = data + kHeaderSize;
  if (crc32(payload, payloadLen) != crcStored) return false;  // [R4] crc

  SessionState t;  // parse into a temp; *out stays untouched on failure
  Reader pr(payload, size_t(payloadLen));
  Reader sec;

  // ── 0 dims ────────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  if (!sec.readI32(&t.rows) || !sec.readI32(&t.cols)) return false;
  if (t.rows < 0 || t.rows > kMaxRows) return false;  // [R6] rows 范围
  if (t.cols < 0 || t.cols > kMaxCols) return false;  // [R6] cols 范围
  if ((t.rows == 0) != (t.cols == 0)) return false;   // [R6] 空状态 rows==cols==0
  if (sec.remaining() != 0) return false;             // [R5] 节体恰好用尽

  // ── 1 cursor ──────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint8_t wrap = 0, shape = 0, savedWrap = 0, savedOrig = 0;
    if (!sec.readI32(&t.cursorRow) || !sec.readI32(&t.cursorCol) ||
        !sec.readU8(&wrap) || !sec.readU8(&shape) ||
        !sec.readI32(&t.scrollTop) || !sec.readI32(&t.scrollBottom) ||
        !sec.readI32(&t.savedCursorRow) || !sec.readI32(&t.savedCursorCol) ||
        !sec.readU8(&savedWrap) || !sec.readU8(&savedOrig)) {
      return false;  // [R5] 截断
    }
    if (wrap > 1 || savedWrap > 1 || savedOrig > 1) return false;  // [R7] 布尔值域
    if (shape > 2) return false;  // [R8] CursorShape 值域
    t.wrapPending = wrap != 0;
    t.cursorShape = static_cast<CursorShape>(shape);
    t.savedWrapPending = savedWrap != 0;
    t.savedOriginMode = savedOrig != 0;
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 2 modes ───────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint8_t b[6] = {0, 0, 0, 0, 0, 0};
    for (int i = 0; i < 6; ++i) {
      if (!sec.readU8(&b[i])) return false;  // [R5] 截断
    }
    for (int i = 0; i < 6; ++i) {
      if (b[i] > 1) return false;  // [R7] 布尔值域
    }
    t.cursorVisible = b[0] != 0;
    t.applicationCursor = b[1] != 0;
    t.bracketedPaste = b[2] != 0;
    t.alternateScreen = b[3] != 0;
    t.reverseVideo = b[4] != 0;
    t.originMode = b[5] != 0;
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 3 styles ──────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint32_t count = 0;
    if (!sec.readU32(&count)) return false;
    if (count > kMaxStyles) return false;  // [R9] style 数 <= kMaxStyles
    if (sec.remaining() < size_t(count) * kStyleBytes) return false;  // 截断预检
    t.styles.resize(count);
    for (Style& st : t.styles) {
      if (!readStyle(&sec, &st)) return false;  // [R8] 枚举值域
    }
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 4 grid ────────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint32_t count = 0;
    if (!sec.readU32(&count)) return false;
    const int64_t want = int64_t(t.rows) * int64_t(t.cols);
    if (int64_t(count) != want) return false;  // [R10] cell 数 == rows*cols
    if (sec.remaining() < size_t(count) * kCellBytes) return false;  // 截断预检
    t.grid.resize(count);
    for (Cell& c : t.grid) {
      if (!readCell(&sec, t.styles.size(), &c)) return false;  // [R11]
    }
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 5 marks (visible screen) ──────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint32_t count = 0;
    if (!sec.readU32(&count)) return false;
    if (sec.remaining() / kMarkMinBytes < count) return false;  // 截断预检
    t.gridMarks.resize(count);
    for (Mark& m : t.gridMarks) {
      if (!readMark(&sec, t.rows, t.cols, &m)) return false;  // [R12]
    }
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 6 scrollback ──────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint32_t lineCount = 0;
    if (!sec.readU32(&lineCount)) return false;
    if (lineCount > uint32_t(kMaxScrollbackSave)) return false;  // [R13] 行数上限
    if (t.rows == 0 && lineCount != 0) return false;  // [R6] 空状态 → scrollback 空
    if (sec.remaining() / kRowMinBytes < lineCount) return false;  // 截断预检
    t.scrollback.resize(lineCount);
    for (uint32_t i = 0; i < lineCount; ++i) {
      if (!readRow(&sec, t.cols, t.styles.size(), int32_t(lineCount),
                   &t.scrollback[i])) {
        return false;  // [R11]/[R13]
      }
    }
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 7 tabs ────────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint32_t count = 0;
    if (!sec.readU32(&count)) return false;
    if (count != uint32_t(t.cols)) return false;  // [R14] tabStops 大小 == cols
    if (sec.remaining() < count) return false;  // 截断预检
    t.tabStops.resize(count);
    if (!sec.readBytes(t.tabStops.data(), count)) return false;
    for (uint8_t v : t.tabStops) {
      if (v > 1) return false;  // [R14] 值域 {0,1}
    }
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 8 title ───────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint32_t titleLen = 0;
    if (!sec.readU32(&titleLen)) return false;
    if (titleLen > kMaxTitleBytes) return false;  // [R15] title 长度上限
    if (!sec.readStringN(&t.title, titleLen)) return false;  // 截断检查
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 9 charsets ────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint8_t g0 = 0, g1 = 0, so = 0;
    if (!sec.readU8(&g0) || !sec.readU8(&g1) || !sec.readU8(&so)) return false;
    if (g0 > 1 || g1 > 1 || so > 1) return false;  // [R7] 布尔值域
    t.g0DecGraphics = g0 != 0;
    t.g1DecGraphics = g1 != 0;
    t.shiftOutG1 = so != 0;
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 10 mouse ──────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint8_t mode = 0, enc = 0;
    if (!sec.readU8(&mode) || !sec.readU8(&enc)) return false;
    if (mode > 4) return false;  // [R8] mouseMode 值域
    if (enc > 3) return false;  // [R8] mouseEncoding 值域
    t.mouseMode = mode;
    t.mouseEncoding = enc;
    if (sec.remaining() != 0) return false;  // [R5]
  }

  // ── 11 misc ───────────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  if (!sec.readU32(&t.lastPrintable)) return false;
  if (!sec.readI64(&t.scrollbackLinesEver)) return false;
  if (sec.remaining() != 0) return false;  // [R5]

  // ── 12 sgr stack ──────────────────────────────────────────────────────
  if (!frameSection(&pr, &sec)) return false;
  {
    uint32_t count = 0;
    if (!sec.readU32(&count)) return false;
    if (count > kMaxSgrStack) return false;  // [R16] sgrStack <= 16
    if (sec.remaining() < size_t(count) * kStyleBytes) return false;  // 截断预检
    t.sgrStack.resize(count);
    for (Style& st : t.sgrStack) {
      if (!readStyle(&sec, &st)) return false;  // [R8] 枚举值域
    }
    if (sec.remaining() != 0) return false;  // [R5]
  }

  if (pr.remaining() != 0) return false;  // [R5] payload 恰好被 13 节用尽

  *out = std::move(t);  // commit — everything validated
  return true;
}

}  // namespace apex::vt::session
