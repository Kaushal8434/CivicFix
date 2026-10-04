package com.civicfix.app.ml

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pure-Kotlin inference for the TF-IDF + Logistic Regression model trained in
 * ml/05_train_text_classifier.py. Mirrors scikit-learn's TfidfVectorizer
 * (lowercase, token_pattern \b\w\w+\b, word 1-2 grams, sublinear tf, l2 norm)
 * so on-device probabilities match the Python ones.
 */
class TextClassifier(context: Context) {

    class Head(val classes: List<String>, val coef: Array<FloatArray>, val intercept: FloatArray)

    private val vocab = HashMap<String, Int>()
    private val idf: FloatArray
    private val nMin: Int
    private val nMax: Int
    private val category: Head
    private val severity: Head
    // Android's ICU regex treats \w as Unicode already (like Python's (?u)\w);
    // it rejects the UNICODE_CHARACTER_CLASS flag.
    private val token = Pattern.compile("\\b\\w\\w+\\b")
    val metrics: JSONObject

    init {
        val json = JSONObject(context.assets.open("text_model.json").bufferedReader().readText())
        val v = json.getJSONObject("vocabulary")
        v.keys().forEach { vocab[it] = v.getInt(it) }
        idf = json.getJSONArray("idf").toFloatArray()
        val ng = json.getJSONArray("ngram_range")
        nMin = ng.getInt(0); nMax = ng.getInt(1)
        category = head(json.getJSONObject("category"))
        severity = head(json.getJSONObject("severity"))
        metrics = json.optJSONObject("metrics") ?: JSONObject()
    }

    private fun JSONArray.toFloatArray() = FloatArray(length()) { getDouble(it).toFloat() }

    private fun head(o: JSONObject): Head {
        val cls = o.getJSONArray("classes")
        val coef = o.getJSONArray("coef")
        return Head(
            (0 until cls.length()).map { cls.getString(it) },
            Array(coef.length()) { coef.getJSONArray(it).toFloatArray() },
            o.getJSONArray("intercept").toFloatArray(),
        )
    }

    /** Sparse TF-IDF vector: feature index -> weight. */
    private fun vectorize(text: String): Map<Int, Float> {
        val m = token.matcher(text.lowercase())
        val tokens = mutableListOf<String>()
        while (m.find()) tokens += m.group()
        val counts = HashMap<Int, Int>()
        for (n in nMin..nMax) {
            for (i in 0..tokens.size - n) {
                val gram = tokens.subList(i, i + n).joinToString(" ")
                vocab[gram]?.let { counts[it] = (counts[it] ?: 0) + 1 }
            }
        }
        val w = counts.mapValues { (idx, c) -> (1f + ln(c.toFloat())) * idf[idx] }
        val norm = sqrt(w.values.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm == 0f) w else w.mapValues { it.value / norm }
    }

    private fun predict(h: Head, x: Map<Int, Float>): Map<String, Float> {
        val z = FloatArray(h.classes.size) { k ->
            var s = h.intercept[k]
            for ((i, v) in x) s += h.coef[k][i] * v
            s
        }
        return softmax(z).let { p -> h.classes.indices.associate { h.classes[it] to p[it] } }
    }

    /** Returns null when the text contains no known words (nothing to base a guess on). */
    fun classify(text: String): Pair<Map<String, Float>, Map<String, Float>>? {
        val x = vectorize(text)
        if (x.isEmpty()) return null
        return predict(category, x) to predict(severity, x)
    }
}

fun softmax(z: FloatArray): FloatArray {
    val max = z.max()
    val e = z.map { exp((it - max).toDouble()) }
    val sum = e.sum()
    return FloatArray(z.size) { (e[it] / sum).toFloat() }
}
