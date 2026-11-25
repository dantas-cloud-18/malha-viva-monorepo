package br.malhaviva.feature.trains

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.ceil

private const val LINE_WIDTH = 8f
private const val STATION_DOT_DP = 12
private const val STATION_STROKE_DIV = 4
private const val FIT_PADDING_PX = 64
private const val LINE_COLOR = 0xFF1565C0.toInt()
private const val ON_TIME_COLOR = 0xFF2E7D32.toInt()
private const val DOUBTFUL_COLOR = 0xFFEF6C00.toInt()
private const val NO_SIGNAL_COLOR = 0xFF757575.toInt()

private data class TrainPin(
    val id: String,
    val point: GeoPoint,
    val title: String,
    val snippet: String,
    val color: Int,
)

/** Mapa OSM com a linha, as estacoes e os trens (mesma projecao da lista). */
@Composable
internal fun TrainsMap(
    line: LineNetwork,
    views: List<TrainView>,
    modifier: Modifier = Modifier,
) {
    val path = remember(line) { line.path() }
    if (path.size < 2) {
        Text(stringResource(R.string.trains_map_unavailable), modifier)
        return
    }
    val pins =
        views.mapNotNull { view ->
            val at = alongToLatLon(line, view.alongM) ?: return@mapNotNull null
            val eta = view.stationId?.let { view.etasS[it] }?.let { ceil(it / TrainsPolicy.S_PER_MIN).toInt() }
            val fresh = freshnessLabel(view.freshness)
            TrainPin(
                id = view.id,
                point = GeoPoint(at.lat, at.lon),
                title = statusLabel(view, line),
                snippet = if (eta == null) fresh else "${etaLabel(eta)} · $fresh",
                color = pinColor(view),
            )
        }
    val holder = remember(line) { MapHolder() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, holder) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> holder.map?.onResume()
                    Lifecycle.Event.ON_PAUSE -> holder.map?.onPause()
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            holder.map?.onDetach()
        }
    }
    AndroidView(
        factory = { ctx -> buildMap(ctx, line, path).also { holder.map = it } },
        update = { holder.sync(it, pins) },
        modifier = modifier,
    )
}

private fun pinColor(view: TrainView): Int {
    val doubtful = view.freshness == Freshness.Estimated || view.confidence < TrainsPolicy.LOW_CONFIDENCE
    return when {
        view.freshness == Freshness.NoSignal -> NO_SIGNAL_COLOR
        doubtful -> DOUBTFUL_COLOR
        else -> ON_TIME_COLOR
    }
}

private class MapHolder {
    var map: MapView? = null
    private val markers = HashMap<String, Marker>()

    fun sync(
        view: MapView,
        pins: List<TrainPin>,
    ) {
        val live = pins.map { it.id }.toSet()
        markers.keys.filter { it !in live }.forEach { view.overlays.remove(markers.remove(it)) }
        pins.forEach { pin ->
            val marker =
                markers.getOrPut(pin.id) {
                    Marker(view).also {
                        it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        view.overlays.add(it)
                    }
                }
            marker.position = pin.point
            marker.title = pin.title
            marker.snippet = pin.snippet
            marker.icon = trainIcon(view.context, pin.color)
        }
        view.invalidate()
    }
}

private fun trainIcon(
    context: Context,
    color: Int,
): Drawable? = context.getDrawable(R.drawable.ic_train_silhouette)?.mutate()?.also { it.setTint(color) }

private fun buildMap(
    ctx: Context,
    line: LineNetwork,
    path: List<LatLon>,
): MapView {
    configureOsm(ctx)
    val map = MapView(ctx)
    map.setTileSource(TileSourceFactory.MAPNIK)
    map.setMultiTouchControls(true)
    map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    map.overlays.add(
        Polyline(map).also {
            it.setPoints(path.map { p -> GeoPoint(p.lat, p.lon) })
            it.outlinePaint.color = LINE_COLOR
            it.outlinePaint.strokeWidth = LINE_WIDTH
        },
    )
    val dot = stationDot(ctx)
    line.stations.forEach { station ->
        val at = line.shape?.stationPos?.get(station.id) ?: return@forEach
        map.overlays.add(
            Marker(map).also {
                it.position = GeoPoint(at.lat, at.lon)
                it.title = station.name
                it.icon = dot
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            },
        )
    }
    val box = BoundingBox.fromGeoPoints(path.map { GeoPoint(it.lat, it.lon) })
    map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box, false, FIT_PADDING_PX) }
    return map
}

private fun stationDot(ctx: Context): Drawable {
    val px = (STATION_DOT_DP * ctx.resources.displayMetrics.density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(px / STATION_STROKE_DIV, LINE_COLOR)
        setSize(px, px)
    }
}

private fun configureOsm(ctx: Context) {
    val config = Configuration.getInstance()
    config.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    config.userAgentValue = ctx.packageName
    config.osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
    config.osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
}
