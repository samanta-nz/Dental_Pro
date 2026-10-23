package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads only release metadata from Supabase. Android always confirms package installation. */
object AppUpdateManager {
    enum class UpdateType { MAJOR, MINOR, PATCH }
    data class AppUpdateInfo(
        val versionCode: Long, val versionName: String, val apkUrl: String,
        val changelog: String, val isMandatory: Boolean, val updateType: UpdateType,
        val sha256: String = "", val sizeBytes: Long = 0
    )
    data class DownloadProgress(val totalBytes: Long, val downloadedBytes: Long, val percent: Float, val speedBytesPerSecond: Long)
    private val downloadMutex = Mutex()
    private const val MAX_APK_BYTES = 512L * 1024 * 1024

    fun parseSemver(name: String): Triple<Int, Int, Int>? {
        val match = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(name.trim()) ?: return null
        val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
        return Triple(parts[0], parts[1], parts[2])
    }
    fun classifyUpdate(currentName: String, remoteName: String): UpdateType {
        val cur = parseSemver(currentName) ?: return UpdateType.MINOR
        val rem = parseSemver(remoteName) ?: return UpdateType.MINOR
        return when { rem.first > cur.first -> UpdateType.MAJOR; rem.first == cur.first && rem.second > cur.second -> UpdateType.MINOR; else -> UpdateType.PATCH }
    }
    fun currentVersionCode(context: Context): Long = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    fun currentVersionName(context: Context): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun parseUpdate(row: JsonObject, currentCode: Long, currentName: String): AppUpdateInfo? {
        fun text(key: String) = row.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val code = row.get("version_code")?.asLong ?: return null
        if (code <= currentCode) return null
        val url = text("apk_url").toHttpUrlOrNull()
        require(url?.isHttps == true && url.username.isBlank() && url.password.isBlank()) { "لینک امن دانلود نسخه تنظیم نشده است" }
        val hash = text("apk_sha256").lowercase()
        require(Regex("[a-f0-9]{64}").matches(hash)) { "اطلاعات صحت فایل نسخه جدید کامل نیست" }
        val size = row.get("apk_size_bytes")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        require(size in 1..MAX_APK_BYTES) { "حجم معتبر نسخه جدید مشخص نشده است" }
        val type = classifyUpdate(currentName, text("version_name"))
        return AppUpdateInfo(code, text("version_name"), url.toString(), text("changelog"),
            row.get("is_mandatory")?.asBoolean == true || currentCode < (row.get("min_supported_version_code")?.asLong ?: 0L), type, hash, size)
    }
    suspend fun checkForUpdate(context: Context): AppUpdateInfo? = withContext(Dispatchers.IO) {
        Supabase.fetchLatestAppUpdate()?.let { parseUpdate(it, currentVersionCode(context), currentVersionName(context)) }
    }

