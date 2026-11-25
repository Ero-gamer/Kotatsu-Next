package org.koitharu.kotatsu.core.network

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.network.cookies.AndroidCookieJar
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.cookies.PreferencesCookieJar
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.network.imageproxy.RealImageProxyInterceptor
import org.koitharu.kotatsu.core.network.proxy.ProxyProvider
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.assertNotInMainThread
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.LocalStorageManager
import java.util.concurrent.TimeUnit
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {
    @Binds
    fun bindCookieJar(androidCookieJar: MutableCookieJar): CookieJar

    @Binds
    fun bindImageProxyInterceptor(impl: RealImageProxyInterceptor): ImageProxyInterceptor

    companion object {
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val IDLE_KEEP_ALIVE_SECONDS = 30L
        private const val PING_INTERVAL_SECONDS = 30L

        @Provides
        @Singleton
        fun provideCookieJar(
            @ApplicationContext context: Context,
        ): MutableCookieJar = runCatching {
            AndroidCookieJar()
        }.getOrElse { e ->
            e.printStackTraceDebug()
            // WebView is not available
            PreferencesCookieJar(context)
        }

        @Provides
        @Singleton
        fun provideHttpCache(localStorageManager: LocalStorageManager): Cache = localStorageManager.createHttpCache()

        @Provides
        @Singleton
        @BaseHttpClient
        fun provideBaseHttpClient(
            @ApplicationContext contextProvider: Provider<Context>,
            cache: Cache,
            cookieJar: CookieJar,
            settings: AppSettings,
            proxyProvider: ProxyProvider,
        ): OkHttpClient = OkHttpClient
            .Builder()
            .apply {
                assertNotInMainThread()
                connectTimeout(20, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(20, TimeUnit.SECONDS)
                // Sockets that sat idle through a sleep / network switch are dead more often than not: drop idle
                // ones after 30 s (default 5 min) and ping HTTP/2 connections so a dead one is noticed in ~30 s
                // instead of after the full read timeout (the retry interceptor then reconnects).
                connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, IDLE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
                pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                cookieJar(cookieJar)
                proxySelector(proxyProvider.selector)
                proxyAuthenticator(proxyProvider.authenticator)
                dns(DoHManager(cache, settings))
                if (settings.isSSLBypassEnabled) {
                    disableCertificateVerification()
                } else {
                    installExtraCertificates(contextProvider.get())
                }
                cache(cache)
                // addInterceptor(GZipInterceptor())
                addInterceptor(UnexpectedErrorInterceptor())
                addInterceptor(proxyProvider.configInterceptor)
                addInterceptor(CloudFlareInterceptor())
                addInterceptor(RateLimitInterceptor())
                if (BuildConfig.DEBUG) {
                    addInterceptor(CurlLoggingInterceptor())
                }
                // Innermost: retries only transient GET/HEAD transport failures (see RetryInterceptor).
                addInterceptor(RetryInterceptor())
            }.build()

        @Provides
        @Singleton
        @MangaHttpClient
        fun provideMangaHttpClient(
            @BaseHttpClient baseClient: OkHttpClient,
            commonHeadersInterceptor: CommonHeadersInterceptor,
        ): OkHttpClient = baseClient
            .newBuilder()
            .apply {
                addNetworkInterceptor(CacheLimitInterceptor())
                addInterceptor(commonHeadersInterceptor)
            }.build()
    }
}
