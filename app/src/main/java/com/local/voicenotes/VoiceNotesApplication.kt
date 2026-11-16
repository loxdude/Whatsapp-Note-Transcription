package com.local.voicenotes

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.local.voicenotes.work.WorkNotifications

/**
 * Owns the process-wide [AppGraph]. The transcription coordinator lives here rather than in
 * the Activity's ViewModel so that an import or transcription keeps running (inside
 * [com.local.voicenotes.work.TranscriptionService]) after the activity is destroyed.
 */
class VoiceNotesApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    /** Started activities; touched only on the main thread. */
    private var startedActivities = 0

    /** True while any activity of the app is visible (started). Main thread only. */
    val isInForeground: Boolean get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        WorkNotifications.ensureChannels(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

val Context.voiceNotesApp: VoiceNotesApplication get() = applicationContext as VoiceNotesApplication

val Context.appGraph: AppGraph get() = voiceNotesApp.graph
