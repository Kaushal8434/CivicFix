package com.civicfix.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.nio.FloatBuffer

/**
 * Runs the MobileNetV3 photo classifier (fine-tuned on CLIP-cleaned real photos,
 * see ml/03_train_image_classifier.py) with ONNX Runtime.
 *
 * The exported logits are already temperature-calibrated, so the softmax
 * probabilities can be shown to the user as confidence.
 *
 * If assets/civic_classifier.onnx has not been generated yet, [available] is
 * false and the app falls back to the text model + manual category choice.
 */
class ImageClassifier(context: Context) {
    private var session: OrtSession? = null
    private val env: OrtEnvironment? by lazy { runCatching { OrtEnvironment.getEnvironment() }.getOrNull() }
    var labels: List<String> = emptyList(); private set
    var testAccuracy: Double? = null; private set
    var testMacroF1: Double? = null; private set
    var trainImages: Int? = null; private set
    var testImages: Int? = null; private set
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
                if (meta.has("test_macro_f1")) testMacroF1 = meta.getDouble("test_macro_f1")
                if (meta.has("train_images")) trainImages = meta.getInt("train_images")
                if (meta.has("test_images")) testImages = meta.getInt("test_images")
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

    /**
     * Category probabilities for the photo, or null if the model is unavailable.
     * The photo is squeezed to 224×224 (same as training) and classified together
     * with its mirror image; the two predictions are averaged (test-time augmentation).
     */
    fun classify(bitmap: Bitmap): Map<String, Float>? {
        val s = session ?: return null
        val e = env ?: return null
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            val plane = size * size
            val buf = FloatBuffer.allocate(2 * 3 * plane)
            for (y in 0 until size) for (x in 0 until size) {
                val i = y * size + x
                val p = pixels[i]
                val r = (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0]
                val g = (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1]
                val b = ((p and 0xFF) / 255f - mean[2]) / std[2]
                val m = 3 * plane + y * size + (size - 1 - x) // mirrored copy
                buf.put(i, r); buf.put(plane + i, g); buf.put(2 * plane + i, b)
                buf.put(m, r); buf.put(m + plane, g); buf.put(m + 2 * plane, b)
            }
            // The exported model has a fixed batch of 1, so run the two views separately.
            val probs = FloatArray(labels.size)
            for (view in 0 until 2) {
                val one = FloatBuffer.allocate(3 * plane)
                for (k in 0 until 3 * plane) one.put(k, buf.get(view * 3 * plane + k))
                OnnxTensor.createTensor(e, one, longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
                    s.run(mapOf(s.inputNames.first() to input)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val p = softmax((out[0].value as Array<FloatArray>)[0])
                        for (k in probs.indices) probs[k] += p[k] / 2f
                    }
                }
            }
            labels.indices.associate { labels[it] to probs[it] }
        } catch (t: Throwable) {
            null
        }
    }
}
