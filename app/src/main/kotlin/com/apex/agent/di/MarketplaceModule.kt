package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.ModelScopeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarketplaceModule {

    @Provides
    @Singleton
    fun provideConnectorRegistry(@ApplicationContext context: Context): ConnectorRegistry {
        return ConnectorRegistry(File(context.filesDir, "connector_config"))
    }

    @Provides
    @Singleton
    fun provideModelScopeSource(
        httpClient: OkHttpClient
    ): ModelScopeSource {
        return ModelScopeSource(httpClient)
    }
}
