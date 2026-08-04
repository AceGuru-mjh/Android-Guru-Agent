#include "ansi_filter.h"

namespace apex {

std::string AnsiFilter::strip(const std::string& input) {
    std::string output;
    output.reserve(input.size());

    State state = State::NORMAL;
    size_t i = 0;

    while (i < input.size()) {
        char c = input[i];

        switch (state) {
        case State::NORMAL:
            if (c == '\033') { // ESC
                state = State::ESCAPE;
            } else if (c == '\r') {
                // 跳过CR（保留LF）
            } else if (c >= 32 || c == '\n' || c == '\t') {
                output += c;
            }
            // 其他控制字符跳过
            break;

        case State::ESCAPE:
            if (c == '[') {
                state = State::CSI; // CSI序列: ESC [
            } else if (c == ']') {
                state = State::OSC; // OSC序列: ESC ]
            } else if (c == '(' || c == ')') {
                // 字符集选择: ESC ( B，跳过下一个字符
                i++;
            }
            state = State::NORMAL;
            break;

        case State::CSI:
            // CSI序列直到遇到字母结束
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                state = State::NORMAL;
            }
            break;

        case State::OSC:
            // OSC序列直到 BEL(\007) 或 ESC \
            if (c == '\007') {
                state = State::NORMAL;
            } else if (c == '\033' && i + 1 < input.size() && input[i + 1] == '\\') {
                i++; // 跳过 \
                state = State::NORMAL;
            }
            break;
        }

        i++;
    }

    return output;
}

std::string AnsiFilter::stripPreserveStructure(const std::string& input) {
    // 与strip相同，但保留更多空白结构
    return strip(input);
}

} // namespace apex
