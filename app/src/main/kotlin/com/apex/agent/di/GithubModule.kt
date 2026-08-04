package com.apex.agent.di

import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object GithubModule {

    @Provides
    @Singleton
    fun provideGithubApiService(tokenManager: GithubTokenManager): GithubApiService {
        return GithubApiService(tokenManager)
    }
}
