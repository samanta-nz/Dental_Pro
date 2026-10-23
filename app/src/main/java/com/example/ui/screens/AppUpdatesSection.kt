package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.DentalViewModel
import com.example.util.AppUpdateManager

@Composable
fun AppUpdatesSection(viewModel: DentalViewModel, isPersian: Boolean) {
    val context = LocalContext.current
    val checking by viewModel.isCheckingAppUpdate.collectAsStateWithLifecycle()
    val message by viewModel.appUpdateStatus.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("dental_updates", Context.MODE_PRIVATE) }
    var autoDownload by remember { mutableStateOf(prefs.getBoolean("auto_download_wifi", true)) }
    fun t(fa: String, en: String) = if (isPersian) fa else en
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(t("بروزرسانی برنامه", "App updates"), style = MaterialTheme.typography.titleMedium)
            Text(t("نسخه نصب‌شده: ", "Installed version: ") + AppUpdateManager.currentVersionName(context))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t("دانلود خودکار با Wi-Fi", "Auto-download on Wi-Fi"), modifier = Modifier.weight(1f))
                Switch(checked = autoDownload, onCheckedChange = { autoDownload = it; prefs.edit().putBoolean("auto_download_wifi", it).apply() })
            }
            Text(t("نسخه‌های جدید خودکار بررسی می‌شوند. نصب با تأیید شما انجام می‌شود.", "New versions are checked automatically. Installation requires your confirmation."), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { viewModel.checkForAppUpdate(notify = false) }, enabled = !checking) {
                Text(if (checking) t("در حال بررسی…", "Checking…") else t("بررسی و دریافت نسخه جدید", "Check for updates"))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
