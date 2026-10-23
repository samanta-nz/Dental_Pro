package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.DentalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * "Today's appointments" home screen widget. Uses classic RemoteViews (not
 * Jetpack Glance) so it works without adding a new dependency and stays
 * consistent with the app's minSdk 24 target.
 */
class AppointmentsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val repository = DentalRepository(db)
                val settings = repository.clinicSettings.first()
                val appointments = repository.allAppointments.first()

                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val startOfDay = cal.timeInMillis
                val endOfDay = startOfDay + 24 * 60 * 60 * 1000L

                val todays = appointments
                    .filter { !it.isArchived && it.date in startOfDay until endOfDay }
                    .sortedBy { it.date }

                val views = RemoteViews(context.packageName, R.layout.widget_appointments)
                views.setTextViewText(R.id.widget_clinic_name, settings?.clinicName?.takeIf { it.isNotBlank() } ?: "دنتال‌پرو")
                views.setTextViewText(R.id.widget_today_summary, "امروز: ${todays.size} نوبت")

                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val lineIds = listOf(R.id.widget_appt_line_1, R.id.widget_appt_line_2, R.id.widget_appt_line_3)
                lineIds.forEachIndexed { index, viewId ->
                    val appt = todays.getOrNull(index)
                    if (appt != null) {
                        views.setTextViewText(viewId, "${timeFmt.format(java.util.Date(appt.date))} — نوبت مطب")
                        views.setViewVisibility(viewId, android.view.View.VISIBLE)
                    } else {
                        views.setViewVisibility(viewId, android.view.View.GONE)
                    }
                }
                views.setViewVisibility(R.id.widget_empty_state, if (todays.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE)

                val openAppIntent = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                appWidgetManager.updateAppWidget(widgetId, views)
            } catch (e: Exception) {
                // Leave the widget showing its last successfully-rendered state.
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** Call after any appointment change so widgets refresh immediately instead of waiting for the next system update cycle. */
        fun requestUpdate(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AppointmentsWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                val intent = Intent(context, AppointmentsWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}