    fun extractDriveFileId(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host !in setOf("drive.google.com", "drive.usercontent.google.com", "docs.google.com")) return null
        return parsed.queryParameter("id") ?: Regex("/d/([a-zA-Z0-9_-]{10,})").find(parsed.encodedPath)?.groupValues?.get(1)
    }
    fun isGoogleDriveUrl(url: String): Boolean = extractDriveFileId(url) != null
    fun toDirectDriveDownloadUrl(originalUrl: String): String = extractDriveFileId(originalUrl)?.let {
        "https://drive.usercontent.google.com/download?id=$it&export=download&confirm=t"
    } ?: originalUrl
    fun getEffectiveDownloadUrl(originalUrl: String): String = toDirectDriveDownloadUrl(originalUrl)

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Suppress("DEPRECATION")
    fun validateApk(context: Context, file: File, info: AppUpdateInfo) {
        require(file.isFile && file.length() == info.sizeBytes && sha256(file).equals(info.sha256, true)) { "فایل نسخه جدید ناقص یا نامعتبر است؛ دوباره دانلود کنید" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, flags)) { "فایل دانلودشده APK معتبر نیست" }
        require(archive.packageName == context.packageName && PackageInfoCompat.getLongVersionCode(archive) == info.versionCode && info.versionCode > currentVersionCode(context)) { "فایل دانلودشده متعلق به نسخه جدید این برنامه نیست" }
        fun signers(pi: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures)
            .orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        val expected = signers(context.packageManager.getPackageInfo(context.packageName, flags))
        require(expected.isNotEmpty() && signers(archive) == expected) { "امضای نسخه جدید با این برنامه مطابقت ندارد" }
        require((archive.applicationInfo?.minSdkVersion ?: 0) <= Build.VERSION.SDK_INT) { "این نسخه با اندروید دستگاه سازگار نیست" }
    }
    suspend fun downloadApk(context: Context, info: AppUpdateInfo, onProgress: (DownloadProgress) -> Unit): File = withContext(Dispatchers.IO) { downloadMutex.withLock {
        val dir = File(context.filesDir, "updates").apply { check(isDirectory || mkdirs()) }
        val destination = File(dir, "dentalpro-${info.versionCode}.apk")
        if (destination.isFile && runCatching { validateApk(context, destination, info) }.isSuccess) return@withLock destination
        val temporary = File.createTempFile("download-", ".part", dir)
        try {
            val client = Supabase.http.newBuilder().readTimeout(120, TimeUnit.SECONDS).followSslRedirects(false).build()
            val request = Request.Builder().url(getEffectiveDownloadUrl(info.apkUrl)).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful && response.request.url.isHttps) { "دانلود نسخه جدید ناموفق بود (${response.code})" }
                val body = checkNotNull(response.body) { "پاسخ دانلود خالی است" }
                check(body.contentType()?.subtype?.contains("html", true) != true) { "لینک دانلود به صفحه وب اشاره می‌کند؛ لینک مستقیم APK لازم است" }
                if (body.contentLength() >= 0L) check(body.contentLength() == info.sizeBytes) { "حجم فایل سرور با نسخه اعلام‌شده مطابقت ندارد" }
                var downloaded = 0L
                var lastReport = 0L
                val start = System.nanoTime()
                body.byteStream().use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(downloaded <= info.sizeBytes && downloaded <= MAX_APK_BYTES) { "حجم فایل دانلودی معتبر نیست" }
                        output.write(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now - lastReport >= 200_000_000L) {
                            onProgress(DownloadProgress(info.sizeBytes, downloaded, downloaded.toFloat() / info.sizeBytes, (downloaded * 1_000_000_000.0 / (now - start).coerceAtLeast(1)).toLong()))
                            lastReport = now
                        }
                    }
                } }
            }
            validateApk(context, temporary, info)
            check(temporary.renameTo(destination)) { "ذخیره فایل نسخه جدید ناموفق بود" }
            onProgress(DownloadProgress(info.sizeBytes, info.sizeBytes, 1f, 0L))
            dir.listFiles()?.filter { it != destination && it.extension == "apk" }?.forEach { it.delete() }
            destination
        } finally { temporary.delete() }
    } }
    @JvmName("downloadApkFloat")
    suspend fun downloadApk(context: Context, info: AppUpdateInfo, onProgressFloat: (Float) -> Unit): File = downloadApk(context, info) { p: DownloadProgress -> onProgressFloat(p.percent) }
    fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()
    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun installApk(context: Context, apkFile: File) {
        check(canInstallPackages(context)) { "اجازه نصب بروزرسانی را فعال کنید" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    fun formatBytes(bytes: Long): String = formatBytesFa(bytes, true)
    fun formatBytesFa(bytes: Long, isPersian: Boolean): String = when {
        bytes <= 0 -> if (isPersian) "نامشخص" else "Unknown"
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
    fun formatSpeed(bps: Long, isPersian: Boolean): String = if (bps <= 0) "—" else formatBytesFa(bps, isPersian) + "/s"
}
