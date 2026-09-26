package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.code.longtask.LongTaskStore
import com.apex.agent.core.code.longtask.LongTaskTracker
import com.apex.agent.core.code.longtask.TaskCopyEngine
import com.apex.agent.core.code.thinking.CodeThinkingEvolutionTracker
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * # Code Long Task Module — 长任务中心 DI（coding 专属，v1.2 起归属 code-engine）
 *
 * 三件单例的装配关系（+ 档位效能追踪）：
 *
 * - [LongTaskStore]：`filesDir/longtask/` 的 JSON 存储（原子写 + 内存缓存），
 *   构造即 mkdirs，惰性扫描目录——进程冷启动零额外 IO；
 * - [LongTaskTracker]：事件流聚合器。**注入的 persistScope 是独立的
 *   SupervisorJob + IO**（对齐 CodeViewModel.persistScope 惯例）——
 *   fire-and-forget 落库绝不阻塞 VM 主线程，单条落库失败不传染后续
 *   （SupervisorJob 语义）；
 * - [TaskCopyEngine]：复制引擎（无状态，持 store 引用做读改写）；
 * - [CodeThinkingEvolutionTracker]：档位效能统计（长任务记录 → 工作区 ×
 *   档位聚合），与长任务共用 [LongTaskPersistScope] 落盘。
 *
 * 附带启动维护：provide 的副作用里后台跑一次 [LongTaskStore.prune]
 * （保留最新 50 条，模板豁免）——存储有界，用户无感（失败仅 warn，
 * 不阻断注入，对齐 McpModule Bootstrap 先例）。
 *
 * Tracker 的生命周期语义：@Singleton 挂整个进程，但「当前 run」状态只在
 * CodeViewModel 的 sendMessage..finally 窗口内被驱动——即使 VM 销毁重建，
 * 未收尾的 run 会在下一次 beginRun 时自动按 ABORTED 收尾（防御语义见
 * LongTaskTracker KDoc），不会泄漏或错记。
 */
@Module
@InstallIn(SingletonComponent::class)
object CodeLongTaskModule {

    /** 长任务落库专用 scope（Supervisor：单条失败不取消兄弟落库任务）。 */
    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class LongTaskPersistScope

    @Provides
    @Singleton
    fun provideLongTaskStore(@ApplicationContext context: Context): LongTaskStore {
        val store = LongTaskStore(java.io.File(context.filesDir, "longtask"))
        // 启动维护：后台裁剪历史记录（保留 50 条 + 模板豁免）。
        // 只预置不阻塞：@Provides 副作用模式（McpModule Bootstrap 先例），
        // 失败仅 warn——prune 是优化不是正确性前提。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { store.prune(keep = LONG_TASK_KEEP) }
                .onSuccess { pruned ->
                    if (pruned > 0) {
                        AppLogger.instance.info(
                            LogCategory.SYSTEM, "CodeLongTaskModule",
                            "长任务存储启动裁剪：清理 $pruned 条旧记录"
                        )
                    }
                }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "CodeLongTaskModule",
                        "长任务启动裁剪失败（不影响功能）：${it.message}"
                    )
                }
        }
        return store
    }

    @Provides
    @Singleton
    @LongTaskPersistScope
    fun provideLongTaskPersistScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideLongTaskTracker(
        store: LongTaskStore,
        @LongTaskPersistScope persistScope: CoroutineScope
    ): LongTaskTracker = LongTaskTracker(store, persistScope)

    @Provides
    @Singleton
    fun provideTaskCopyEngine(store: LongTaskStore): TaskCopyEngine =
        TaskCopyEngine(store)

    @Provides
    @Singleton
    fun provideCodeThinkingEvolutionTracker(
        @ApplicationContext context: Context,
        @LongTaskPersistScope persistScope: CoroutineScope
    ): CodeThinkingEvolutionTracker = CodeThinkingEvolutionTracker(
        baseDir = java.io.File(context.filesDir, "longtask/thinking-stats"),
        persistScope = persistScope
    )

    private const val LONG_TASK_KEEP = 50
}
