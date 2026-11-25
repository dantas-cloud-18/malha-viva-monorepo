package br.malhaviva.app

import android.app.Application
import br.malhaviva.core.data.net.UrlConnectionTransport
import br.malhaviva.feature.tracking.TrackingEnvironment
import br.malhaviva.feature.tracking.net.HttpBatchUploader
import br.malhaviva.feature.tracking.net.NetworkRouteProvider
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class MalhaVivaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val transport = UrlConnectionTransport()
        val routes =
            NetworkRouteProvider(
                BuildConfig.API_BASE_URL,
                transport,
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        TrackingEnvironment.routeProvider = routes
        TrackingEnvironment.uploader = HttpBatchUploader(BuildConfig.API_BASE_URL, transport, integrityTokenProvider())
        routes.prefetch()
    }
}
