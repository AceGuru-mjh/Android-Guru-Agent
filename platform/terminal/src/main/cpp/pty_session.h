#pragma once

#include <string>
#include <atomic>
#include <mutex>
#include <vector>

namespace apex {

/**
 * 单个PTY会话
 * 封装 forkpty() 创建的子进程及其master fd
 */
class PtySession {
public:
    PtySession(int id, const std::string& shell, const std::string& workDir,
               const std::vector<std::pair<std::string, std::string>>& envVars,
               int rows, int cols);
    ~PtySession();

    PtySession(const PtySession&) = delete;
    PtySession& operator=(const PtySession&) = delete;

    int id() const { return id_; }
    int masterFd() const { return masterFd_; }
    pid_t pid() const { return pid_; }

    bool isAlive();
    bool write(const char* data, size_t len);
    bool writeLine(const std::string& line);
    std::string read(int maxBytes);
    bool hasData();
    bool waitForData(int timeoutMs);
    bool sendSignal(int sig);
    void resize(int rows, int cols);
    void close();
    int exitCode() const { return exitCode_; }

private:
    void reapChild();

    int id_;
    int masterFd_ = -1;
    pid_t pid_ = -1;
    std::atomic<bool> alive_{false};
    int exitCode_ = -1;
    std::mutex ioMutex_;
};

} // namespace apex
