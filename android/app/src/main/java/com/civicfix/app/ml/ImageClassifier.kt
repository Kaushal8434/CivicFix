package com.civicfix.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.nio.FloatBuffer

/**
 * Runs the MobileNetV3 photo classifier (trained on real + Stable-Diffusion
 * synthetic images, see ml/03_train_image_classifier.py) with ONNX Runtime.
 *
 * If assets/civic_classifier.onnx has not been generated yet, [available] is
 * false and the app falls back to the text model + manual category choice.
 */
class ImageClassifier(context: Context) {
    private var session: OrtSession? = null
    private val env: OrtEnvironment? by lazy { runCatching { OrtEnvironment.getEnvironment() }.getOrNull() }
    var labels: List<String> = emptyList(); private set
    var testAccuracy: Double? = null; private set
    private var size = 224
    private var mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private var std = floatArrayOf(0.229f, 0.224f, 0.225f)
    var loadError: String? = null; private set

    val available get() = session != null

    init {
        try {
            val assets = context.assets.list("")?.toSet().orEmpty()
            if ("civic_classifier.onnx" in assets && "image_labels.json" in assets) {
                val meta = JSONObject(context.assets.open("image_labels.json").bufferedReader().readText())
                val l = meta.getJSONArray("labels")
                labels = (0 until l.length()).map { l.getString(it) }
                size = meta.optInt("input_size", 224)
                meta.optJSONArray("mean")?.let { a -> mean = FloatArray(3) { a.getDouble(it).toFloat() } }
                meta.optJSONArray("std")?.let { a -> std = FloatArray(3) { a.getDouble(it).toFloat() } }
                if (meta.has("test_accuracy")) testAccuracy = meta.getDouble("test_accuracy")
                val bytes = context.assets.open("civic_classifier.onnx").readBytes()
                session = env?.createSession(bytes, OrtSession.SessionOptions())
            } else {
                loadError = "Model not bundled yet – run ml/03_train_image_classifier.py"
            }
        } catch (e: Throwable) {
            loadError = e.message
            session = null
        }
    }

    /** Category probabilities for the photo, or null if the model is unavailable. */
    fun classify(bitmap: Bitmap): Map<String, Float>? {
        val s = session ?: return null
        val e = env ?: return null
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)
        val plane = size * size
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            buf.put(i, (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0])
            buf.put(plane + i, (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1])
            buf.put(2 * plane + i, ((p and 0xFF) / 255f - mean[2]) / std[2])
        }
        OnnxTensor.createTensor(e, buf, longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            s.run(mapOf(s.inputNames.first() to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out[0].value as Array<FloatArray>)[0]
                val p = softmax(logits)
                return labels.indices.associate { labels[it] to p[it] }
            }
        }
    }
}
