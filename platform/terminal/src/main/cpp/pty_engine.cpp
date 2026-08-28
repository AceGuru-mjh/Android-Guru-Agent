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

PtySession* PtyEngine::getSession(int id) {
    auto it = sessions_.find(id);
    return (it != sessions_.end()) ? it->second.get() : nullptr;
}

int PtyEngine::createSession(const std::string& shell, const std::string& workDir,
                              const std::vector<std::pair<std::string, std::string>>& envVars,
                              int rows, int cols) {
    std::lock_guard<std::mutex> lock(mutex_);
    int id = nextId_++;
    auto session = std::make_shared<PtySession>(id, shell, workDir, envVars, rows, cols);
    if (session->pid() <= 0) {
        return -1; // 创建失败
    }
    sessions_[id] = std::move(session);
    LOGI("Created session %d (total active: %zu)", id, sessions_.size());
    return id;
}

bool PtyEngine::write(int sessionId, const char* data, size_t len) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->write(data, len) : false;
}

bool PtyEngine::writeLine(int sessionId, const std::string& line) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->writeLine(line) : false;
}

std::string PtyEngine::read(int sessionId, int maxBytes) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->read(maxBytes) : "";
}

// ReadOutcome 声明于 apex 命名空间作用域（pty_engine.h:28），
// 而非 PtyEngine 的嵌套类型 —— 此处不得写 PtyEngine::ReadOutcome。
ReadOutcome PtyEngine::readEx(int sessionId, int maxBytes) {
    std::lock_guard<std::mutex> lock(mutex_);
    ReadOutcome out;
    auto* s = getSession(sessionId);
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
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->hasData() : false;
}

bool PtyEngine::waitForData(int sessionId, int timeoutMs) {
    // 不持锁等待，避免阻塞其他操作。
    // P70 生命周期加固：锁内拷贝 shared_ptr（而不是裸指针）再锁外使用 ——
    // closeSession 可能在此期间从 map 摘除并触发 close/析构，shared_ptr
    // 保证等待期间对象存活（close 只把 fd 置 -1，select 随即返回假）。
    std::shared_ptr<PtySession> session;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = sessions_.find(sessionId);
        if (it == sessions_.end()) return false;
        session = it->second;
    }
    return session ? session->waitForData(timeoutMs) : false;
}

bool PtyEngine::sendSignal(int sessionId, int signal) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->sendSignal(signal) : false;
}

void PtyEngine::resize(int sessionId, int rows, int cols) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    if (s) s->resize(rows, cols);
}

bool PtyEngine::isAlive(int sessionId) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->isAlive() : false;
}

int PtyEngine::getPid(int sessionId) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->pid() : -1;
}

int PtyEngine::getExitCode(int sessionId) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
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
