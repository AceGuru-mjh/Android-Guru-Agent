package com.apex.agent.browser

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 给浮窗中的 ComposeView 一个独立的 [LifecycleOwner] + [SavedStateRegistryOwner]。
 *
 * 浮窗由 [android.view.WindowManager] 挂在应用窗口之外（TYPE_APPLICATION_OVERLAY），
 * 不在任何 Activity/Fragment 里，因此 Compose 组件无法复用宿主生命周期，需要自建一个。
 * 对标 Operit 的 ComposeLifecycleOwner：窗口 addView 时 ON_START/ON_RESUME，
 * removeView 时 ON_PAUSE/ON_STOP/ON_DESTROY，保证 DisposableEffect/remember 等正确回收。
 *
 * v2 修复：补 [SavedStateRegistryOwner]——ComposeView 在 onAttachedToWindow 时会同时
 * 查找 ViewTreeLifecycleOwner 与 ViewTreeSavedStateRegistryOwner，缺失后者时
 * `rememberSaveable`/Compose 内部保存恢复链路直接抛 IllegalStateException。
 * 旧实现只挂了生命周期（且从未真正 set 到 View 树上，见 BrowserOverlay v2 修复说明），
 * 浮窗 Compose 内容从未成功渲染过。
 */
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)

    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    /**
     * 恢复保存状态（必须在 View attach 到窗口**之前**调用，否则注册的
     * SavedStateProvider 会错过恢复窗口）。
     */
    fun performRestore() {
        savedStateController.performRestore(null)
    }

    fun onCreate() = registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onStart() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause() = registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onDestroy() = registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
}
