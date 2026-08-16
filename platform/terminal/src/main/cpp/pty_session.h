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

    /**
     * 向整个进程组发送信号（Spec PR #51 §1）。
     *
     * forkpty() 在子进程中调用 setsid()，因此 shell 同时是 session leader 和
     * process-group leader：PGID == PID == shell pid。kill(-pid_, sig) 可以送达
     * shell + child + grandchild。交互 shell 使用作业控制时，前台作业有独立的
     * process group（pgid != shell pid），因此额外通过 tcgetpgrp(masterFd_) 获取
     * 前台组并一并发信号。
     */
    bool killProcessGroup(int sig);

    int id_;
    int masterFd_ = -1;
    pid_t pid_ = -1;
    std::atomic<bool> alive_{false};
    int exitCode_ = -1;
    std::mutex ioMutex_;
};

} // namespace apex
