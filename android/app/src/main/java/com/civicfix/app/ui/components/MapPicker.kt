package com.civicfix.app.ui.components

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/** Default view when nothing is selected yet: New Delhi. */
private val DEFAULT_CENTER = GeoPoint(28.6139, 77.2090)

/**
 * OpenStreetMap map with one pin.
 *
 * Interactive mode: tap anywhere or drag the pin to choose the location; [onPick] gets the
 * coordinates. Read-only mode (onPick = null): a small preview, tapping it calls [onClick].
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapPicker(
    lat: Double?,
    lng: Double?,
    modifier: Modifier = Modifier,
    heightDp: Int = 280,
    onPick: ((Double, Double) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pick by rememberUpdatedState(onPick)
    val interactive = onPick != null

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            controller.setZoom(if (lat != null) 17.0 else 11.0)
            controller.setCenter(if (lat != null && lng != null) GeoPoint(lat, lng) else DEFAULT_CENTER)
        }
    }
    val marker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            isDraggable = interactive
            title = "Problem location"
            setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                override fun onMarkerDrag(m: Marker) {}
                override fun onMarkerDragStart(m: Marker) {}
                override fun onMarkerDragEnd(m: Marker) { pick?.invoke(m.position.latitude, m.position.longitude) }
            })
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    DisposableEffect(lifecycle) {
        if (interactive) {
            mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { pick?.invoke(p.latitude, p.longitude); return true }
                override fun longPressHelper(p: GeoPoint): Boolean { pick?.invoke(p.latitude, p.longitude); return true }
            }))
            // Let the map receive drags instead of the page scrolling.
            mapView.setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        } else {
            mapView.setOnTouchListener { _, _ -> true }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier.fillMaxWidth().height(heightDp.dp).clip(RoundedCornerShape(18.dp))) {
        AndroidView(
            factory = { mapView },
            update = { map ->
                if (lat != null && lng != null) {
                    val p = GeoPoint(lat, lng)
                    if (marker.position != p) {
                        marker.position = p
                        if (marker !in map.overlays) map.overlays.add(marker)
                        if (map.zoomLevelDouble < 15.0) map.controller.setZoom(17.0)
                        map.controller.animateTo(p)
                    }
                } else {
                    map.overlays.remove(marker)
                }
                map.invalidate()
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!interactive && onClick != null) {
            Box(Modifier.fillMaxSize().clickable(onClick = onClick))
        }
        // OpenStreetMap licence requires attribution.
        Surface(color = Color.White.copy(alpha = 0.8f), shape = RoundedCornerShape(topStart = 8.dp),
            modifier = Modifier.align(Alignment.BottomEnd)) {
            Text("© OpenStreetMap contributors", fontSize = 9.sp, color = Color.DarkGray, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        if (interactive && lat == null) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), shape = RoundedCornerShape(10.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)) {
                Text("Tap the map to drop a pin", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}
