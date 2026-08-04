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
    auto session = std::make_unique<PtySession>(id, shell, workDir, envVars, rows, cols);
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

bool PtyEngine::hasData(int sessionId) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* s = getSession(sessionId);
    return s ? s->hasData() : false;
}

bool PtyEngine::waitForData(int sessionId, int timeoutMs) {
    // 不持锁等待，避免阻塞其他操作
    PtySession* session = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        session = getSession(sessionId);
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
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = sessions_.find(sessionId);
    if (it != sessions_.end()) {
        it->second->close();
        sessions_.erase(it);
        LOGI("Closed session %d (remaining: %zu)", sessionId, sessions_.size());
    }
}

void PtyEngine::closeAll() {
    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& [id, session] : sessions_) {
        session->close();
    }
    sessions_.clear();
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
