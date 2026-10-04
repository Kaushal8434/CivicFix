package com.civicfix.app

import android.app.Application
import com.civicfix.app.data.Api
import com.civicfix.app.data.ReferenceData
import com.civicfix.app.data.Repository
import com.civicfix.app.ml.AiAnalyzer
import com.civicfix.app.ml.ImageClassifier
import com.civicfix.app.ml.TextClassifier

/** Simple service locator – the prototype does not need a DI framework. */
class CivicFixApp : Application() {
    lateinit var ref: ReferenceData; private set
    lateinit var api: Api; private set
    lateinit var repo: Repository; private set

    /** On-device AI (instant category hint before upload). Loaded lazily – first access happens off the main thread. */
    val ai: AiAnalyzer by lazy { AiAnalyzer(ref, ImageClassifier(this), TextClassifier(this)) }

    override fun onCreate() {
        super.onCreate()
        ref = ReferenceData(this)
        api = Api(this)
        repo = Repository(api)
        // OpenStreetMap tiles: identify the app and keep the tile cache in private storage.
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = java.io.File(cacheDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid/tiles")
        }
    }
}
