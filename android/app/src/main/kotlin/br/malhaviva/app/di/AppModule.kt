package br.malhaviva.app.di

import android.content.Context
import br.malhaviva.app.BuildConfig
import br.malhaviva.app.LocationConsentStore
import br.malhaviva.app.SharingBlockStore
import br.malhaviva.app.captchaTokenProvider
import br.malhaviva.app.integrityTokenProvider
import br.malhaviva.core.data.net.ApiBaseUrlStore
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

/** Grafo de rede e sessao do app. A base da API vem de [ApiBaseUrlStore], lida a cada requisicao. */
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
    @Singleton
    fun apiBaseUrl(
        @ApplicationContext context: Context,
    ): ApiBaseUrlStore = ApiBaseUrlStore(context, BuildConfig.API_BASE_URL, BuildConfig.DEBUG)

    @Provides
    fun captcha(): CaptchaTokenProvider = captchaTokenProvider()

    @Provides
    fun integrity(): IntegrityTokenProvider = integrityTokenProvider()

    @Provides
    @Singleton
    fun authApi(
        baseUrl: ApiBaseUrlStore,
        transport: HttpTransport,
    ): AuthApi = AuthApi(baseUrl::current, transport)

    @Provides
    @Singleton
    fun accountApi(
        baseUrl: ApiBaseUrlStore,
        transport: HttpTransport,
    ): AccountApi = AccountApi(baseUrl::current, transport)

    @Provides
    @Singleton
    fun privacyApi(
        baseUrl: ApiBaseUrlStore,
        transport: HttpTransport,
    ): PrivacyApi = PrivacyApi(baseUrl::current, transport)

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

    /** Faixa etaria da conta (`GET /me`) decide o bloqueio de compartilhamento; falha fechada. */
    @Provides
    @Singleton
    fun sharingBlock(
        session: AuthSession,
        privacy: PrivacyApi,
        scope: CoroutineScope,
    ): SharingBlockStore =
        SharingBlockStore(
            session.state,
            { (session.authed { privacy.getAccount(it) } as? ApiResult.Ok)?.value?.ageBracket },
            scope,
        )

    /** Telemetria anonima: usa o proprio token efemero, nunca a sessao da conta. */
    @Provides
    @Singleton
    fun uploader(
        baseUrl: ApiBaseUrlStore,
        transport: HttpTransport,
        integrity: IntegrityTokenProvider,
    ): HttpBatchUploader = HttpBatchUploader(baseUrl::current, transport, integrity)

    @Provides
    @Singleton
    fun routes(
        baseUrl: ApiBaseUrlStore,
        transport: HttpTransport,
        scope: CoroutineScope,
    ): NetworkRouteProvider = NetworkRouteProvider(baseUrl::current, transport, scope)
}

/** Trens ao vivo (publico, sem sessao): SSE com reconexao e polling de reserva. */
@Module
@InstallIn(SingletonComponent::class)
object TrainsModule {
    @Provides
    @Singleton
    fun liveTrains(
        baseUrl: ApiBaseUrlStore,
        transport: HttpTransport,
    ): LiveTrainsClient = LiveTrainsClient(baseUrl::current, transport, UrlConnectionSseSource())
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
