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
    /**
     * P71 (N1)：通用 argv 构造 —— child 执行 execv(argv[0], argv)。
     * 本地会话：argv = {"/system/bin/sh", "-i"}；
     * Linux 会话：argv = {libproot.so, "-r", rootfs, ..., "--", "/bin/bash", "-i"}。
     *
     * env 语义：child 先设置与旧 shell 路径完全一致的安全默认值
     *（TERM/HOME/USER/SHELL/LANG/LC_ALL/PATH，SHELL 默认取 argv[0]），
     * 再以 envVars 覆盖 —— 调用方显式传入的值永远生效。
     */
    PtySession(int id, const std::vector<std::string>& argv, const std::string& workDir,
               const std::vector<std::pair<std::string, std::string>>& envVars,
               int rows, int cols);

    /** Legacy shell 构造（P70 前唯一入口）。等价于 argv = {shell, "-i"}。 */
    PtySession(int id, const std::string& shell, const std::string& workDir,
               const std::vector<std::pair<std::string, std::string>>& envVars,
               int rows, int cols);

    ~PtySession();

    PtySession(const PtySession&) = delete;
    PtySession& operator=(const PtySession&) = delete;

    int id() const { return id_; }
    int masterFd() const { return masterFd_.load(std::memory_order_acquire); }
    pid_t pid() const { return pid_.load(std::memory_order_relaxed); }

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
    int exitCode() const { return exitCode_.load(std::memory_order_relaxed); }

private:
    void reapChild();

    /** T81 (N-2)：waitpid status → exitCode_ 单一解析出口（reapChild/close 共用）。 */
    void applyExitStatus(int status);

    /**
     * T81 (N-4/N-5)：有界等待子进程退出 —— 轮询 reapChild（WNOHANG），
     * [timeoutMs] 内退出返回 true。替代 close() 中原先后果更差的两件套：
     *   a) 固定 usleep(50ms/100ms) —— 进程早退时白白阻塞；
     *   b) 阻塞 waitpid(..., 0) —— SIGKILL 后进程处于 D-state 时会把
     *      JNI 调用线程永久挂死。
     */
    bool waitExitBounded(int timeoutMs);

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
    // T81 (N-3 补强)：pid_ 由构造线程写、close() 写 -1、reapChild/readEx 的
    // EOF 分支（持 ioMutex_）并发读 —— atomic 消除数据竞争（operator= 即
    // store / 隐式转换即 load，调用点语法不变）。
    std::atomic<pid_t> pid_{-1};
    std::atomic<bool> alive_{false};
    // T81 (N-3)：exitCode_ 由 reapChild（任意调用 isAlive 的线程）写、
    // exitCode()（JNI 线程）读 —— relaxed atomic 消除数据竞争 UB。
    std::atomic<int> exitCode_{-1};
    std::mutex ioMutex_;
};

} // namespace apex
