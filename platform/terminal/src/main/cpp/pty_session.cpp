#include "pty_session.h"

#include <pty.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <termios.h>
#include <fcntl.h>
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define TAG "ApexPty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace apex {

// P70 生命周期加固：master 写满（EAGAIN）时的退避参数。
// 200 次 × 1ms = 最多 200ms 有界重试，替代原先的无限忙等。
static constexpr int MAX_EAGAIN_RETRIES = 200;
static constexpr useconds_t WRITE_BACKOFF_US = 1000;

// ─── P71 (N1): 通用 argv 构造（主实现） ───
PtySession::PtySession(int id, const std::vector<std::string>& argv,
                       const std::string& workDir,
                       const std::vector<std::pair<std::string, std::string>>& envVars,
                       int rows, int cols)
    : id_(id) {

    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    // forkpty: 创建PTY对并fork子进程
    // 父进程获得master fd，子进程连接到slave
    int masterFd = -1;
    pid_ = forkpty(&masterFd, nullptr, nullptr, &ws);
    masterFd_.store(masterFd, std::memory_order_release);

    if (pid_ < 0) {
        LOGE("Session %d: forkpty failed: %s", id_, strerror(errno));
        masterFd_.store(-1, std::memory_order_release);
        return;
    }

    if (pid_ == 0) {
        // ═══ 子进程 ═══

        // 重置信号处理
        signal(SIGINT, SIG_DFL);
        signal(SIGTERM, SIG_DFL);
        signal(SIGQUIT, SIG_DFL);

        // 切换工作目录
        if (!workDir.empty()) {
            if (chdir(workDir.c_str()) != 0) {
                // 目录不存在时回退到 /data/local/tmp
                chdir("/data/local/tmp");
            }
        }

        // 设置环境变量：先安全默认值（与旧 shell 路径逐字节一致），
        // 再以调用方 envVars 覆盖 —— LocalShellBackend 显式传入同一组值，
        // 本地路径行为保持完全不变（golden 契约）。
        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", "/data/local/tmp", 1);
        setenv("USER", "shell", 1);
        setenv("SHELL", argv.empty() ? "/system/bin/sh" : argv[0].c_str(), 1);
        setenv("LANG", "en_US.UTF-8", 1);
        setenv("LC_ALL", "en_US.UTF-8", 1);
        setenv("PATH",
               "/system/bin:/system/xbin:/vendor/bin:"
               "/data/local/tmp/bin:/product/bin", 1);

        // 自定义环境变量（覆盖默认值）
        for (const auto& [key, val] : envVars) {
            setenv(key.c_str(), val.c_str(), 1);
        }

        // 执行 argv[0]（P71: execv 泛化 —— 本地 shell 与 proot 共用同一条路）
        if (argv.empty()) {
            _exit(127);
        }
        std::vector<char*> cargv;
        cargv.reserve(argv.size() + 1);
        for (const auto& a : argv) cargv.push_back(const_cast<char*>(a.c_str()));
        cargv.push_back(nullptr);
        execv(cargv[0], cargv.data());

        // execv 仅在失败时返回（ENOENT/EACCES/ENOEXEC...）—— 127 语义与 shell 一致。
        LOGE("Session %d: execv(%s) failed: %s", id_, cargv[0], strerror(errno));
        _exit(127);
    }

    // ═══ 父进程 ═══
    alive_ = true;

    // master fd 设为非阻塞
    int flags = fcntl(masterFd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(masterFd, F_SETFL, flags | O_NONBLOCK);
    }

    // 设置PTY属性：关闭回显（避免输出重复）
    struct termios tio{};
    if (tcgetattr(masterFd, &tio) == 0) {
        tio.c_lflag &= ~(ECHO | ECHONL);  // 关闭回显
        tio.c_iflag &= ~(ICRNL);          // 不转换CR为NL
        tcsetattr(masterFd, TCSANOW, &tio);
    }

    LOGI("Session %d created: pid=%d fd=%d argv0=%s cwd=%s",
         id_, pid_.load(), masterFd, argv.empty() ? "(empty)" : argv[0].c_str(),
         workDir.c_str());
}

// ─── Legacy shell 构造：委托给 argv 构造 ───
// 旧实现的 "execl(shell,shell,'-i') 失败后再 execl(shell,shell)" 回退是死代码
//（execl 失败意味着二进制本身无法 exec，参数不改变结果），委托后行为等价。
PtySession::PtySession(int id, const std::string& shell, const std::string& workDir,
                       const std::vector<std::pair<std::string, std::string>>& envVars,
                       int rows, int cols)
    : PtySession(id,
                 std::vector<std::string>{
                     shell.empty() ? "/system/bin/sh" : shell, "-i"},
                 workDir, envVars, rows, cols) {
}

PtySession::~PtySession() {
    close();
}

bool PtySession::isAlive() {
    if (!alive_) return false;
    reapChild();
    return alive_;
}

