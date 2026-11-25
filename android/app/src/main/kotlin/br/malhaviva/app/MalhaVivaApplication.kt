package br.malhaviva.app

import android.app.Application
import br.malhaviva.feature.tracking.TrackingEnvironment
import br.malhaviva.feature.tracking.net.HttpBatchUploader
import br.malhaviva.feature.tracking.net.NetworkRouteProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MalhaVivaApplication : Application() {
    @Inject lateinit var routes: NetworkRouteProvider

    @Inject lateinit var uploader: HttpBatchUploader

    @Inject lateinit var consent: LocationConsentStore

    override fun onCreate() {
        super.onCreate()
        // O servico de rastreamento vive fora da UI e le o ambiente por aqui (veja TrackingEnvironment).
        TrackingEnvironment.routeProvider = routes
        TrackingEnvironment.uploader = uploader
        TrackingEnvironment.consent = consent
        routes.prefetch()
    }
}
