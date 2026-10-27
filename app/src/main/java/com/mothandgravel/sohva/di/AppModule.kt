package com.mothandgravel.sohva.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.mothandgravel.sohva.BuildConfig
import com.mothandgravel.sohva.data.HttpTrace
import com.mothandgravel.sohva.protocol.BraviaApi
import com.mothandgravel.sohva.protocol.SsdpDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient {
        val client = BraviaApi.defaultClient()
        return if (BuildConfig.DEBUG) client.newBuilder().addInterceptor(HttpTrace()).build() else client
    }

    @Provides
    @Singleton
    fun ssdpDiscovery(client: OkHttpClient): SsdpDiscovery = SsdpDiscovery(client)

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
