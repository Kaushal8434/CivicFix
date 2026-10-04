package com.civicfix.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.location.LocationManagerCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object Photos {
    fun dir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

    fun newFile(context: Context, prefix: String) = File(dir(context), "${prefix}_${System.currentTimeMillis()}.jpg")

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Copies a gallery image into app storage so it survives after the picker grant expires. */
    fun importUri(context: Context, uri: Uri, prefix: String): File? = try {
        val f = newFile(context, prefix)
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        f
    } catch (e: Exception) {
        null
    }

    /** Decodes a down-sampled, EXIF-rotated bitmap (enough for the 224px model and previews). */
    fun loadBitmap(path: String, maxSide: Int = 640): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (rotation == 0f) bmp else
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }
}

object Gps {
    private val worker = Executors.newSingleThreadExecutor()

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun current(context: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) return onResult(null)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return onResult(null)
        }
        val main = ContextCompat.getMainExecutor(context)
        LocationManagerCompat.getCurrentLocation(lm, provider, CancellationSignal(), worker) { loc ->
            val result = loc ?: lm.getLastKnownLocation(provider)
            main.execute { onResult(result) }
        }
    }
}

object Notifier {
    private const val CHANNEL = "complaint_updates"

    @SuppressLint("MissingPermission")
    fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Complaint updates", NotificationManager.IMPORTANCE_DEFAULT))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

object Maps {
    fun openGoogleMaps(context: Context, lat: Double, lng: Double, label: String = "") {
        val encodedLabel = Uri.encode(label)
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($encodedLabel)")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng"))
        try {
            context.startActivity(mapIntent)
        } catch (e: Exception) {
            runCatching { context.startActivity(fallbackIntent) }
        }
    }
}

data class Place(val label: String, val lat: Double, val lng: Double)

/**
 * Address search ("type the address") and reverse geocoding ("which address is under the pin").
 * Uses Android's built-in Geocoder (Google's service on most phones, no API key);
 * falls back to OpenStreetMap Nominatim when the phone has no geocoder.
 * Call from a background thread – both functions do network I/O.
 */
object Geo {
    @Suppress("DEPRECATION")
    fun search(context: Context, query: String): List<Place> {
        if (query.isBlank()) return emptyList()
        if (android.location.Geocoder.isPresent()) {
            runCatching {
                android.location.Geocoder(context, Locale.getDefault()).getFromLocationName(query, 5)
                    ?.filter { it.hasLatitude() && it.hasLongitude() }
                    ?.map { Place(addressLine(it) ?: query, it.latitude, it.longitude) }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return nominatim("search?format=jsonv2&limit=5&q=" + Uri.encode(query))?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Place(o.optString("display_name", query), o.getString("lat").toDouble(), o.getString("lon").toDouble())
            }
        }.orEmpty()
    }

    @Suppress("DEPRECATION")
    fun reverse(context: Context, lat: Double, lng: Double): String? {
        if (android.location.Geocoder.isPresent()) {
            runCatching {
                android.location.Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()?.let(::addressLine)
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            val url = java.net.URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                setRequestProperty("User-Agent", "CivicFix/1.2 (academic prototype)")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            org.json.JSONObject(conn.inputStream.bufferedReader().readText()).optString("display_name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun nominatim(path: String): org.json.JSONArray? = runCatching {
        val conn = (java.net.URL("https://nominatim.openstreetmap.org/$path").openConnection() as java.net.HttpURLConnection).apply {
            setRequestProperty("User-Agent", "CivicFix/1.2 (academic prototype)")
            connectTimeout = 10_000; readTimeout = 10_000
        }
        org.json.JSONArray(conn.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun addressLine(a: android.location.Address): String? =
        (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ").takeIf { it.isNotBlank() }
}

fun formatDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
fun formatDay(ms: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))
