package eu.kanade.tachiyomi.extension.all.comickfun

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class ComickFunUrlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pathSegments = intent?.data?.pathSegments

        if (pathSegments != null 
            && pathSegments.size > 1 
            && pathSegments[2] != "follows" 
            && pathSegments[2] != "covers") {

            val seriesId = pathSegments[1]

            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", ComickFun.prefixIdSearch + seriesId)
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("ComickFunUrlActivity", e.toString())
            }
        } else {
            Log.e("ComickFunUrlActivity", "Could not parse URI from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
