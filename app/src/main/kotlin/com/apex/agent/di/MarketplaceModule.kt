package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import com.apex.agent.github.GithubTokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarketplaceModule {

    /**
     * 魔搭（ModelScope）技能源：对接官方 modelscope/modelscope-skills 仓库。
     * GitHub token 可选注入（已登录用户走认证限流配额，未登录走匿名配额）。
     */
    @Provides
    @Singleton
    fun provideModelScopeSource(
        httpClient: OkHttpClient,
        githubTokenManager: GithubTokenManager
    ): ModelScopeSource {
        return ModelScopeSource(httpClient, gitHubToken = githubTokenManager.getToken())
    }
}
