package me.zssu.ime

import android.app.Application
import me.zssu.ime.mozc.ProfileBackup

class KomorebiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore before Activities/services construct repositories that cache settings or files.
        ProfileBackup.applyPendingRestore(this)
    }
}
