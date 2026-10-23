package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.AppUpdateManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Download a verified update and hand it to Android's user-confirmed installer. */
@Composable
fun AppUpdateDialog(
    info: AppUpdateManager.AppUpdateInfo,
    isPersian: Boolean = true,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var totalBytes by remember { mutableStateOf(-1L) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var speedBps by remember { mutableStateOf(0L) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var needsInstallPermission by remember { mutableStateOf(false) }

    val isDriveLink = remember(info.apkUrl) {
        AppUpdateManager.isGoogleDriveUrl(info.apkUrl)
    }

    fun startDownloadAndInstall() {
        downloading = true
        errorMessage = null
        progress = 0f
        totalBytes = -1L
        downloadedBytes = 0L
        speedBps = 0L
        scope.launch {
            try {
                val file = AppUpdateManager.downloadApk(context, info) { prog: AppUpdateManager.DownloadProgress ->
                    scope.launch(Dispatchers.Main) {
                        progress = prog.percent
                        totalBytes = prog.totalBytes
                        downloadedBytes = prog.downloadedBytes
                        speedBps = prog.speedBytesPerSecond
                    }
                }
                downloadedFile = file
                downloading = false
                if (AppUpdateManager.canInstallPackages(context)) {
                    AppUpdateManager.installApk(context, file)
                } else {
                    needsInstallPermission = true
                    AppUpdateManager.openInstallPermissionSettings(context)
                }
            } catch (e: Exception) {
                downloading = false
                errorMessage = e.message ?: (if (isPersian) "خطا در دانلود" else "Download failed")
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!info.isMandatory && !downloading) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (isPersian) "بروزرسانی جدید — نسخه ${info.versionName}" else "New Update — v${info.versionName}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (isPersian) "نسخه فعلی شما: ${AppUpdateManager.currentVersionName(context)}"
                           else "Current version: ${AppUpdateManager.currentVersionName(context)}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )

                val typeLabel = when (info.updateType) {
                    AppUpdateManager.UpdateType.MAJOR -> if (isPersian) "🔴 آپدیت اصلی — نصب اجباری (نوع ۱)" else "🔴 Major update — mandatory (Type 1)"
                    AppUpdateManager.UpdateType.MINOR -> if (isPersian) "🟡 تغییرات جزیی — اختیاری" else "🟡 Minor update — optional"
                    AppUpdateManager.UpdateType.PATCH -> if (isPersian) "🟢 بهبود و رفع باگ" else "🟢 Fixes & improvements"
                }
                Text(
                    text = typeLabel,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (info.updateType) {
                        AppUpdateManager.UpdateType.MAJOR -> MaterialTheme.colorScheme.error
                        AppUpdateManager.UpdateType.MINOR -> Color(0xFFC2410C)
                        AppUpdateManager.UpdateType.PATCH -> Color(0xFF2E7D32)
                    }
                )

                if (isDriveLink) {
                    Surface(
                        color = Color(0xFF4285F4).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isPersian) "📁 منبع: گوگل‌درایو — دانلود مستقیم بدون باز کردن مرورگر" else "📁 Source: Google Drive — direct download",
                            fontSize = 10.5.sp,
                            color = Color(0xFF1A73E8),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }

                if (info.changelog.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isPersian) "تغییرات این نسخه:" else "What's new:",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(info.changelog, fontSize = 11.5.sp)
                        }
                    }
                }

                if (info.isMandatory) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isPersian) "⚠️ برای ادامه استفاده، این نسخه را نصب کنید."
                                   else "⚠️ Install this update to continue.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                // بخش دانلود با جزئیات کامل
                if (downloading) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (isPersian) "در حال دانلود مستقیم..." else "Downloading...",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "${(progress * 100).toInt()}٪",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                )
                            }

                            // ردیف حجم کل / دانلود شده / درصد
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                                        Text(
                                            text = if (isPersian) "حجم کل:" else "Total:",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                        Text(
                                            text = AppUpdateManager.formatBytesFa(totalBytes, isPersian),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF2E7D32))
                                        Text(
                                            text = if (isPersian) "دانلود شده:" else "Downloaded:",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                        Text(
                                            text = AppUpdateManager.formatBytesFa(downloadedBytes, isPersian),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32)
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF1565C0))
                                        Text(
                                            text = if (isPersian) "سرعت:" else "Speed:",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                        Text(
                                            text = AppUpdateManager.formatSpeed(speedBps, isPersian),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1565C0)
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = if (isPersian) "بدون باز کردن مرورگر" else "Direct download",
                                        fontSize = 9.sp,
                                        color = Color.Gray
                                    )
                                }
                            }

                            // متن توضیحی اضافی
                            if (isDriveLink) {
                                Text(
                                    text = if (isPersian) "⏳ در حال دریافت مستقیم از گوگل‌درایو — لطفاً تا پایان دانلود منتظر بمانید. پس از اتمام، اپ بسته و نصب‌کننده باز می‌شود."
                                           else "⏳ Fetching directly from Google Drive. App will close and installer will open when done.",
                                    fontSize = 9.5.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }

                if (downloadedFile != null && !downloading) {
                    Surface(
                        color = Color(0xFF2E7D32).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (isPersian) "✅ دانلود کامل شد — ${AppUpdateManager.formatBytesFa(downloadedFile!!.length(), isPersian)}"
                                       else "✅ Download complete — ${AppUpdateManager.formatBytesFa(downloadedFile!!.length(), isPersian)}",
                                fontSize = 11.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isPersian) "اگر نصب‌کننده باز نشد، دوباره دکمه «نصب» را لمس کنید. پس از نصب، اپ به‌صورت خودکار بسته می‌شود."
                                       else "If installer didn't open, tap Install again. App will close automatically.",
                                fontSize = 10.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                if (needsInstallPermission) {
                    Surface(
                        color = Color(0xFFC2410C).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isPersian) "برای نصب، در صفحهٔ بازشده اجازهٔ «نصب برنامه‌های ناشناس» را به دنتال‌پرو بدهید و سپس دوباره «نصب» را لمس کنید."
                                   else "Grant 'Install unknown apps' permission to DentalPro, then tap Install again.",
                            fontSize = 11.sp,
                            color = Color(0xFFC2410C),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                errorMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⛔ $msg",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { startDownloadAndInstall() },
                enabled = !downloading,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        downloading -> if (isPersian) "در حال دانلود... ${(progress * 100).toInt()}٪" else "Downloading... ${(progress * 100).toInt()}%"
                        downloadedFile != null -> if (isPersian) "نصب (بستن اپ)" else "Install (close app)"
                        else -> if (isPersian) "دانلود و نصب" else "Download & Install"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            if (!info.isMandatory) {
                TextButton(onClick = onDismiss, enabled = !downloading) {
                    Text(if (isPersian) "بعداً" else "Later")
                }
            }
            // برای آپدیت اجباری نوع ۱ دکمه بعداً نمایش داده نمی‌شود — مطابق درخواست
        }
    )
}

/**
 * اعلان متنی خود-حذف‌شونده برای بروزرسانی نوع PATCH
 */
@Composable
fun PatchUpdateAutoBanner(
    info: AppUpdateManager.AppUpdateInfo,
    isPersian: Boolean = true,
    onTap: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(info.versionCode) {
        kotlinx.coroutines.delay(8_000)
        onDismiss()
    }

    androidx.compose.ui.window.Popup(
        alignment = Alignment.BottomCenter,
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isPersian)
                        "🔧 نسخه ${info.versionName} (بهبود و رفع باگ) منتشر شد — برای دانلود لمس کنید"
                    else
                        "🔧 v${info.versionName} (fixes & improvements) is available — tap to download",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onTap, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (isPersian) "دانلود" else "Get", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
