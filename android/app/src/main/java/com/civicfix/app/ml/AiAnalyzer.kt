package com.civicfix.app.ml

import android.graphics.Bitmap
import com.civicfix.app.data.ReferenceData

data class AiResult(
    val category: String,
    val confidence: Float,
    val ranked: List<Pair<String, Float>>,
    val severity: String,
    val severityConfidence: Float,
    val safetyFlag: String?,
    val usedImage: Boolean,
    val usedText: Boolean,
) {
    val lowConfidence get() = confidence < AiAnalyzer.CONFIDENT

    fun summary(ref: ReferenceData): String {
        val src = listOfNotNull("photo".takeIf { usedImage }, "description".takeIf { usedText }).joinToString(" + ")
        return "AI ($src): ${ref.category(category).label} ${(confidence * 100).toInt()}%, " +
            "severity $severity" + (safetyFlag?.let { " (safety keyword: \"$it\")" } ?: "")
    }
}

/**
 * Combines the photo model and the text model into one suggestion.
 * The citizen/officer always has the final say: the suggestion only
 * pre-selects the category and severity (report section 5.1/5.2).
 */
class AiAnalyzer(
    private val ref: ReferenceData,
    val image: ImageClassifier,
    val text: TextClassifier,
) {
    fun analyze(photo: Bitmap?, description: String): AiResult? {
        val imgP = photo?.let { image.classify(it) }
        val txt = description.takeIf { it.isNotBlank() }?.let { text.classify(it) }
        val txtP = txt?.first
        if (imgP == null && txtP == null) return null

        val keys = ref.categories.map { it.key }
        val fused = keys.associateWith { k ->
            when {
                imgP != null && txtP != null -> IMAGE_WEIGHT * (imgP[k] ?: 0f) + (1 - IMAGE_WEIGHT) * (txtP[k] ?: 0f)
                imgP != null -> imgP[k] ?: 0f
                else -> txtP!![k] ?: 0f
            }
        }
        val ranked = fused.entries.sortedByDescending { it.value }.map { it.key to it.value }

        // Severity: text model, then raised to "high" if a safety keyword is present.
        var severity = "medium"
        var sevConf = 0f
        txt?.second?.maxByOrNull { it.value }?.let { severity = it.key; sevConf = it.value }
        val lower = description.lowercase()
        val hit = ref.safetyKeywords.firstOrNull { lower.contains(it) }
        if (hit != null && severity != "high") {
            severity = "high"; sevConf = 1f
        }
        return AiResult(ranked[0].first, ranked[0].second, ranked.take(3), severity, sevConf, hit,
            usedImage = imgP != null, usedText = txtP != null)
    }

    /**
     * Resolution check (report section 4.2 "Verify"): if the after-work photo is still
     * confidently recognised as the original problem, warn the citizen/supervisor.
     */
    fun checkAfterPhoto(after: Bitmap, originalCategory: String): String? {
        val p = image.classify(after) ?: return null
        val top = p.maxByOrNull { it.value } ?: return null
        val stillIssue = p[originalCategory] ?: 0f
        return if (originalCategory != "other" && stillIssue >= STILL_ISSUE_THRESHOLD) {
            "⚠️ AI check: after-photo still looks like ${ref.category(originalCategory).label} (${(stillIssue * 100).toInt()}%). Please verify carefully."
        } else {
            "✅ AI check: after-photo no longer shows the reported problem (top guess: ${ref.category(top.key).label} ${(top.value * 100).toInt()}%)."
        }
    }

    companion object {
        const val IMAGE_WEIGHT = 0.65f
        const val CONFIDENT = 0.45f
        const val STILL_ISSUE_THRESHOLD = 0.6f
    }
}
