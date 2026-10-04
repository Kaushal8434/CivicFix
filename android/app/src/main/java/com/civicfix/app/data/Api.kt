package com.civicfix.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ApiException(message: String, val code: Int = 0) : Exception(message)

data class FilePart(val field: String, val file: File, val mime: String)

/**
 * Minimal HTTP client for the CivicFix backend (the FastAPI app in backend/app).
 * Server address defaults to the PC running the backend as seen from the Android emulator.
 */
class Api(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("url", DEFAULT_URL)!!.trimEnd('/')
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    fun mediaUrl(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> baseUrl + path
    }

    suspend fun get(path: String): Any = request("GET", path, null)
    suspend fun post(path: String, body: Any? = JSONObject()): Any = request("POST", path, body)
    suspend fun patch(path: String, body: Any): Any = request("PATCH", path, body)

    private suspend fun request(method: String, path: String, body: Any?): Any = withContext(Dispatchers.IO) {
        val conn = open(path, method)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        read(conn)
    }

    /** multipart/form-data upload (complaint photo, completion photo + video). */
    suspend fun multipart(path: String, fields: Map<String, String?>, files: List<FilePart>): Any = withContext(Dispatchers.IO) {
        val boundary = "----CivicFix" + UUID.randomUUID().toString().replace("-", "")
        val conn = open(path, "POST").apply {
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            fields.forEach { (k, v) ->
                if (v != null) {
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n")
                    out.write(v.toByteArray())
                    out.writeBytes("\r\n")
                }
            }
            files.forEach { f ->
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"${f.field}\"; filename=\"${f.file.name}\"\r\n")
                out.writeBytes("Content-Type: ${f.mime}\r\n\r\n")
                f.file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n")
            }
            out.writeBytes("--$boundary--\r\n")
        }
        read(conn)
    }

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun read(conn: HttpURLConnection): Any {
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            val json = text.takeIf { it.isNotBlank() }?.let { runCatching { JSONTokener(it).nextValue() }.getOrNull() }
            if (code !in 200..299) {
                val detail = (json as? JSONObject)?.opt("detail")
                val msg = when (detail) {
                    is String -> detail
                    is JSONArray -> (0 until detail.length()).joinToString { detail.getJSONObject(it).optString("msg") }
                    else -> "Server error $code"
                }
                throw ApiException(msg, code)
            }
            return json ?: JSONObject()
        } catch (e: java.io.IOException) {
            throw ApiException("Cannot reach the CivicFix server at $baseUrl – is it running? (${e.message})")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 10.0.2.2 = the PC from inside the Android emulator. On a real phone use the PC's Wi-Fi IP. */
        const val DEFAULT_URL = "http://10.0.2.2:8000"
    }
}