void PtySession::reapChild() {
    if (pid_ <= 0) return;
    int status;
    pid_t ret = waitpid(pid_, &status, WNOHANG);
    if (ret == pid_) {
        alive_ = false;
        applyExitStatus(status);
        LOGI("Session %d: child %d exited with code %d", id_, pid_.load(), exitCode_.load());
    }
}

// T81 (N-2)：waitpid 状态解析单一出口 —— reapChild 与 close() 共用，
// 修复「经 close() 终止的 session exitCode 恒 -1」（原先 close 内 waitpid
// 后直接丢弃 status）。
void PtySession::applyExitStatus(int status) {
    if (WIFEXITED(status)) {
        exitCode_.store(WEXITSTATUS(status), std::memory_order_relaxed);
    } else if (WIFSIGNALED(status)) {
        exitCode_.store(128 + WTERMSIG(status), std::memory_order_relaxed);
    }
}

// T81 (N-4/N-5)：有界轮询等待退出（2ms 步进）。进程未及 timeout 退出返回 false。
bool PtySession::waitExitBounded(int timeoutMs) {
    const int steps = timeoutMs / 2 + 1;
    for (int i = 0; i < steps; ++i) {
        reapChild();
        if (!alive_.load(std::memory_order_acquire)) return true;
        usleep(2000);
    }
    reapChild();
    return !alive_.load(std::memory_order_acquire);
}

bool PtySession::write(const char* data, size_t len) {
    std::lock_guard<std::mutex> lock(ioMutex_);
    const int fd = masterFd_.load(std::memory_order_acquire);
    if (fd < 0 || !alive_) return false;

    size_t totalWritten = 0;
    int eagainRetries = 0;
    while (totalWritten < len) {
        ssize_t n = ::write(fd, data + totalWritten, len - totalWritten);
        if (n < 0) {
            if (errno == EINTR) {
                // 被信号打断 —— 直接重试（P70-1：EINTR 不等同于错误）。
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // master 缓冲区满。有限退避重试，防止无限忙等（P70 生命周期加固）。
                if (++eagainRetries > MAX_EAGAIN_RETRIES) {
                    LOGW("Session %d: write stalled after %d EAGAIN retries, giving up",
                         id_, eagainRetries);
                    return false;
                }
                usleep(WRITE_BACKOFF_US);
                continue;
            }
            // 真实错误（EPIPE/EIO/EBADF...）向上传播。
            return false;
        }
        totalWritten += static_cast<size_t>(n);
    }
    return true;
}

bool PtySession::writeLine(const std::string& line) {
    std::string data = line + "\n";
    return write(data.c_str(), data.size());
}

std::string PtySession::read(int maxBytes) {
    // Legacy：仅返回数据字节，不区分 idle/EOF/error。内部统一走 readEx。
    // 生产路径（PtyOutputPump → JniNativePty）一律使用 nativeReadBytes/readEx。
    return readEx(maxBytes).data;
}

ReadResult PtySession::readEx(int maxBytes) {
    std::lock_guard<std::mutex> lock(ioMutex_);
    ReadResult result;
    result.status = ReadStatus::NO_DATA;

    const int fd = masterFd_.load(std::memory_order_acquire);
    if (fd < 0) {
        // fd 已关闭 —— 输出流已结束（既非 idle 也非进程状态判断）。
        result.status = ReadStatus::EOF_;
        return result;
    }
    if (maxBytes <= 0) {
        return result;
    }

    result.data.reserve(static_cast<size_t>(std::min(maxBytes, 64 * 1024)));

    char buf[4096];
    while (static_cast<int>(result.data.size()) < maxBytes) {
        int toRead = std::min(static_cast<int>(sizeof(buf)),
                              maxBytes - static_cast<int>(result.data.size()));
        ssize_t n = ::read(fd, buf, toRead);
        if (n > 0) {
            // 二进制安全：append(buf, n) 保留 NUL 与任意字节（P70-2）。
            result.data.append(buf, static_cast<size_t>(n));
            result.status = ReadStatus::DATA;
        } else if (n == 0) {
            // read()==0：所有 slave 已关闭且无缓冲数据 —— EOF。
            // P70-1：EOF 只代表 PTY 输出流结束，绝不修改 alive_（进程存活由
            // waitpid/reapChild 判定，两个概念独立）。
            // T81 (N-2 补强)：EOF 意味着输出方全部关闭 —— 此时子进程大概率已死，
            // 顺手 reap 一次（WNOHANG，非阻塞），让上层「EOF 后立即查 exitCode」
            // 可得（原实现必须等下一次 isAlive 轮询）。
            reapChild();
            if (result.data.empty()) {
                result.status = ReadStatus::EOF_;
            }
            // 本次已读到数据则保持 DATA，EOF 留给下一次调用报告（drain 语义，
            // 保证不丢数据、不提前终止）。
            break;
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // idle：暂时无数据。绝不视为 EOF/错误（P70-1 修复点）。
                if (result.data.empty()) {
                    result.status = ReadStatus::NO_DATA;
                }
                break;
            }
            if (errno == EINTR) {
                // 被信号打断 —— 重试，不改变状态语义。
                continue;
            }
            if (errno == EIO) {
                // Linux PTY 语义：slave 全部关闭后 read(master) 返回 EIO，
                // 等价于输出流 EOF（shell 及其子进程都已退出/关闭）。
                // T81 (N-2 补强)：同 EOF 分支 —— 顺手 reap，exitCode 立即可得。
                reapChild();
                if (result.data.empty()) {
                    result.status = ReadStatus::EOF_;
                }
                break;
            }
            // 真实错误（EBADF/EFAULT/...）：向上传播（P70-1）。
            result.status = ReadStatus::ERROR_;
            result.err = errno;
            break;
        }
    }
    return result;
}

