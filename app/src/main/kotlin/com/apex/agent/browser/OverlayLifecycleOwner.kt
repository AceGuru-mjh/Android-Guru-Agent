package com.apex.agent.browser

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 给浮窗中的 ComposeView 一个独立的 [LifecycleOwner]。
 *
 * 浮窗由 [android.view.WindowManager] 挂在应用窗口之外（TYPE_APPLICATION_OVERLAY），
 * 不在任何 Activity/Fragment 里，因此 Compose 组件无法复用宿主生命周期，需要自建一个。
 * 对标 Operit 的 ComposeLifecycleOwner：窗口 addView 时 ON_START/ON_RESUME，
 * removeView 时 ON_PAUSE/ON_STOP/ON_DESTROY，保证 DisposableEffect/remember 等正确回收。
 */
class OverlayLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    fun onCreate() = registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onStart() = registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause() = registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop() = registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onDestroy() = registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
}
