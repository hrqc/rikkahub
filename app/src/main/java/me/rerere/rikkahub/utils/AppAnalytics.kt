package me.rerere.rikkahub.utils

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import me.rerere.rikkahub.BuildConfig

/** Local fork builds never request Firebase Analytics or send usage events. */
class AppAnalytics {
    fun logEvent(name: String) {
        if (BuildConfig.FIREBASE_ENABLED) {
            Firebase.analytics.logEvent(name, null)
        }
    }
}
