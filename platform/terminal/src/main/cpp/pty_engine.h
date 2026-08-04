#pragma once

#include <string>
#include <unordered_map>
#include <memory>
#include <mutex>
#include <vector>

namespace apex {

class PtySession;

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
    std::string read(int sessionId, int maxBytes);
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

    PtySession* getSession(int id);

    std::unordered_map<int, std::unique_ptr<PtySession>> sessions_;
    std::mutex mutex_;
    int nextId_ = 1;
};

} // namespace apex
