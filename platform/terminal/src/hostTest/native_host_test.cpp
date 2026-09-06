// T81 native host-side verification harness（本地 Linux 真实 forkpty 验证，
// 不进 APK —— 仅用于 CI 之外的语法/行为回归。Android 上走
// NativePtyJniInstrumentationTest 真机 instrumentation）。
#include "pty_engine.h"
#include <android/log.h>
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include <chrono>
#include <atomic>
#include <csignal>

using namespace apex;

static int g_failures = 0;
#define CHECK(cond, msg) do { \
    if (cond) { printf("  [PASS] %s\n", msg); } \
    else { printf("  [FAIL] %s\n", msg); ++g_failures; } \
} while (0)

int main() {
    printf("== T81 native host verification ==\n");

    // ─── T81 N-2: close() 路径 exit code 保留 ───
    {
        printf("[N-2] exit code preserved via close()\n");
        PtyEngine& eng = PtyEngine::instance();
        // spawn `sh -c 'exit 42'` via argv path
        int id = eng.createSessionArgv({"/bin/sh", "-c", "exit 42"}, "", {}, 24, 80);
        CHECK(id > 0, "session created");
        // 等子进程退出（轮询 isAlive -> reapChild）
        bool exited = false;
        for (int i = 0; i < 200; ++i) {
            if (!eng.isAlive(id)) { exited = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(exited, "child exited within 1s");
        CHECK(eng.getExitCode(id) == 42, "exit code 42 preserved after natural exit");
        eng.closeSession(id);

        // T81 N-2 可观察修复点：signal 终止（session 仍在 map）→ 等死亡 → exitCode 可查。
        // closeSession 会把 session 从 map 摘除（NOT_FOUND → -1，语义如此），
        // 所以「经 close() 杀死」的 exitCode 只在 PtySession 内部一致；
        // 外部可观察路径 = 先 signal、后查询、再 close。
        int id2 = eng.createSessionArgv({"/bin/sh", "-c", "sleep 30"}, "", {}, 24, 80);
        CHECK(id2 > 0, "long-running session created");
        CHECK(eng.isAlive(id2), "long-running child alive");
        CHECK(eng.sendSignal(id2, SIGKILL), "SIGKILL sent to process group");
        bool died = false;
        for (int i = 0; i < 100; ++i) {
            if (!eng.isAlive(id2)) { died = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(died, "child died after SIGKILL");
        int ec = eng.getExitCode(id2);
        CHECK(ec == 137, "signal-death exit code == 137 (128+SIGKILL)");
        printf("    (signal-death exit code = %d)\n", ec);
        eng.closeSession(id2);
    }

    // ─── T81 N-1: 多 session 并发 IO 不被全局锁串行化 ───
    {
        printf("[N-1] concurrent multi-session IO\n");
        PtyEngine& eng = PtyEngine::instance();
        std::vector<int> ids;
        for (int i = 0; i < 20; ++i) {
            int id = eng.createSessionArgv({"/bin/sh", "-i"}, "", {}, 24, 80);
            if (id > 0) ids.push_back(id);
        }
        CHECK(ids.size() == 20, "20 sessions created");
        // 20 线程并发 readEx（EAGAIN → NO_DATA 立即返回）—— 若仍持全局锁，
        // 单次 readEx 无数据时也应快速返回；真正要验证的是 write 并发不互相阻塞。
        std::atomic<int> done{0};
        std::vector<std::thread> writers;
        auto t0 = std::chrono::steady_clock::now();
        for (int id : ids) {
            writers.emplace_back([&eng, id, &done]() {
                // 写一条命令（session 的 master 非 EAGAIN 时快速完成）
                eng.write(id, "echo t81concurrent\n", strlen("echo t81concurrent\n"));
                // 再做一次有界 readEx
                eng.readEx(id, 4096);
                done.fetch_add(1);
            });
        }
        for (auto& w : writers) w.join();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        CHECK(done.load() == 20, "all 20 concurrent writers finished");
        printf("    (20 sessions × write+readEx took %lld ms)\n", (long long)ms);
        for (int id : ids) eng.closeSession(id);
        CHECK(eng.activeCount() == 0, "all closed, no native leak");
    }

    // ─── T81 N-4: close() 不永久阻塞（有界回收） ───
    {
        printf("[N-4] close() bounded, idempotent, double-close safe\n");
        PtyEngine& eng = PtyEngine::instance();
        int id = eng.createSessionArgv({"/bin/sh", "-c", "sleep 300"}, "", {}, 24, 80);
        auto t0 = std::chrono::steady_clock::now();
        eng.closeSession(id);
        eng.closeSession(id);  // double close must be a no-op
        eng.closeSession(id);  // triple close safe
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t0).count();
        CHECK(ms < 2000, "close bounded (<2s, was potentially unbounded)");
        printf("    (close+double-close took %lld ms)\n", (long long)ms);
    }

    // ─── EOF / EIO 语义保持（P70 回归）───
    {
        printf("[P70 regression] EOF semantics after child exit\n");
        PtyEngine& eng = PtyEngine::instance();
        int id = eng.createSessionArgv({"/bin/sh", "-c", "printf done"}, "", {}, 24, 80);
        // drain until EOF
        ReadOutcome out;
        int eofSeen = 0;
        for (int i = 0; i < 300; ++i) {
            out = eng.readEx(id, 65536);
            if (out.status == PTY_READ_EOF) { eofSeen = 1; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
        CHECK(eofSeen == 1, "EOF reported after output drained");
        // T81 N-2 补强：readEx 的 EOF/EIO 分支顺手 reap —— 无需 isAlive 轮询即可查
        CHECK(eng.getExitCode(id) == 0, "exit 0 queryable immediately after EOF");
        CHECK(!eng.isAlive(id), "alive==false after EOF (reaped)");
        eng.closeSession(id);
    }

    // ─── signal 送达整个进程组（回归）───
    {
        printf("[regression] signal to process group\n");
        PtyEngine& eng = PtyEngine::instance();
        // sh -c 'sleep 300 & wait' → 前台是 wait（同 pgid）
        int id = eng.createSessionArgv({"/bin/sh", "-c", "sleep 300; echo never"}, "", {}, 24, 80);
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        CHECK(eng.sendSignal(id, SIGTERM), "SIGTERM delivered");
        bool exited = false;
        for (int i = 0; i < 100; ++i) {
            if (!eng.isAlive(id)) { exited = true; break; }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        CHECK(exited, "process group terminated by SIGTERM");
        eng.closeSession(id);
    }

    printf("== %s (%d failures) ==\n", g_failures == 0 ? "ALL PASS" : "FAILURES", g_failures);
    return g_failures == 0 ? 0 : 1;
}
