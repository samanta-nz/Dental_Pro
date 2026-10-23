package com.example.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R

const val CHANNEL_APPOINTMENTS = "dentalpro_appointments"
const val CHANNEL_CHEQUES = "dentalpro_cheques"
const val CHANNEL_OCCASIONS = "dentalpro_occasions"
const val CHANNEL_BANK_SMS = "dentalpro_bank_sms"
const val CHANNEL_APP_UPDATES = "dentalpro_app_updates"

/** Creates the notification channels (safe to call repeatedly — Android ignores re-creation of an existing channel). */
fun ensureNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_APPOINTMENTS, "یادآوری نوبت‌ها", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "یادآوری نوبت‌های نزدیک بیماران"
        }
    )
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_OCCASIONS, "یادآوری مناسبت‌ها", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "اطلاع‌رسانی مناسبت‌های تقویم"
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_CHEQUES, "یادآوری سررسید چک", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "یادآوری چک‌های نزدیک به سررسید"
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_BANK_SMS, "اعلام تراکنش پیامک بانکی", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "اعلام زنده واریز و برداشت پیامک‌های بانکی جهت ثبت خودکار در حسابداری"
        }
    )

    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_APP_UPDATES, "بروزرسانی برنامه", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "اعلان نسخه جدید برنامه — با لمس، دانلود و نصب می‌شود"
        }
    )
}

private fun openAppPendingIntent(context: Context, requestCode: Int): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getActivity(context, requestCode, intent, flags)
}

/** True if the app is allowed to post notifications on this device/OS version. */
fun canPostNotifications(context: Context): Boolean {
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

fun showAppointmentReminder(context: Context, notificationId: Int, patientName: String, timeText: String) {
    if (!canPostNotifications(context)) return
    ensureNotificationChannels(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_APPOINTMENTS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("یادآوری نوبت مطب")
        .setContentText("نوبت در $timeText")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(openAppPendingIntent(context, notificationId))
        .build()
    postNotificationSafely(context, notificationId, notification)
}

fun showChequeDueReminder(context: Context, notificationId: Int, patientName: String, amountText: String, dueText: String) {
    if (!canPostNotifications(context)) return
    ensureNotificationChannels(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_CHEQUES)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("یادآوری سررسید چک")
        .setContentText("برای مشاهده جزئیات وارد برنامه شوید")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(openAppPendingIntent(context, notificationId))
        .build()
    postNotificationSafely(context, notificationId, notification)
}

/**
 * یادآوری موعد پرداخت‌های مطب — حقوق پرسنل، اجاره و شارژ ساختمان (یک روز قبل از موعد).
 */
fun showPaymentDueReminder(context: Context, notificationId: Int, title: String, body: String) {
    if (!canPostNotifications(context)) return
    ensureNotificationChannels(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_CHEQUES)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(body)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(openAppPendingIntent(context, notificationId))
        .build()
    postNotificationSafely(context, notificationId, notification)
}

fun showOccasionReminder(context: Context, notificationId: Int, title: String, typeName: String) {
    if (!canPostNotifications(context)) return
    ensureNotificationChannels(context)
    val notification = NotificationCompat.Builder(context, CHANNEL_OCCASIONS)
        .setSmallIcon(android.R.drawable.ic_menu_today)
        .setContentTitle("فردا: $title")
        .setContentText("یادآوری مناسبت ($typeName)")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .setContentIntent(openAppPendingIntent(context, notificationId))
        .build()
    postNotificationSafely(context, notificationId, notification)
}

fun showBankSmsNotification(
    context: Context,
    notificationId: Int,
    bankName: String,
    transactionType: String,
    amountText: String,
    dateStr: String = "امروز",
    timeStr: String = "",
    rawSmsText: String
) {
    if (!canPostNotifications(context)) return
    ensureNotificationChannels(context)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("EXTRA_OPEN_BANK_SMS", rawSmsText)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val pendingIntent = PendingIntent.getActivity(context, notificationId, intent, flags)

    val isDeposit = transactionType == "DEPOSIT"
    // بانک و مبلغ به‌عنوان مهم‌ترین اطلاعات در عنوان برجسته (Bold)
    val title = if (isDeposit) "📥 $amountText تومان واریز — $bankName" else "📤 $amountText تومان برداشت — $bankName"
    val timePart = if (timeStr.isNotBlank()) "    🕒 ساعت: $timeStr" else ""
    val expandedBody = "🏦 بانک: $bankName\n💰 مبلغ: $amountText تومان\n📅 تاریخ: $dateStr$timePart\nلمس کنید جهت ثبت خودکار در حسابداری"

    val notification = NotificationCompat.Builder(context, CHANNEL_BANK_SMS)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("تراکنش بانکی جدید")
        .setContentText("برای مشاهده و ثبت وارد برنامه شوید")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    postNotificationSafely(context, notificationId, notification)
}

/**
 * اعلان «بروزرسانی جدید برنامه» — با لمس، اپ باز شده و دیالوگ دانلود و نصب نمایش داده می‌شود.
 * [mandatory] = آپدیت اصلی (تغییر رقم اول نسخه): اعلان پایدار (غیرقابل‌کنارزدن) با متن اجباری.
 */
fun showAppUpdateNotification(
    context: Context,
    versionName: String,
    changelog: String,
    mandatory: Boolean = false
) {
    if (!canPostNotifications(context)) return
    ensureNotificationChannels(context)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra("EXTRA_OPEN_APP_UPDATE", true)
    }
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val pendingIntent = PendingIntent.getActivity(context, 990017, intent, flags)

    val title = if (mandatory)
        "⚠️ بروزرسانی اجباری دنتال‌پرو — نسخه $versionName"
    else
        "🚀 بروزرسانی جدید دنتال‌پرو — نسخه $versionName"

    val body = buildString {
        if (mandatory) {
            append("این یک آپدیت اصلی است و نصب آن الزامی است. برای دانلود و نصب لمس کنید.")
        } else {
            append("نسخه $versionName آماده دانلود است. برای دانلود و نصب لمس کنید.")
        }
        if (changelog.isNotBlank()) {
            append("\n\nتغییرات:\n")
            append(changelog)
        }
    }

    val notification = NotificationCompat.Builder(context, CHANNEL_APP_UPDATES)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText("برای دانلود و نصب لمس کنید")
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(!mandatory)
        .setOngoing(mandatory)
        .setContentIntent(pendingIntent)
        .build()

    postNotificationSafely(context, 990017, notification)
}

private fun postNotificationSafely(context: Context, id: Int, notification: android.app.Notification) {
    if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.POST_NOTIFICATIONS
    ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
    try { NotificationManagerCompat.from(context).notify(id, notification) }
    catch (_: SecurityException) { /* Permission can be revoked between the check and delivery. */ }
}
