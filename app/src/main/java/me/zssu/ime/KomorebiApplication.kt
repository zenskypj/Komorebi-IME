package me.zssu.ime

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import me.zssu.ime.settings.BeamInbox
import me.zssu.ime.settings.BeamReceiver
import me.zssu.ime.settings.MeetsBeamActivity
import me.zssu.ime.settings.NfcMigrationActivity
import me.zssu.ime.mozc.ProfileBackup

class KomorebiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore before Activities/services construct repositories that cache settings or files.
        ProfileBackup.applyPendingRestore(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var reader: BeamReceiver? = null
            override fun onActivityResumed(activity: Activity) {
                if (activity !is ComponentActivity || activity is MeetsBeamActivity || activity is NfcMigrationActivity) return
                reader?.stop()
                reader = BeamReceiver(activity, { message ->
                    BeamInbox.put(message)
                    activity.startActivity(Intent(activity, MeetsBeamActivity::class.java).putExtra("beam_inbox", true))
                }).also { it.start() }
            }
            override fun onActivityPaused(activity: Activity) { reader?.stop(); reader = null }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
