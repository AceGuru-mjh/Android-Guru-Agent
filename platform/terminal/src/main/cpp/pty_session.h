#pragma once

#include <string>
#include <atomic>
#include <mutex>
#include <vector>

namespace apex {

/**
 * PTY 读取状态（P70-1：显式区分 idle / EOF / error，禁止互相混淆）。
 *
 * 数值与 jni_bridge.cpp 的 PTY_READ_* 常量、Kotlin 侧 PtyJniReadStatus 一一对应，勿改：
 *   0 = DATA      读到数据（本次调用至少 1 字节）
 *   1 = NO_DATA   EAGAIN/EWOULDBLOCK —— 暂时无数据（idle）。PTY 与进程均正常。
 *   2 = EOF       输出流结束：read()==0 或 EIO（Linux 上 slave 全部关闭后的返回值）。
 *                 注意：EOF 只描述 PTY 输出流，绝不据此修改 alive_（进程存活由
 *                 waitpid/reapChild 决定，二者是独立概念）。
 *   3 = ERROR     真实 read 错误（errno 有效，如 EBADF/EFAULT）。
 */
enum class ReadStatus {
    DATA = 0,
    NO_DATA = 1,
    EOF_ = 2,
    ERROR_ = 3,
};

/** PtySession::readEx 的结果：原始字节（可含 NUL，P70-2）+ 明确状态。 */
struct ReadResult {
    std::string data;
    ReadStatus status = ReadStatus::NO_DATA;
    int err = 0;  // 仅 status==ERROR_ 时有效
};

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
    int masterFd() const { return masterFd_.load(std::memory_order_acquire); }
    pid_t pid() const { return pid_; }

    bool isAlive();
    bool write(const char* data, size_t len);
    bool writeLine(const std::string& line);

    /**
     * Legacy 读取（仅返回数据字节）。无法区分 idle/EOF/error，生产路径禁止使用 ——
     * 一律走 readEx()（P70-1）。保留仅为 pty_engine::read 的 ABI 兼容。
     */
    std::string read(int maxBytes);

    /** P70-1：带状态的读取。语义见 [ReadStatus]。 */
    ReadResult readEx(int maxBytes);

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
    // P70 生命周期加固：masterFd_ 会被 close()（可能来自另一个线程）置 -1，
    // 与 readEx/write 并发读写 —— 用 atomic 消除数据竞争（fd 关闭后 read/write
    // 返回 EBADF → ERROR_，由上层按状态语义处理，而非 UB）。
    std::atomic<int> masterFd_{-1};
    pid_t pid_ = -1;
    std::atomic<bool> alive_{false};
    int exitCode_ = -1;
    std::mutex ioMutex_;
};

} // namespace apex
