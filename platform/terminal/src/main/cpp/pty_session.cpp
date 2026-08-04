#include "pty_session.h"

#include <pty.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <termios.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#define TAG "ApexPty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace apex {

PtySession::PtySession(int id, const std::string& shell, const std::string& workDir,
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
    pid_ = forkpty(&masterFd_, nullptr, nullptr, &ws);

    if (pid_ < 0) {
        LOGE("Session %d: forkpty failed: %s", id_, strerror(errno));
        return;
    }

    if (pid_ == 0) {
        // ═══ 子进程（shell）═══

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

        // 设置环境变量
        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", "/data/local/tmp", 1);
        setenv("USER", "shell", 1);
        setenv("SHELL", shell.c_str(), 1);
        setenv("LANG", "en_US.UTF-8", 1);
        setenv("LC_ALL", "en_US.UTF-8", 1);
        setenv("PATH",
               "/system/bin:/system/xbin:/vendor/bin:"
               "/data/local/tmp/bin:/product/bin", 1);

        // 自定义环境变量
        for (const auto& [key, val] : envVars) {
            setenv(key.c_str(), val.c_str(), 1);
        }

        // 执行shell
        const char* shellPath = shell.empty() ? "/system/bin/sh" : shell.c_str();

        // 尝试以交互模式启动
        execl(shellPath, shellPath, "-i", nullptr);

        // 如果 -i 失败，尝试无参数
        execl(shellPath, shellPath, nullptr);

        // 不应到达这里
        _exit(127);
    }

    // ═══ 父进程 ═══
    alive_ = true;

    // master fd 设为非阻塞
    int flags = fcntl(masterFd_, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(masterFd_, F_SETFL, flags | O_NONBLOCK);
    }

    // 设置PTY属性：关闭回显（避免输出重复）
    struct termios tio{};
    if (tcgetattr(masterFd_, &tio) == 0) {
        tio.c_lflag &= ~(ECHO | ECHONL);  // 关闭回显
        tio.c_iflag &= ~(ICRNL);          // 不转换CR为NL
        tcsetattr(masterFd_, TCSANOW, &tio);
    }

    LOGI("Session %d created: pid=%d fd=%d shell=%s cwd=%s",
         id_, pid_, masterFd_, shell.c_str(), workDir.c_str());
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
        if (WIFEXITED(status)) {
            exitCode_ = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exitCode_ = 128 + WTERMSIG(status);
        }
        LOGI("Session %d: child %d exited with code %d", id_, pid_, exitCode_);
    }
}

bool PtySession::write(const char* data, size_t len) {
    std::lock_guard<std::mutex> lock(ioMutex_);
    if (masterFd_ < 0 || !alive_) return false;

    size_t totalWritten = 0;
    while (totalWritten < len) {
        ssize_t n = ::write(masterFd_, data + totalWritten, len - totalWritten);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // 缓冲区满，短暂等待
                usleep(1000);
                continue;
            }
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
    std::lock_guard<std::mutex> lock(ioMutex_);
    if (masterFd_ < 0) return "";

    std::string result;
    result.reserve(maxBytes);

    char buf[4096];
    while (static_cast<int>(result.size()) < maxBytes) {
        int toRead = std::min(static_cast<int>(sizeof(buf)),
                              maxBytes - static_cast<int>(result.size()));
        ssize_t n = ::read(masterFd_, buf, toRead);
        if (n > 0) {
            result.append(buf, static_cast<size_t>(n));
        } else if (n == 0) {
            // EOF
            alive_ = false;
            break;
        } else {
            // EAGAIN = 没有更多数据
            break;
        }
    }
    return result;
}

bool PtySession::hasData() {
    if (masterFd_ < 0) return false;
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(masterFd_, &fds);
    struct timeval tv{0, 0};
    return select(masterFd_ + 1, &fds, nullptr, nullptr, &tv) > 0;
}

bool PtySession::waitForData(int timeoutMs) {
    if (masterFd_ < 0) return false;
    fd_set fds;
    FD_ZERO(&fds);
    FD_SET(masterFd_, &fds);
    struct timeval tv;
    tv.tv_sec = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;
    int ret = select(masterFd_ + 1, &fds, nullptr, nullptr, &tv);
    return ret > 0;
}

bool PtySession::sendSignal(int sig) {
    if (pid_ <= 0 || !alive_) return false;
    return kill(pid_, sig) == 0;
}

void PtySession::resize(int rows, int cols) {
    if (masterFd_ < 0) return;
    struct winsize ws{};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    ioctl(masterFd_, TIOCSWINSZ, &ws);
}

void PtySession::close() {
    if (masterFd_ >= 0) {
        ::close(masterFd_);
        masterFd_ = -1;
    }

    if (pid_ > 0 && alive_) {
        // 先尝试优雅终止
        kill(pid_, SIGHUP);
        usleep(50000); // 50ms

        if (isAlive()) {
            kill(pid_, SIGTERM);
            usleep(100000); // 100ms
        }

        if (isAlive()) {
            kill(pid_, SIGKILL);
        }

        // 回收子进程
        int status;
        waitpid(pid_, &status, 0);
        pid_ = -1;
    }

    alive_ = false;
    LOGI("Session %d closed", id_);
}

} // namespace apex
