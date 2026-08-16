#ifndef APEX_TERMINAL_ANSI_FILTER_H
#define APEX_TERMINAL_ANSI_FILTER_H

#include <string>

namespace apex {

/**
 * Strips ANSI/VT100 escape sequences from a string so the raw terminal output
 * can be presented as plain text (used by NativePty.nativeRead(stripAnsi=true)).
 *
 * Handles the common cases:
 *  - CSI sequences: ESC [ ... <final byte 0x40-0x7E>
 *  - OSC sequences: ESC ] ... BEL (0x07) or ESC \ (ST)
 *  - lone ESC + one byte
 *
 * Non-escaped bytes are passed through unchanged.
 */
class AnsiFilter {
public:
    static std::string strip(const std::string& input) {
        std::string out;
        out.reserve(input.size());
        const size_t n = input.size();
        size_t i = 0;
        while (i < n) {
            const unsigned char c = static_cast<unsigned char>(input[i]);
            if (c == 0x1B) { // ESC
                if (i + 1 < n && input[i + 1] == '[') {
                    // CSI sequence: ESC [ params intermediates final
                    i += 2;
                    while (i < n) {
                        const unsigned char d = static_cast<unsigned char>(input[i]);
                        if (d >= 0x40 && d <= 0x7E) { // final byte
                            ++i;
                            break;
                        }
                        ++i;
                    }
                } else if (i + 1 < n && input[i + 1] == ']') {
                    // OSC sequence: ESC ] ... BEL or ST(ESC \)
                    i += 2;
                    while (i < n) {
                        const unsigned char d = static_cast<unsigned char>(input[i]);
                        if (d == 0x07) { // BEL
                            ++i;
                            break;
                        }
                        if (d == 0x1B && i + 1 < n && input[i + 1] == '\\') { // ST
                            i += 2;
                            break;
                        }
                        ++i;
                    }
                } else {
                    // lone ESC or other 2-byte escape — drop ESC and the next byte
                    i += 2;
                }
                continue;
            }
            out.push_back(static_cast<char>(c));
            ++i;
        }
        return out;
    }
};

} // namespace apex

#endif // APEX_TERMINAL_ANSI_FILTER_H
