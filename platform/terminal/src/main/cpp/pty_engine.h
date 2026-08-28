#pragma once

#include <string>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <vector>

namespace apex {

class PtySession;

/**
 * PTY读取状态常量（P70-1）。
 *
 * 单一事实来源：数值与 jni_bridge.cpp 的 PTY_READ_* 常量、Kotlin 侧
 * PtyJniReadStatus 一一对应，勿改。
 */
enum PtyReadStatus {
    PTY_READ_DATA = 0,              // 读到数据
    PTY_READ_NO_DATA = 1,           // EAGAIN/EWOULDBLOCK —— idle，流与进程均正常
    PTY_READ_EOF = 2,               // 输出流结束（read()==0 或 EIO）
    PTY_READ_ERROR = 3,             // 真实 read 错误（errno 有效）
    PTY_READ_SESSION_NOT_FOUND = 4, // sessionId 不存在（已 close 或从未创建）
};

/** PtyEngine::readEx 的结果：原始字节（可含 NUL，P70-2）+ 明确状态。 */
struct ReadOutcome {
    std::string data;
    int status = PTY_READ_NO_DATA;
    int err = 0;  // 仅 status==PTY_READ_ERROR 时有效
};

/**
 * PTY引擎：管理所有终端会话
 * 线程安全的单例
 */
class PtyEngine {
public:
    static PtyEngine& instance();

    int createSession(const std::string& shell, const std::string& workDir,
                      const std::vector<std::pair<std::string, std::string>>& envVars,
                      int rows, int cols);

    bool write(int sessionId, const char* data, size_t len);
    bool writeLine(int sessionId, const std::string& line);

    /** Legacy：仅返回数据字节（不区分 idle/EOF/error）。保留供旧 JNI String 通道。 */
    std::string read(int sessionId, int maxBytes);

    /**
     * P70-1/P70-2：带状态的二进制安全读取。生产路径（nativeReadBytes）唯一入口。
     * session 不存在时 status = PTY_READ_SESSION_NOT_FOUND。
     */
    ReadOutcome readEx(int sessionId, int maxBytes);

    bool hasData(int sessionId);
    bool waitForData(int sessionId, int timeoutMs);
    bool sendSignal(int sessionId, int signal);
    void resize(int sessionId, int rows, int cols);
    bool isAlive(int sessionId);
    int getPid(int sessionId);
    int getExitCode(int sessionId);
    void closeSession(int sessionId);
    void closeAll();
    int activeCount();
    std::vector<int> listSessionIds();

private:
    PtyEngine() = default;
    ~PtyEngine();
    PtyEngine(const PtyEngine&) = delete;
    PtyEngine& operator=(const PtyEngine&) = delete;

    // 仅在持有 mutex_ 时调用/使用（返回的裸指针不得在锁外解引用）。
    PtySession* getSession(int id);

    // P70 生命周期加固：waitForData 在锁外等待，closeSession 在锁外执行阻塞的
    // kill/waitpid 序列（最长 ~150ms+）。若 map 存 unique_ptr，锁外 close 触发析构
    // 的同时 waitForData 仍持有裸指针 → use-after-free。shared_ptr 保证锁外使用者
    // 期间对象存活（close 只是把 fd 置 -1，析构延迟到最后一个引用释放）。
    std::unordered_map<int, std::shared_ptr<PtySession>> sessions_;
    std::mutex mutex_;
    int nextId_ = 1;
};

} // namespace apex
