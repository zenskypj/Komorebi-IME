package me.zssu.ime.update

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.atomic.AtomicBoolean

/** JobScheduler chooses the time; there is no polling while the keyboard is in use. */
class UpdateCheckService : JobService() {
    private val running = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        if (!UpdateManager.enabled(this)) return false
        running.set(true)
        UpdateManager.checkAsync(this, notify = true) {
            if (running.compareAndSet(true, false)) jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.set(false); return false }
}
