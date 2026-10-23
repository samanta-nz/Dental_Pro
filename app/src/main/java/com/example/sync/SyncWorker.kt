package com.example.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.DentalRepository
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "dentalpro_periodic_sync"

/**
 * سینکِ پس‌زمینه به‌جای حلقهٔ `while(true){ delay(15000); pushPending() }` که قبلاً
 * داخل ViewModel بود.
 *
 * چرا WorkManager؟ آن حلقه تا زمانی که ViewModel زنده بود می‌چرخید — حتی وقتی اپ
 * در background بود — و باتری/شبکه مصرف می‌کرد بی‌آنکه سیستم‌عامل بتواند آن را
 * batch یا به تعویق بیندازد. WorkManager این کار را با قید «فقط وقتی شبکه هست»
 * و به‌صورت batch‌شده با سایر jobها انجام می‌دهد؛ ضمناً پس از بسته‌شدن اپ هم
 * تضمینِ اجرا دارد.
 *
 * توجه: سینکِ آنیِ حینِ استفاده همچنان توسط collectorهای واکنشیِ داخل ViewModel
 * (isOnline + pendingSyncOpsCount) انجام می‌شود؛ این Worker فقط تورِ ایمنیِ
 * دوره‌ای است.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = DentalRepository(db) {
                applicationContext.getSharedPreferences("dental_pro_prefs", Context.MODE_PRIVATE).getString("auth_account_id", null)
            }
            // اگر چیزی برای ارسال نیست، بی‌جهت شبکه باز نمی‌کنیم.
            val engine = SyncEngine(isGuestMode = false, context = applicationContext, repository = repository, database = db, observeNetwork = false)
            engine.pushPending()
            if (db.pendingSyncOpDao().countOnce() > 0) return Result.retry()
            engine.pullAll()
            if (engine.failedCollections.value.isEmpty() && engine.lastError.value.isNullOrBlank()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // حداقلِ دورهٔ مجازِ WorkManager ۱۵ دقیقه است (همان بازهٔ منطقی برای
            // یک تورِ ایمنیِ پس‌زمینه؛ سینکِ آنی از مسیر واکنشی انجام می‌شود).
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