bool PtySession::hasData() {
    const int fd = masterFd_.load(std::memory_order_acquire);
    if (fd < 0) return false;
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(fd, &fds);
    struct timeval tv{0, 0};
    return select(fd + 1, &fds, nullptr, nullptr, &tv) > 0;
}

bool PtySession::waitForData(int timeoutMs) {
    const int fd = masterFd_.load(std::memory_order_acquire);
    if (fd < 0) return false;
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(fd, &fds);
    struct timeval tv;
    tv.tv_sec = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;
    int ret = select(fd + 1, &fds, nullptr, nullptr, &tv);
    return ret > 0;
}

bool PtySession::sendSignal(int sig) {
    if (pid_ <= 0 || !alive_) return false;
    // 目标必须是整个进程组（shell + child + grandchild），而不是只有 shell pid。
    return killProcessGroup(sig);
}

bool PtySession::killProcessGroup(int sig) {
    if (pid_ <= 0) return false;
    bool delivered = false;

    // 1) 前台作业进程组（作业控制）。
    //    交互 shell 会把前台作业（例如 `sh -c 'sleep 60 & wait'`）放进独立的 process
    //    group（pgid != shell pid）。如果只给 shell 的组发信号，作业会存活下来，因此
    //    还要对控制终端的前台组一并发信号。
    const int fd = masterFd_.load(std::memory_order_acquire);
    int fg = fd >= 0 ? tcgetpgrp(fd) : -1;
    if (fg > 0 && fg != pid_) {
        if (kill(-fg, sig) == 0) delivered = true;
        // ESRCH（前台组已不存在）可忽略，shell 组信号随后处理。
    }

    // 2) 会话自身的进程组。
    //    forkpty() 在子进程调用 setsid()，shell 是 session leader 且是 process-group
    //    leader：PGID == PID == shell pid。kill(-pid_, sig) 可送达 shell + 同组子进程 +
    //    孙进程（非作业控制路径）。
    if (kill(-pid_, sig) == 0) {
        delivered = true;
    } else if (errno == ESRCH) {
        // 进程组已为空（shell 已退出）→ 回退到直接 kill PID。
        if (kill(pid_, sig) == 0) delivered = true;
    }

    return delivered;
}

void PtySession::resize(int rows, int cols) {
    const int fd = masterFd_.load(std::memory_order_acquire);
    if (fd < 0) return;
    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    ioctl(fd, TIOCSWINSZ, &ws);
}

void PtySession::close() {
    // 注意顺序：先向整个进程组发信号，最后才关闭 master fd ——
    // killProcessGroup() 需要 master fd 做 tcgetpgrp()。
    if (pid_ > 0 && alive_) {
        // 先尝试优雅终止整个进程组（shell + child + grandchild）。
        // T81 (N-5)：等待改为有界轮询（进程早退即返回，不再固定睡满 50/100ms）。
        killProcessGroup(SIGHUP);
        if (!waitExitBounded(50)) {
            killProcessGroup(SIGTERM);
            if (!waitExitBounded(100)) {
                killProcessGroup(SIGKILL);
                waitExitBounded(150);
            }
        }

        // T81 (N-4)：回收 shell 子进程 —— 只用 WNOHANG 有界重试（reapChild）。
        // 原实现 waitpid(pid_, &status, 0) 是无限期阻塞：SIGKILL 后子进程若处于
        // uninterruptible sleep（D-state，罕见但存在，如 FUSE/nfs 持有者），
        // JNI 调用线程会被永久挂死。200ms 后放弃回收（子进程由 init 收养）。
        // T81 (N-2)：回收到的 exit status 经 applyExitStatus 写入 exitCode_，
        // 经 close() 终止的会话不再丢失退出码。
        for (int i = 0; i < 100 && alive_.load(std::memory_order_acquire); ++i) {
            usleep(2000);
            reapChild();
        }
        pid_ = -1;
    }

    // P70 并发加固：fd 关闭与 readEx/write（ioMutex_）串行化，
    // 避免close 与 read 并发时的 fd 复用竞争。
    {
        std::lock_guard<std::mutex> lock(ioMutex_);
        const int fd = masterFd_.load(std::memory_order_acquire);
        if (fd >= 0) {
            ::close(fd);
            masterFd_.store(-1, std::memory_order_release);
        }
    }

    alive_ = false;
    LOGI("Session %d closed", id_);
}

} // namespace apex
