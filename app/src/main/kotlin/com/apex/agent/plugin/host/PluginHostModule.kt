package com.apex.agent.plugin.host

import com.apex.agent.plugin.api.IApexPluginHost
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 插件宿主桥的 Hilt 绑定：[IApexPluginHost]（plugin-api 生成的 AIDL 接口）→
 * [PluginHostBridge]（Binder 实现）。
 *
 * plugin-host 模块的 [PluginManager] 以接口类型注入宿主桥；AIDL 接口无
 * @Inject 构造器，必须经本 @Binds 模块补上实现绑定，否则 app 的 Hilt 图
 * （SkillModule / MarketViewModel / SlashMenuProvider 均注入 PluginManager）
 * 缺失依赖无法编译。作用域与实现类一致（@Singleton，进程内单桥）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginHostModule {

    @Binds
    @Singleton
    abstract fun bindPluginHostBridge(impl: PluginHostBridge): IApexPluginHost
}
