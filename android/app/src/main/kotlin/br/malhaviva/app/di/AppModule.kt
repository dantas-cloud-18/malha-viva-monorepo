package br.malhaviva.app.di

import android.content.Context
import br.malhaviva.app.BuildConfig
import br.malhaviva.app.LocationConsentStore
import br.malhaviva.app.captchaTokenProvider
import br.malhaviva.app.integrityTokenProvider
import br.malhaviva.core.data.net.HttpTransport
import br.malhaviva.core.data.net.UrlConnectionTransport
import br.malhaviva.core.data.secure.KeystoreSecretStore
import br.malhaviva.core.data.secure.SecretStore
import br.malhaviva.feature.auth.AccountRecovery
import br.malhaviva.feature.auth.AuthSession
import br.malhaviva.feature.auth.CaptchaTokenProvider
import br.malhaviva.feature.auth.ParentalConsent
import br.malhaviva.feature.auth.isLocationGranted
import br.malhaviva.feature.auth.net.AccountApi
import br.malhaviva.feature.auth.net.ApiResult
import br.malhaviva.feature.auth.net.AuthApi
import br.malhaviva.feature.auth.net.PrivacyApi
import br.malhaviva.feature.tracking.net.HttpBatchUploader
import br.malhaviva.feature.tracking.net.IntegrityTokenProvider
import br.malhaviva.feature.tracking.net.NetworkRouteProvider
import br.malhaviva.feature.trains.net.LiveTrainsClient
import br.malhaviva.feature.trains.net.UrlConnectionSseSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Grafo de rede e sessao do app. A base da API vem so de [BuildConfig.API_BASE_URL]. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun transport(): HttpTransport = UrlConnectionTransport()

    @Provides
    @Singleton
    fun secrets(
        @ApplicationContext context: Context,
    ): SecretStore = KeystoreSecretStore(context)

    @Provides
    fun captcha(): CaptchaTokenProvider = captchaTokenProvider()

    @Provides
    fun integrity(): IntegrityTokenProvider = integrityTokenProvider()

    @Provides
    @Singleton
    fun authApi(transport: HttpTransport): AuthApi = AuthApi(BuildConfig.API_BASE_URL, transport)

    @Provides
    @Singleton
    fun accountApi(transport: HttpTransport): AccountApi = AccountApi(BuildConfig.API_BASE_URL, transport)

    @Provides
    @Singleton
    fun privacyApi(transport: HttpTransport): PrivacyApi = PrivacyApi(BuildConfig.API_BASE_URL, transport)

    @Provides
    @Singleton
    fun session(
        api: AuthApi,
        secrets: SecretStore,
        captcha: CaptchaTokenProvider,
    ): AuthSession = AuthSession(api, secrets, captcha)
}

/** Consentimento de localizacao e telemetria anonima (nada aqui usa a sessao da conta para enviar dados). */
@Module
@InstallIn(SingletonComponent::class)
object TrackingModule {
    @Provides
    @Singleton
    fun consent(
        session: AuthSession,
        privacy: PrivacyApi,
        scope: CoroutineScope,
    ): LocationConsentStore =
        LocationConsentStore(
            session.state,
            { (session.authed { privacy.getConsents(it) } as? ApiResult.Ok)?.value?.isLocationGranted() },
            scope,
        )

    /** Telemetria anonima: usa o proprio token efemero, nunca a sessao da conta. */
    @Provides
    @Singleton
    fun uploader(
        transport: HttpTransport,
        integrity: IntegrityTokenProvider,
    ): HttpBatchUploader = HttpBatchUploader(BuildConfig.API_BASE_URL, transport, integrity)

    @Provides
    @Singleton
    fun routes(
        transport: HttpTransport,
        scope: CoroutineScope,
    ): NetworkRouteProvider = NetworkRouteProvider(BuildConfig.API_BASE_URL, transport, scope)
}

/** Trens ao vivo (publico, sem sessao): SSE com reconexao e polling de reserva. */
@Module
@InstallIn(SingletonComponent::class)
object TrainsModule {
    @Provides
    @Singleton
    fun liveTrains(transport: HttpTransport): LiveTrainsClient =
        LiveTrainsClient(BuildConfig.API_BASE_URL, transport, UrlConnectionSseSource())
}

/** Subfluxos de conta que dependem da API e da sessao. */
@Module
@InstallIn(SingletonComponent::class)
object AccountModule {
    @Provides
    @Singleton
    fun recovery(
        api: AuthApi,
        captcha: CaptchaTokenProvider,
        session: AuthSession,
    ): AccountRecovery = AccountRecovery(api, captcha, session)

    @Provides
    @Singleton
    fun parental(
        api: AuthApi,
        captcha: CaptchaTokenProvider,
    ): ParentalConsent = ParentalConsent(api, captcha)
}
