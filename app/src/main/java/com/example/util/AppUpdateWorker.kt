package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.*
import com.example.reminders.showAppUpdateNotification
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Receives future releases even when the UI is closed; installation stays user initiated. */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val info = AppUpdateManager.checkForUpdate(applicationContext)
        if (info != null) {
            val prefs = applicationContext.getSharedPreferences("dental_updates", Context.MODE_PRIVATE)
            val network = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (prefs.getBoolean("auto_download_wifi", true) && !network.isActiveNetworkMetered) {
                AppUpdateManager.downloadApk(applicationContext, info) { _: AppUpdateManager.DownloadProgress -> }
            }
            if (prefs.getLong("notified_version", 0L) != info.versionCode) {
                showAppUpdateNotification(applicationContext, info.versionName, info.changelog, info.isMandatory)
                prefs.edit().putLong("notified_version", info.versionCode).apply()
            }
        }
        Result.success()
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("dentalpro_app_updates", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
