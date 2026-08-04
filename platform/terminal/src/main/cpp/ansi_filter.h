#pragma once

#include <string>

namespace apex {

/**
 * ANSI转义序列过滤器
 * 将终端原始输出清理为纯文本，供Agent消费
 */
class AnsiFilter {
public:
    /**
     * 去除所有ANSI转义序列，保留纯文本
     */
    static std::string strip(const std::string& input);

    /**
     * 去除ANSI但保留换行结构
     */
    static std::string stripPreserveStructure(const std::string& input);

private:
    enum class State { NORMAL, ESCAPE, CSI, OSC };
};

} // namespace apex
