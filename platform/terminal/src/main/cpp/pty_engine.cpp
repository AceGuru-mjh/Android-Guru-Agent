#include "pty_engine.h"
#include "pty_session.h"
#include <android/log.h>

#define TAG "ApexPtyEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace apex {

PtyEngine& PtyEngine::instance() {
    static PtyEngine engine;
    return engine;
}

PtyEngine::~PtyEngine() {
    closeAll();
}

// T81 (N-1)：锁内拷贝 shared_ptr —— IO/信号/resize 一律锁外执行（见头文件注释）。
std::shared_ptr<PtySession> PtyEngine::acquire(int id) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = sessions_.find(id);
    return (it != sessions_.end()) ? it->second : nullptr;
}

int PtyEngine::createSession(const std::string& shell, const std::string& workDir,
                              const std::vector<std::pair<std::string, std::string>>& envVars,
                              int rows, int cols) {
    // P71 (N1)：legacy shell 入口转发到通用 argv 路径（行为等价：
    // child exec {shell, "-i"}，env 默认值由 PtySession 统一设置）。
    return createSessionArgv(
        std::vector<std::string>{shell.empty() ? "/system/bin/sh" : shell, "-i"},
        workDir, envVars, rows, cols);
}

int PtyEngine::createSessionArgv(const std::vector<std::string>& argv,
                                  const std::string& workDir,
                                  const std::vector<std::pair<std::string, std::string>>& envVars,
                                  int rows, int cols) {
    if (argv.empty()) {
        LOGI("createSessionArgv rejected: empty argv");
        return -1;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    int id = nextId_++;
    auto session = std::make_shared<PtySession>(id, argv, workDir, envVars, rows, cols);
    if (session->pid() <= 0) {
        return -1; // 创建失败
    }
    sessions_[id] = std::move(session);
    LOGI("Created session %d (total active: %zu)", id, sessions_.size());
    return id;
}

bool PtyEngine::write(int sessionId, const char* data, size_t len) {
    // T81 (N-1)：IO 移出全局锁 —— 原实现持 engine mutex_ 调用 session->write，
    // session A 的写退避（EASON 最多 200ms）会阻塞所有其他 session 的一切
    // IO/控制操作，多 session 并发被单锁串行化。PtySession 自身的 ioMutex_
    // 保证 per-session 串行语义不变。
    auto s = acquire(sessionId);
    return s ? s->write(data, len) : false;
}

bool PtyEngine::writeLine(int sessionId, const std::string& line) {
    auto s = acquire(sessionId);
    return s ? s->writeLine(line) : false;
}

std::string PtyEngine::read(int sessionId, int maxBytes) {
    auto s = acquire(sessionId);
    return s ? s->read(maxBytes) : "";
}

// ReadOutcome 声明于 apex 命名空间作用域（pty_engine.h:28），
// 而非 PtyEngine 的嵌套类型 —— 此处不得写 PtyEngine::ReadOutcome。
ReadOutcome PtyEngine::readEx(int sessionId, int maxBytes) {
    // T81 (N-1)：read 移出全局锁（同 write）。readEx 可能读满 64KB ——
    // 持锁期间其他 session 全部 IO 被阻塞（多 session 并发吞吐瓶颈根因）。
    auto s = acquire(sessionId);
    ReadOutcome out;
    if (!s) {
        out.status = PTY_READ_SESSION_NOT_FOUND;
        return out;
    }
    ReadResult r = s->readEx(maxBytes);
    out.data = std::move(r.data);
    out.status = static_cast<int>(r.status);
    out.err = r.err;
    return out;
}

bool PtyEngine::hasData(int sessionId) {
    auto s = acquire(sessionId);
    return s ? s->hasData() : false;
}

bool PtyEngine::waitForData(int sessionId, int timeoutMs) {
    // 不持锁等待，避免阻塞其他操作。
    // P70 生命周期加固 / T81 (N-1)：acquire 锁内拷贝 shared_ptr 后锁外等待 ——
    // closeSession 可能在此期间从 map 摘除并触发 close/析构，shared_ptr
    // 保证等待期间对象存活（close 只把 fd 置 -1，select 随即返回假）。
    auto session = acquire(sessionId);
    return session ? session->waitForData(timeoutMs) : false;
}

bool PtyEngine::sendSignal(int sessionId, int signal) {
    auto s = acquire(sessionId);
    return s ? s->sendSignal(signal) : false;
}

void PtyEngine::resize(int sessionId, int rows, int cols) {
    auto s = acquire(sessionId);
    if (s) s->resize(rows, cols);
}

bool PtyEngine::isAlive(int sessionId) {
    auto s = acquire(sessionId);
    return s ? s->isAlive() : false;
}

int PtyEngine::getPid(int sessionId) {
    auto s = acquire(sessionId);
    return s ? s->pid() : -1;
}

int PtyEngine::getExitCode(int sessionId) {
    auto s = acquire(sessionId);
    return s ? s->exitCode() : -1;
}

void PtyEngine::closeSession(int sessionId) {
    // P70 并发加固：close() 内部是 kill → usleep → waitpid 的阻塞序列
    // （最长 ~150ms+）。原先在 engine 锁内执行，会阻塞所有其他 session 的
    // read/write/hasData。现在先持锁从 map 摘除（此后新调用一律 NOT_FOUND），
    // 再锁外执行阻塞清理；shared_ptr 保证并发等待者（waitForData）期间对象存活。
    std::shared_ptr<PtySession> session;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = sessions_.find(sessionId);
        if (it == sessions_.end()) return;
        session = it->second;
        sessions_.erase(it);
    }
    if (session) {
        session->close();
        LOGI("Closed session %d", sessionId);
    }
}

void PtyEngine::closeAll() {
    // 同 closeSession：锁内摘除全部会话，锁外逐个执行阻塞清理。
    std::vector<std::shared_ptr<PtySession>> toClose;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& [id, session] : sessions_) {
            toClose.push_back(session);
        }
        sessions_.clear();
    }
    for (auto& s : toClose) {
        if (s) s->close();
    }
    LOGI("All sessions closed");
}

int PtyEngine::activeCount() {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int>(sessions_.size());
}

std::vector<int> PtyEngine::listSessionIds() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<int> ids;
    ids.reserve(sessions_.size());
    for (const auto& [id, _] : sessions_) {
        ids.push_back(id);
    }
    return ids;
}

} // namespace apex
