package com.example.ui

import android.util.Log
import com.example.security.SecurityUtils
import com.example.security.SecureSecretStore
import com.example.ui.screens.toEnglishLong
import com.example.ui.screens.parseStagesList
import com.example.ui.screens.parseStageMarkers
import com.example.ui.screens.joinStageMarkers
import com.example.ui.screens.joinStagesList
import com.example.ui.screens.parseStageSessions
import com.example.ui.screens.joinStageSessions

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*
import java.io.File

import android.content.Context


data class BackupSelectionConfig(
    val includePatients: Boolean = true,
    val includeTreatments: Boolean = true,
    val includePayments: Boolean = true,
    val includeAppointments: Boolean = true,
    val includeInstallments: Boolean = true,
    val includeAccounting: Boolean = true,
    val includeSuppliersLab: Boolean = true,
    val includeSettingsTemplates: Boolean = true
)

data class SmsParseResult(
    val amount: Long,
    val detectedAccount: BankAccount?,
    val originalText: String,
    val isDeposit: Boolean
)

/**
 * یک سطر تخصیص مبلغ در پاپ‌آپ ثبت تراکنش پیامک بانکی (واریز/برداشت).
 * kind: "PATIENT" | "MISC_INCOME" | "CLINIC_EXPENSE" | "PERSONAL_SUB" | "PERSON"
 */
data class BankSmsAllocation(
    val kind: String,
    val targetId: Long? = null,
    val targetName: String = "",
    val amount: Long,
    val description: String = ""
)

data class CloudBackupRecord(
    val id: String,
    val timestamp: Long,
    val deviceName: String,
    val recordSummary: String,
    val jsonContent: String,
    val fileName: String = "",
    val isAutoSync: Boolean = false
)

class DentalViewModel(application: Application) : AndroidViewModel(application) {
    val database = AppDatabase.getDatabase(application)
    val appContext = application.applicationContext
    private val repository = DentalRepository(database) {
        application.getSharedPreferences("dental_pro_prefs", Context.MODE_PRIVATE).getString("auth_account_id", null)
    }
    var syncEngine = com.example.sync.SyncEngine(false, appContext, repository, database)

    /** قفل همگام‌سازی بوت‌استرپ — از اجرای همزمانِ چند pullAll در اثر رویدادهای گوناگون لاگین
     *  (بازگشت از OAuth CustomTab + LaunchedEffect auto-login + دکمه‌ی لاگین) جلوگیری می‌کند. */
    private val bootstrapMutex = Mutex()
    @Volatile private var bootstrapCompletedForAccount: String? = null

    val lastSyncedAt: StateFlow<Long?> get() = syncEngine.lastSyncedAt
    val lastSyncError: StateFlow<String?> get() = syncEngine.lastError
    // فقط از طریق collectAsStateWithLifecycle مصرف می‌شود؛ پس WhileSubscribed امن
    // است و از استارت بی‌جهت به دیتابیس کوئری نمی‌زند.
    val pendingSyncOpsCount: StateFlow<Int> = repository.pendingSyncOpsCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ------------------------------------------------------------------
    // Updates are checked at startup, manually, and by WorkManager.
    // Mandatory installation is an explicit server policy, independent of semver classification.
    /** Latest verified release metadata. */
    val availableAppUpdate = MutableStateFlow<com.example.util.AppUpdateManager.AppUpdateInfo?>(null)

    /** نسخهٔ جدید PATCH — فقط بنر متنی خود-حذف‌شونده در استارت اپ (روزی یک‌بار) */
    val patchUpdateNotice = MutableStateFlow<com.example.util.AppUpdateManager.AppUpdateInfo?>(null)

    fun dismissPatchUpdateNotice() {
        patchUpdateNotice.value = null
    }

    /**
     * چک بروزرسانی از سرور و اعمال سیاست اعلان بر اساس نوع نسخه.
     */
    val isCheckingAppUpdate = MutableStateFlow(false)
    val appUpdateStatus = MutableStateFlow<String?>(null)
    fun checkForAppUpdate(notify: Boolean = true) {
        if (isCheckingAppUpdate.value) return
        isCheckingAppUpdate.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = com.example.util.AppUpdateManager.checkForUpdate(appContext)
                if (info == null) {
                    appUpdateStatus.value = "برنامه به‌روز است."
                    availableAppUpdate.value = null
                } else {
                    appUpdateStatus.value = "نسخه ${info.versionName} آماده دریافت است."
                    availableAppUpdate.value = info
                    if (notify) com.example.reminders.showAppUpdateNotification(appContext, info.versionName, info.changelog, info.isMandatory)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { appUpdateStatus.value = "بررسی نسخه انجام نشد؛ اتصال را بررسی و دوباره تلاش کنید." }
            finally { isCheckingAppUpdate.value = false }
        }
    }

    init {
        // چک بروزرسانی با کمی تأخیر پس از اجرا (بدون مزاحمت برای بوت‌استرپ اپ)
        viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            checkForAppUpdate()
        }
        // کلیدهای API از سرور خوانده می‌شوند (منبع حقیقت سرور است؛ کش محلی فقط
        // برای کار در حالت آفلاین نگه داشته می‌شود).
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(2_000)
            com.example.ai.ServerApiSettings.pullFromServer(appContext)
        }
    }

    /** فراخوانی دستی تنظیمات API از سرور — از صفحهٔ تنظیمات کلیدها استفاده می‌شود. */
    suspend fun refreshApiSettingsFromServer(): Boolean =
        com.example.ai.ServerApiSettings.pullFromServer(appContext)
    
    private val prefs = application.getSharedPreferences("dental_pro_prefs", Context.MODE_PRIVATE)

    private fun loadOrMigrateSecret(name: String, legacyPreference: String, generateIfMissing: Boolean): String {
        SecureSecretStore.get(appContext, name)?.let { return it }
        val value = prefs.getString(legacyPreference, null)?.takeIf(String::isNotBlank)
            ?: if (generateIfMissing) SecurityUtils.generateRandomEncryptionKey() else ""
        if (value.isNotEmpty()) SecureSecretStore.put(appContext, name, value)
        prefs.edit().remove(legacyPreference).commit()
        return value
    }
    
    
    fun scanRecentSmsForBankTransactions(context: Context) {
        viewModelScope.launch {
            try {
                val cursor = context.contentResolver.query(
                    android.net.Uri.parse("content://sms/inbox"),
                    arrayOf("body", "date", "address"),
                    null,
                    null,
                    "date DESC LIMIT 20"
                )
                cursor?.use {
                    val bodyIndex = it.getColumnIndex("body")
                    while (it.moveToNext()) {
                        if (bodyIndex != -1) {
                            val body = it.getString(bodyIndex)
                            if (body.contains("واریز") || body.contains("انتقال") || body.contains("مبلغ")) {
                                incomingSmsFlow.emit(body)
                                break // Emit only the latest matching one for now
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        val incomingSmsFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    }
    
    // UI Language State: "fa" (Persian) or "en" (English)
    val isPersian = MutableStateFlow(true)

    // Clinic Logo & Dashboard Occasions
    /**
     * Uploads a freshly picked local logo file immediately and stores the resulting fileId on
     * ClinicSettings.logoFileId — the logo's only source of truth (read via settings.logoFileId
     * + SmartImage, not a local path). SyncEngine.pushPending() still contains its existing
     * opportunistic upload fallback for a pre-update logoFileId that is still a raw local path;
     * that fallback is left as-is and simply becomes unnecessary going forward.
     */
    fun updateClinicLogo(localFilePath: String) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            isMediaUploading.value = true
            try {
                // رفع باگ ۱-۵/۲-۲-۱: هر بار از تازه‌ترین نسخه‌ی دیتابیس بخوان — نه از کشِ
                // settings.value که ممکن است چند لحظه عقب‌تر باشد و تغییرات موازی را بپوشاند.
                val current = repository.getSettingsOnce() ?: settings.value
                repository.insertSettings(current.copy(logoFileId = localFilePath))
                val uploadedFileId = uploadLocalFileOrNull(localFilePath, com.example.util.StorageCategory.CLINIC)
                if (uploadedFileId != null) {
                    val fresh = repository.getSettingsOnce() ?: current
                    repository.insertSettings(fresh.copy(logoFileId = uploadedFileId))
                    com.example.ui.screens.deleteLocalStagingFileQuietly(localFilePath)
                }
                // چه آپلود موفق باشد چه نه، عملیات سینک در صف است؛ در صورت آفلاین‌بودن،
                // pushPending بعدی فایل را آپلود و مسیر محلی را با شناسه‌ی سروری جایگزین می‌کند.
                try {
                    syncEngine.pushPending()
                } catch (e: Exception) {
                    // Background sync will retry later
                }
            } finally {
                isMediaUploading.value = false
            }
        }
    }

    val showOccasions = MutableStateFlow(prefs.getBoolean("show_occasions", true))
    fun setShowOccasions(enabled: Boolean) {
        showOccasions.value = enabled
        prefs.edit().putBoolean("show_occasions", enabled).apply()
    }

    // UI Language State: "fa", "en", "ar", "tr", "az", "zh", "de"
    val selectedLanguage = MutableStateFlow(prefs.getString("app_language", "fa") ?: "fa")

    fun setLanguage(langCode: String) {
        val clean = langCode.lowercase().trim()
        selectedLanguage.value = clean
        isPersian.value = (clean == "fa")
        val supported = com.example.util.AppLocalization.SupportedLanguage.fromCode(clean)
        
        // Synchronize default calendar system based on language
        setCalendarType(supported.defaultCalendar)
        
        // Synchronize default currency based on language
        when (clean) {
            "fa" -> setCurrencyUnit("تومان", 1.0f)
            "ar" -> setCurrencyUnit("SAR", 1.0f)
            "tr" -> setCurrencyUnit("₺ TRY", 1.0f)
            "az" -> setCurrencyUnit("₼ AZN", 1.0f)
            "zh" -> setCurrencyUnit("¥ CNY", 1.0f)
            "de" -> setCurrencyUnit("€ EUR", 1.0f)
            "en" -> setCurrencyUnit("$ USD", 1.0f)
            else -> setCurrencyUnit("تومان", 1.0f)
        }

        prefs.edit().putString("app_language", clean).putBoolean("is_persian", clean == "fa").apply()
    }

    // User Roles & Granular Role Permissions
    val currentUserRole = MutableStateFlow(
        com.example.feature.auth.UserRole.fromCode(
            prefs.getString("current_user_role", com.example.feature.auth.UserRole.ASSISTANT.code)
                ?: com.example.feature.auth.UserRole.ASSISTANT.code
        ).let { stored ->
            // Doctor elevation is a process/session capability, never a durable login state.
            if (stored == com.example.feature.auth.UserRole.DOCTOR && prefs.getBoolean("pin_security_enabled", false)) {
                prefs.edit().putString("current_user_role", com.example.feature.auth.UserRole.ASSISTANT.code).commit()
                com.example.feature.auth.UserRole.ASSISTANT
            } else stored
        }
    )

    val assistantPermissions = MutableStateFlow(
        prefs.getInt("assistant_permissions", -1).let { mask ->
            if (mask >= 0) com.example.feature.auth.RolePermissions.fromBitMask(mask)
            else com.example.feature.auth.UserRole.defaultPermissionsFor(com.example.feature.auth.UserRole.ASSISTANT)
        }
    )

    fun hasModuleAccess(moduleKey: String): Boolean {
        val normalized = moduleKey.trim().lowercase(Locale.US)
        val knownModule = normalized in setOf(
            "dashboard", "patients", "appointments", "treatments", "clinical",
            "billing", "payments", "invoices", "expenses", "labs", "lab",
            "maintenance", "reports", "bank", "cheques", "settings", "vendors",
            "vendor_invoices", "personal", "templates", "calendar", "backup"
        )
        if (!knownModule) return false // Unknown authorization keys must fail closed for every role.

        val role = currentUserRole.value
        if (role == com.example.feature.auth.UserRole.DOCTOR) return true
        val p = assistantPermissions.value
        return when (normalized) {
            "dashboard" -> true
            "patients" -> p.canAccessPatients
            "appointments", "calendar" -> p.canAccessAppointments
            "treatments", "clinical", "templates" -> p.canAccessTreatments
            "billing", "payments", "invoices", "personal" -> p.canAccessFinancial
            "expenses" -> p.canAccessExpenses
            "labs", "lab" -> p.canAccessLabs
            "maintenance" -> p.canAccessMaintenance
            "reports" -> p.canAccessReports
            "bank" -> p.canAccessBankAccounts
            "cheques" -> p.canAccessCheques
            "settings" -> p.canAccessSettings
            "vendors", "vendor_invoices" -> p.canAccessFinancial
            "backup" -> false // Backup/restore is intentionally doctor-only.
            else -> false
        }
    }

    private fun authorizeDomain(
        moduleKey: String,
        action: String,
        isDelete: Boolean = false,
        doctorOnly: Boolean = false
    ): Boolean {
        val role = currentUserRole.value
        val permissions = when (role) {
            com.example.feature.auth.UserRole.DOCTOR -> com.example.feature.auth.UserRole.defaultPermissionsFor(role)
            com.example.feature.auth.UserRole.ASSISTANT -> assistantPermissions.value
        }
        val allowed = hasModuleAccess(moduleKey) &&
            (!isDelete || permissions.canDeleteRecords) &&
            (!doctorOnly || role == com.example.feature.auth.UserRole.DOCTOR)
        if (!allowed) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    repository.recordAudit(
                        AuditEvent(
                            actorRole = role.code,
                            action = action,
                            entityType = moduleKey,
                            result = "DENIED",
                            details = "Authorization policy rejected the operation"
                        )
                    )
                }
            }
        }
        return allowed
    }

    fun updateRolePermission(role: com.example.feature.auth.UserRole, permissionKey: String, isGranted: Boolean) {
        if (!authorizeDomain("settings", "UPDATE_ROLE_PERMISSION", doctorOnly = true)) return
        if (role == com.example.feature.auth.UserRole.ASSISTANT) {
            val current = assistantPermissions.value
            val updated = when (permissionKey) {
                "patients" -> current.copy(canAccessPatients = isGranted)
                "appointments" -> current.copy(canAccessAppointments = isGranted)
                "treatments" -> current.copy(canAccessTreatments = isGranted)
                "financial" -> current.copy(canAccessFinancial = isGranted)
                "expenses" -> current.copy(canAccessExpenses = isGranted)
                "labs" -> current.copy(canAccessLabs = isGranted)
                "maintenance" -> current.copy(canAccessMaintenance = isGranted)
                "reports" -> current.copy(canAccessReports = isGranted)
                "bank" -> current.copy(canAccessBankAccounts = isGranted)
                "cheques" -> current.copy(canAccessCheques = isGranted)
                "settings" -> current.copy(canAccessSettings = isGranted)
                "delete" -> current.copy(canDeleteRecords = isGranted)
                else -> current
            }
            assistantPermissions.value = updated
            prefs.edit().putInt("assistant_permissions", updated.toBitMask()).apply()
        }
    }

    fun requestRoleSwitch(targetRole: com.example.feature.auth.UserRole, pin: String? = null): Boolean {
        val current = currentUserRole.value
        if (current == targetRole) return true

        if (hasPinConfigured(targetRole)) {
            if (pin == null || !verifyRolePin(targetRole, pin)) {
                return false
            }
        }
        currentUserRole.value = targetRole
        prefs.edit().putString("current_user_role", targetRole.code).apply()
        return true
    }

    fun setUserRole(role: com.example.feature.auth.UserRole) {
        if (!hasPinConfigured(role)) {
            currentUserRole.value = role
            prefs.edit().putString("current_user_role", role.code).apply()
        }
    }

    fun setCachedUserProfile(name: String, phone: String, email: String, doctorName: String = "", accountId: String = "") {
        val editor = prefs.edit()
            .putString("auth_user_name", name)
            .putString("auth_user_phone", phone)
            .putString("auth_user_email", email)
            .putString("auth_doctor_name", doctorName)
        if (accountId.isNotBlank()) editor.putString("auth_account_id", accountId)
        editor.apply()
    }

    /**
     * به صراحت اعلام می‌کند که اطلاعات پروفایل کاربر (نام، نام پزشک، شماره موبایل،
     * سمت) کامل شده و یا از سند «users» سرور دریافت شده است. بدون این پرچم، در
     * بازگشت از OAuth گوگل — که هنوز GoogleProfileSetupScreen نمایش داده نشده —
     * LaunchedEffect فقط با دیدنِ نام/ایمیل کش‌شده گمان می‌کرد پروفایل کامل است
     * و کاربر را یک‌راست به داشبورد می‌فرستاد (با نقش/شماره ناقص) و این به شکل
     * «رفت‌وبرگشت/لوپ لاگین گوگل» دیده می‌شد.
     */
    fun markProfileSetupComplete() {
        prefs.edit().putBoolean("auth_profile_setup_complete", true).apply()
    }

    fun isProfileSetupComplete(): Boolean = prefs.getBoolean("auth_profile_setup_complete", false)

    fun getCurrentUserDisplayName(): String {
        val name = prefs.getString("auth_user_name", "") ?: ""
        if (name.isNotBlank()) return name
        val doc = settings.value.doctorName
        return if (doc.isNotBlank() && doc != "دکتر") doc else ""
    }

    fun getCurrentDoctorName(): String {
        val docName = prefs.getString("auth_doctor_name", "") ?: ""
        if (docName.isNotBlank()) return docName
        return getCurrentUserDisplayName() // fallback
    }

    fun getCurrentUserPhone(): String {
        val phone = prefs.getString("auth_user_phone", "") ?: ""
        if (phone.isNotBlank()) return phone
        return settings.value.clinicPhone
    }

    fun getCurrentUserEmail(): String {
        return prefs.getString("auth_user_email", "") ?: ""
    }

    /** رفعِ باگِ «لاگین با ایمیل موجود در دیتابیس»: فقط تطبیق ایمیل کافی نیست — اگر شناسه‌ی
     * حساب هم کش شده باشد، باید دقیقاً با حساب فعلی یکی باشد تا پروفایلِ کش‌شده «معتبر» تلقی شود. */
    fun getCachedAccountId(): String {
        return prefs.getString("auth_account_id", "") ?: ""
    }

    // Currency Unit State & Conversion Rate
    val currencyUnit = MutableStateFlow(prefs.getString("currency_unit", "تومان") ?: "تومان")
    val currencyConversionRate = MutableStateFlow(prefs.getFloat("currency_conversion_rate", 1.0f))

    fun setCurrencyUnit(unit: String, rateToToman: Float = 1.0f) {
        currencyUnit.value = unit
        currencyConversionRate.value = rateToToman
        prefs.edit()
            .putString("currency_unit", unit)
            .putFloat("currency_conversion_rate", rateToToman)
            .apply()
    }

    fun clearAllClinicalData() {
        if (!authorizeDomain("settings", "CLEAR_ALL_DATA", isDelete = true, doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            // Room clears every table in one transaction; the former hand-written list omitted
            // related clinical and accounting tables and could leave partial data behind.
            repository.clearAllData()
            repository.insertSettings(ClinicSettings(themeName = "NEON_CARBON", isDarkMode = true))
            seedReferenceData()
            seedDefaultCalendarEvents()
            selectedPatientId.value = null
            repository.recordAudit(
                AuditEvent(
                    actorRole = currentUserRole.value.code,
                    action = "CLEAR_ALL_DATA",
                    entityType = "database",
                    result = "ALLOWED"
                )
            )
        }
    }

    // Security & PIN State (Salted PBKDF2 Hashing)
    val isPinSecurityEnabled = MutableStateFlow(prefs.getBoolean("pin_security_enabled", false))
    private val pinHash = MutableStateFlow(prefs.getString("pin_hash", "") ?: "")
    private val pinSalt = MutableStateFlow(prefs.getString("pin_salt", "") ?: "")
    val isAppUnlocked = MutableStateFlow(false)
    val isGuestMode = MutableStateFlow(false)

    /**
     * سیگنالِ «اپ برای نمایش آماده است». اسپلشِ استاندارد تا وقتی این true شود
     * روی صفحه می‌ماند، تا کاربر به‌جای پرشِ از صفحهٔ خالی به صفحهٔ پرشده، یک
     * گذارِ نرم ببیند. با خواندنِ اولین مقدارِ تنظیمات (که یعنی دیتابیس واقعاً باز
     * و پاسخ‌گو شده) true می‌شود؛ یک سقفِ زمانی هم دارد تا هرگز گیر نکند.
     */
    val isBootReady = MutableStateFlow(false)

    // True while a photo/document/logo is being uploaded to cloud immediately after being
    // picked (see addPatientDocument, addPatient/addPatientFull, updatePatientPhoto,
    // updateClinicLogo) — UI can observe this to show an "uploading..." indicator.
    val isMediaUploading = MutableStateFlow(false)

    fun hasPinConfigured(role: com.example.feature.auth.UserRole): Boolean {
        if (role == com.example.feature.auth.UserRole.DOCTOR) {
            val hasDoctorHash = prefs.getString("pin_hash_DOCTOR", "")?.isNotBlank() == true || pinHash.value.isNotBlank()
            return isPinSecurityEnabled.value && hasDoctorHash
        }
        return prefs.getString("pin_hash_${role.code}", "")?.isNotBlank() == true
    }

    fun hasDoctorPinConfigured(): Boolean = hasPinConfigured(com.example.feature.auth.UserRole.DOCTOR)

    fun verifyRolePin(role: com.example.feature.auth.UserRole, pin: String): Boolean {
        val prefix = "${role.code.lowercase()}_pin"
        if (System.currentTimeMillis() < prefs.getLong("${prefix}_blocked_until", 0L)) return false
        val hash = prefs.getString("pin_hash_${role.code}", "")?.ifBlank {
            if (role == com.example.feature.auth.UserRole.DOCTOR) pinHash.value else ""
        } ?: ""
        val salt = prefs.getString("pin_salt_${role.code}", "")?.ifBlank {
            if (role == com.example.feature.auth.UserRole.DOCTOR) pinSalt.value else ""
        } ?: ""
        if (hash.isBlank() || salt.isBlank()) return false
        val success = SecurityUtils.verifyPin(pin, hash, salt)
        recordPinAttempt(prefix, success)
        if (success) upgradePinHashIfNeeded(pin, hash, salt, role.code)
        return success
    }

    fun verifyDoctorPin(pin: String): Boolean = verifyRolePin(com.example.feature.auth.UserRole.DOCTOR, pin)

    private fun recordPinAttempt(prefix: String, success: Boolean) {
        if (success) {
            prefs.edit()
                .remove("${prefix}_failed_attempts")
                .remove("${prefix}_blocked_until")
                .apply()
            return
        }
        val attempts = prefs.getInt("${prefix}_failed_attempts", 0) + 1
        val editor = prefs.edit().putInt("${prefix}_failed_attempts", attempts)
        if (attempts >= 5) {
            editor.putInt("${prefix}_failed_attempts", 0)
                .putLong("${prefix}_blocked_until", System.currentTimeMillis() + 5 * 60 * 1000L)
        }
        editor.apply()
    }

    fun configureRolePin(role: com.example.feature.auth.UserRole, pin: String) {
        require(pin.matches(Regex("\\d{4,12}"))) { "PIN must contain 4 to 12 digits" }
        val salt = SecurityUtils.generateSalt()
        val hash = SecurityUtils.hashPin(pin, salt)
        if (role == com.example.feature.auth.UserRole.DOCTOR) {
            isPinSecurityEnabled.value = true
            pinHash.value = hash
            pinSalt.value = salt
            prefs.edit()
                .putBoolean("pin_security_enabled", true)
                .putString("pin_hash_DOCTOR", hash)
                .putString("pin_salt_DOCTOR", salt)
                .remove("security_pin")
                .apply()
        } else {
            prefs.edit()
                .putString("pin_hash_${role.code}", hash)
                .putString("pin_salt_${role.code}", salt)
                .apply()
        }
        // Sync the hash/salt to ClinicSettings (same pattern as updateClinicSettings/
        // updateWorkingDays) so a second device, or a fresh install signed in to the same
        // clinic account, can unlock without the PIN having to be re-entered from scratch.
        if (role == com.example.feature.auth.UserRole.DOCTOR || role == com.example.feature.auth.UserRole.ASSISTANT) {
            viewModelScope.launch(Dispatchers.IO) {
                val current = settings.value
                val updated = if (role == com.example.feature.auth.UserRole.DOCTOR) {
                    current.copy(doctorPinHash = hash, doctorPinSalt = salt)
                } else {
                    current.copy(assistantPinHash = hash, assistantPinSalt = salt)
                }
                repository.insertSettings(updated)
                try {
                    syncEngine.pushPending()
                } catch (e: Exception) {
                    // Background sync will retry later
                }
            }
        }
    }

    fun configureDoctorPin(pin: String) = configureRolePin(com.example.feature.auth.UserRole.DOCTOR, pin)

    fun setPinSecurityEnabled(enabled: Boolean, pin: String) {
        val alreadyConfigured = hasDoctorPinConfigured()
        if (alreadyConfigured || !enabled) {
            if (!authorizeDomain("settings", "UPDATE_PIN_SECURITY", doctorOnly = true)) return
        }
        if (enabled) {
            // Routed through configureRolePin so the doctor PIN hash/salt also gets synced
            // to ClinicSettings (see section 3) instead of only ever living in local prefs.
            configureRolePin(com.example.feature.auth.UserRole.DOCTOR, pin)
        } else {
            isPinSecurityEnabled.value = false
            prefs.edit().putBoolean("pin_security_enabled", false).apply()
            isAppUnlocked.value = true
        }
    }

    fun setRolePin(role: com.example.feature.auth.UserRole, pin: String) {
        if (!authorizeDomain("settings", "UPDATE_PIN_SECURITY", doctorOnly = true)) return
        require(pin.matches(Regex("\\d{4,12}"))) { "PIN must contain 4 to 12 digits" }
        configureRolePin(role, pin)
    }

    fun unlockAppWithPin(enteredPin: String, role: com.example.feature.auth.UserRole = com.example.feature.auth.UserRole.DOCTOR): Boolean {
        if (System.currentTimeMillis() < prefs.getLong("app_pin_blocked_until", 0L)) return false
        var hash = prefs.getString("pin_hash_${role.code}", "")?.ifBlank {
            if (role == com.example.feature.auth.UserRole.DOCTOR) pinHash.value else ""
        } ?: ""
        var salt = prefs.getString("pin_salt_${role.code}", "")?.ifBlank {
            if (role == com.example.feature.auth.UserRole.DOCTOR) pinSalt.value else ""
        } ?: ""
        if (hash.isBlank() || salt.isBlank()) {
            // First unlock attempt on this device (e.g. a second phone signed in to the same
            // clinic account): fall back to the hash/salt pulled from the cloud copy of
            // ClinicSettings, same as the existing in-memory pinHash/pinSalt fallback above.
            val cloudSettings = settings.value
            val cloudHash = if (role == com.example.feature.auth.UserRole.DOCTOR) cloudSettings.doctorPinHash else cloudSettings.assistantPinHash
            val cloudSalt = if (role == com.example.feature.auth.UserRole.DOCTOR) cloudSettings.doctorPinSalt else cloudSettings.assistantPinSalt
            if (!cloudHash.isNullOrBlank() && !cloudSalt.isNullOrBlank()) {
                hash = cloudHash
                salt = cloudSalt
                prefs.edit()
                    .putString("pin_hash_${role.code}", hash)
                    .putString("pin_salt_${role.code}", salt)
                    .apply()
                if (role == com.example.feature.auth.UserRole.DOCTOR) {
                    isPinSecurityEnabled.value = true
                    pinHash.value = hash
                    pinSalt.value = salt
                    prefs.edit().putBoolean("pin_security_enabled", true).apply()
                }
            }
        }
        if (hash.isBlank() || salt.isBlank()) return false
        val isMatch = SecurityUtils.verifyPin(enteredPin, hash, salt)
        recordPinAttempt("app_pin", isMatch)
        if (isMatch) {
            upgradePinHashIfNeeded(enteredPin, hash, salt, role.code)
            currentUserRole.value = role
            prefs.edit().putString("current_user_role", role.code).apply()
            isAppUnlocked.value = true
        }
        return isMatch
    }

    private fun upgradePinHashIfNeeded(pin: String, previousHash: String, salt: String, roleCode: String = "DOCTOR") {
        if (!SecurityUtils.needsPinHashUpgrade(previousHash)) return
        val upgraded = SecurityUtils.hashPin(pin, salt)
        check(prefs.edit().putString("pin_hash_$roleCode", upgraded).commit()) { "Could not upgrade PIN protection" }
        if (roleCode == "DOCTOR") {
            pinHash.value = upgraded
        }
    }

    
    fun getSettings(): com.example.data.ClinicSettings {
        return settings.value
    }
    
    /**
     * تنها منبع تم خروجی‌ها: رنگ‌های تم فعال + پلهٔ سایز فونت کاربر.
     * هم مقدار را برمی‌گرداند و هم در [com.example.reports.ExportThemeSpec.current]
     * نصب می‌کند تا مسیرهایی که ViewModel ندارند (Worker، پرینت، تبدیل PDF به
     * تصویر) هم همان تم را بگیرند.
     */
    fun currentExportTheme(): com.example.reports.ExportThemeSpec {
        val palette = com.example.ui.theme.neonPaletteById(settings.value.themeName)
        val spec = com.example.reports.ExportThemeSpec.fromPalette(palette, settings.value.globalFontScaleIndex)
        com.example.reports.ExportThemeSpec.install(spec)
        return spec
    }

    fun lockApp() {
        // Always lock to lock screen if security is enabled.
        // Role will be chosen on unlock.
        if (isPinSecurityEnabled.value) {
            isAppUnlocked.value = false
        }
    }

    fun onAppBackgrounded() {
        prefs.edit().putLong("app_backgrounded_at", System.currentTimeMillis()).apply()
    }

    fun onAppForegrounded() {
        if (bootstrapCompletedForAccount != null && com.example.util.Supabase.currentSession != null && !isGuestMode.value) {
            viewModelScope.launch(Dispatchers.IO) {
                try { syncEngine.pushPending(); if (database.pendingSyncOpDao().countOnce() == 0) syncEngine.pullAll() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { Log.w("DentalViewModel", "Foreground sync will retry") }
            }
        }
        val backgroundedAt = prefs.getLong("app_backgrounded_at", 0L)
        if (backgroundedAt > 0L && System.currentTimeMillis() - backgroundedAt >= 2 * 60 * 1000L) {
            lockApp()
        }
        prefs.edit().remove("app_backgrounded_at").apply()
        // A due date is a reminder, never evidence of bank clearing.
        autoClearOverdueCheques()
    }

    // Auto Backup Settings
    val autoBackupInterval = MutableStateFlow(prefs.getString("auto_backup_interval", "DAILY") ?: "DAILY")
    val lastBackupTimestamp = MutableStateFlow(prefs.getLong("last_backup_time", System.currentTimeMillis()))

    fun setAutoBackupInterval(interval: String) {
        if (!authorizeDomain("backup", "UPDATE_BACKUP_INTERVAL", doctorOnly = true)) return
        autoBackupInterval.value = interval
        prefs.edit().putString("auto_backup_interval", interval).apply()
    }

    fun recordBackupPerformed() {
        val now = System.currentTimeMillis()
        lastBackupTimestamp.value = now
        prefs.edit().putLong("last_backup_time", now).apply()
    }

    init {
        // Migrate legacy plain text pin if present
        if (prefs.contains("security_pin")) {
            val legacyPin = prefs.getString("security_pin", "") ?: ""
            if (legacyPin.isNotBlank()) {
                val salt = SecurityUtils.generateSalt()
                val hash = SecurityUtils.hashPin(legacyPin, salt)
                prefs.edit()
                    .putString("pin_hash_DOCTOR", hash)
                    .putString("pin_salt_DOCTOR", salt)
                    .remove("security_pin")
                    .apply()
                pinHash.value = hash
                pinSalt.value = salt
            }
        }
        loadCloudBackups()
    }

    // ---- Local Encrypted Backup: dedicated, user-chosen passphrase ----
    // Kept independent from any Google/Drive secret so a local backup file can
    // always be restored later - even on a different device or after a
    // reinstall - as long as the clinic still knows this passphrase.

    
    // ---- Local Encrypted Backup & Cloud Sync Engine ----
    val localBackupSecret = MutableStateFlow(
        loadOrMigrateSecret("local_backup_secret", "local_backup_secret", generateIfMissing = false)
    )

    fun setLocalBackupSecret(secret: String) {
        if (!authorizeDomain("backup", "SET_LOCAL_BACKUP_SECRET", doctorOnly = true)) return
        require(secret.length >= 12) { "Backup passphrase must be at least 12 characters" }
        SecureSecretStore.put(appContext, "local_backup_secret", secret)
        localBackupSecret.value = secret
    }

    val customBackupPassword = MutableStateFlow(
        SecureSecretStore.get(appContext, "custom_backup_password") ?: ""
    )

    fun getEffectiveBackupEncryptionSecret(): String {
        val pass = customBackupPassword.value.trim()
        if (pass.isNotBlank()) {
            val email = googleDriveAccountEmail.value.trim()
            val combined = "$email::$pass"
            return com.example.security.SecurityUtils.sha256(combined).substring(0, 32)
        }
        return cloudEncryptionKey.value
    }

    fun setCustomBackupPassword(password: String) {
        val p = password.trim()
        customBackupPassword.value = p
        SecureSecretStore.put(appContext, "custom_backup_password", p)
    }

    private fun requireLocalBackupSecret(): String {
        val p = customBackupPassword.value.trim()
        if (p.isNotBlank()) return getEffectiveBackupEncryptionSecret()

        val secret = localBackupSecret.value.ifBlank { cloudEncryptionKey.value }
        if (secret.length >= 12) return secret
        val fallback = "DentalClinicBackupKey2026!"
        SecureSecretStore.put(appContext, "local_backup_secret", fallback)
        localBackupSecret.value = fallback
        return fallback
    }



    val isCloudSyncEnabled = MutableStateFlow(prefs.getBoolean("cloud_sync_enabled", false))
    val lastCloudSyncTimestamp = MutableStateFlow(prefs.getLong("last_cloud_sync_time", 0L))
    val cloudBackups = MutableStateFlow<List<CloudBackupRecord>>(emptyList())

    val googleDriveAccountEmail = MutableStateFlow(
        prefs.getString("google_drive_account_email", "") ?: ""
    )
    val googleDriveAccountName = MutableStateFlow(
        prefs.getString("google_drive_account_name", "") ?: ""
    )
    val isGoogleDriveConnected = googleDriveAccountEmail.map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val cloudServerUrl = MutableStateFlow(prefs.getString("cloud_server_url", "") ?: "")
    val cloudAccountName = MutableStateFlow(prefs.getString("cloud_account_name", "") ?: "")
    val cloudBackupFrequency = MutableStateFlow(prefs.getString("cloud_backup_frequency", "DAILY") ?: "DAILY")
    val cloudEncryptionKey = MutableStateFlow(
        loadOrMigrateSecret("cloud_encryption_key", "cloud_encryption_key", generateIfMissing = true)
    )
    private val cloudApiToken = MutableStateFlow(
        loadOrMigrateSecret("cloud_api_token", "cloud_api_token", generateIfMissing = false)
    )
    val cloudHasApiToken = cloudApiToken.map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, cloudApiToken.value.isNotBlank())
    val cloudIncludePhotos = MutableStateFlow(prefs.getBoolean("cloud_include_photos", false))
    val cloudMaxHistory = MutableStateFlow(prefs.getInt("cloud_max_history", 10))

    fun setGoogleDriveAccount(email: String, name: String, token: String) {
        googleDriveAccountEmail.value = email
        googleDriveAccountName.value = name
        cloudAccountName.value = email
        cloudApiToken.value = token
        prefs.edit()
            .putString("google_drive_account_email", email)
            .putString("google_drive_account_name", name)
            .putString("cloud_account_name", email)
            .apply()
        SecureSecretStore.put(appContext, "cloud_api_token", token)
        refreshGoogleDriveBackups()
    }

    fun disconnectGoogleDrive() {
        viewModelScope.launch {
            
            googleDriveAccountEmail.value = ""
            googleDriveAccountName.value = ""
            cloudApiToken.value = ""
            prefs.edit()
                .remove("google_drive_account_email")
                .remove("google_drive_account_name")
                .apply()
            SecureSecretStore.put(appContext, "cloud_api_token", "")
            cloudBackups.value = emptyList()
        }
    }

    fun refreshGoogleDriveBackups() { }

    fun updateCloudSettings(
        serverUrl: String,
        accountName: String,
        frequency: String,
        encryptionKey: String,
        apiToken: String,
        includePhotos: Boolean,
        maxHistory: Int
    ) {
        if (!authorizeDomain("backup", "UPDATE_CLOUD_SETTINGS", doctorOnly = true)) return
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val effectiveToken = apiToken.trim().ifBlank { cloudApiToken.value }
        val effectiveKey = if (encryptionKey.isBlank()) SecurityUtils.generateRandomEncryptionKey() else encryptionKey
        cloudServerUrl.value = normalizedUrl
        cloudAccountName.value = accountName.trim()
        cloudBackupFrequency.value = frequency
        cloudEncryptionKey.value = effectiveKey
        cloudApiToken.value = effectiveToken
        cloudIncludePhotos.value = includePhotos
        cloudMaxHistory.value = maxHistory.coerceIn(1, 100)
        SecureSecretStore.put(appContext, "cloud_encryption_key", effectiveKey)
        SecureSecretStore.put(appContext, "cloud_api_token", effectiveToken)

        prefs.edit()
            .putString("cloud_server_url", normalizedUrl)
            .putString("cloud_account_name", accountName.trim())
            .putString("cloud_backup_frequency", frequency)
            .remove("cloud_encryption_key")
            .remove("cloud_api_token")
            .putBoolean("cloud_include_photos", includePhotos)
            .putInt("cloud_max_history", cloudMaxHistory.value)
            .apply()
    }

    private fun loadCloudBackups() {
        try {
            val json = prefs.getString("cloud_backups_list_json", "[]") ?: "[]"
            val array = org.json.JSONArray(json)
            val list = mutableListOf<CloudBackupRecord>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(CloudBackupRecord(
                    id = obj.optString("id", System.currentTimeMillis().toString()),
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    deviceName = obj.optString("deviceName", "دستگاه مطب"),
                    recordSummary = obj.optString("recordSummary", ""),
                    jsonContent = obj.optString("jsonContent", ""),
                    isAutoSync = obj.optBoolean("isAutoSync", false)
                ))
            }
            cloudBackups.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCloudBackupsList(list: List<CloudBackupRecord>) {
        try {
            val array = org.json.JSONArray()
            list.forEach { rec ->
                val obj = org.json.JSONObject()
                obj.put("id", rec.id)
                obj.put("timestamp", rec.timestamp)
                obj.put("deviceName", rec.deviceName)
                obj.put("recordSummary", rec.recordSummary)
                obj.put("jsonContent", rec.jsonContent)
                obj.put("isAutoSync", rec.isAutoSync)
                array.put(obj)
            }
            prefs.edit().putString("cloud_backups_list_json", array.toString()).apply()
            cloudBackups.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleCloudSync(enabled: Boolean) {
        if (!authorizeDomain("backup", "TOGGLE_CLOUD_SYNC", doctorOnly = true)) return
        isCloudSyncEnabled.value = enabled
        prefs.edit().putBoolean("cloud_sync_enabled", enabled).apply()
    }

    suspend fun uploadBackupToCloud(config: BackupSelectionConfig = BackupSelectionConfig(), isAuto: Boolean = false): CloudBackupRecord { return CloudBackupRecord("", 0, "", "", "") }

    suspend fun downloadCloudBackupPayload(recordId: String): String { return "" }

    private fun cloudHttpClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun deleteCloudBackup(recordId: String) { }

    fun clearCloudBackups() { }

    fun exportDatabaseToFile(context: Context, uri: android.net.Uri) { }

    fun importDatabaseFromFile(context: Context, uri: android.net.Uri) { }

    /**
     * Shared safe-install routine for a decrypted [EncryptedBackupManager.RestoreBundle],
     * used by both the local-file restore and the Google Drive restore path: keeps an
     * encrypted rollback snapshot, stages the new database, and rolls back automatically
     * if anything fails partway through.
     */
    private suspend fun installRestoredDatabase(bundle: Any) { }


    suspend fun generateBackupJson(config: BackupSelectionConfig = BackupSelectionConfig()): String {
        check(authorizeDomain("backup", "GENERATE_JSON_BACKUP", doctorOnly = true)) { "Doctor authorization is required" }
        val root = org.json.JSONObject()
        root.put("timestamp", System.currentTimeMillis())
        root.put("app", "DentalPro ERP")
        root.put("version", "2.5")

        // 1. Export Patients
        if (config.includePatients) {
            val patientArray = org.json.JSONArray()
            patients.value.forEach { p ->
                val obj = org.json.JSONObject()
                obj.put("id", p.id)
                obj.put("patientCode", p.patientCode)
                obj.put("name", p.name)
                obj.put("phone", p.phone)
                obj.put("email", p.email)
                obj.put("address", p.address)
                obj.put("medicalHistory", p.medicalHistory)
                obj.put("plannedTreatmentIds", p.plannedTreatmentIds)
                obj.put("note", p.note)
                obj.put("referrer", p.referrer)
                obj.put("photoFileId", p.photoFileId ?: "")
                obj.put("documentUris", p.documentUris)
                obj.put("createdAt", p.createdAt)
                obj.put("gender", p.gender)
                obj.put("medicalAlerts", p.medicalAlerts)
                obj.put("selfDeclarationJson", p.selfDeclarationJson)
                obj.put("age", p.age)
                obj.put("birthDate", p.birthDate)
                obj.put("preferredMessenger", p.preferredMessenger)
                obj.put("fatherName", p.fatherName)
                obj.put("nationalId", p.nationalId)
                obj.put("signatureFileId", p.signatureFileId ?: "")
                patientArray.put(obj)
            }
            root.put("patients", patientArray)
        }

        // 2. Export Treatments
        if (config.includeTreatments) {
            val treatmentArray = org.json.JSONArray()
            allTreatments.value.forEach { t ->
                val obj = org.json.JSONObject()
                obj.put("id", t.id)
                obj.put("patientId", t.patientId)
                obj.put("title", t.title)
                obj.put("cost", t.cost)
                obj.put("discount", t.discount)
                obj.put("selectedTeeth", t.selectedTeeth)
                obj.put("date", t.date)
                obj.put("notes", t.notes)
                obj.put("status", t.status)
                obj.put("stages", t.stages)
                obj.put("completedStages", t.completedStages)
                obj.put("ignoredStages", t.ignoredStages)
                treatmentArray.put(obj)
            }
            root.put("treatments", treatmentArray)
        }

        // 3. Export Payments
        if (config.includePayments) {
            val paymentArray = org.json.JSONArray()
            allPayments.value.forEach { p ->
                val obj = org.json.JSONObject()
                obj.put("id", p.id)
                obj.put("patientId", p.patientId)
                obj.put("amount", p.amount)
                obj.put("type", p.type)
                obj.put("bankAccountId", p.bankAccountId ?: -1L)
                obj.put("chequeNumber", p.chequeNumber ?: "")
                obj.put("chequeDueDate", p.chequeDueDate ?: -1L)
                obj.put("date", p.date)
                obj.put("isCleared", p.isCleared)
                obj.put("sayadiId", p.sayadiId ?: "")
                obj.put("chequeBank", p.chequeBank ?: "")
                obj.put("chequeDrawer", p.chequeDrawer ?: "")
                obj.put("chequePhotoFileId", p.chequePhotoFileId ?: "")
                obj.put("chequeStatus", p.chequeStatus)
                obj.put("transferredTo", p.transferredTo ?: "")
                obj.put("transferDate", p.transferDate ?: -1L)
                // ارجاع اسنادِ انتقال و تأییدهای بانکی — بدون این‌ها، بازیابیِ پشتیبان
                // چکِ منتقل‌شده‌ای می‌ساخت که دیگر به سند هزینه‌اش وصل نبود.
                obj.put("transferExpensePaymentId", p.transferExpensePaymentId ?: -1L)
                obj.put("transferExpenseId", p.transferExpenseId ?: -1L)
                obj.put("transferTransactionId", p.transferTransactionId ?: -1L)
                obj.put("transferChildrenExpenseId", p.transferChildrenExpenseId ?: -1L)
                obj.put("bankReceiptConfirmed", p.bankReceiptConfirmed)
                obj.put("bankTransferConfirmed", p.bankTransferConfirmed)
                paymentArray.put(obj)
            }
            root.put("payments", paymentArray)
        }

        // 4. Export Appointments
        if (config.includeAppointments) {
            val apptArray = org.json.JSONArray()
            appointments.value.forEach { a ->
                val obj = org.json.JSONObject()
                obj.put("id", a.id)
                obj.put("patientId", a.patientId)
                obj.put("patientName", a.patientName)
                obj.put("date", a.date)
                obj.put("treatmentType", a.treatmentType)
                obj.put("plannedTreatmentIds", a.plannedTreatmentIds)
                obj.put("note", a.note)
                obj.put("isCompleted", a.isCompleted)
                obj.put("isArchived", a.isArchived)
                obj.put("dischargeSent", a.dischargeSent)
                obj.put("dischargeMethod", a.dischargeMethod)
                obj.put("unit", a.unit ?: "UNIT_1")
                apptArray.put(obj)
            }
            root.put("appointments", apptArray)
        }

        // 5. Export Installments
        if (config.includeInstallments) {
            val instArray = org.json.JSONArray()
            allInstallments.value.forEach { inst ->
                val obj = org.json.JSONObject()
                obj.put("id", inst.id)
                obj.put("patientId", inst.patientId)
                obj.put("treatmentId", inst.treatmentId ?: -1L)
                obj.put("title", inst.title)
                obj.put("amount", inst.amount)
                obj.put("dueDate", inst.dueDate)
                obj.put("isPaid", inst.isPaid)
                obj.put("paidDate", inst.paidDate ?: -1L)
                obj.put("note", inst.note)
                instArray.put(obj)
            }
            root.put("installments", instArray)
        }

        // 6. Export Accounting
        if (config.includeAccounting) {
            val expArray = org.json.JSONArray()
            clinicExpenses.value.forEach { exp ->
                val obj = org.json.JSONObject()
                obj.put("id", exp.id)
                obj.put("category", exp.category)
                obj.put("amount", exp.amount)
                obj.put("description", exp.description)
                obj.put("date", exp.date)
                obj.put("invoicePhotoFileId", exp.invoicePhotoFileId ?: "")
                obj.put("paymentMethod", exp.paymentMethod)
                obj.put("subAccountName", exp.subAccountName)
                obj.put("chequeNumber", exp.chequeNumber ?: "")
                obj.put("labOrderId", exp.labOrderId ?: -1L)
                expArray.put(obj)
            }
            root.put("clinicExpenses", expArray)

            val pTxArray = org.json.JSONArray()
            personalTransactions.value.forEach { tx ->
                val obj = org.json.JSONObject()
                obj.put("id", tx.id)
                obj.put("type", tx.type)
                obj.put("subAccountName", tx.subAccountName)
                obj.put("bankAccountId", tx.bankAccountId ?: -1L)
                obj.put("amount", tx.amount)
                obj.put("description", tx.description)
                obj.put("paymentMethod", tx.paymentMethod)
                obj.put("chequeNumber", tx.chequeNumber ?: "")
                obj.put("date", tx.date)
                pTxArray.put(obj)
            }
            root.put("personalTransactions", pTxArray)

            val bankArray = org.json.JSONArray()
            bankAccounts.value.forEach { b ->
                val obj = org.json.JSONObject()
                obj.put("id", b.id)
                obj.put("bankName", b.bankName)
                obj.put("cardHolder", b.cardHolder)
                obj.put("cardNumber", b.cardNumber)
                obj.put("accountNumber", b.accountNumber)
                obj.put("balance", b.balance)
                obj.put("smsKeywords", b.smsKeywords)
                obj.put("isHidden", b.isHidden)
                obj.put("sortOrder", b.sortOrder)
                bankArray.put(obj)
            }
            root.put("bankAccounts", bankArray)

            val subAccArray = org.json.JSONArray()
            personalSubAccounts.value.forEach { sa ->
                val obj = org.json.JSONObject()
                obj.put("id", sa.id)
                obj.put("name", sa.name)
                obj.put("category", sa.category)
                obj.put("isQuickSelect", sa.isQuickSelect)
                subAccArray.put(obj)
            }
            root.put("personalSubAccounts", subAccArray)

            val fixedIncArray = org.json.JSONArray()
            fixedIncomes.value.forEach { fi ->
                val obj = org.json.JSONObject()
                obj.put("id", fi.id)
                obj.put("title", fi.title)
                obj.put("amount", fi.amount)
                obj.put("dayOfMonth", fi.dayOfMonth)
                obj.put("bankAccountId", fi.bankAccountId ?: -1L)
                obj.put("bankAccountName", fi.bankAccountName)
                obj.put("subAccountName", fi.subAccountName)
                obj.put("lastProcessedMonth", fi.lastProcessedMonth)
                obj.put("isActive", fi.isActive)
                fixedIncArray.put(obj)
            }
            root.put("fixedIncomes", fixedIncArray)

            val childExpArray = org.json.JSONArray()
            childrenExpenses.value.forEach { ce ->
                val obj = org.json.JSONObject()
                obj.put("id", ce.id)
                obj.put("childName", ce.childName)
                obj.put("title", ce.title)
                obj.put("amount", ce.amount)
                obj.put("date", ce.date)
                childExpArray.put(obj)
            }
            root.put("childrenExpenses", childExpArray)
        }

        // 7. Export Suppliers & Lab
        if (config.includeSuppliersLab) {
            val vendorArray = org.json.JSONArray()
            vendors.value.forEach { v ->
                val obj = org.json.JSONObject()
                obj.put("id", v.id)
                obj.put("name", v.name)
                obj.put("company", v.company)
                obj.put("phone", v.phone)
                obj.put("itemType", v.itemType)
                obj.put("totalOrders", v.totalOrders)
                obj.put("totalPaid", v.totalPaid)
                obj.put("invoiceNumber", v.invoiceNumber ?: "")
                obj.put("invoiceDetails", v.invoiceDetails ?: "")
                obj.put("invoicePhotoFileId", v.invoicePhotoFileId ?: "")
                vendorArray.put(obj)
            }
            root.put("vendors", vendorArray)

            val labOrderArray = org.json.JSONArray()
            labOrders.value.forEach { lo ->
                val obj = org.json.JSONObject()
                obj.put("id", lo.id)
                obj.put("labName", lo.labName)
                obj.put("patientName", lo.patientName)
                obj.put("toothNumber", lo.toothNumber)
                obj.put("prosthesisType", lo.prosthesisType)
                obj.put("cost", lo.cost)
                obj.put("orderDate", lo.orderDate)
                obj.put("dueDate", lo.dueDate)
                obj.put("status", lo.status)
                labOrderArray.put(obj)
            }
            root.put("labOrders", labOrderArray)

            val labServiceArray = org.json.JSONArray()
            labServices.value.forEach { ls ->
                val obj = org.json.JSONObject()
                obj.put("id", ls.id)
                obj.put("labName", ls.labName)
                obj.put("serviceName", ls.serviceName)
                obj.put("price", ls.price)
                obj.put("notes", ls.notes)
                obj.put("frameDeliveryDays", ls.frameDeliveryDays)
                obj.put("finalDeliveryDays", ls.finalDeliveryDays)
                labServiceArray.put(obj)
            }
            root.put("labServices", labServiceArray)

            val maintArray = org.json.JSONArray()
            maintenanceList.value.forEach { m ->
                val obj = org.json.JSONObject()
                obj.put("id", m.id)
                obj.put("equipmentName", m.equipmentName)
                obj.put("description", m.description)
                obj.put("cost", m.cost)
                obj.put("date", m.date)
                obj.put("nextServiceDate", m.nextServiceDate ?: -1L)
                obj.put("technicianName", m.technicianName ?: "")
                obj.put("invoiceNumber", m.invoiceNumber ?: "")
                maintArray.put(obj)
            }
            root.put("maintenanceList", maintArray)
        }

        // 8. Export Templates & Settings
        if (config.includeSettingsTemplates) {
            val templateArray = org.json.JSONArray()
            templates.value.forEach { temp ->
                val obj = org.json.JSONObject()
                obj.put("id", temp.id)
                obj.put("title", temp.title)
                obj.put("defaultCost", temp.defaultCost)
                obj.put("defaultNotes", temp.defaultNotes)
                obj.put("stages", temp.stages)
                obj.put("isStarred", temp.isStarred)
                obj.put("sortOrder", temp.sortOrder)
                templateArray.put(obj)
            }
            root.put("templates", templateArray)

            val setObj = org.json.JSONObject()
            val st = settings.value
            setObj.put("clinicName", st.clinicName)
            setObj.put("doctorName", st.doctorName)
            setObj.put("childrenNames", st.childrenNames)
            setObj.put("staffNames", st.staffNames)
            setObj.put("showSummaryCard", st.showSummaryCard)
            setObj.put("showQuickActions", st.showQuickActions)
            setObj.put("showCharts", st.showCharts)
            setObj.put("smsSampleTemplate", st.smsSampleTemplate)
            setObj.put("themeName", st.themeName)
            setObj.put("isDarkMode", st.isDarkMode)
            setObj.put("clinicalFontSizePt", st.clinicalFontSizePt)
            setObj.put("globalFontScaleIndex", st.globalFontScaleIndex)
            setObj.put("appointmentTemplate", st.appointmentTemplate)
            setObj.put("invoiceTemplate", st.invoiceTemplate)
            setObj.put("treatmentPlanTemplate", st.treatmentPlanTemplate)
            setObj.put("installmentTemplate", st.installmentTemplate)
            setObj.put("postOpTemplate", st.postOpTemplate)
            setObj.put("endoAnatomyOverrides", st.endoAnatomyOverrides)
            root.put("clinicSettings", setObj)
        }

        recordBackupPerformed()
        return root.toString(2)
    }

    suspend fun restoreFromBackupJson(jsonStr: String, config: BackupSelectionConfig = BackupSelectionConfig()): Boolean {
        if (!authorizeDomain("backup", "RESTORE_JSON_BACKUP", doctorOnly = true)) return false
        return try {
            repository.inTransaction {
            val decryptedJson = try {
                SecurityUtils.decryptPayload(jsonStr, localBackupSecret.value)
            } catch (e: Exception) {
                jsonStr
            }
            val root = org.json.JSONObject(decryptedJson)

            // Restore Patients
            if (config.includePatients && root.has("patients")) {
                val array = root.getJSONArray("patients")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    repository.insertPatient(Patient(
                        id = obj.optLong("id", 0L),
                        patientCode = obj.optString("patientCode", ""),
                        name = obj.optString("name", ""),
                        phone = obj.optString("phone", ""),
                        email = obj.optString("email", ""),
                        address = obj.optString("address", ""),
                        medicalHistory = obj.optString("medicalHistory", ""),
                        plannedTreatmentIds = obj.optString("plannedTreatmentIds", ""),
                        note = obj.optString("note", ""),
                        referrer = obj.optString("referrer", ""),
                        // "photoFileId" is the current key; "photoUri" is read for backward
                        // compatibility with backups made before the fileId-only refactor.
                        photoFileId = (obj.optString("photoFileId", "").ifBlank { obj.optString("photoUri", "") }).ifBlank { null },
                        documentUris = obj.optString("documentUris", ""),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        gender = obj.optString("gender", "مرد"),
                        medicalAlerts = obj.optString("medicalAlerts", ""),
                        selfDeclarationJson = obj.optString("selfDeclarationJson", ""),
                        age = obj.optInt("age", 0),
                        birthDate = obj.optString("birthDate", ""),
                        preferredMessenger = obj.optString("preferredMessenger", "واتساپ"),
                        fatherName = obj.optString("fatherName", ""),
                        nationalId = obj.optString("nationalId", ""),
                        signatureFileId = obj.optString("signatureFileId", "").ifBlank { null }
                    ))
                }
            }

            // Restore Treatments
            if (config.includeTreatments && root.has("treatments")) {
                val array = root.getJSONArray("treatments")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    repository.insertTreatment(Treatment(
                        id = obj.optLong("id", 0L),
                        patientId = obj.optLong("patientId", 0L),
                        title = obj.optString("title", ""),
                        cost = obj.optLong("cost", 0L),
                        discount = obj.optLong("discount", 0L),
                        selectedTeeth = obj.optString("selectedTeeth", ""),
                        date = obj.optLong("date", System.currentTimeMillis()),
                        notes = obj.optString("notes", ""),
                        status = obj.optString("status", "PLANNED"),
                        stages = obj.optString("stages", ""),
                        completedStages = obj.optString("completedStages", ""),
                        ignoredStages = obj.optString("ignoredStages", "")
                    ))
                }
            }

            // Restore Payments
            if (config.includePayments && root.has("payments")) {
                val array = root.getJSONArray("payments")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val bId = obj.optLong("bankAccountId", -1L)
                    val chDueDate = obj.optLong("chequeDueDate", -1L)
                    val trDate = obj.optLong("transferDate", -1L)
                    repository.insertPayment(Payment(
                        id = obj.optLong("id", 0L),
                        patientId = obj.optLong("patientId", 0L),
                        amount = obj.optLong("amount", 0L),
                        type = obj.optString("type", "CASH"),
                        bankAccountId = if (bId > 0) bId else null,
                        chequeNumber = if (obj.optString("chequeNumber", "").isNotBlank()) obj.optString("chequeNumber") else null,
                        chequeDueDate = if (chDueDate > 0) chDueDate else null,
                        date = obj.optLong("date", System.currentTimeMillis()),
                        isCleared = obj.optBoolean("isCleared", true),
                        sayadiId = if (obj.optString("sayadiId", "").isNotBlank()) obj.optString("sayadiId") else null,
                        chequeBank = if (obj.optString("chequeBank", "").isNotBlank()) obj.optString("chequeBank") else null,
                        chequeDrawer = if (obj.optString("chequeDrawer", "").isNotBlank()) obj.optString("chequeDrawer") else null,
                        chequePhotoFileId = if (obj.optString("chequePhotoFileId", "").isNotBlank()) obj.optString("chequePhotoFileId") else null,
                        chequeStatus = obj.optString("chequeStatus", "PENDING"),
                        transferredTo = if (obj.optString("transferredTo", "").isNotBlank()) obj.optString("transferredTo") else null,
                        transferDate = if (trDate > 0) trDate else null,
                        transferExpensePaymentId = obj.optLong("transferExpensePaymentId", -1L).takeIf { it > 0 },
                        transferExpenseId = obj.optLong("transferExpenseId", -1L).takeIf { it > 0 },
                        transferTransactionId = obj.optLong("transferTransactionId", -1L).takeIf { it > 0 },
                        transferChildrenExpenseId = obj.optLong("transferChildrenExpenseId", -1L).takeIf { it > 0 },
                        bankReceiptConfirmed = obj.optBoolean("bankReceiptConfirmed", false),
                        bankTransferConfirmed = obj.optBoolean("bankTransferConfirmed", false)
                    ))
                }
            }

            // Restore Appointments
            if (config.includeAppointments && root.has("appointments")) {
                val array = root.getJSONArray("appointments")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    repository.insertAppointment(Appointment(
                        id = obj.optLong("id", 0L),
                        patientId = obj.optLong("patientId", 0L),
                        patientName = obj.optString("patientName", ""),
                        date = obj.optLong("date", System.currentTimeMillis()),
                        treatmentType = obj.optString("treatmentType", ""),
                        plannedTreatmentIds = obj.optString("plannedTreatmentIds", ""),
                        note = obj.optString("note", ""),
                        referrer = obj.optString("referrer", ""),
                        isCompleted = obj.optBoolean("isCompleted", false),
                        isArchived = obj.optBoolean("isArchived", false),
                        dischargeSent = obj.optBoolean("dischargeSent", false),
                        dischargeMethod = obj.optString("dischargeMethod", ""),
                        unit = obj.optString("unit", "UNIT_1"),
                        durationMinutes = obj.optInt("durationMinutes", 30).coerceAtLeast(10)
                    ))
                }
            }

            // Restore Installments
            if (config.includeInstallments && root.has("installments")) {
                val array = root.getJSONArray("installments")
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val trId = obj.optLong("treatmentId", -1L)
                    val pDate = obj.optLong("paidDate", -1L)
                    repository.insertInstallment(Installment(
                        id = obj.optLong("id", 0L),
                        patientId = obj.optLong("patientId", 0L),
                        treatmentId = if (trId > 0) trId else null,
                        title = obj.optString("title", "قسط درمان"),
                        amount = obj.optLong("amount", 0L),
                        dueDate = obj.optLong("dueDate", System.currentTimeMillis()),
                        isPaid = obj.optBoolean("isPaid", false),
                        paidDate = if (pDate > 0) pDate else null,
                        note = obj.optString("note", "")
                    ))
                }
            }

            // Restore Accounting
            if (config.includeAccounting) {
                if (root.has("clinicExpenses")) {
                    val array = root.getJSONArray("clinicExpenses")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val lId = obj.optLong("labOrderId", -1L)
                        repository.insertExpense(ClinicExpense(
                            id = obj.optLong("id", 0L),
                            category = obj.optString("category", "سایر"),
                            amount = obj.optLong("amount", 0L),
                            description = obj.optString("description", ""),
                            date = obj.optLong("date", System.currentTimeMillis()),
                            invoicePhotoFileId = if (obj.optString("invoicePhotoFileId", "").isNotBlank()) obj.optString("invoicePhotoFileId") else null,
                            paymentMethod = obj.optString("paymentMethod", "CARD_TRANSFER"),
                            subAccountName = obj.optString("subAccountName", ""),
                            chequeNumber = if (obj.optString("chequeNumber", "").isNotBlank()) obj.optString("chequeNumber") else null,
                            labOrderId = if (lId > 0) lId else null
                        ))
                    }
                }
                if (root.has("personalTransactions")) {
                    val array = root.getJSONArray("personalTransactions")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val bId = obj.optLong("bankAccountId", -1L)
                        repository.insertTransaction(PersonalTransaction(
                            id = obj.optLong("id", 0L),
                            type = obj.optString("type", "EXPENSE"),
                            subAccountName = obj.optString("subAccountName", "سایر"),
                            bankAccountId = if (bId > 0) bId else null,
                            amount = obj.optLong("amount", 0L),
                            description = obj.optString("description", ""),
                            paymentMethod = obj.optString("paymentMethod", "CARD_TRANSFER"),
                            chequeNumber = if (obj.optString("chequeNumber", "").isNotBlank()) obj.optString("chequeNumber") else null,
                            date = obj.optLong("date", System.currentTimeMillis())
                        ))
                    }
                }
                if (root.has("bankAccounts")) {
                    val array = root.getJSONArray("bankAccounts")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        repository.insertBankAccount(BankAccount(
                            id = obj.optLong("id", 0L),
                            bankName = obj.optString("bankName", ""),
                            cardHolder = obj.optString("cardHolder", ""),
                            cardNumber = obj.optString("cardNumber", ""),
                            accountNumber = obj.optString("accountNumber", ""),
                            balance = obj.optLong("balance", 0L),
                            smsKeywords = obj.optString("smsKeywords", ""),
                            isHidden = obj.optBoolean("isHidden", false),
                            sortOrder = obj.optInt("sortOrder", 0)
                        ))
                    }
                }
                if (root.has("personalSubAccounts")) {
                    val array = root.getJSONArray("personalSubAccounts")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        repository.insertSubAccount(PersonalSubAccount(
                            id = obj.optLong("id", 0L),
                            name = obj.optString("name", ""),
                            category = obj.optString("category", "EXPENSE"),
                            isQuickSelect = obj.optBoolean("isQuickSelect", false)
                        ))
                    }
                }
            }

            // Restore Templates & Settings
            if (config.includeSettingsTemplates) {
                if (root.has("templates")) {
                    val array = root.getJSONArray("templates")
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        repository.insertTemplate(TreatmentTemplate(
                            id = obj.optLong("id", 0L),
                            title = obj.optString("title", ""),
                            defaultCost = obj.optLong("defaultCost", 0L),
                            defaultNotes = obj.optString("defaultNotes", ""),
                            stages = obj.optString("stages", ""),
                            isStarred = obj.optBoolean("isStarred", true),
                            sortOrder = obj.optInt("sortOrder", 0)
                        ))
                    }
                }
                if (root.has("clinicSettings")) {
                    val obj = root.getJSONObject("clinicSettings")
                    repository.insertSettings(ClinicSettings(
                        clinicName = obj.optString("clinicName", ""),
                        doctorName = obj.optString("doctorName", "دکتر"),
                        childrenNames = obj.optString("childrenNames", ""),
                        staffNames = obj.optString("staffNames", "منشی"),
                        showSummaryCard = obj.optBoolean("showSummaryCard", true),
                        showQuickActions = obj.optBoolean("showQuickActions", true),
                        showCharts = obj.optBoolean("showCharts", true),
                        smsSampleTemplate = obj.optString("smsSampleTemplate", "واریز مبلغ {amount} ریال به کارت {card}"),
                        themeName = obj.optString("themeName", "NEON_TEAL"),
                        isDarkMode = obj.optBoolean("isDarkMode", true),
                        clinicalFontSizePt = obj.optInt("clinicalFontSizePt", 11),
                        globalFontScaleIndex = obj.optInt("globalFontScaleIndex", 9),
                        appointmentTemplate = obj.optString("appointmentTemplate", "{clinic}\nیادآوری نوبت\nمراجع عزیز: {patient}\nتاریخ: {date} ساعت {time}"),
                        invoiceTemplate = obj.optString("invoiceTemplate", "{clinic}\nصورتحساب\nمراجع عزیز: {patient}\nقابل پرداخت: {amount}\nپرداختی: {paid}"),
                        treatmentPlanTemplate = obj.optString("treatmentPlanTemplate", "{clinic}\nطرح درمان\nمراجع عزیز: {patient}\nموارد: {treatments}"),
                        installmentTemplate = obj.optString("installmentTemplate", "{clinic}\nسررسید اقساط\nمراجع عزیز: {patient}\nمبلغ قسط: {amount}\nسررسید: {date}"),
                        postOpTemplate = obj.optString("postOpTemplate", "{clinic}\nتوصیه های ترخیص و مراقبتی\nمراجع عزیز: {patient}\n{instructions}"),
                        endoAnatomyOverrides = obj.optString("endoAnatomyOverrides", "")
                    ))
                }
            }

            true
            }
        } catch (e: Exception) {
            android.util.Log.e("CloudRestore", "Transactional restore failed", e)
            false
        }
    }


    // Current Active Tab
    val currentTab = MutableStateFlow("dashboard")
    // گزینه‌های پیشرفته تنظیمات — پیش‌فرض نمایان (منوی مخفی حذف شد؛ از داخل «تنظیمات مطب»
    // با یک کلید آشکار قابل مخفی‌سازی/نمایش است، دیگر نیازی به لمس ۲ثانیه‌ای چرخ‌دنده نیست)
    val advancedSettingsRevealed = MutableStateFlow(true)
    val currentViewingImage = MutableStateFlow<String?>(null) // dashboard, patients, appointments, vendors, labs, maintenance, expenses, personal, more

    // Selected Patient for Record Details
    val selectedPatientId = MutableStateFlow<Long?>(null)

    init {
        isPersian.value = prefs.getBoolean("is_persian", true)

        viewModelScope.launch {
            isGuestMode.collect { guest ->
                syncEngine.isGuestMode = guest
            }
        }

        // Force update defaults if user is still on old neon theme
        viewModelScope.launch {
            val currentSettings = repository.clinicSettings.firstOrNull()
            // «بدون پیش‌فرض» (درخواست کاربر): اگر هنوز هیچ تنظیماتی ثبت نشده، هیچ
            // ردیف پیش‌فرضی ساخته نمی‌شود — UI از ClinicSettings() درون‌حافظه استفاده
            // می‌کند و اولین ذخیرهٔ خودِ کاربر تنها ردیف (id=1) را می‌سازد. درجِ
            // پیش‌فرض در این نقطه باعث می‌شد با هر لاگین (قبل از رسیدن pull) یک
            // ردیف تازه در clinic_settings سرور ثبت شود.
            if (currentSettings != null &&
                (currentSettings.themeName == "NEON_TEAL" || currentSettings.themeName == "CLINICAL_CLEAN")) {
                repository.insertSettings(currentSettings.copy(themeName = "NEON_CARBON", isDarkMode = true))
            }
        }
        

    }

    // Streams from Repository
    val patients = repository.allPatients.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val archivedPatients = repository.allArchivedPatients.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val appointments = repository.allAppointments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val vendors = repository.allVendors.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val labOrders = repository.allLabOrders.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val labServices = repository.allLabServices.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val maintenanceList = repository.allMaintenance.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val clinicExpenses = repository.allExpenses.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allExpensePayments = repository.allExpensePayments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    // طرف‌حساب‌ها (طرف‌حساب چندطرف‌حسابیِ هزینه‌های مطب) — فقط طرف‌حساب‌های فعال.
    val partyAccounts: StateFlow<List<com.example.data.PartyAccount>> = repository.allPartyAccounts
        .map { list -> list.filter { !it.isDeleted }.sortedWith(compareBy({ it.section }, { it.name })) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val personalTransactions = repository.allTransactions.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allTreatments = repository.allTreatments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allPayments = repository.allPayments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // NEW STREAMS FOR COMPREHENSIVE ERP
    val templates = repository.allTemplates.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val bankAccounts = repository.allBankAccounts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val unregisteredBankSmsList = database.unregisteredBankSmsDao().getPendingSmsFlow().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val unregisteredBankSmsCount = database.unregisteredBankSmsDao().getPendingCountFlow().stateIn(viewModelScope, SharingStarted.Lazily, 0)
    // لیست تک‌سطری تراکنش‌های پیامکی «ثبت‌شده» (مبلغ | بابت خلاصه | تاریخ)
    val registeredBankSmsList = database.unregisteredBankSmsDao().getRegisteredSmsFlow().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    // لیست مجزای «نادیده‌گرفته‌شده‌ها» — بعداً قابل بازگردانی یا ثبت
    val ignoredBankSmsList = database.unregisteredBankSmsDao().getIgnoredSmsFlow().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    // سطل آشغال پیامک‌های بانکی (حذف‌نرم؛ با سینک ابری بعد از ورود دوباره هم باقی می‌ماند)
    val trashedBankSmsList = database.unregisteredBankSmsDao().getDeletedSmsFlow().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---------- سیستم کارتخوان (POS) — تسویه‌ی روزانه ----------
    // درخواست کاربر: تراکنش‌های کارتخوان هر روز بدون انتخاب حساب ثبت می‌شوند؛ روز بعد در
    // شروع اپ پاپ‌آپ می‌پرسد این تراکنش‌ها به کدام حساب واریز شوند. پس از تایید، تراکنش‌ها
    // از لیست فعال مخفی شده و به‌صورت بایگانی روزانه (جمع روز + حساب انتخابی + حفظ طرف‌حساب
    // هر تراکنش) نمایش داده می‌شوند.

    /** تراکنش‌های کارتخوان تسویه‌نشده: پرداخت‌های از نوع POS که هنوز حساب بانکی ندارند. */
    val unsettledPosByDay = allPayments.map { payments ->
        payments
            .filter { !it.isDeleted && it.type == "POS" && it.bankAccountId == null }
            .groupBy { com.example.util.PersianCalendarHelper.jalaliKeyOf(it.date) }
            .toList()
            .sortedByDescending { it.first }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** بایگانی کارتخوان: پرداخت‌های POS تسویه‌شده (دارای حساب بانکی) به تفکیک روز. */
    val posArchiveByDay = allPayments.map { payments ->
        payments
            .filter { !it.isDeleted && it.type == "POS" && it.bankAccountId != null }
            .groupBy { com.example.util.PersianCalendarHelper.jalaliKeyOf(it.date) }
            .toList()
            .sortedByDescending { it.first }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** آیا برای روزهای قبل از امروز تراکنش کارتخوان تسویه‌نشده وجود دارد؟ (محرک پاپ‌آپ استارت اپ) */
    val hasUnsettledPosFromPreviousDays = unsettledPosByDay.map { days ->
        val todayKey = com.example.util.PersianCalendarHelper.jalaliKeyOf(System.currentTimeMillis())
        days.any { (dayKey, list) -> dayKey < todayKey && list.isNotEmpty() }
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    /**
     * تسویه‌ی تمام تراکنش‌های کارتخوان یک روز: تخصیص حساب بانکی به تک‌تک پرداخت‌ها.
     * موجودی حساب از مسیر repository.updatePayment (reconcileBankImpact) به‌روزرسانی می‌شود
     * و طرف‌حساب (بیمار) هر تراکنش دست‌نخورده باقی می‌ماند.
     */
    fun settlePosDay(dateKey: String, bankAccountId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = bankAccounts.value.find { it.id == bankAccountId } ?: return@launch
            val rows = allPayments.value.filter {
                !it.isDeleted && it.type == "POS" && it.bankAccountId == null &&
                    com.example.util.PersianCalendarHelper.jalaliKeyOf(it.date) == dateKey
            }
            rows.forEach { payment ->
                repository.updatePayment(
                    payment.copy(bankAccountId = account.id, bankAccountSyncId = account.syncId)
                )
            }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }
    val personalSubAccounts = repository.allSubAccounts.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val childrenExpenses = repository.allChildrenExpenses.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val fixedIncomes = repository.allFixedIncomes.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val settings = repository.clinicSettings.map { it ?: ClinicSettings() }.stateIn(viewModelScope, SharingStarted.Lazily, ClinicSettings())

    // هر تغییر تم یا سایز فونت را بلافاصله در نگهدارندهٔ تم خروجی‌ها می‌نشیند تا
    // همهٔ ژنراتورهای تصویر/PDF (و مسیرهای بدون ViewModel) همیشه تم زنده را
    // ببینند؛ این‌طور «کارت طلاییِ» همیشگی دیگر قابل تکرار نیست.
    init {
        viewModelScope.launch {
            settings.collect { currentExportTheme() }
        }
        // اسپلش را تا آماده‌شدن واقعیِ داده نگه می‌داریم: اولین مقدارِ تنظیمات که
        // از دیتابیس بیاید یعنی DB باز و پاسخ‌گوست. یک سقفِ ۳ ثانیه‌ای هم هست تا
        // در بدترین حالت (دیتابیسِ خالی/کند) اسپلش هرگز قفل نشود.
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(3_000) {
                    repository.clinicSettings.first()
                }
            } finally {
                isBootReady.value = true
            }
        }
    }
    val allInstallments = repository.allInstallments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allTreatmentStages = repository.allTreatmentStages.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allPatientDocuments = repository.allPatientDocuments.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allVendorInvoices = repository.allVendorInvoices.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addInstallment(patientId: Long, treatmentId: Long?, title: String, amount: Long, dueDate: Long, note: String) {
        if (!authorizeDomain("billing", "ADD_INSTALLMENT")) return
        viewModelScope.launch {
            val patientSyncId = patients.value.find { it.id == patientId }?.syncId ?: ""
            val treatmentSyncId = if (treatmentId != null) allTreatments.value.find { it.id == treatmentId }?.syncId else null
            repository.insertInstallment(
                Installment(
                    patientId = patientId,
                    patientSyncId = patientSyncId,
                    treatmentId = treatmentId,
                    treatmentSyncId = treatmentSyncId ?: "",
                    title = title,
                    amount = amount,
                    dueDate = dueDate,
                    note = note
                )
            )
        }
    }

    fun deleteInstallment(installment: Installment) {
        if (!authorizeDomain("billing", "DELETE_INSTALLMENT", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteInstallment(installment)
        }
    }

    /**
     * پرداخت قسط با انتخاب روش دریافت (نقد/کارتخوان/کارت به کارت) و حساب بانکی:
     * درخواست کاربر — «اقساط و بدهی هر قسط باید در لیست تراکنش‌های بانکی/نقدی/پوز وارد
     * بشوند تا معلوم شود دریافت یا پرداخت». پس از این پرداخت، رکورد در همه‌ی لیست‌های
     * تراکنش (بانک/نقد/پوز) با طرف‌حسابِ همان بیمار دیده می‌شود.
     */
    fun payInstallmentWithTransaction(installment: Installment, method: String, bankAccountId: Long?) {
        if (!authorizeDomain("payments", "PAY_INSTALLMENT")) return
        viewModelScope.launch {
            repository.inTransaction {
                updateInstallment(
                    installment.copy(isPaid = true, paidDate = System.currentTimeMillis())
                )
                val account = bankAccountId?.let { id -> bankAccounts.value.find { it.id == id } }
                insertPayment(
                    Payment(
                        patientId = installment.patientId,
                        patientSyncId = patients.value.find { it.id == installment.patientId }?.syncId ?: "",
                        amount = installment.amount,
                        type = method, // CASH, CARD یا POS — همان روش دریافت انتخابی
                        bankAccountId = account?.id,
                        bankAccountSyncId = account?.syncId ?: "",
                        date = System.currentTimeMillis(),
                        isCleared = true,
                        chequeDrawer = "پرداخت قسط: ${installment.title}",
                        notes = "قسط: ${installment.title}"
                    )
                )
            }
        }
    }

    init {
        // Runs the automatic monthly fixed-income deposit check once fixedIncomes/bankAccounts streams are ready
        viewModelScope.launch {
            fixedIncomes.first()
            checkAndProcessFixedIncomes()
        }
        
        // پیش‌تر اینجا یک حلقهٔ `while(true){ delay(15000); pushPending() }` بود که
        // تا زمانِ زنده‌بودنِ ViewModel (حتی در background) می‌چرخید و باتری/شبکه
        // مصرف می‌کرد. آن حلقه حذف شد و کارِ دوره‌ای به SyncWorker (WorkManager)
        // سپرده شد. سینکِ آنیِ حینِ استفاده همچنان با دو collector واکنشیِ زیر
        // (تغییر وضعیت شبکه + تغییر تعداد عملیاتِ در صف) انجام می‌شود.
        viewModelScope.launch {
            syncEngine.isOnline.collect { online ->
                if (online) {
                    try {
                        syncEngine.pushPending()
                    } catch (e: Exception) {
                        // Ignored
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.pendingSyncOpsCount.collect { count ->
                if (count > 0 && syncEngine.isOnline.value) {
                    try {
                        syncEngine.pushPending()
                    } catch (e: Exception) {
                        // Ignored
                    }
                }
            }
        }
    }

    // Selected Patient's Specific Treatments & Payments
    val selectedPatient = selectedPatientId.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else patients.map { list -> list.find { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val selectedPatientTreatments = selectedPatientId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else repository.getTreatmentsForPatient(id)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val selectedPatientPayments = selectedPatientId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else repository.getPaymentsForPatient(id)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Toggle Language
    fun toggleLanguage() {
        isPersian.value = !isPersian.value
        prefs.edit().putBoolean("is_persian", isPersian.value).apply()
    }


    private fun checkGuestLimit(listSize: Int): Boolean {
        if (isGuestMode.value && listSize >= 1) {
            return false
        }
        return true
    }

    // Add Patient
    fun addPatient(name: String, phone: String, note: String, photoLocalFilePath: String? = null) {
        if (!checkGuestLimit(patients.value.size)) return
        if (!authorizeDomain("patients", "ADD_PATIENT")) return
        viewModelScope.launch(Dispatchers.IO) {
            val code = "P-" + (10000 + (System.currentTimeMillis() % 90000))
            val uploadedPhotoFileId = uploadLocalFileOrNull(photoLocalFilePath, com.example.util.StorageCategory.PATIENTS)
            val effectivePhotoId = uploadedPhotoFileId ?: photoLocalFilePath
            val id = repository.insertPatient(Patient(patientCode = code,
                    name = name, phone = phone, note = note,
                    photoFileId = effectivePhotoId,
                    photoLocalPath = photoLocalFilePath))
            selectedPatientId.value = id
        }
    }


    
    // Add Patient Full Detail (with Gender, Medical Alerts, Self-Declaration Form, Age & BirthDate)
    fun addPatientFull(
        name: String,
        phone: String = "",
        email: String = "",
        address: String = "",
        medicalHistory: String = "",
        note: String = "",
        photoLocalFilePath: String? = null,
        documentUris: String = "",
        gender: String = "مرد",
        medicalAlerts: String = "",
        selfDeclarationJson: String = "",
        age: Int = 0,
        birthDate: String = "",
        preferredMessenger: String = "واتساپ",
        patientCode: String = "",
        referrer: String = "",
        assistantIds: String = "",
        fatherName: String = "",
        nationalId: String = "",
        signatureLocalFilePath: String? = null
    ) {
        if (!checkGuestLimit(patients.value.size)) return
        if (!authorizeDomain("patients", "ADD_PATIENT")) return
        viewModelScope.launch(Dispatchers.IO) {
            val code = if (patientCode.isNotBlank()) patientCode else "P-" + (10000 + (System.currentTimeMillis() % 90000))
            val uploadedPhotoFileId = uploadLocalFileOrNull(photoLocalFilePath, com.example.util.StorageCategory.PATIENTS)
            val effectivePhotoId = uploadedPhotoFileId ?: photoLocalFilePath
            val uploadedSigFileId = uploadLocalFileOrNull(signatureLocalFilePath, com.example.util.StorageCategory.PATIENTS)
            val effectiveSigId = uploadedSigFileId ?: signatureLocalFilePath
            val id = repository.insertPatient(
                Patient(
                    patientCode = code,
                    referrer = referrer,
                    name = name,
                    phone = phone,
                    email = email,
                    address = address,
                    medicalHistory = medicalHistory,
                    note = note,
                    photoFileId = effectivePhotoId,
                    photoLocalPath = photoLocalFilePath,
                    documentUris = documentUris,
                    gender = gender,
                    medicalAlerts = medicalAlerts,
                    selfDeclarationJson = selfDeclarationJson,
                    age = age,
                    birthDate = birthDate,
                    preferredMessenger = preferredMessenger,
                    assistantIds = assistantIds,
                    fatherName = fatherName,
                    nationalId = nationalId,
                    // «صادره از» همیشه از روی خود کد ملی مشتق می‌شود تا هرگز با آن ناسازگار نشود
                    issuancePlace = com.example.util.NationalIdCityLookup.cityOfValid(nationalId).orEmpty(),
                    signatureFileId = effectiveSigId
                )
            )
            selectedPatientId.value = id
        }
    }

    // Update Patient Details
    fun updatePatient(patient: Patient) {
        if (!authorizeDomain("patients", "UPDATE_PATIENT")) return
        viewModelScope.launch {
            repository.insertPatient(patient)
        }
    }

    fun updatePatientFull(
        patient: Patient,
        name: String,
        phone: String,
        address: String,
        note: String,
        gender: String,
        medicalAlerts: String,
        medicalHistory: String,
        selfDeclarationJson: String,
        age: Int,
        birthDate: String,
        preferredMessenger: String,
        referrer: String,
        assistantIds: String,
        fatherName: String,
        nationalId: String,
        signatureLocalFilePath: String?
    ) {
        if (!authorizeDomain("patients", "UPDATE_PATIENT")) return
        viewModelScope.launch(Dispatchers.IO) {
            val effectiveSigId = if (!signatureLocalFilePath.isNullOrBlank() && signatureLocalFilePath != patient.signatureFileId && signatureLocalFilePath != patient.photoLocalPath) {
                val uploadedId = uploadLocalFileOrNull(signatureLocalFilePath, com.example.util.StorageCategory.PATIENTS)
                uploadedId ?: signatureLocalFilePath
            } else {
                patient.signatureFileId
            }

            repository.insertPatient(
                patient.copy(
                    name = name,
                    phone = phone,
                    address = address,
                    note = note,
                    gender = gender,
                    medicalAlerts = medicalAlerts,
                    medicalHistory = medicalHistory,
                    selfDeclarationJson = selfDeclarationJson.ifBlank { patient.selfDeclarationJson },
                    age = age,
                    birthDate = birthDate,
                    preferredMessenger = preferredMessenger,
                    referrer = referrer,
                    assistantIds = assistantIds,
                    fatherName = fatherName,
                    nationalId = nationalId,
                    issuancePlace = com.example.util.NationalIdCityLookup.cityOfValid(nationalId).orEmpty(),
                    signatureFileId = effectiveSigId,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Uploads a freshly picked/captured local photo immediately and stores the resulting
     * fileId on the patient record — with instant local fallback.
     */
    fun updatePatientPhoto(patient: Patient, localFilePath: String) {
        if (!authorizeDomain("patients", "UPDATE_PATIENT")) return
        viewModelScope.launch(Dispatchers.IO) {
            isMediaUploading.value = true
            try {
                repository.insertPatient(patient.copy(photoFileId = localFilePath, photoLocalPath = localFilePath))
                val uploadedFileId = uploadLocalFileOrNull(localFilePath, com.example.util.StorageCategory.PATIENTS)
                if (uploadedFileId != null) {
                    repository.insertPatient(patient.copy(photoFileId = uploadedFileId, photoLocalPath = null))
                    com.example.ui.screens.deleteLocalStagingFileQuietly(localFilePath)
                }
            } finally {
                isMediaUploading.value = false
            }
        }
    }

    /**
     * Uploads [localFilePath] to cloud Storage and returns the new fileId, or null if the
     * path is blank/missing/upload failed.
     *
     * [category] پوشهٔ مقصد را تعیین می‌کند (چک‌ها / بیماران / مطب). نامِ شیء از
     * هشِ محتوای فایل ساخته می‌شود، پس آپلود دوبارهٔ همان تصویر هرگز نسخهٔ
     * تکراری با شناسهٔ متفاوت نمی‌سازد.
     */
    suspend fun uploadLocalFileOrNull(
        localFilePath: String?,
        category: com.example.util.StorageCategory = com.example.util.StorageCategory.MISC
    ): String? {
        if (localFilePath.isNullOrBlank()) return null
        val file = File(localFilePath)
        if (!file.exists()) return null
        // اگر همین مسیر خودش یک شناسهٔ Storage باشد (نه فایل محلی)، دوباره آپلود
        // نمی‌کنیم — این همان حالتی است که رکورد بدون تغییرِ عکس ذخیره می‌شود.
        if (!localFilePath.startsWith("/")) return localFilePath
        val bucket = when (category) {
            com.example.util.StorageCategory.CHEQUES, com.example.util.StorageCategory.PATIENTS -> com.example.util.Supabase.BUCKET_PATIENT_MEDIA
            else -> com.example.util.Supabase.BUCKET_CLINIC_ASSETS
        }
        return try {
            syncEngine.uploadFile(file, bucket, category)
        } catch (e: Exception) {
            null
        }
    }

    // Delete Patient Cascading (completely clean database records)
    fun deletePatient(patient: Patient) {
        if (!authorizeDomain("patients", "DELETE_PATIENT", isDelete = true)) return
        viewModelScope.launch {
            if (selectedPatientId.value == patient.id) {
                selectedPatientId.value = null
            }
            repository.deletePatientCascading(patient)
        }
    }

    // ————————————————————————————————————————————————
    // بایگانی بیمار (درخواست): بیمار بایگانی‌شده از لیست‌های فعال و انتخاب‌گرهای ورودی
    // (نوبت، پرداخت و...) مخفی می‌شود و در «لیست آرشیو» با فیلتر بخش‌های ورودی دیده می‌شود.
    // ————————————————————————————————————————————————
    /** بایگانی بیمار — حذف نمی‌کند، فقط از لیست‌های فعال مخفی می‌کند. */
    fun archivePatient(patient: Patient) {
        if (!authorizeDomain("patients", "ARCHIVE_PATIENT")) return
        viewModelScope.launch {
            if (selectedPatientId.value == patient.id) {
                selectedPatientId.value = null
            }
            repository.insertPatient(
                patient.copy(isArchived = true, updatedAt = System.currentTimeMillis())
            )
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک بعداً تلاش می‌کند */ }
        }
    }

    /** بازگرداندن بیمار از آرشیو به لیست فعال. */
    fun unarchivePatient(patient: Patient) {
        if (!authorizeDomain("patients", "UNARCHIVE_PATIENT")) return
        viewModelScope.launch {
            repository.insertPatient(
                patient.copy(isArchived = false, updatedAt = System.currentTimeMillis())
            )
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک بعداً تلاش می‌کند */ }
        }
    }

    /** بیماران فعال (بایگانی‌نشده) — برای انتخاب‌گرهای ورودی مانند نوبت و پرداخت. */
    fun activePatients(): List<Patient> = patients.value.filter { !it.isDeleted && !it.isArchived }
    fun archivedPatientList(): List<Patient> = archivedPatients.value.filter { !it.isDeleted }

    // Add Treatment with Tooth selection & discount
    fun addTreatment(patientId: Long, title: String, cost: Long, notes: String = "") {
        if (!authorizeDomain("treatments", "ADD_TREATMENT")) return
        viewModelScope.launch {
            val patientSyncId = patients.value.find { it.id == patientId }?.syncId ?: ""
            repository.insertTreatment(Treatment(patientId = patientId, title = title, cost = cost, notes = notes, patientSyncId = patientSyncId))
        }
    }

    fun addTreatmentWithDetails(patientId: Long, title: String, cost: Long, discount: Long, selectedTeeth: String, notes: String = "", stages: String = "", assistantIds: String = "") {
        if (!authorizeDomain("treatments", "ADD_TREATMENT")) return
        viewModelScope.launch {
            var finalStages = stages
            if (finalStages.isBlank()) {
                val matchedTemp = templates.value.find { temp ->
                    title.contains(temp.title) || temp.title.contains(title)
                }
                if (matchedTemp != null && matchedTemp.stages.isNotBlank()) {
                    finalStages = matchedTemp.stages
                }
            }
            // مراحل همیشه در قالب رسمی ذخیره می‌شوند تا تفکیک جلسات پایدار بماند.
            finalStages = joinStagesList(parseStagesList(finalStages))
            
            val isGroupedTherapy = title.contains("روکش") || title.contains("بریج") || title.lowercase().contains("crown") || title.lowercase().contains("bridge")
            val teethList = selectedTeeth.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            
            val patientSyncId = patients.value.find { it.id == patientId }?.syncId ?: ""
            
            if (isGroupedTherapy && teethList.isNotEmpty()) {
                // Grouped therapy (like bridges/crowns) - insert as a single treatment
                val totalCost = cost * teethList.size
                repository.insertTreatment(
                    Treatment(
                        patientId = patientId,
                        patientSyncId = patientSyncId,
                        title = "$title (دندان‌های ${selectedTeeth.trim()})",
                        cost = totalCost,
                        discount = discount,
                        selectedTeeth = selectedTeeth.trim(),
                        notes = notes,
                        status = "PLANNED",
                        stages = finalStages,
                        assistantIds = assistantIds
                    )
                )
            } else if (teethList.isNotEmpty()) {
                // Separate therapy for each tooth — remainder of integer division on last tooth
                val discounts = splitAmountAcrossUnits(discount, teethList.size)
                teethList.forEachIndexed { index, tooth ->
                    repository.insertTreatment(
                        Treatment(
                            patientId = patientId,
                            patientSyncId = patientSyncId,
                            title = "$title (دندان $tooth)",
                            cost = cost,
                            discount = discounts[index],
                            selectedTeeth = tooth,
                            notes = notes,
                            status = "PLANNED",
                            stages = finalStages,
                            assistantIds = assistantIds
                        )
                    )
                }
            } else {
                // No teeth selected
                repository.insertTreatment(
                    Treatment(
                        patientId = patientId,
                        patientSyncId = patientSyncId,
                        title = title,
                        cost = cost,
                        discount = discount,
                        selectedTeeth = selectedTeeth,
                        notes = notes,
                        status = "PLANNED",
                        stages = finalStages,
                        assistantIds = assistantIds
                    )
                )
            }
        }
    }

    fun updateTreatmentStatus(treatment: Treatment, newStatus: String) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STATUS")) return
        viewModelScope.launch {
            repository.insertTreatment(treatment.copy(status = newStatus))
        }
    }

    // Delete Treatment
    fun deleteTreatment(treatment: Treatment) {
        if (!authorizeDomain("treatments", "DELETE_TREATMENT", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteTreatment(treatment)
        }
    }

    /** Restores a treatment that was just deleted (used by the Undo snackbar action). */
    fun undoDeleteTreatment(treatment: Treatment) {
        if (!authorizeDomain("treatments", "UNDO_DELETE_TREATMENT")) return
        viewModelScope.launch {
            repository.insertTreatment(treatment)
        }
    }

    // Add Payment with Bank Account mapping
    fun addPayment(
        patientId: Long,
        amount: Long,
        type: String,
        chequeNum: String? = null,
        chequeDue: Long? = null,
        sayadiId: String? = null,
        chequeBank: String? = null,
        chequeDrawer: String? = null,
        chequePhotoFileId: String? = null,
        notes: String? = null
    ) {
        if (!authorizeDomain("payments", "ADD_PAYMENT")) return
        viewModelScope.launch {
            val patientSyncId = patients.value.find { it.id == patientId }?.syncId ?: ""
            repository.insertPayment(
                Payment(
                    patientId = patientId,
                    patientSyncId = patientSyncId,
                    amount = amount,
                    type = type,
                    chequeNumber = chequeNum,
                    chequeDueDate = chequeDue,
                    isCleared = type != "CHEQUE",
                    sayadiId = sayadiId,
                    chequeBank = chequeBank,
                    chequeDrawer = chequeDrawer,
                    chequePhotoFileId = chequePhotoFileId,
                    notes = notes
                )
            )
        }
    }

    fun addPaymentWithAccount(
        patientId: Long,
        amount: Long,
        type: String,
        bankAccountId: Long?,
        chequeNum: String? = null,
        chequeDue: Long? = null,
        sayadiId: String? = null,
        chequeBank: String? = null,
        chequeDrawer: String? = null,
        chequePhotoFileId: String? = null,
        date: Long? = null,
        assistantIds: String = "",
        sourceSmsNativeId: String = ""
    ) {
        if (!authorizeDomain("payments", "ADD_PAYMENT")) return
        viewModelScope.launch {
            val patient = patients.value.find { it.id == patientId }
            val patientSyncId = patient?.syncId ?: ""
            val patientName = patient?.name ?: "ناشناس"
            val bankAccountSyncId = if (bankAccountId != null) bankAccounts.value.find { it.id == bankAccountId }?.syncId else null
            repository.insertPayment(
                Payment(
                    patientId = patientId,
                    patientSyncId = patientSyncId,
                    amount = amount,
                    type = type,
                    bankAccountId = bankAccountId,
                    bankAccountSyncId = bankAccountSyncId ?: "",
                    sourceSmsNativeId = sourceSmsNativeId,
                    chequeNumber = chequeNum,
                    chequeDueDate = chequeDue,
                    date = date ?: System.currentTimeMillis(),
                    isCleared = type != "CHEQUE",
                    sayadiId = sayadiId,
                    chequeBank = chequeBank,
                    chequeDrawer = chequeDrawer,
                    chequePhotoFileId = chequePhotoFileId,
                    assistantIds = assistantIds
                )
            )
            if (sourceSmsNativeId.isNotBlank()) {
                val sms = unregisteredBankSmsList.value.find { it.smsNativeId == sourceSmsNativeId }
                    ?: registeredBankSmsList.value.find { it.smsNativeId == sourceSmsNativeId }
                if (sms != null) {
                    val acc = bankAccountId?.let { id -> bankAccounts.value.find { it.id == id } }
                    repository.upsertUnregisteredBankSms(
                        sms.copy(
                            status = "ASSIGNED",
                            assignedAccountId = acc?.id,
                            assignedAccountSyncId = acc?.syncId.orEmpty(),
                            registeredForSummary = "دریافتی از بیمار - $patientName"
                        )
                    )
                }
            }
        }
    }

    // Update Payment
    /**
     * به‌روزرسانی پرداخت — و اگر چکِ منتقل‌شده باشد، **همگام‌سازی سندِ مقصد**.
     *
     * پیش از این، ویرایشِ مبلغِ یک چکِ منتقل‌شده فقط خودِ چک را عوض می‌کرد و
     * هزینهٔ مقصد با مبلغ قدیمی می‌ماند؛ دو طرفِ دفتر نامساوی می‌شدند. همچنین
     * برگشت‌خوردنِ چک، تسویهٔ هزینهٔ مقصد را برنمی‌گرداند.
     */
    fun updatePayment(payment: Payment) {
        if (!authorizeDomain("payments", "UPDATE_PAYMENT")) return
        viewModelScope.launch {
            repository.inTransaction {
                updatePayment(payment)
                syncChequeTransferDocs(payment)
            }
        }
    }

    /**
     * سندِ مقصدِ انتقال را با وضعیت و مبلغِ فعلیِ چک همگام می‌کند.
     *
     * ـ مبلغ/شماره/بانک/سررسید از روی خودِ چک به‌روز می‌شوند.
     * ـ اگر چک برگشت خورده باشد، سندِ مقصد هم BOUNCED می‌شود تا تسویه برگردد.
     * ـ اگر چک دوباره در جریان یا وصول شود، سند به PENDING برمی‌گردد.
     */
    private suspend fun DentalRepository.syncChequeTransferDocs(payment: Payment) {
        if (payment.type != "CHEQUE") return

        payment.transferPartyPaymentId?.let { id ->
            getExpensePaymentByIdOnce(id)?.let { doc ->
                insertExpensePayment(
                    doc.copy(
                        amount = payment.amount,
                        chequeNumber = payment.chequeNumber,
                        chequeBank = payment.chequeBank,
                        chequeDueDate = payment.chequeDueDate,
                        chequeStatus = if (payment.chequeStatus == "BOUNCED") "BOUNCED" else "PENDING",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        payment.transferExpensePaymentId?.let { id ->
            getExpensePaymentByIdOnce(id)?.let { doc ->
                insertExpensePayment(
                    doc.copy(
                        amount = payment.amount,
                        chequeNumber = payment.chequeNumber,
                        chequeBank = payment.chequeBank,
                        chequeDueDate = payment.chequeDueDate,
                        chequeStatus = if (payment.chequeStatus == "BOUNCED") "BOUNCED" else "PENDING",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        payment.transferTransactionId?.let { id ->
            getPersonalTransactionByIdOnce(id)?.let { doc ->
                insertTransaction(
                    doc.copy(
                        amount = payment.amount,
                        chequeNumber = payment.chequeNumber,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        payment.transferChildrenExpenseId?.let { id ->
            getChildrenExpenseByIdOnce(id)?.let { doc ->
                insertChildrenExpense(doc.copy(amount = payment.amount, updatedAt = System.currentTimeMillis()))
            }
        }
        payment.transferExpenseId?.let { id ->
            getExpenseByIdOnce(id)?.let { doc ->
                updateExpense(
                    doc.copy(
                        amount = payment.amount,
                        chequeNumber = payment.chequeNumber,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // Delete Payment
    /**
     * حذف پرداخت — و اگر چکِ منتقل‌شده باشد، **اثرِ انتقال هم برگردانده می‌شود**.
     *
     * رفع باگ: پیش از این با حذف چک، سندی که در بخش هزینه‌ها ساخته شده بود
     * باقی می‌ماند و آن هزینه تا ابد «پرداخت‌شده» به نظر می‌رسید.
     */
    fun deletePayment(payment: Payment) {
        if (!authorizeDomain("payments", "DELETE_PAYMENT", isDelete = true)) return
        viewModelScope.launch {
            repository.inTransaction {
                reverseChequeTransferDocs(payment)
                deletePayment(payment)
            }
        }
    }

    /** Restores a payment and its exact ledger/bank impact in one transaction. */
    fun undoDeletePayment(payment: Payment) {
        if (!authorizeDomain("payments", "UNDO_DELETE_PAYMENT")) return
        viewModelScope.launch {
            repository.inTransaction {
                // اسنادِ انتقالِ قبلی با حذف پاک شده‌اند؛ چک بدون آن ارجاع‌ها
                // بازگردانده می‌شود تا رکوردهای مرده به جا نماند. اگر لازم بود،
                // کاربر دوباره انتقال می‌دهد.
                val restored = if (payment.chequeStatus == "TRANSFERRED") {
                    payment.clearedOfTransfer().copy(chequeStatus = "PENDING", isCleared = false)
                } else {
                    payment.clearedOfTransfer().copy(chequeStatus = payment.chequeStatus)
                }
                insertPayment(if (payment.type == "CHEQUE") restored else payment)
            }
        }
    }

    // Add Appointment
    fun addAppointment(appointment: Appointment, context: android.content.Context? = null) {
        if (!authorizeDomain("appointments", "ADD_APPOINTMENT")) return
        viewModelScope.launch {
            val patientSyncId = patients.value.find { it.id == appointment.patientId }?.syncId ?: ""
            val updatedAppt = appointment.copy(patientSyncId = patientSyncId)
            val id = repository.insertAppointment(updatedAppt)
            if (context != null && updatedAppt.status != "CANCELED") {
                com.example.reminders.AppointmentAlarmReceiver.scheduleExactReminder(context, id, updatedAppt.patientName, updatedAppt.date)
            }
        }
    }

    
    fun updateAppointment(appointment: Appointment) {
        if (!authorizeDomain("appointments", "UPDATE_APPOINTMENT")) return
        viewModelScope.launch {
            repository.updateAppointment(appointment)
        }
    }

    // Delete Appointment & Sync linked therapy status
    /**
     * حذفِ یک جلسه و برگرداندنِ کاملِ اثرِ آن روی تراپی‌ها: با کمکِ
     * [Treatment.stageSessions] فقط مراحلِ همان جلسه برگردانده و وضعیتِ تراپی
     * از نو محاسبه می‌شود (مراحلِ جلساتِ دیگر دست نمی‌خورند).
     */
    fun deleteAppointment(appointment: Appointment) {
        if (!authorizeDomain("appointments", "DELETE_APPOINTMENT", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteAppointment(appointment)
            val txIds = appointment.plannedTreatmentIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            txIds.forEach { id ->
                val tx = allTreatments.value.find { it.id == id } ?: return@forEach

                // مراحلی که در همین جلسه ثبت شده‌اند
                val sessionMap = parseStageSessions(tx.stageSessions)
                val stagesOfThisSession = sessionMap.filterValues { it == appointment.id }.keys

                val completed = parseStageMarkers(tx.completedStages)
                    .filter { it !in stagesOfThisSession }
                val ignored = parseStageMarkers(tx.ignoredStages)
                    .filter { it !in stagesOfThisSession }
                val remainingMap = sessionMap.filterValues { it != appointment.id }

                val allStages = parseStagesList(tx.stages)
                val resolved = (completed + ignored).distinct()
                val newStatus = when {
                    allStages.isNotEmpty() && resolved.size >= allStages.size -> "COMPLETED"
                    resolved.isNotEmpty() -> "IN_PROGRESS"
                    else -> "PLANNED"
                }

                repository.insertTreatment(
                    tx.copy(
                        completedStages = joinStageMarkers(completed),
                        ignoredStages = joinStageMarkers(ignored),
                        stageSessions = joinStageSessions(remainingMap),
                        status = newStatus
                    )
                )
            }
            refreshSelectedPatient()
        }
    }

    /** Restores an appointment that was just deleted (used by the Undo snackbar action). */
    fun undoDeleteAppointment(appointment: Appointment) {
        if (!authorizeDomain("appointments", "UNDO_DELETE_APPOINTMENT")) return
        viewModelScope.launch {
            repository.insertAppointment(appointment)
        }
    }

    // Add Vendor (with invoice number/details/photo support)
    fun addVendor(name: String, company: String, phone: String, itemType: String, totalOrders: Long, totalPaid: Long, invoicePhotoFileId: String? = null) {
        if (!checkGuestLimit(vendors.value.size)) return
        if (!authorizeDomain("vendors", "ADD_VENDOR")) return
        viewModelScope.launch {
            val newVendorId = repository.insertVendor(
                Vendor(
                    name = name,
                    company = company,
                    phone = phone,
                    itemType = itemType,
                    totalOrders = totalOrders,
                    totalPaid = totalPaid,
                    invoicePhotoFileId = invoicePhotoFileId
                )
            )
            if (totalOrders > 0) {
                val expDesc = "خرید از فروشنده ($name) - شرکت: $company - نوع کالا: $itemType"
                // درخواست کاربر: «نیاز نیست به پرداخت هنگام ثبت» — فقط فاکتور ثبت می‌شود؛
                // پرداخت از دفترِ طرف حساب انجام می‌شود (نقد از صندوق / پیامک برداشت / چک).
                val storeParty = repository.findOrCreateParty(
                    com.example.data.PartySection.STORE,
                    com.example.data.PartySection.STORE.faTitle,
                    name
                )
                repository.insertExpense(
                    ClinicExpense(
                        category = com.example.data.PartySection.STORE.faTitle,
                        expenseGroup = com.example.data.PartySection.STORE.key,
                        partyAccountId = storeParty.id,
                        amount = totalOrders,
                        description = expDesc,
                        date = System.currentTimeMillis(),
                        subAccountName = name,
                        paymentMethod = "DEBT",
                        invoicePhotoFileId = invoicePhotoFileId,
                        vendorId = newVendorId
                    )
                )
            }
        }
    }

    fun addVendorWithInvoice(name: String, company: String, phone: String, itemType: String, totalOrders: Long, totalPaid: Long, invoiceNum: String?, invoiceDetails: String?) {
        if (!authorizeDomain("vendors", "ADD_VENDOR_INVOICE")) return
        viewModelScope.launch {
            val newVendorId = repository.insertVendor(
                Vendor(
                    name = name,
                    company = company,
                    phone = phone,
                    itemType = itemType,
                    totalOrders = totalOrders,
                    totalPaid = totalPaid,
                    invoiceNumber = invoiceNum,
                    invoiceDetails = invoiceDetails
                )
            )
            if (totalOrders > 0) {
                val invStr = if (!invoiceNum.isNullOrBlank()) " - فاکتور: $invoiceNum" else ""
                val detStr = if (!invoiceDetails.isNullOrBlank()) " ($invoiceDetails)" else ""
                val expDesc = "خرید از فروشنده ($name) - $company$invStr$detStr"
                // فقط فاکتور؛ پرداختِ هنگام ثبت حذف شد (درخواست کاربر).
                val storeParty = repository.findOrCreateParty(
                    com.example.data.PartySection.STORE,
                    com.example.data.PartySection.STORE.faTitle,
                    name
                )
                repository.insertExpense(
                    ClinicExpense(
                        category = com.example.data.PartySection.STORE.faTitle,
                        expenseGroup = com.example.data.PartySection.STORE.key,
                        partyAccountId = storeParty.id,
                        amount = totalOrders,
                        description = expDesc,
                        date = System.currentTimeMillis(),
                        subAccountName = name,
                        paymentMethod = "DEBT",
                        vendorId = newVendorId
                    )
                )
            }
        }
    }

    // Delete Vendor
    fun deleteVendor(vendor: Vendor) {
        if (!authorizeDomain("vendors", "DELETE_VENDOR", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteVendor(vendor)
        }
    }

    // Add Lab Order
    fun addLabOrder(
        labName: String,
        patientName: String,
        toothNum: String,
        prosthesis: String,
        cost: Long,
        due: Long,
        shadeBrand: String? = null,
        shadeColor: String? = null
    ) {
        if (!authorizeDomain("labs", "ADD_LAB_ORDER")) return
        viewModelScope.launch {
            repository.insertLabOrder(
                LabOrder(
                    labName = labName,
                    patientName = patientName,
                    toothNumber = toothNum,
                    prosthesisType = prosthesis,
                    cost = cost,
                    dueDate = due,
                    status = "ORDERED",
                    shadeBrand = shadeBrand,
                    shadeColor = shadeColor
                )
            )
        }
    }

    // --- Normalized Relational Entities Methods ---
    fun getStagesForTreatment(treatmentId: Long): Flow<List<TreatmentStage>> =
        repository.getStagesForTreatment(treatmentId)

    fun addTreatmentStage(treatmentId: Long, title: String, stageIndex: Int = 0, notes: String = "") {
        if (!authorizeDomain("treatments", "ADD_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val treatmentSyncId = allTreatments.value.find { it.id == treatmentId }?.syncId ?: ""
            repository.insertTreatmentStage(
                TreatmentStage(
                    treatmentId = treatmentId,
                    treatmentSyncId = treatmentSyncId,
                    stageIndex = stageIndex,
                    title = title,
                    notes = notes
                )
            )
        }
    }

    // NOTE: unused by the current UI. Every call site (DentalScreens.kt, AdvancedErpScreens.kt)
    // uses the toggleTreatmentStage(treatment: Treatment, stageName: String) overload further
    // below, which mutates Treatment.completedStages directly instead of the treatment_stages
    // table this operates on. addTreatmentStage/deleteTreatmentStage above DO use this table, so
    // it isn't orphaned — only this particular toggle path is currently dead. Kept for now in
    // case treatment_stages-based completion tracking is wired back into the UI later; if not
    // needed, remove this overload to avoid the two-models-of-completion confusion.
    fun toggleTreatmentStage(stage: TreatmentStage) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val updated = stage.copy(
                isCompleted = !stage.isCompleted,
                completedDate = if (!stage.isCompleted) System.currentTimeMillis() else null
            )
            repository.updateTreatmentStage(updated)
        }
    }

    fun deleteTreatmentStage(stage: TreatmentStage) {
        if (!authorizeDomain("treatments", "DELETE_TREATMENT_STAGE", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteTreatmentStage(stage)
        }
    }

    fun getDocumentsForPatient(patientId: Long): Flow<List<PatientDocument>> =
        repository.getDocumentsForPatient(patientId)

    /**
     * Uploads [localFilePath] to cloud immediately and stores the resulting fileId on the
     * new PatientDocument — the record is not a final/shareable document until fileId is set;
     * it no longer relies on the background SyncEngine.pushPending() to eventually pick up a
     * bare local path.
     */
    fun addPatientDocument(patientId: Long, localFilePath: String, docType: String, title: String = "", notes: String = "") {
        if (!authorizeDomain("patients", "ADD_PATIENT_DOCUMENT")) return
        viewModelScope.launch(Dispatchers.IO) {
            isMediaUploading.value = true
            try {
                val patientSyncId = patients.value.find { it.id == patientId }?.syncId ?: ""
                val uploadedFileId = uploadLocalFileOrNull(localFilePath, com.example.util.StorageCategory.PATIENTS)
                repository.insertPatientDocument(
                    PatientDocument(
                        patientId = patientId,
                        patientSyncId = patientSyncId,
                        uri = localFilePath,
                        fileId = uploadedFileId,
                        // موفق: نسخه‌ی محلی مجزا نگه نمی‌داریم؛ SyncEngine.uploadFile خودش یک کپی زیر
                        // filesDir/images/<fileId> نگه می‌دارد که fetchSignatureBase64/SmartImage از آن می‌خوانند.
                        localCachePath = null,
                        docType = docType,
                        title = title,
                        notes = notes
                    )
                )
                if (uploadedFileId != null) {
                    // آپلود تایید شد؛ فایل موقتِ پیش از آپلود را از دیسک پاک می‌کنیم.
                    com.example.ui.screens.deleteLocalStagingFileQuietly(localFilePath)
                    try {
                        syncEngine.pushPending()
                    } catch (e: Exception) {
                        // Metadata sync will retry later; the file itself is already uploaded
                    }
                }
            } finally {
                isMediaUploading.value = false
            }
        }
    }

    fun deletePatientDocument(doc: PatientDocument) {
        if (!authorizeDomain("patients", "DELETE_PATIENT_DOCUMENT", isDelete = true)) return
        viewModelScope.launch {
            repository.deletePatientDocument(doc)
        }
    }

    fun getInvoicesForVendor(vendorId: Long): Flow<List<VendorInvoice>> =
        repository.getInvoicesForVendor(vendorId)

    fun addVendorInvoice(vendorId: Long, invoiceNumber: String, amount: Long, date: Long = System.currentTimeMillis(), details: String = "", documentFileId: String? = null, isPaid: Boolean = true) {
        if (!authorizeDomain("vendor_invoices", "ADD_VENDOR_INVOICE")) return
        viewModelScope.launch {
            repository.insertVendorInvoice(
                VendorInvoice(
                    vendorId = vendorId,
                    invoiceNumber = invoiceNumber,
                    amount = amount,
                    date = date,
                    details = details,
                    documentFileId = documentFileId,
                    isPaid = isPaid
                )
            )
        }
    }

    fun deleteVendorInvoice(invoice: VendorInvoice) {
        if (!authorizeDomain("vendor_invoices", "DELETE_VENDOR_INVOICE", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteVendorInvoice(invoice)
        }
    }

    // Update Lab Order Status
    fun updateLabOrderStatus(order: LabOrder, newStatus: String) {
        if (!authorizeDomain("labs", "UPDATE_LAB_ORDER")) return
        viewModelScope.launch {
            repository.insertLabOrder(order.copy(status = newStatus))
        }
    }

    // Delete Lab Order — به‌همراه هزینهٔ متصل و پرداخت‌هایش
    fun deleteLabOrder(order: LabOrder) {
        if (!authorizeDomain("labs", "DELETE_LAB_ORDER", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteLabOrder(order)
        }
    }

    /**
     * ویرایش کامل یک سفارش لابراتوار — تغییرات روی هزینهٔ متصل هم اعمال می‌شود
     * تا مبلغ سفارش و مبلغ هزینه هرگز از هم جدا نیفتند.
     */
    fun updateLabOrder(
        order: LabOrder,
        labName: String = order.labName,
        patientName: String = order.patientName,
        toothNumber: String = order.toothNumber,
        prosthesisType: String = order.prosthesisType,
        cost: Long = order.cost,
        dueDate: Long = order.dueDate,
        shadeBrand: String? = order.shadeBrand,
        shadeColor: String? = order.shadeColor
    ) {
        if (!authorizeDomain("labs", "UPDATE_LAB_ORDER")) return
        viewModelScope.launch {
            repository.updateLabOrderCascading(
                order.copy(
                    labName = labName.trim(),
                    patientName = patientName.trim(),
                    toothNumber = toothNumber.trim(),
                    prosthesisType = prosthesisType.trim(),
                    cost = cost,
                    dueDate = dueDate,
                    shadeBrand = shadeBrand,
                    shadeColor = shadeColor,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // Lab Services Catalog Management
    fun addLabService(
        labName: String,
        serviceName: String,
        price: Long,
        notes: String = "",
        frameDeliveryDays: Int = 3,
        finalDeliveryDays: Int = 7
    ) {
        if (!authorizeDomain("labs", "ADD_LAB_SERVICE")) return
        viewModelScope.launch {
            repository.insertLabService(
                LabService(
                    labName = labName,
                    serviceName = serviceName,
                    price = price,
                    notes = notes,
                    frameDeliveryDays = frameDeliveryDays,
                    finalDeliveryDays = finalDeliveryDays
                )
            )
            // به‌درخواست کاربر: لابراتوارِ تعریف‌شده در تعرفهٔ خدمات، همان طرف‌حسابِ
            // بخش «هزینه‌ها ← لابراتوار» است (منبع واحد در کل اپ) — اگر نبود ساخته می‌شود.
            repository.findOrCreateParty(
                com.example.data.PartySection.LAB,
                com.example.data.PartySection.LAB.faTitle,
                labName
            )
        }
    }

    fun updateLabService(service: LabService) {
        if (!authorizeDomain("labs", "UPDATE_LAB_SERVICE")) return
        viewModelScope.launch {
            repository.insertLabService(service)
        }
    }

    fun deleteLabService(service: LabService) {
        if (!authorizeDomain("labs", "DELETE_LAB_SERVICE", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteLabService(service)
        }
    }

    // Integrated Prosthesis Order & Automatic Expense Posting
    fun addProsthesisOrderAndExpense(
        labName: String,
        patientName: String,
        toothNum: String,
        prosthesis: String,
        cost: Long,
        dueDate: Long,
        shadeBrand: String? = null,
        shadeColor: String? = null
    ) {
        if (!authorizeDomain("labs", "ADD_PROSTHESIS_ORDER")) return
        viewModelScope.launch {
            val newOrderId = repository.insertLabOrder(
                LabOrder(
                    labName = labName,
                    patientName = patientName,
                    toothNumber = toothNum,
                    prosthesisType = prosthesis,
                    cost = cost,
                    orderDate = System.currentTimeMillis(),
                    dueDate = dueDate,
                    status = "ORDERED",
                    shadeBrand = shadeBrand,
                    shadeColor = shadeColor
                )
            )
            // Immediately post as Clinic Expense debt under category "لابراتوار"
            val count = com.example.ui.screens.parseTeethCount(toothNum)
            val countStr = if (count > 0) "$count واحد" else ""
            val expDesc = "سفارش $prosthesis $patientName $countStr".trim()
            val matchedVendor = vendors.value.firstOrNull { v ->
                !v.isDeleted && v.name.trim().equals(labName.trim(), ignoreCase = true)
            }
            // فاکتورِ سفارش به طرف‌حسابِ همان لابراتوار وصل می‌شود («لابراتوار آ» جدا از «لابراتوار ب»).
            val labParty = repository.findOrCreateParty(
                com.example.data.PartySection.LAB,
                com.example.data.PartySection.LAB.faTitle,
                labName
            )
            repository.insertExpense(
                ClinicExpense(
                    category = com.example.data.PartySection.LAB.faTitle,
                    expenseGroup = com.example.data.PartySection.LAB.key,
                    partyAccountId = labParty.id,
                    amount = cost,
                    description = expDesc,
                    date = System.currentTimeMillis(),
                    subAccountName = labName,
                    paymentMethod = "DEBT",
                    labOrderId = newOrderId,
                    vendorId = matchedVendor?.id,
                    vendorSyncId = matchedVendor?.syncId.orEmpty()
                )
            )
        }
    }

    // ==========================================================
    // منطق حسابیِ چک — انتقال، برگشتِ انتقال، و همگام‌سازی اسناد
    // ==========================================================

    /** آیا این چک در وضعیتی هست که بشود منتقلش کرد؟ فقط «در جریان وصول». */
    fun canTransferCheque(payment: Payment): Boolean =
        payment.type == "CHEQUE" &&
            !payment.isDeleted &&
            payment.chequeStatus == "PENDING"

    /**
     * اثرِ حسابداریِ یک انتقالِ قبلی را کامل برمی‌گرداند: هر سه سندِ ساخته‌شده در
     * انتقال (ExpensePayment/PersonalTransaction/ChildrenExpense) + ClinicExpenseِ
     * ساخته‌شده حذف می‌شوند. هزینهٔ از پیش‌موجودِ کاربر (expenseId ورودی) هرگز پاک
     * نمی‌شود.
     */
    private suspend fun DentalRepository.reverseChequeTransferDocs(payment: Payment) {
        payment.transferPartyPaymentId?.let { id ->
            getExpensePaymentByIdOnce(id)?.let { deleteExpensePayment(it) }
        }
        payment.transferExpensePaymentId?.let { id ->
            getExpensePaymentByIdOnce(id)?.let { deleteExpensePayment(it) }
        }
        payment.transferTransactionId?.let { id ->
            getPersonalTransactionByIdOnce(id)?.let { deleteTransaction(it) }
        }
        payment.transferChildrenExpenseId?.let { id ->
            getChildrenExpenseByIdOnce(id)?.let { deleteChildrenExpense(it) }
        }
        payment.transferExpenseId?.let { id ->
            getExpenseByIdOnce(id)?.let { deleteExpense(it) }
        }
    }

    /** پاک‌کردن ارجاع‌های انتقال از روی خودِ چک. */
    private fun Payment.clearedOfTransfer(): Payment = copy(
        transferredTo = null,
        transferDate = null,
        transferPartyPaymentId = null,
        transferExpensePaymentId = null,
        transferExpenseId = null,
        transferTransactionId = null,
        transferChildrenExpenseId = null,
        bankTransferConfirmed = false
    )

    /**
     * برگرداندنِ یک انتقال — چک به «در جریان وصول» برمی‌گردد و اسنادِ مقصد
     * حذف می‌شوند.
     */
    fun undoChequeTransfer(payment: Payment) {
        if (!authorizeDomain("cheques", "TRANSFER_CHEQUE_EXPENSE")) return
        viewModelScope.launch {
            repository.inTransaction {
                reverseChequeTransferDocs(payment)
                updatePayment(
                    payment.clearedOfTransfer().copy(
                        chequeStatus = "PENDING",
                        isCleared = false,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /** برچسب فارسی/انگلیسی وضعیت چک. */
    fun chequeStatusLabel(payment: Payment, isPersian: Boolean = true): String = when (payment.chequeStatus) {
        "CLEARED" -> if (isPersian) "پاس‌شده" else "Cleared"
        "BOUNCED" -> if (isPersian) "برگشتی" else "Bounced"
        "TRANSFERRED" -> if (isPersian) {
            "انتقال یافته به: ${payment.transferredTo.orEmpty().ifBlank { "—" }}"
        } else {
            "Transferred to: ${payment.transferredTo.orEmpty().ifBlank { "—" }}"
        }
        else -> if (isPersian) "در جریان وصول" else "In progress"
    }

    /**
     * «پاس شد» — فقط از «در جریان وصول».
     * چکِ انتقال‌یافته را نمی‌شود پاس کرد؛ آن چک دیگر در اختیار مطب نیست.
     */
    fun markChequeCleared(payment: Payment) {
        if (payment.chequeStatus != "PENDING") return
        updatePayment(
            payment.copy(
                chequeStatus = "CLEARED",
                isCleared = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * «برگشت شد» — از هر وضعیتی جز خودِ برگشتی.
     *
     * اگر چک منتقل شده بود، سندِ مقصد هم BOUNCED می‌شود (در
     * [syncChequeTransferDocs]) تا تسویهٔ آن هزینه برگردد؛ ارجاع‌ها عمداً حفظ
     * می‌شوند تا رد حسابداری گم نشود.
     */
    fun markChequeBounced(payment: Payment) {
        if (payment.chequeStatus == "BOUNCED") return
        updatePayment(
            payment.copy(
                chequeStatus = "BOUNCED",
                isCleared = false,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** برگرداندن چک به «در جریان وصول» (خنثی‌کردن پاس/برگشتِ اشتباه). */
    fun markChequePending(payment: Payment) {
        if (payment.chequeStatus == "TRANSFERRED") return
        updatePayment(
            payment.copy(
                chequeStatus = "PENDING",
                isCleared = false,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * تیکِ اولِ یک‌بارمصرف: «تأیید دریافت بانکی».
     *
     * تا زمانی که چک منتقل نشده باشد، این تیک باید زده شود؛ اگر نباشد کارتِ چک در
     * مدیریت چک‌ها با هالهٔ زردِ ۱۰٪ متمایز می‌شود (منطقِ نمایش در UI). فقط برای
     * چکی که هنوز انتقال نیافته (PENDING یا CLEARED) قابل زدن است و پس از فعال‌شدن
     * دیگر برداشته نمی‌شود.
     */
    fun confirmChequeBankReceipt(payment: Payment) {
        if (payment.bankReceiptConfirmed) return
        // تا وقتی منتقل نشده؛ چکِ انتقال‌یافته دیگر در اختیار مطب نیست.
        if (payment.chequeStatus == "TRANSFERRED") return
        updatePayment(payment.copy(bankReceiptConfirmed = true, updatedAt = System.currentTimeMillis()))
    }

    /**
     * تیکِ دومِ یک‌بارمصرف: «تأیید انتقال بانکی».
     *
     * پیش‌نیاز: تیکِ اول (bankReceiptConfirmed) و اینکه چک واقعاً منتقل شده باشد.
     * پس از فعال‌شدن، تا وقتی چک وصول یا برگشت نخورده، هیچ ویرایش/حذفی روی آن
     * ممکن نیست (منطقِ قفل در [isChequeLockedByTransferConfirm]).
     */
    fun confirmChequeBankTransfer(payment: Payment) {
        if (payment.bankTransferConfirmed) return
        if (payment.chequeStatus != "TRANSFERRED") return
        if (!payment.bankReceiptConfirmed) return // پیش‌نیازِ تیکِ اول
        updatePayment(payment.copy(bankTransferConfirmed = true, updatedAt = System.currentTimeMillis()))
    }

    /**
     * آیا این چک به‌خاطرِ «تأیید انتقال بانکی» قفلِ ویرایش/حذف است؟
     * قفل فقط تا وقتی است که چک هنوز وصول/برگشت نخورده باشد.
     */
    fun isChequeLockedByTransferConfirm(payment: Payment): Boolean =
        payment.bankTransferConfirmed &&
            payment.chequeStatus != "CLEARED" &&
            payment.chequeStatus != "BOUNCED"

    /** Compatibility hook: clearing always requires explicit confirmation. */
    fun autoClearOverdueCheques() = Unit

    // Cheque Transfer Helpers
    fun transferChequeToExpense(payment: Payment, recipientName: String, category: String = "سایر", expenseId: Long? = null) {
        if (!authorizeDomain("cheques", "TRANSFER_CHEQUE_EXPENSE")) return
        if (!canTransferCheque(payment)) return
        viewModelScope.launch {
            repository.inTransaction {
                val desc = "انتقال چک شماره ${payment.chequeNumber ?: ""} (بانک ${payment.chequeBank ?: ""}) به $recipientName"
                // هزینه‌ای که خودِ انتقال می‌سازد باید قابل برگشت باشد؛ هزینه‌ای که
                // کاربر از قبل انتخاب کرده هرگز حذف نمی‌شود.
                val createdExpenseId = if (expenseId == null) {
                    insertExpense(
                        ClinicExpense(
                            category = category,
                            amount = payment.amount,
                            description = desc,
                            paymentMethod = "CHEQUE_TRANSFER",
                            chequeNumber = payment.chequeNumber
                        )
                    )
                } else null
                val targetExpenseId = expenseId ?: createdExpenseId!!
                // The endorsed cheque settles the selected expense until it is bounced.
                val expensePaymentId = insertExpensePayment(
                    ExpensePayment(
                        clinicExpenseId = targetExpenseId,
                        amount = payment.amount,
                        method = "CHEQUE",
                        chequeNumber = payment.chequeNumber,
                        chequeBank = payment.chequeBank,
                        chequeDueDate = payment.chequeDueDate,
                        chequeStatus = "PENDING",
                        note = desc
                    )
                )
                updatePayment(
                    payment.copy(
                        chequeStatus = "TRANSFERRED",
                        transferredTo = recipientName,
                        transferDate = System.currentTimeMillis(),
                        transferExpensePaymentId = expensePaymentId,
                        transferExpenseId = createdExpenseId,
                        transferTransactionId = null,
                        transferChildrenExpenseId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun transferChequeToPersonal(payment: Payment, recipientName: String, subAccountName: String = "") {
        if (!authorizeDomain("cheques", "TRANSFER_CHEQUE_PERSONAL")) return
        if (!canTransferCheque(payment)) return
        viewModelScope.launch {
            repository.inTransaction {
                val desc = "انتقال چک شماره ${payment.chequeNumber ?: ""} (بانک ${payment.chequeBank ?: ""}) بابت $recipientName"
                val txId = insertTransaction(
                    PersonalTransaction(
                        type = "EXPENSE",
                        subAccountName = subAccountName.ifBlank { "انتقال چک" },
                        amount = payment.amount,
                        description = desc,
                        paymentMethod = "CHEQUE_TRANSFER",
                        chequeNumber = payment.chequeNumber
                    )
                )
                updatePayment(
                    payment.copy(
                        chequeStatus = "TRANSFERRED",
                        transferredTo = recipientName,
                        transferDate = System.currentTimeMillis(),
                        transferTransactionId = txId,
                        transferExpensePaymentId = null,
                        transferExpenseId = null,
                        transferChildrenExpenseId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * انتقال چک به «حساب اشخاص» — مستند با دو سند حسابداری، دقیقاً مثل مسیر پیامک‌های بانکی:
     *  1) ChildrenExpense (حساب اشخاص) — مبنای گزارش «حساب اشخاص» با نام شخص
     *  2) PersonalTransaction با پیشوند «حساب اشخاص:» — دیده می‌شود در جریات همان شخص
     * در هر دو حالت وضعیت چک به TRANSFERRED تغییر می‌کند.
     */
    fun transferChequeToPerson(payment: Payment, personName: String) {
        val person = personName.trim()
        if (person.isBlank()) return
        if (!authorizeDomain("cheques", "TRANSFER_CHEQUE_PERSONAL")) return
        if (!canTransferCheque(payment)) return
        viewModelScope.launch {
            repository.inTransaction {
                val desc = "انتقال چک شماره ${payment.chequeNumber ?: ""} (بانک ${payment.chequeBank ?: ""}) بابت $person"
                val childId = insertChildrenExpense(
                    ChildrenExpense(childName = person, title = desc, amount = payment.amount)
                )
                val txId = insertTransaction(
                    PersonalTransaction(
                        type = "EXPENSE",
                        subAccountName = "حساب اشخاص: $person",
                        amount = payment.amount,
                        description = desc,
                        paymentMethod = "CHEQUE_TRANSFER",
                        chequeNumber = payment.chequeNumber
                    )
                )
                updatePayment(
                    payment.copy(
                        chequeStatus = "TRANSFERRED",
                        transferredTo = person,
                        transferDate = System.currentTimeMillis(),
                        transferChildrenExpenseId = childId,
                        transferTransactionId = txId,
                        transferExpensePaymentId = null,
                        transferExpenseId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // Fixed Incomes Management & Automatic Processing
    fun addFixedIncome(title: String, amount: Long, dayOfMonth: Int, bankAccountId: Long?, bankAccountName: String, subAccountName: String) {
        if (!authorizeDomain("personal", "ADD_FIXED_INCOME")) return
        viewModelScope.launch {
            repository.insertFixedIncome(
                FixedIncome(
                    title = title,
                    amount = amount,
                    dayOfMonth = dayOfMonth,
                    bankAccountId = bankAccountId,
                    bankAccountName = bankAccountName,
                    subAccountName = subAccountName
                )
            )
        }
    }

    fun deleteFixedIncome(income: FixedIncome) {
        if (!authorizeDomain("personal", "DELETE_FIXED_INCOME", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteFixedIncome(income)
        }
    }

    fun checkAndProcessFixedIncomes() {
        viewModelScope.launch {
            val cal = java.util.Calendar.getInstance()
            val currentDay = cal.get(java.util.Calendar.DAY_OF_MONTH)
            val shamsiDate = toShamsiDate(System.currentTimeMillis())
            val currentYearMonth = if (shamsiDate.length >= 7) shamsiDate.substring(0, 7) else "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.MONTH) + 1}"

            fixedIncomes.value.filter { it.isActive }.forEach { income ->
                if (income.lastProcessedMonth != currentYearMonth && currentDay >= income.dayOfMonth) {
                    repository.insertTransaction(
                        PersonalTransaction(
                            type = "INCOME",
                            subAccountName = income.subAccountName.ifBlank { "درآمد ثابت" },
                            bankAccountId = income.bankAccountId,
                            amount = income.amount,
                            description = "واریز خودکار درآمد ثابت ماهانه: ${income.title}",
                            paymentMethod = "CARD_TRANSFER"
                        )
                    )
                    repository.insertFixedIncome(income.copy(lastProcessedMonth = currentYearMonth))
                }
            }
        }
    }

    // Appointment Archive Helper
    fun archiveAppointment(appointment: Appointment) {
        if (!authorizeDomain("appointments", "ARCHIVE_APPOINTMENT")) return
        viewModelScope.launch {
            repository.updateAppointment(appointment.copy(isCompleted = true, isArchived = true))
            val txIds = appointment.plannedTreatmentIds.split(",").mapNotNull { it.trim().toLongOrNull() }
            txIds.forEach { id ->
                val tx = allTreatments.value.find { it.id == id }
                if (tx != null) {
                    val allStages = parseStagesList(tx.stages)
                    val completedStages = parseStageMarkers(tx.completedStages)
                    val ignoredStages = parseStageMarkers(tx.ignoredStages)
                    val resolvedStages = (completedStages + ignoredStages).distinct()
                    val completedCount = resolvedStages.size
                    val totalSessions = allStages.size
                    
                    if (completedCount >= totalSessions && totalSessions > 0) {
                        repository.insertTreatment(tx.copy(status = "COMPLETED"))
                    } else if (totalSessions == 0) {
                        repository.insertTreatment(tx.copy(status = "COMPLETED"))
                    } else if (completedCount > 0 && tx.status != "IN_PROGRESS") {
                        // تراپی هنوز تمام نشده — وضعیت باید «در حال انجام» بماند،
                        // نه اینکه روی مقدار قبلی (مثلاً PLANNED) جا بماند.
                        repository.insertTreatment(tx.copy(status = "IN_PROGRESS"))
                    }
                }
            }
        }
    }

    // Add Maintenance Record (with invoice and tech support)
    fun addMaintenance(equipment: String, desc: String, cost: Long, date: Long, nextCheck: Long?) {
        if (!authorizeDomain("maintenance", "ADD_MAINTENANCE")) return
        viewModelScope.launch {
            val newMaintId = repository.insertMaintenance(
                EquipmentMaintenance(
                    equipmentName = equipment,
                    description = desc,
                    cost = cost,
                    date = date,
                    nextServiceDate = nextCheck
                )
            )
            if (cost > 0) {
                val expDesc = "تعمیر و نگهداری ($equipment) - $desc"
                // فاکتورِ سرویس به طرف‌حسابِ «فنی مهندسی» با نام تکنسین وصل می‌شود
                // (هر تکنسین/شرکتِ تعمیراتی طرف‌حسابِ خودش را دارد).
                val engParty = repository.findOrCreateParty(
                    com.example.data.PartySection.ENGINEERING,
                    com.example.data.PartySection.ENGINEERING.faTitle,
                    "تکنسین تجهیزات"
                )
                repository.insertExpense(
                    ClinicExpense(
                        category = com.example.data.PartySection.ENGINEERING.faTitle,
                        expenseGroup = com.example.data.PartySection.ENGINEERING.key,
                        partyAccountId = engParty.id,
                        amount = cost,
                        description = expDesc,
                        date = date,
                        subAccountName = "تکنسین تجهیزات",
                        paymentMethod = "DEBT",
                        maintenanceId = newMaintId
                    )
                )
            }
        }
    }

    fun addMaintenanceWithInvoice(equipment: String, desc: String, cost: Long, date: Long, nextCheck: Long?, technician: String?, invoiceNum: String?) {
        if (!authorizeDomain("maintenance", "ADD_MAINTENANCE")) return
        viewModelScope.launch {
            val newMaintId = repository.insertMaintenance(
                EquipmentMaintenance(
                    equipmentName = equipment,
                    description = desc,
                    cost = cost,
                    date = date,
                    nextServiceDate = nextCheck,
                    technicianName = technician,
                    invoiceNumber = invoiceNum
                )
            )
            val techName = technician.takeIf { !it.isNullOrBlank() } ?: "تکنسین تجهیزات"
            if (cost > 0) {
                val invStr = if (!invoiceNum.isNullOrBlank()) " - فاکتور: $invoiceNum" else ""
                val expDesc = "تعمیر و نگهداری ($equipment) - $desc$invStr"
                // طرف‌حسابِ فنی مهندسی با نامِ واقعیِ تکنسین/شرکتِ تعمیراتی (نه نامِ کلی).
                val engParty = repository.findOrCreateParty(
                    com.example.data.PartySection.ENGINEERING,
                    com.example.data.PartySection.ENGINEERING.faTitle,
                    techName
                )
                repository.insertExpense(
                    ClinicExpense(
                        category = com.example.data.PartySection.ENGINEERING.faTitle,
                        expenseGroup = com.example.data.PartySection.ENGINEERING.key,
                        partyAccountId = engParty.id,
                        amount = cost,
                        description = expDesc,
                        date = date,
                        subAccountName = techName,
                        paymentMethod = "DEBT",
                        maintenanceId = newMaintId
                    )
                )
            }
        }
    }

    // Expense Payment Actions
    fun insertExpensePayment(payment: ExpensePayment) {
        if (!authorizeDomain("expenses", "ADD_EXPENSE_PAYMENT")) return
        viewModelScope.launch {
            repository.insertExpensePayment(payment)
        }
    }

    fun deleteExpensePayment(payment: ExpensePayment) {
        if (!authorizeDomain("expenses", "DELETE_EXPENSE_PAYMENT", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteExpensePayment(payment)
        }
    }

    fun updateExpenseChequeStatus(payment: ExpensePayment, status: String) {
        if (payment.method != "CHEQUE" || status !in setOf("PENDING", "CLEARED", "BOUNCED")) return
        if (!authorizeDomain("expenses", "UPDATE_EXPENSE_PAYMENT")) return
        viewModelScope.launch {
            repository.insertExpensePayment(payment.copy(chequeStatus = status, updatedAt = System.currentTimeMillis()))
        }
    }

    // Delete Maintenance Record
    fun deleteMaintenance(record: EquipmentMaintenance) {
        if (!authorizeDomain("maintenance", "DELETE_MAINTENANCE", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteMaintenance(record)
        }
    }

    // Update Vendor
    fun updateVendor(vendor: Vendor) {
        if (!authorizeDomain("vendors", "UPDATE_VENDOR")) return
        viewModelScope.launch {
            repository.insertVendor(vendor)
        }
    }

    /**
     * تسویه فروشنده با تخصیص [ExpensePayment] روی هزینه‌های پرداخت‌نشدهٔ همان فروشنده.
     * دیگر هزینهٔ خرید جدید نمی‌سازد (باگ قبلی addExpense روی «تسویه حساب فروشنده»).
     */
    fun payVendor(vendor: Vendor, amount: Long, note: String = "") {
        if (amount <= 0L) return
        if (!authorizeDomain("vendors", "UPDATE_VENDOR")) return
        viewModelScope.launch {
            val related = clinicExpenses.value
                .filter { it.belongsToVendor(vendor) }
                .sortedBy { it.date }
            val existingPays = allExpensePayments.value.filter { !it.isDeleted }
            val payNote = note.ifBlank { "پرداخت به ${vendor.name}" }
            if (related.isEmpty()) {
                repository.insertVendor(
                    vendor.copy(
                        totalPaid = vendor.totalPaid + amount,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                return@launch
            }
            var remaining = amount
            for (exp in related) {
                if (remaining <= 0L) break
                val already = existingPays.filter { it.countsAsPartyPayment() && it.clinicExpenseId == exp.id }.sumOf { it.amount }
                val due = (exp.amount - already).coerceAtLeast(0L)
                if (due <= 0L) continue
                val chunk = minOf(due, remaining)
                repository.insertExpensePayment(
                    ExpensePayment(
                        clinicExpenseId = exp.id,
                        amount = chunk,
                        method = "CARD_TRANSFER",
                        date = System.currentTimeMillis(),
                        note = payNote
                    )
                )
                remaining -= chunk
            }
            if (remaining > 0L) {
                repository.insertExpensePayment(
                    ExpensePayment(
                        clinicExpenseId = related.last().id,
                        amount = remaining,
                        method = "CARD_TRANSFER",
                        date = System.currentTimeMillis(),
                        note = payNote
                    )
                )
            }
            repository.insertVendor(
                vendor.copy(
                    totalPaid = vendor.totalPaid + amount,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    // Add Clinic Expense
    fun addExpense(
        category: String,
        amount: Long,
        desc: String,
        paymentMethod: String = "CARD_TRANSFER",
        subAccountName: String = "",
        chequeNumber: String? = null,
        date: Long = System.currentTimeMillis(),
        expenseGroup: String = ""
    ) {
        if (!authorizeDomain("expenses", "ADD_EXPENSE")) return
        viewModelScope.launch {
            repository.insertExpense(
                ClinicExpense(
                    category = category,
                    expenseGroup = expenseGroup,
                    amount = amount,
                    description = desc,
                    paymentMethod = paymentMethod,
                    subAccountName = subAccountName,
                    chequeNumber = chequeNumber,
                    date = date
                )
            )
        }
    }

    // Delete Clinic Expense
    fun updateExpense(expense: ClinicExpense) {
        if (!authorizeDomain("expenses", "UPDATE_EXPENSE")) return
        viewModelScope.launch {
            repository.updateExpense(expense)
        }
    }

    fun deleteExpense(expense: ClinicExpense) {
        if (!authorizeDomain("expenses", "DELETE_EXPENSE", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteExpense(expense)
        }
    }

    // ======================================================================
    //  طرف‌حساب‌ها (Party Accounts) — طرف‌حساب‌های چندطرف‌حسابی هزینه‌های مطب
    // ======================================================================

    /** افزودن طرف‌حساب جدید: مثلاً (LAB, «لابراتوار», «آ»). بدون تکرارِ (بخش، نام). */
    fun addPartyAccount(
        section: com.example.data.PartySection,
        name: String,
        phone: String = "",
        notes: String = "",
        title: String = ""
    ) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        if (!authorizeDomain("expenses", "ADD_PARTY_ACCOUNT")) return
        viewModelScope.launch {
            val existing = partyAccounts.value.firstOrNull {
                it.section.trim() == section.key && it.name.trim().equals(trimmed, ignoreCase = true)
            }
            if (existing != null) return@launch
            val letter = ('A'.code + partyAccounts.value.count { it.section.trim() == section.key }).toChar().toString()
            val code = when (section) {
                com.example.data.PartySection.LAB -> "LAB"
                com.example.data.PartySection.ENGINEERING -> "ENG"
                com.example.data.PartySection.STORE -> "STORE"
                com.example.data.PartySection.RUNNING -> "RUN"
                com.example.data.PartySection.SALARY -> "SAL"
                com.example.data.PartySection.OTHER -> "OTHER"
            } + letter
            repository.insertPartyAccount(
                com.example.data.PartyAccount(
                    section = section.key,
                    code = code,
                    title = title.trim().ifBlank { section.faTitle },
                    name = trimmed,
                    phone = phone.trim(),
                    notes = notes.trim()
                )
            )
        }
    }

    /**
     * هماهنگ‌سازی طرف‌حساب‌های «حقوق پرسنل» با لیست پرسنل مطب — منبع واحد:
     * برای هر پرسنل فعال، اگر طرف‌حساب SALARY نبود ساخته می‌شود؛ پرسنل حذف‌شده
     * از لیست حقوق نمایش داده نمی‌شود (فیلتر در UI).
     */
    fun syncSalaryParties() {
        viewModelScope.launch {
            val staff = repository.allStaffMembers.first().filter { !it.isDeleted && it.fullName.isNotBlank() }
            staff.forEach { s ->
                repository.findOrCreateParty(
                    com.example.data.PartySection.SALARY,
                    com.example.data.PartySection.SALARY.faTitle,
                    s.fullName
                )
            }
        }
    }

    fun updatePartyAccount(account: com.example.data.PartyAccount) {
        if (!authorizeDomain("expenses", "UPDATE_PARTY_ACCOUNT")) return
        viewModelScope.launch {
            repository.insertPartyAccount(account.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    /** حذف نرمِ طرف‌حساب — فاکتورها و پرداخت‌هایش برای حفظ دفتر باقی می‌مانند. */
    fun deletePartyAccount(account: com.example.data.PartyAccount) {
        if (!authorizeDomain("expenses", "DELETE_PARTY_ACCOUNT", isDelete = true)) return
        viewModelScope.launch {
            repository.deletePartyAccount(account)
        }
    }

    /**
     * صدور فاکتور برای یک طرف‌حساب — بدون پرداختِ هنگام ثبت (خواستهٔ کاربر).
     * فاکتور در clinic_expenses با partyAccountId و شمارهٔ خودکارِ per-party می‌نشیند.
     */
    fun addPartyInvoice(
        party: com.example.data.PartyAccount,
        amount: Long,
        description: String = "",
        date: Long = System.currentTimeMillis(),
        invoiceDetails: String = ""
    ) {
        if (amount <= 0L) return
        if (!authorizeDomain("expenses", "ADD_EXPENSE")) return
        viewModelScope.launch {
            repository.insertExpense(
                ClinicExpense(
                    category = party.title.ifBlank { party.section },
                    expenseGroup = party.section,
                    partyAccountId = party.id,
                    amount = amount,
                    description = listOf(description.trim(), invoiceDetails.trim()).filter { it.isNotBlank() }.joinToString(" — "),
                    date = date,
                    subAccountName = party.name,
                    paymentMethod = "DEBT"
                )
            )
        }
    }

    /**
     * پرداخت به طرف حساب — از هر درگاهی: نقد از صندوق (bankAccountId = صندوق)،
     * کارت/کارت‌خوان (bankAccountId = حساب بانکی)، برداشت پیامک بانکی
     * (sourceSmsNativeId) یا انتقال چک (method = CHEQUE با شمارهٔ چک).
     * پرداختِ «کلیِ طرف‌حساب» (بدون فاکتورِ مشخص) با clinicExpenseId = 0 ثبت می‌شود.
     */
    fun addPartyPayment(
        party: com.example.data.PartyAccount,
        amount: Long,
        method: String = "CARD_TRANSFER",
        bankAccountId: Long? = null,
        date: Long = System.currentTimeMillis(),
        note: String = "",
        chequeNumber: String? = null,
        chequeDueDate: Long? = null,
        chequeBank: String? = null,
        sourceSmsNativeId: String = ""
    ) {
        if (amount <= 0L) return
        if (!authorizeDomain("expenses", "ADD_EXPENSE_PAYMENT")) return
        viewModelScope.launch {
            repository.insertExpensePayment(
                ExpensePayment(
                    clinicExpenseId = 0L,
                    partyAccountId = party.id,
                    amount = amount,
                    method = method,
                    bankAccountId = bankAccountId,
                    sourceSmsNativeId = sourceSmsNativeId,
                    chequeNumber = chequeNumber,
                    chequeDueDate = chequeDueDate,
                    chequeBank = chequeBank,
                    chequeStatus = if (method == "CHEQUE") "PENDING" else "CLEARED",
                    date = date,
                    note = note.ifBlank { "پرداخت به ${party.displayLabel}" }
                )
            )
        }
    }

    /** پرداختِ یک فاکتورِ مشخصِ یک طرف‌حساب (مبلغ می‌تواند از ماندهٔ فاکتور بیشتر باشد = بستانکاری). */
    fun payPartyInvoice(
        party: com.example.data.PartyAccount,
        invoice: ClinicExpense,
        amount: Long,
        method: String = "CARD_TRANSFER",
        bankAccountId: Long? = null,
        date: Long = System.currentTimeMillis(),
        note: String = ""
    ) {
        if (amount <= 0L) return
        if (!authorizeDomain("expenses", "ADD_EXPENSE_PAYMENT")) return
        viewModelScope.launch {
            repository.insertExpensePayment(
                ExpensePayment(
                    clinicExpenseId = invoice.id,
                    partyAccountId = party.id,
                    amount = amount,
                    method = method,
                    bankAccountId = bankAccountId,
                    date = date,
                    note = note.ifBlank { "پرداخت فاکتور ${invoice.invoiceNumber.ifBlank { invoice.id.toString() }} — ${party.displayLabel}" }
                )
            )
        }
    }

    /**
     * انتقال چک به یک طرف حساب — یک سند واحد: ExpensePayment چکی با partyAccountId.
     * برخلاف مسیرهای قدیمی، دیگر ClinicExpenseٔ تازه ساخته نمی‌شود و اشاره‌گرِ
     * transferPartyPaymentId روی چک نگه داشته می‌شود تا وصول/برگشت/لغوِ انتقال
     * اثرش را روی دفتر طرف‌حساب برساند.
     */
    fun transferChequeToParty(payment: Payment, party: com.example.data.PartyAccount) {
        if (!authorizeDomain("cheques", "TRANSFER_CHEQUE_EXPENSE")) return
        if (!canTransferCheque(payment)) return
        viewModelScope.launch {
            repository.inTransaction {
                val desc = "انتقال چک شماره ${payment.chequeNumber ?: ""} (بانک ${payment.chequeBank ?: ""}) به ${party.displayLabel}"
                val partyPaymentId = repository.insertExpensePayment(
                    ExpensePayment(
                        clinicExpenseId = 0L,
                        partyAccountId = party.id,
                        amount = payment.amount,
                        method = "CHEQUE",
                        chequeNumber = payment.chequeNumber,
                        chequeDueDate = payment.chequeDueDate,
                        chequeBank = payment.chequeBank,
                        chequeStatus = "PENDING",
                        date = System.currentTimeMillis(),
                        note = desc
                    )
                )
                updatePayment(
                    payment.copy(
                        chequeStatus = "TRANSFERRED",
                        transferredTo = party.displayLabel,
                        transferDate = System.currentTimeMillis(),
                        transferPartyPaymentId = partyPaymentId,
                        transferExpensePaymentId = null,
                        transferExpenseId = null,
                        transferTransactionId = null,
                        transferChildrenExpenseId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // Add Personal Transaction with Sub-Account & Bank Card mappings
    fun addPersonalTx(
        type: String,
        amount: Long,
        desc: String,
        paymentMethod: String = "CARD_TRANSFER",
        subAccountName: String = "",
        chequeNumber: String? = null
    ) {
        if (!authorizeDomain("personal", "ADD_PERSONAL_TRANSACTION")) return
        viewModelScope.launch {
            repository.insertTransaction(
                PersonalTransaction(
                    type = type,
                    amount = amount,
                    description = desc,
                    paymentMethod = paymentMethod,
                    subAccountName = subAccountName,
                    chequeNumber = chequeNumber
                )
            )
        }
    }

    fun addPersonalTxDetailed(type: String, subAccountName: String, bankAccountId: Long?, amount: Long, desc: String) {
        if (!authorizeDomain("personal", "ADD_PERSONAL_TRANSACTION")) return
        viewModelScope.launch {
            repository.insertTransaction(
                PersonalTransaction(
                    type = type,
                    subAccountName = subAccountName,
                    bankAccountId = bankAccountId,
                    amount = amount,
                    description = desc
                )
            )
        }
    }

    /**
     * ثبت تراکنش شخصی با همهٔ متغیرها (فرم جدید «هزینه شخصی / درآمد شخصی»):
     * زیرحساب مقصد، شخصِ اختیاری (حساب اشخاص)، مبلغ، تاریخِ انتخابی از تقویم،
     * توضیحات و حساب بانکی مرتبط.
     *
     * اثر بانکی فقط از خودِ تراکنش می‌آد (personalBankDelta): هزینه موجودی را کم
     * و درآمد موجودی را زیاد می‌کند. اگر «شخص» انتخاب شده باشد، علاوه بر تراکنشِ
     * زیرحساب، یک سندِ ریزحسابِ شخص (ChildrenExpense — بدون اثر بانکی) ثبت
     * می‌شود تا جریاتِ همان شخص در «حساب اشخاص» ظاهر شود (الگوی مشترک با
     * انتقال چک به شخص) و هیچ سند دوگانهٔ بانکی ساخته نشود.
     */
    fun addPersonalTransactionFull(
        type: String,
        subAccountName: String,
        amount: Long,
        description: String,
        bankAccountId: Long?,
        date: Long,
        personName: String = ""
    ) {
        if (amount <= 0L) return
        if (!authorizeDomain("personal", "ADD_PERSONAL_TRANSACTION")) return
        viewModelScope.launch {
            repository.inTransaction {
                insertTransaction(
                    PersonalTransaction(
                        type = type,
                        subAccountName = subAccountName.trim().ifBlank { if (type == "INCOME") "درآمد شخصی" else "سایر" },
                        bankAccountId = bankAccountId,
                        amount = amount,
                        description = description.trim(),
                        date = date
                    )
                )
                val person = personName.trim()
                if (person.isNotBlank()) {
                    insertChildrenExpense(
                        ChildrenExpense(
                            childName = person,
                            title = description.trim().ifBlank { subAccountName.trim().ifBlank { if (type == "INCOME") "درآمد شخصی" else "هزینه شخصی" } },
                            amount = amount,
                            date = date
                        )
                    )
                }
            }
        }
    }

    // Delete Personal Transaction
    fun deletePersonalTx(tx: PersonalTransaction) {
        if (!authorizeDomain("personal", "DELETE_PERSONAL_TRANSACTION", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteTransaction(tx)
        }
    }

    // Update Personal Transaction
    fun updatePersonalTx(tx: PersonalTransaction) {
        if (!authorizeDomain("personal", "UPDATE_PERSONAL_TRANSACTION")) return
        viewModelScope.launch {
            repository.insertTransaction(tx)
        }
    }

    // --- OPERATIONS ON THE NEW ERPS ---

    // Treatment Templates
    fun addTemplate(title: String, defaultCost: Long, defaultNotes: String, stages: String = "", isStarred: Boolean = true, titleFa: String = "") {
        if (!authorizeDomain("templates", "ADD_TEMPLATE")) return
        viewModelScope.launch {
            repository.insertTemplate(TreatmentTemplate(title = title, titleFa = titleFa, defaultCost = defaultCost, defaultNotes = defaultNotes, stages = stages, isStarred = isStarred))
        }
    }

    fun updateTemplate(template: TreatmentTemplate) {
        if (!authorizeDomain("templates", "UPDATE_TEMPLATE")) return
        viewModelScope.launch {
            repository.insertTemplate(template)
        }
    }

    // Prescriptions — ثبت/به‌روزرسانی نسخه دارویی پس از تایید پزشک
    
    val drugTemplates: StateFlow<List<com.example.data.DrugTemplate>> = repository.allDrugTemplates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveDrugTemplate(template: com.example.data.DrugTemplate) {
        viewModelScope.launch {
            repository.insertDrugTemplate(template)
        }
    }

    fun deleteDrugTemplate(template: com.example.data.DrugTemplate) {
        viewModelScope.launch {
            repository.deleteDrugTemplate(template)
        }
    }

    fun savePrescription(prescription: Prescription, onSaved: (Long) -> Unit = {}) {
        if (!authorizeDomain("clinical", "SAVE_PRESCRIPTION", doctorOnly = true)) return
        viewModelScope.launch {
            val id = repository.insertPrescription(prescription)
            onSaved(id)
        }
    }

    // جایگزینی و ویرایش متن مرحله/جلسه جاری فقط برای همین پرونده بیمار
    fun replaceCurrentStageText(treatment: Treatment, oldStageText: String, newStageText: String) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val stagesList = parseStagesList(treatment.stages).toMutableList()
            val idx = stagesList.indexOf(oldStageText)
            if (idx != -1) {
                stagesList[idx] = newStageText
            } else if (newStageText.isNotBlank()) {
                stagesList.add(newStageText)
            }
            val completedList = parseStageMarkers(treatment.completedStages).toMutableList()
            val cIdx = completedList.indexOf(oldStageText)
            if (cIdx != -1) completedList[cIdx] = newStageText

            val ignoredList = parseStageMarkers(treatment.ignoredStages).toMutableList()
            val iIdx = ignoredList.indexOf(oldStageText)
            if (iIdx != -1) ignoredList[iIdx] = newStageText

            // نگاشت «مرحله → جلسه» هم باید با نام جدید جابه‌جا شود، وگرنه با حذف
            // آن جلسه، مرحلهٔ تغییرنام‌یافته دیگر پیدا و برگردانده نمی‌شود.
            val sessionMap = parseStageSessions(treatment.stageSessions).toMutableMap()
            sessionMap.remove(oldStageText)?.let { sessionMap[newStageText] = it }

            repository.insertTreatment(
                treatment.copy(
                    stages = joinStagesList(stagesList),
                    completedStages = joinStageMarkers(completedList),
                    ignoredStages = joinStageMarkers(ignoredList),
                    stageSessions = joinStageSessions(sessionMap)
                )
            )
        }
    }

    // تکرار جلسه قبلی (کپی از مرحله قبلی با عنوان تکرار جلسه قبل)
    fun repeatStageForCurrentSession(treatment: Treatment, stageToRepeat: String) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val stagesList = parseStagesList(treatment.stages).toMutableList()
            val repeatedTitle = if (stageToRepeat.contains("تکرار جلسه قبل")) stageToRepeat else "$stageToRepeat (تکرار جلسه قبل)"
            stagesList.add(repeatedTitle)
            repository.insertTreatment(treatment.copy(stages = joinStagesList(stagesList)))
        }
    }

    fun moveTemplateUp(template: TreatmentTemplate) {
        if (!authorizeDomain("templates", "REORDER_TEMPLATE")) return
        viewModelScope.launch {
            val list = templates.value
            val index = list.indexOfFirst { it.id == template.id }
            if (index > 0) {
                val updatedList = list.mapIndexed { idx, item -> item.copy(sortOrder = idx) }.toMutableList()
                val targetItem = updatedList[index]
                val prevItem = updatedList[index - 1]
                val targetStarred = targetItem.isStarred
                val prevStarred = prevItem.isStarred
                updatedList[index] = prevItem.copy(sortOrder = index, isStarred = targetStarred)
                updatedList[index - 1] = targetItem.copy(sortOrder = index - 1, isStarred = prevStarred)
                repository.insertTemplates(updatedList)
            }
        }
    }

    fun moveTemplateDown(template: TreatmentTemplate) {
        if (!authorizeDomain("templates", "REORDER_TEMPLATE")) return
        viewModelScope.launch {
            val list = templates.value
            val index = list.indexOfFirst { it.id == template.id }
            if (index in 0 until list.lastIndex) {
                val updatedList = list.mapIndexed { idx, item -> item.copy(sortOrder = idx) }.toMutableList()
                val targetItem = updatedList[index]
                val nextItem = updatedList[index + 1]
                val targetStarred = targetItem.isStarred
                val nextStarred = nextItem.isStarred
                updatedList[index] = nextItem.copy(sortOrder = index, isStarred = targetStarred)
                updatedList[index + 1] = targetItem.copy(sortOrder = index + 1, isStarred = nextStarred)
                repository.insertTemplates(updatedList)
            }
        }
    }

    fun toggleTemplateStar(template: TreatmentTemplate) {
        if (!authorizeDomain("templates", "UPDATE_TEMPLATE")) return
        viewModelScope.launch {
            repository.insertTemplate(template.copy(isStarred = !template.isStarred))
        }
    }

    fun deleteTemplate(template: TreatmentTemplate) {
        if (!authorizeDomain("templates", "DELETE_TEMPLATE", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteTemplate(template)
        }
    }

    // Bank Accounts
    fun addBankAccount(
        bankName: String,
        cardHolder: String,
        cardNumber: String,
        accountNumber: String,
        balance: Long,
        smsKeywords: String,
        cvv2: String = "",
        expireMonth: String = "",
        expireYear: String = "",
        iban: String = ""
    ) {
        if (!authorizeDomain("bank", "ADD_BANK_ACCOUNT")) return
        viewModelScope.launch {
            repository.insertBankAccount(
                BankAccount(
                    bankName = bankName,
                    cardHolder = cardHolder,
                    cardNumber = cardNumber,
                    accountNumber = accountNumber,
                    balance = balance,
                    smsKeywords = smsKeywords,
                    cvv2 = cvv2,
                    expireMonth = expireMonth,
                    expireYear = expireYear,
                    iban = iban
                )
            )
        }
    }

    fun updateBankAccount(account: BankAccount, updateBalance: Boolean = true) {
        if (!authorizeDomain("bank", "UPDATE_BANK_ACCOUNT")) return
        viewModelScope.launch {
            repository.insertBankAccount(account, updateBalance = updateBalance)
        }
    }

    fun deleteBankAccount(account: BankAccount) {
        if (!authorizeDomain("bank", "DELETE_BANK_ACCOUNT", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteBankAccount(account)
        }
    }

    /** مخفی/نمایشِ کارت بانکی (میانبر مکث طولانی روی لوگو). */
    fun toggleBankAccountHidden(account: BankAccount) {
        if (!authorizeDomain("bank", "UPDATE_BANK_ACCOUNT")) return
        viewModelScope.launch {
            repository.insertBankAccount(account.copy(isHidden = !account.isHidden, updatedAt = System.currentTimeMillis()), updateBalance = false)
        }
    }

    /**
     * جابه‌جایی ترتیبِ نمایشِ یک کارت بین کارت‌های بانکی (مثل کارت‌های واقعی).
     * ابتدا اگر ترتیب‌ها هم‌مقدارند نرمال‌سازی می‌شوند، سپس دو کارتِ مجاور جای
     * sortOrder خود را عوض می‌کنند.
     */
    fun moveBankAccount(account: BankAccount, up: Boolean) {
        if (!authorizeDomain("bank", "UPDATE_BANK_ACCOUNT")) return
        viewModelScope.launch {
            val ordered = bankAccounts.value
                .filter { !it.isCashFund }
                .sortedWith(compareBy({ it.sortOrder }, { it.bankName }))
            // نرمال‌سازی sortOrder به ۰..n تا جابه‌جایی همیشه قابل‌اتکا باشد
            val normalized = ordered.mapIndexed { idx, acc -> acc.copy(sortOrder = idx) }
            val index = normalized.indexOfFirst { it.id == account.id }
            if (index < 0) return@launch
            val target = if (up) index - 1 else index + 1
            if (target < 0 || target >= normalized.size) {
                // فقط نرمال‌سازی را ذخیره کن (اگر لازم بود) بدون جابه‌جایی
                normalized.forEachIndexed { i, acc ->
                    if (ordered[i].sortOrder != acc.sortOrder) repository.insertBankAccount(acc, updateBalance = false)
                }
                return@launch
            }
            val a = normalized[index]
            val b = normalized[target]
            repository.insertBankAccount(a.copy(sortOrder = b.sortOrder, updatedAt = System.currentTimeMillis()), updateBalance = false)
            repository.insertBankAccount(b.copy(sortOrder = a.sortOrder, updatedAt = System.currentTimeMillis()), updateBalance = false)
        }
    }

    /**
     * تخصیص گروهی (Bulk Assignment) تمامی پیامک‌های ثبت‌نشده با شماره حساب مشابه به یک حساب بانکی
     */
    fun assignPendingSmsToAccount(accountNumberRaw: String, bankAccount: BankAccount) {
        viewModelScope.launch(Dispatchers.IO) {
            // رفع باگ ۵ (ریستور لیست پیامک‌ها): قبلاً اینجا فقط با یک UPDATE خامِ دیتابیس محلی
            // وضعیت را عوض می‌کرد؛ در نتیجه نه updatedAt جلو می‌رفت و نه عملیات سینک در صف قرار
            // می‌گرفت — بعد از خروج/ورود، سرور همان نسخه قدیمی را برمی‌گرداند. حالا تک‌تک
            // پیامک‌ها از مسیر repository (درج + صف سینک) ثبت می‌شوند.
            val pendings = database.unregisteredBankSmsDao().getPendingSmsList()
                .filter { accountNumberRaw.isEmpty() || it.accountNumberRaw == accountNumberRaw }
            pendings.forEach { row ->
                repository.upsertUnregisteredBankSms(
                    row.copy(status = "ASSIGNED", assignedAccountId = bankAccount.id, assignedAccountSyncId = bankAccount.syncId)
                )
            }
            Log.d("DentalViewModel", "Assigned ${pendings.size} pending SMS to account ${bankAccount.bankName}")
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    // ---------- سطل آشغال پیامک‌های بانکی (باگ ۵) ----------
    /** حذف نرم: پیامک با isDeleted به سرور سینک می‌شود تا بعد از ورود دوباره هم در سطل آشغال بماند */
    fun trashBankSms(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = database.unregisteredBankSmsDao().getById(id) ?: return@launch
            repository.upsertUnregisteredBankSms(row.copy(isDeleted = true))
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /** بازگردانی پیامک از سطل آشغال به وضعیت قبلی‌اش (بدون تغییر وضعیت) */
    fun restoreBankSmsFromTrash(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = database.unregisteredBankSmsDao().getById(id) ?: return@launch
            repository.upsertUnregisteredBankSms(row.copy(isDeleted = false))
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /** خالی‌کردن کامل سطل آشغال — حذف قطعی محلی + عملیات DELETE برای سرور */
    fun emptyBankSmsTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = database.unregisteredBankSmsDao().getDeletedList()
            rows.forEach { repository.deleteUnregisteredBankSms(it) }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    // ——— عملیات گروهی روی پیامک‌های بانکی ———
    // درخواست: امکان عملیات گروهی برای برداشت‌ها. همهٔ این توابع یک بار
    // pushPending می‌زنند (نه به ازای هر ردیف) تا صف سینک شلوغ نشود.

    /** نادیده‌گرفتنِ گروهیِ چند پیامک. */
    fun ignorePendingSmsBulk(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                val row = database.unregisteredBankSmsDao().getById(id) ?: return@forEach
                if (row.status == "PENDING") {
                    repository.upsertUnregisteredBankSms(row.copy(status = "IGNORED"))
                }
            }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /** انتقالِ گروهی به سطل آشغال. */
    fun trashBankSmsBulk(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                val row = database.unregisteredBankSmsDao().getById(id) ?: return@forEach
                repository.upsertUnregisteredBankSms(row.copy(isDeleted = true))
            }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /** بازگردانیِ گروهی به «ثبت‌نشده». */
    fun restoreBankSmsBulk(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { id ->
                val row = database.unregisteredBankSmsDao().getById(id) ?: return@forEach
                repository.upsertUnregisteredBankSms(
                    row.copy(status = "PENDING", isDeleted = false, assignedAccountId = null, assignedAccountSyncId = "")
                )
            }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /** قالب پیامک را به‌عنوان تشخیص اشتباه ذخیره می‌کند تا پیامک‌های هم‌قالب دوباره وارد صف نشوند. */
    fun markBankSmsFormatAsWrong(bankName: String, rawText: String) {
        val key = bankName.trim() + "|" + rawText
            .replace(Regex("[۰-۹]"), "#")
            .replace(Regex("[0-9]"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
        val old = prefs.getStringSet("bank_sms_wrong_formats", emptySet()).orEmpty().toMutableSet()
        old.add(key)
        prefs.edit().putStringSet("bank_sms_wrong_formats", old).apply()
    }

    fun ignorePendingSms(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = database.unregisteredBankSmsDao().getById(id) ?: return@launch
            repository.upsertUnregisteredBankSms(row.copy(status = "IGNORED"))
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /** بازگردانی یک پیامک نادیده‌گرفته‌شده به لیست ثبت‌نشده‌ها */
    fun restoreIgnoredBankSms(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val row = database.unregisteredBankSmsDao().getById(id) ?: return@launch
            repository.upsertUnregisteredBankSms(row.copy(status = "PENDING"))
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /**
     * تغییر وضعیت یک پیامک ثبت‌شده (ASSIGNED): برگرداندن به «ثبت‌نشده» (PENDING)
     * یا «نادیده‌گرفته» (IGNORED) — درخواست: قابلیت تغییر وضعیت پیامک‌های ثبت‌شده.
     */
    fun revertRegisteredBankSms(id: Long, status: String = "PENDING") {
        viewModelScope.launch(Dispatchers.IO) {
            val row = database.unregisteredBankSmsDao().getById(id) ?: return@launch
            if (row.status != "ASSIGNED") return@launch
            val newStatus = if (status == "IGNORED") "IGNORED" else "PENDING"
            repository.upsertUnregisteredBankSms(
                row.copy(status = newStatus, assignedAccountId = null, assignedAccountSyncId = "")
            )
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /**
     * آماده‌سازیِ اسکنِ جدیدِ پیامک‌ها: (۱) pullِ آخرین وضعیتِ سرور تا تشخیصِ
     * تکراری به‌روز باشد، (۲) پاک‌کردنِ صفِ PENDINGِ اسکنِ قبلی (نمای موقت)؛
     * آیتم‌های ASSIGNED/IGNORED/حذف‌شده دست‌نخورده می‌مانند. همیشه پیش از
     * BankSmsEngine.scanHistoricalSmsBackfill صدا زده شود.
     */
    suspend fun prepareForNewSmsScan() {
        try { syncEngine.pullAll() } catch (e: Exception) { /* آفلاین: با آخرین وضعیت محلی موجود ادامه می‌دهیم */ }
        val stalePendings = database.unregisteredBankSmsDao().getPendingSmsList()
        stalePendings.forEach { repository.deleteUnregisteredBankSms(it) }
        try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
    }

    /**
     * درجِ نتایجِ موتورِ پیامکِ بانکی در صفِ «ثبت‌نشده‌ها» (لیستِ همهٔ تراکنش‌های
     * پیامکیِ هنوز ثبت‌نشده در حسابداری). حذفِ تکراری در دو مرحله: (۱) کلیدِ یکتای
     * smsNativeId برای پیامک‌های کاملاً یکسان، (۲) تطبیقِ تاریخ/ساعت با رکوردهای
     * ASSIGNED/IGNORED/حذف‌شده (محلی یا سرور) تا مجدداً به صف اضافه نشوند.
     */
    fun enqueueScannedBankSms(candidates: List<com.example.util.PatternCheckCandidate>) {
        viewModelScope.launch(Dispatchers.IO) {
            candidates.forEach { c ->
                val wrongKey = c.bankName.trim() + "|" + c.rawSms
                    .replace(Regex("[۰-۹]"), "#").replace(Regex("[0-9]"), "#")
                    .replace(Regex("\\s+"), " ").trim().take(500)
                val wrongFormats = prefs.getStringSet("bank_sms_wrong_formats", emptySet()).orEmpty()
                if (wrongFormats.any { it == wrongKey || it.substringAfter("|", it) == wrongKey.substringAfter("|", wrongKey) }) return@forEach
                val hash = (c.rawSms + "|" + c.amountTomans + "|" + c.dateStr + "|" + c.timeStr).hashCode()
                // dedup روی هر سه مسیر ورود (اسکن/زنده/دستی) — پیامکی که قبلاً ثبت شده،
                // با همان وضعیت ASSIGNED در لیست «ثبت‌شده‌ها» (تک‌سطری) باقی می‌ماند
                val existing = database.unregisteredBankSmsDao().getByNativeId("auto_$hash")
                    ?: database.unregisteredBankSmsDao().getByNativeId("live_$hash")
                    ?: database.unregisteredBankSmsDao().getByNativeId("manual_$hash")
                val alreadyHandledByDateTime = existing == null &&
                    database.unregisteredBankSmsDao().findProcessedByDateTime(c.dateStr, c.timeStr) != null
                if (existing == null && !alreadyHandledByDateTime) {
                    repository.upsertUnregisteredBankSms(
                        UnregisteredBankSms(
                            smsNativeId = "auto_$hash",
                            content = c.rawSms,
                            bankNameGuess = c.bankName,
                            type = c.transactionType,
                            amountTomans = c.amountTomans,
                            accountNumberRaw = c.accountNumberRaw,
                            dateStr = c.dateStr,
                            timeStr = c.timeStr,
                            score = c.score
                        )
                    )
                }
            }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /**
     * انتقال دستی یک تراکنش پیامکی (مثلاً از روی پاپ‌آپ نوتیفیکیشن) به صف «پیامک‌های ثبت‌نشده»
     * تا بعداً از داخل صف با لمس همان آیتم، ثبت شود.
     */
    fun enqueueBankSmsToUnregisteredQueue(
        bankName: String,
        type: String,
        amountTomans: Long,
        accountNumberRaw: String,
        dateStr: String,
        timeStr: String,
        rawText: String,
        score: Int = 5,
        sender: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val hash = (rawText + "|" + amountTomans + "|" + dateStr + "|" + timeStr).hashCode()
            // اگر قبلاً از مسیر زنده (live_) یا اسکن (auto_) در صف قرار گرفته، دوباره درج نشود
            val existing = database.unregisteredBankSmsDao().getByNativeId("live_$hash")
                ?: database.unregisteredBankSmsDao().getByNativeId("auto_$hash")
                ?: database.unregisteredBankSmsDao().getByNativeId("manual_$hash")
            if (existing == null) {
                repository.upsertUnregisteredBankSms(
                    UnregisteredBankSms(
                        smsNativeId = "manual_$hash",
                        sender = sender,
                        content = rawText,
                        bankNameGuess = bankName,
                        type = type,
                        amountTomans = amountTomans,
                        accountNumberRaw = accountNumberRaw,
                        dateStr = dateStr,
                        timeStr = timeStr,
                        score = score
                    )
                )
            } else if (existing.status == "IGNORED") {
                // انتقال دوباره به لیست ثبت‌نشده‌ها
                repository.upsertUnregisteredBankSms(existing.copy(status = "PENDING"))
            }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
        }
    }

    /**
     * ثبت نهایی تراکنش پیامک بانکی (واریز/برداشت) با تخصیص‌های چندسطری:
     *  - واریز: سهم هر سطر به بیمار (Payment) یا درآمد متفرقه شخصی (PersonalTransaction/INCOME)
     *  - برداشت: سهم هر سطر به طرف‌حساب هزینه‌های مطب (ExpensePayment)، هزینه شخصی
     *    (PersonalTransaction/EXPENSE) یا حساب اشخاص در بخش فرزندان (ChildrenExpense + تراکنش شخصی)
     *
     * اعمال کسر/اضافه روی موجودی حساب بانکی به‌صورت خودکار توسط Repository
     * (reconcile*BankImpact) انجام می‌شود؛ نیازی به دستکاری دستی balance نیست.
     */
    fun registerBankSmsTransaction(
        isDeposit: Boolean,
        bankAccountId: Long,
        allocations: List<BankSmsAllocation>,
        smsNativeId: String,
        smsDedupHash: Int? = null,
        queueItemId: Long? = null,
        smsRecordSeed: UnregisteredBankSms? = null,
        // تاریخ خودِ پیامک بانکی (میلی‌ثانیه). اگر null باشد، تاریخ امروز استفاده می‌شود.
        // درخواست کاربر: تاریخِ تراکنش باید برابر تاریخِ پیامک ثبت شود، نه روزِ ثبت.
        smsTimestamp: Long? = null,
        onDone: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            try {
                // تاریخِ تراکنش = تاریخِ پیامک (اگر معتبر باشد)، وگرنه اکنون.
                val txnDate = smsTimestamp ?: System.currentTimeMillis()
                allocations.filter { it.amount > 0 }.forEach { alloc ->
                    if (isDeposit) {
                        when (alloc.kind) {
                            "PATIENT" -> repository.insertPayment(
                                Payment(
                                    patientId = alloc.targetId ?: return@forEach,
                                    amount = alloc.amount,
                                    // واریزِ پیامکی همیشه «کارت به کارت» به مقصدِ کارتِ انتخاب‌شده است.
                                    type = "CARD",
                                    bankAccountId = bankAccountId,
                                    sourceSmsNativeId = smsNativeId,
                                    date = txnDate,
                                    notes = alloc.description.ifBlank { null }
                                )
                            )
                            else -> repository.insertTransaction(
                                PersonalTransaction(
                                    type = "INCOME",
                                    subAccountName = alloc.targetName.ifBlank { "درآمد متفرقه" },
                                    amount = alloc.amount,
                                    description = alloc.description,
                                    bankAccountId = bankAccountId,
                                    sourceSmsNativeId = smsNativeId,
                                    paymentMethod = "CARD_TRANSFER",
                                    date = txnDate
                                )
                            )
                        }
                    } else {
                        when (alloc.kind) {
                            "CLINIC_EXPENSE" -> repository.insertExpensePayment(
                                ExpensePayment(
                                    clinicExpenseId = alloc.targetId ?: return@forEach,
                                    amount = alloc.amount,
                                    method = "CARD_TRANSFER",
                                    bankAccountId = bankAccountId,
                                    sourceSmsNativeId = smsNativeId,
                                    date = txnDate,
                                    note = alloc.description
                                )
                            )
                            // برداشتِ پیامکی به «طرف حساب» (طرف‌حسابِ هزینه‌های مطب) —
                            // پرداختِ کلیِ طرف‌حساب بدون نیاز به فاکتورِ مشخص.
                            "PARTY" -> repository.insertExpensePayment(
                                ExpensePayment(
                                    clinicExpenseId = 0L,
                                    partyAccountId = alloc.targetId ?: return@forEach,
                                    amount = alloc.amount,
                                    method = "CARD_TRANSFER",
                                    bankAccountId = bankAccountId,
                                    sourceSmsNativeId = smsNativeId,
                                    date = txnDate,
                                    note = alloc.description
                                )
                            )
                            "PERSON" -> {
                                // حساب اشخاص: در بخش فرزندان با نام شخص و ریز حساب قابل گزارش‌گیری است
                                repository.insertChildrenExpense(
                                    ChildrenExpense(
                                        childName = alloc.targetName,
                                        title = alloc.description.ifBlank { "برداشت بانکی" },
                                        amount = alloc.amount,
                                        date = txnDate
                                    )
                                )
                                repository.insertTransaction(
                                    PersonalTransaction(
                                        type = "EXPENSE",
                                        subAccountName = "حساب اشخاص: ${alloc.targetName}",
                                        amount = alloc.amount,
                                        description = alloc.description.ifBlank { "برداشت بانکی — ${alloc.targetName}" },
                                        bankAccountId = bankAccountId,
                                        sourceSmsNativeId = smsNativeId,
                                        paymentMethod = "CARD_TRANSFER",
                                        date = txnDate
                                    )
                                )
                            }
                            else -> repository.insertTransaction(
                                PersonalTransaction(
                                    type = "EXPENSE",
                                    subAccountName = alloc.targetName.ifBlank { "سایر" },
                                    amount = alloc.amount,
                                    description = alloc.description,
                                    bankAccountId = bankAccountId,
                                    sourceSmsNativeId = smsNativeId,
                                    paymentMethod = "CARD_TRANSFER",
                                    date = txnDate
                                )
                            )
                        }
                    }
                }

                // خروج آیتم از صف «پیامک‌های ثبت‌نشده» پس از ثبت موفق + نگهداری رکورد
                // «ثبت‌شده» با خلاصهٔ «بابت» برای نمایش تک‌سطری (مبلغ | بابت | تاریخ) و سینک ابری
                val accountSyncId = bankAccounts.value.find { it.id == bankAccountId }?.syncId
                    ?: database.bankAccountDao().getAllList().find { it.id == bankAccountId }?.syncId
                    ?: ""
                val forSummary = allocations.filter { it.amount > 0 }.joinToString("، ") { alloc ->
                    when (alloc.kind) {
                        "PATIENT" -> "بیمار: ${alloc.targetName}"
                        "MISC_INCOME" -> "درآمد متفرقه"
                        "CLINIC_EXPENSE" -> "هزینه: ${alloc.targetName}"
                        "PARTY" -> "طرف‌حساب: ${alloc.targetName}"
                        "PERSONAL_SUB" -> "شخصی: ${alloc.targetName}"
                        "PERSON" -> "شخص: ${alloc.targetName}"
                        else -> alloc.targetName.ifBlank { "سایر" }
                    }
                }

                val queuedRow = when {
                    queueItemId != null -> database.unregisteredBankSmsDao().getById(queueItemId)
                    smsDedupHash != null ->
                        database.unregisteredBankSmsDao().getByNativeId("live_$smsDedupHash")
                            ?: database.unregisteredBankSmsDao().getByNativeId("auto_$smsDedupHash")
                            ?: database.unregisteredBankSmsDao().getByNativeId("manual_$smsDedupHash")
                    else -> database.unregisteredBankSmsDao().getByNativeId(smsNativeId)
                }

                if (queuedRow != null) {
                    repository.upsertUnregisteredBankSms(
                        queuedRow.copy(
                            status = "ASSIGNED",
                            assignedAccountId = bankAccountId,
                            assignedAccountSyncId = accountSyncId,
                            registeredForSummary = forSummary
                        )
                    )
                } else if (smsRecordSeed != null) {
                    // ثبت از روی نوتیفیکیشن بدون رکورد صف: یک رکورد «ثبت‌شده» ساخته می‌شود
                    // تا در لیست تک‌سطری ثبت‌شده‌ها دیده شود و به سرور هم سینک شود
                    repository.upsertUnregisteredBankSms(
                        smsRecordSeed.copy(
                            status = "ASSIGNED",
                            assignedAccountId = bankAccountId,
                            assignedAccountSyncId = accountSyncId,
                            registeredForSummary = forSummary
                        )
                    )
                }
                onDone?.invoke()
                try { syncEngine.pushPending() } catch (e: Exception) { /* سینک پس‌زمینه بعداً تلاش می‌کند */ }
            } catch (e: Exception) {
                Log.e("DentalViewModel", "registerBankSmsTransaction failed: ${e.message}", e)
            }
        }
    }

    // Personal Sub Account
    fun addPersonalSubAccount(name: String, category: String, parentName: String = "", ledgerSide: String = "NONE", grandParentName: String = "") {
        if (!authorizeDomain("personal", "ADD_SUB_ACCOUNT")) return
        viewModelScope.launch {
            val trimmedName = name.trim()
            val trimmedParent = parentName.trim()
            val trimmedGrand = grandParentName.trim()
            // Avoid creating duplicate headings: if an identical (category|grand|parent|name)
            // sub-account already exists, just reuse it instead of inserting a repeat.
            val existing = repository.allSubAccounts.first()
                .firstOrNull {
                    !it.isDeleted &&
                        it.category.trim() == category.trim() &&
                        it.grandParentName.trim() == trimmedGrand &&
                        it.parentName.trim() == trimmedParent &&
                        it.name.trim() == trimmedName
                }
            if (existing != null) return@launch
            repository.insertSubAccount(PersonalSubAccount(name = trimmedName, category = category, parentName = trimmedParent, grandParentName = trimmedGrand, ledgerSide = ledgerSide))
        }
    }

    fun updatePersonalSubAccount(sub: PersonalSubAccount) {
        if (!authorizeDomain("personal", "UPDATE_SUB_ACCOUNT")) return
        viewModelScope.launch {
            repository.insertSubAccount(sub)
        }
    }

    fun deletePersonalSubAccount(sub: PersonalSubAccount) {
        if (!authorizeDomain("personal", "DELETE_SUB_ACCOUNT", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteSubAccount(sub)
        }
    }

    // ======================================================================
    //  اشخاص در مالی و حسابداری — نام، نام خانوادگی، عنوان و ماهیت شخص
    //  روی همان جدول personal_sub_accounts با category = PERSONS ذخیره می‌شوند،
    //  بنابراین هیچ مهاجرت دیتابیسی لازم نیست و سینک ابری هم از قبل برقرار است.
    // ======================================================================

    /** پروندهٔ همهٔ اشخاص ثبت‌شده در بخش مالی. */
    val personProfiles: StateFlow<List<com.example.data.PersonProfile>> =
        personalSubAccounts
            .map { list ->
                list.filter { !it.isDeleted && it.category == com.example.data.PERSONS_CATEGORY }
                    .map { com.example.data.PersonProfile.fromSubAccount(it) }
                    .sortedBy { it.fullName }
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * ثبت یا به‌روزرسانی پروندهٔ یک شخص. نام کامل هم به `childrenNames` اضافه
     * می‌شود تا انتخابگرهای قدیمی (تراکنش‌ها و SMS بانکی) همین شخص را ببینند و
     * ریز‌حساب‌های موجود از بین نروند.
     */
    fun savePersonProfile(profile: com.example.data.PersonProfile) {
        if (!authorizeDomain("personal", "ADD_PERSON")) return
        val fullName = profile.fullName
        if (fullName.isBlank()) return
        viewModelScope.launch {
            val all = repository.allSubAccounts.first()
            // اگر رکوردی با همین شناسه یا همین نام هست، به‌جای ساختن رکورد تکراری
            // همان را به‌روز می‌کنیم.
            val existing = all.firstOrNull {
                !it.isDeleted && it.category == com.example.data.PERSONS_CATEGORY &&
                    (it.id == profile.id && profile.id != 0L || it.name.trim().equals(fullName, ignoreCase = true))
            }
            repository.insertSubAccount(profile.toSubAccount(existing))
        }
        // نام را در فهرست قدیمی هم نگه می‌داریم تا سازگاری عقب‌رو حفظ شود
        addPersonName(fullName)
    }

    /** حذف نرم پروندهٔ شخص (ریزحساب‌های ثبت‌شده دست‌نخورده می‌مانند). */
    fun deletePersonProfile(profile: com.example.data.PersonProfile) {
        if (!authorizeDomain("personal", "DELETE_SUB_ACCOUNT", isDelete = true)) return
        viewModelScope.launch {
            val all = repository.allSubAccounts.first()
            val target = all.firstOrNull {
                it.category == com.example.data.PERSONS_CATEGORY &&
                    (it.id == profile.id || it.name.trim().equals(profile.fullName, ignoreCase = true))
            } ?: return@launch
            repository.deleteSubAccount(target)
        }
    }

    /**
     * افزودن شخص جدید به بخش «حساب اشخاص» (ذخیره در childrenNames تنظیمات + سینک ابری).
     * نام‌ها با ویرانه لاتین/فارسی از هم جدا می‌شوند و بدون تکرار اضافه می‌شوند.
     */
    fun addPersonName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        if (!authorizeDomain("personal", "ADD_PERSON")) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.getSettingsOnce() ?: com.example.data.ClinicSettings()
            val existing = current.childrenNames.split(",", "،").map { it.trim() }.filter { it.isNotBlank() }
            if (existing.none { it.equals(trimmed, ignoreCase = true) }) {
                repository.insertSettings(
                    current.copy(
                        childrenNames = (existing + trimmed).joinToString(","),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک بعداً تلاش می‌کند */ }
        }
    }

    // Children Expenses
    fun addChildrenExpense(childName: String, title: String, amount: Long) {
        if (!authorizeDomain("personal", "ADD_CHILD_EXPENSE")) return
        viewModelScope.launch {
            repository.insertChildrenExpense(ChildrenExpense(childName = childName, title = title, amount = amount))
        }
    }

    fun deleteChildrenExpense(expense: ChildrenExpense) {
        if (!authorizeDomain("personal", "DELETE_CHILD_EXPENSE", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteChildrenExpense(expense)
        }
    }

    // Settings
    fun updateClinicSettings(clinicSettings: ClinicSettings) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch {
            repository.insertSettings(clinicSettings)
        }
    }

    fun updateWorkingDays(workingDays: String) {
        if (!authorizeDomain("settings", "UPDATE_WORKING_DAYS", doctorOnly = true)) return
        viewModelScope.launch {
            val current = settings.value
            repository.insertSettings(current.copy(workingDays = workingDays))
        }
    }

    fun isWorkingDay(timestamp: Long): Boolean {
        val jDate = com.example.util.PersianCalendarHelper.timestampToJalali(timestamp)
        val workingDaysConfig = settings.value.workingDays
        return com.example.util.PersianCalendarHelper.isWorkingDay(jDate.year, jDate.month, jDate.day, workingDaysConfig)
    }

    fun getHolidayOrNonWorkingReason(timestamp: Long): String? {
        val jDate = com.example.util.PersianCalendarHelper.timestampToJalali(timestamp)
        val workingDaysConfig = settings.value.workingDays
        val dayOfWeek = com.example.util.PersianCalendarHelper.getDayOfWeekIndex(jDate.year, jDate.month, jDate.day)
        val occasions = com.example.util.PersianCalendarHelper.getOccasions(jDate.year, jDate.month, jDate.day)
        val holidayOccasion = occasions.firstOrNull { it.isHoliday }

        if (holidayOccasion != null) {
            return holidayOccasion.titleFa
        }
        if (dayOfWeek == 6) {
            return "روز جمعه (تعطیل رسمی)"
        }
        val activeDays = workingDaysConfig.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (dayOfWeek !in activeDays) {
            val dayName = com.example.util.PersianCalendarHelper.WEEKDAY_NAMES.getOrElse(dayOfWeek) { "نامشخص" }
            return "روز غیرکاری کلینیک ($dayName)"
        }
        return null
    }

    fun updateSettings(clinicName: String, doctorName: String, staffNames: String, showSummary: Boolean, showQuick: Boolean, showCharts: Boolean) {
        if (!authorizeDomain("settings", "UPDATE_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch {
            repository.updateGeneralSettings(clinicName, doctorName, staffNames, showSummary, showQuick, showCharts)
        }
    }

    // ذخیره پایدار آخرین سایز فونت صفحات شرح بالینی (دستورالعمل بالینی/ممنوعیت‌های درمانی)
    fun updateClinicalFontSize(sizePt: Int) {
        if (!authorizeDomain("settings", "UPDATE_CLINICAL_FONT", doctorOnly = true)) return
        viewModelScope.launch {
            repository.updateClinicalFontSize(sizePt)
        }
    }

    // لغزنده ۸پله‌ای سایز فونت کلی برنامه (۰..۷) — تنظیمات اختصاصی
    fun updateGlobalFontScale(index: Int) {
        if (!authorizeDomain("settings", "UPDATE_GLOBAL_FONT", doctorOnly = true)) return
        viewModelScope.launch {
            repository.updateGlobalFontScale(index.coerceIn(0, 19))
        }
    }

    // دکمه «به‌روزرسانی» در برگ بیمار — فلوهای Room خودکار به‌روز می‌شوند؛
    // این تابع صرفاً یک re-subscribe اجباری برای اطمینان کاربر انجام می‌دهد
    fun refreshSelectedPatient() {
        viewModelScope.launch {
            val id = selectedPatientId.value
            if (id != null) {
                selectedPatientId.value = null
                selectedPatientId.value = id
            }
        }
    }

    // Instantly switch the app's neon theme without touching any other setting
    fun updateTheme(themeId: String) {
        if (!authorizeDomain("settings", "UPDATE_THEME", doctorOnly = true)) return
        viewModelScope.launch {
            repository.insertSettings(settings.value.copy(id = 1, themeName = themeId))
        }
    }

    fun setDarkMode(enabled: Boolean) {
        if (!authorizeDomain("settings", "UPDATE_THEME", doctorOnly = true)) return
        viewModelScope.launch {
            repository.insertSettings(settings.value.copy(id = 1, isDarkMode = enabled))
        }
    }

    // SMS Parsing Engine
    // Automatically analyzes incoming mock/actual SMS texts to detect card transfers
    fun parseBankSms(smsText: String): SmsParseResult? {
        if (smsText.isBlank()) return null
        
        // Find amount (extract numbers from sms)
        val cleanText = smsText.replace(",", "").replace("،", "")
        val regexNumbers = "\\b\\d{4,10}\\b".toRegex()
        val matchResults = regexNumbers.findAll(cleanText).map { it.value.toEnglishLong() }.toList()
        
        val detectedAmount = matchResults.firstOrNull { it > 1000L } ?: 0L
        
        // Try to match a bank account by keywords or credit/debit numbers
        var matchedAccount: BankAccount? = null
        for (account in bankAccounts.value) {
            val keywords = account.smsKeywords.split(",")
            val hasKeyword = keywords.any { kw -> kw.trim().isNotEmpty() && smsText.contains(kw.trim()) }
            val hasCardNo = account.cardNumber.takeLast(4).let { suffix -> suffix.isNotEmpty() && smsText.contains(suffix) }
            
            if (hasKeyword || hasCardNo) {
                matchedAccount = account
                break
            }
        }
        
        // Return structured result to offer semi-automatic registration
        return SmsParseResult(
            amount = detectedAmount,
            detectedAccount = matchedAccount,
            originalText = smsText,
            isDeposit = smsText.contains("واریز") || smsText.contains("آمد به") || smsText.contains("افزایش") || smsText.contains("deposit")
        )
    }

    // Dental Chart System Standard (FDI, Universal, Palmer)
    val dentalChartSystem = MutableStateFlow(prefs.getString("dental_chart_system", "FDI") ?: "FDI")

    fun setDentalChartSystem(system: String) {
        if (!authorizeDomain("settings", "UPDATE_CHART_SYSTEM", doctorOnly = true)) return
        dentalChartSystem.value = system
        prefs.edit().putString("dental_chart_system", system).apply()
    }

    // Calendar Type: "SHAMSI", "QAMARI", "MILADI"
    val calendarType = MutableStateFlow(prefs.getString("calendar_type", "SHAMSI") ?: "SHAMSI")

    fun setCalendarType(type: String) {
        calendarType.value = type
        prefs.edit().putString("calendar_type", type).apply()
    }

    // Date Formatter Helpers - Strictly Shamsi (Solar Hijri) for the entire app
    fun formatDate(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return toShamsiDate(timestamp)
    }

    fun toShamsiDate(timestamp: Long): String {
        try {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            val gYear = cal.get(java.util.Calendar.YEAR)
            val gMonth = cal.get(java.util.Calendar.MONTH) + 1
            val gDay = cal.get(java.util.Calendar.DAY_OF_MONTH)

            val gDaysInMonth = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
            val jDaysInMonth = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 29)

            var gy = gYear - 1600
            var gm = gMonth - 1
            var gd = gDay - 1

            var gDayNo = 365 * gy + (gy + 3) / 4 - (gy + 99) / 100 + (gy + 399) / 400
            for (i in 0 until gm) {
                gDayNo += gDaysInMonth[i]
            }
            if (gm > 1 && ((gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0))) {
                gDayNo++
            }
            gDayNo += gd

            var jDayNo = gDayNo - 79
            val jNp = jDayNo / 12053
            jDayNo %= 12053

            var jy = 979 + 33 * jNp + 4 * (jDayNo / 1461)
            jDayNo %= 1461

            if (jDayNo >= 366) {
                jy += (jDayNo - 1) / 365
                jDayNo = (jDayNo - 1) % 365
            }

            var jm = 0
            while (jm < 11 && jDayNo >= jDaysInMonth[jm]) {
                jDayNo -= jDaysInMonth[jm]
                jm++
            }
            val jd = jDayNo + 1

            val shamsiMonths = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
            val monthName = if (isPersian.value) shamsiMonths.getOrElse(jm) { "" } else "${jm + 1}"
            return if (isPersian.value) "$jd $monthName $jy" else "$jy/${jm + 1}/$jd"
        } catch (e: Exception) {
            return SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun toQamariDate(timestamp: Long): String {
        return try {
            val jDate = com.example.util.PersianCalendarHelper.timestampToJalali(timestamp)
            if (jDate.year == 1405) {
                val threeCal = com.example.util.PersianCalendarHelper.get3CalendarsFor1405(jDate.month, jDate.day)
                val q = threeCal.qamari
                if (isPersian.value) "${q.day} ${q.monthName} ${q.year} (قمری)" else "${q.year}/${q.month}/${q.day} (Hijri)"
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val ld = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    val hijriDate = java.time.chrono.Chronology.of("Hijrah-umalqura").date(ld)
                    val hYear = hijriDate.get(java.time.temporal.ChronoField.YEAR)
                    val hMonth = hijriDate.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
                    val hDay = hijriDate.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
                    val qamariMonths = com.example.util.PersianCalendarHelper.QAMARI_MONTH_NAMES
                    val monthName = qamariMonths.getOrElse(hMonth - 1) { "" }
                    if (isPersian.value) "$hDay $monthName $hYear (قمری)" else "$hYear/${hMonth}/$hDay (Hijri)"
                } else {
                    SimpleDateFormat("yyyy/MM/dd (قمری)", Locale.getDefault()).format(Date(timestamp))
                }
            }
        } catch (e: Exception) {
            SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun toMiladiDate(timestamp: Long): String {
        val jDate = com.example.util.PersianCalendarHelper.timestampToJalali(timestamp)
        if (jDate.year == 1405) {
            val threeCal = com.example.util.PersianCalendarHelper.get3CalendarsFor1405(jDate.month, jDate.day)
            val m = threeCal.miladi
            return if (isPersian.value) "${m.day} ${m.monthNameFa} ${m.year} (میلادی)" else "${m.monthNameEn} ${m.day}, ${m.year}"
        }
        val date = Date(timestamp)
        val format = if (isPersian.value) "yyyy/MM/dd (میلادی)" else "MMM dd, yyyy"
        return SimpleDateFormat(format, Locale.getDefault()).format(date)
    }

    fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    }

    fun formatDateFullVerbalWithTime(timestamp: Long): String {
        val dateVerbal = formatDateFullVerbal(timestamp)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val hour24 = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
        val amPm = if (hour24 < 12) "قبل از ظهر" else "بعد از ظهر"
        val timeFormatted = String.format(java.util.Locale.US, "%02d:%02d", hour12, minute)
        return "$dateVerbal ساعت $timeFormatted $amPm"
    }

    fun formatDateFullVerbal(timestamp: Long): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val zdt = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault())
                val ld = zdt.toLocalDate()
                val dayOfWeekNumber = ld.dayOfWeek.value // 1=Mon, 7=Sun
                val dayOfWeekNamesPersian = mapOf(
                    1 to "دوشنبه", 2 to "سه‌شنبه", 3 to "چهارشنبه", 4 to "پنج‌شنبه", 5 to "جمعه", 6 to "شنبه", 7 to "یکشنبه"
                )
                val weekdayStr = dayOfWeekNamesPersian[dayOfWeekNumber] ?: ""

                val months = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
                var year = ld.year
                var dayOfYear = ld.dayOfYear
                val isLeap = ld.isLeapYear
                var sYear = year - 621
                var sMonth = 1
                var sDay = 1
                val daysBefore = if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) 80 else 79
                if (dayOfYear > daysBefore) {
                    dayOfYear -= daysBefore
                    if (dayOfYear <= 186) {
                        sMonth = 1 + (dayOfYear - 1) / 31
                        sDay = 1 + (dayOfYear - 1) % 31
                    } else {
                        dayOfYear -= 186
                        sMonth = 7 + (dayOfYear - 1) / 30
                        sDay = 1 + (dayOfYear - 1) % 30
                    }
                } else {
                    sYear -= 1
                    dayOfYear += if (isLeap) 287 else 286
                    if (dayOfYear <= 186) {
                        sMonth = 1 + (dayOfYear - 1) / 31
                        sDay = 1 + (dayOfYear - 1) % 31
                    } else {
                        dayOfYear -= 186
                        sMonth = 7 + (dayOfYear - 1) / 30
                        sDay = 1 + (dayOfYear - 1) % 30
                    }
                }
                val monthName = months.getOrNull(sMonth - 1) ?: ""
                "$weekdayStr $sDay $monthName $sYear"
            } else {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
                val jDate = com.example.util.PersianCalendarHelper.gregorianToJalali(
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH) + 1,
                    cal.get(java.util.Calendar.DAY_OF_MONTH)
                )
                val mName = com.example.util.PersianCalendarHelper.MONTH_NAMES.getOrNull(jDate.month - 1) ?: ""
                "${jDate.day} $mName ${jDate.year}"
            }
        } catch (e: Exception) {
            formatDate(timestamp)
        }
    }

    // Single & Batch Therapy Stage Toggles
    /**
     * علامت‌گذاری/برداشتن علامت یک مرحله.
     *
     * [appointmentId] جلسه‌ای است که این مرحله در آن انجام می‌شود. ثبت می‌شود تا
     * اگر بعداً همان جلسه حذف شد، دقیقاً همین مرحله (و نه مراحل جلسات دیگر)
     * برگردانده شود.
     */
    fun toggleTreatmentStage(treatment: Treatment, stageName: String, appointmentId: Long? = null) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val completedList = parseStageMarkers(treatment.completedStages).toMutableList()
            val sessionMap = parseStageSessions(treatment.stageSessions).toMutableMap()
            if (completedList.contains(stageName)) {
                completedList.remove(stageName)
                sessionMap.remove(stageName)
            } else {
                completedList.add(stageName)
                if (appointmentId != null) sessionMap[stageName] = appointmentId
            }
            val newCompletedStr = joinStageMarkers(completedList)
            val allStagesList = parseStagesList(treatment.stages)
            val ignoredList = parseStageMarkers(treatment.ignoredStages)
            val resolved = (completedList + ignoredList).distinct()
            val newStatus = when {
                allStagesList.isNotEmpty() && resolved.size >= allStagesList.size -> "COMPLETED"
                completedList.isNotEmpty() -> "IN_PROGRESS"
                else -> "PLANNED"
            }
            repository.insertTreatment(
                treatment.copy(
                    completedStages = newCompletedStr,
                    stageSessions = joinStageSessions(sessionMap),
                    status = newStatus
                )
            )
        }
    }

    // ایگنور کردن یک مرحله/جلسه به دلیل عدم نیاز، بدون علامت‌گذاری آن به‌عنوان انجام‌شده
    fun ignoreTreatmentStage(treatment: Treatment, stageName: String, appointmentId: Long? = null) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val ignoredList = parseStageMarkers(treatment.ignoredStages).toMutableList()
            if (!ignoredList.contains(stageName)) {
                ignoredList.add(stageName)
            }
            val sessionMap = parseStageSessions(treatment.stageSessions).toMutableMap()
            if (appointmentId != null) sessionMap[stageName] = appointmentId

            val allStagesList = parseStagesList(treatment.stages)
            val completedList = parseStageMarkers(treatment.completedStages)
            val resolved = (completedList + ignoredList).distinct()
            val newStatus = when {
                allStagesList.isNotEmpty() && resolved.size >= allStagesList.size -> "COMPLETED"
                resolved.isNotEmpty() -> "IN_PROGRESS"
                else -> treatment.status
            }
            repository.insertTreatment(
                treatment.copy(
                    ignoredStages = joinStageMarkers(ignoredList),
                    stageSessions = joinStageSessions(sessionMap),
                    status = newStatus
                )
            )
        }
    }

    // بازگرداندن یک مرحله ایگنورشده به لیست مراحل باقی‌مانده
    fun unignoreTreatmentStage(treatment: Treatment, stageName: String) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val ignoredList = parseStageMarkers(treatment.ignoredStages).toMutableList()
            ignoredList.remove(stageName)
            val sessionMap = parseStageSessions(treatment.stageSessions).toMutableMap()
            sessionMap.remove(stageName)

            val allStagesList = parseStagesList(treatment.stages)
            val completedList = parseStageMarkers(treatment.completedStages)
            val resolved = (completedList + ignoredList).distinct()
            val newStatus = when {
                allStagesList.isNotEmpty() && resolved.size >= allStagesList.size -> "COMPLETED"
                resolved.isNotEmpty() -> "IN_PROGRESS"
                else -> "PLANNED"
            }
            repository.insertTreatment(
                treatment.copy(
                    ignoredStages = joinStageMarkers(ignoredList),
                    stageSessions = joinStageSessions(sessionMap),
                    status = newStatus
                )
            )
        }
    }

    // جایگزینی متن جلسه جاری با متن ویرایش شده
    fun replaceTreatmentStage(treatment: Treatment, oldStage: String, newStage: String) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val stagesList = parseStagesList(treatment.stages).toMutableList()
            val index = stagesList.indexOf(oldStage)
            if (index != -1) {
                stagesList[index] = newStage
                repository.insertTreatment(treatment.copy(stages = joinStagesList(stagesList)))
            }
        }
    }

    fun toggleAllTreatmentStages(treatment: Treatment, checkAll: Boolean) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT_STAGE")) return
        viewModelScope.launch {
            val allStagesList = parseStagesList(treatment.stages)
            val newCompletedStr = if (checkAll) joinStageMarkers(allStagesList) else ""
            val newStatus = if (checkAll) "COMPLETED" else "PLANNED"
            repository.insertTreatment(
                treatment.copy(
                    completedStages = newCompletedStr,
                    // با پاک‌کردن همهٔ مراحل، انتساب‌ها هم بی‌معنی می‌شوند.
                    stageSessions = if (checkAll) treatment.stageSessions else "",
                    status = newStatus
                )
            )
        }
    }

    fun updateTreatment(treatment: Treatment) {
        if (!authorizeDomain("treatments", "UPDATE_TREATMENT")) return
        viewModelScope.launch {
            repository.insertTreatment(treatment)
        }
    }

    // Multi-Language Translation Dictionary
    fun label(key: String): String {
        if (key == "currency") {
            return currencyUnit.value
        }

        val localized = com.example.util.AppLocalization.get(key, selectedLanguage.value)
        if (localized != key) {
            return localized
        }

        val faMap = mapOf(

            "app_title" to "DentalPro | ERP",
            "dashboard" to "داشبورد مدیریت",
            "patients" to "پرونده بیماران",
            "appointments" to "نوبت‌دهی و تقویم",
            "vendors" to "فروشندگان و فاکتورها",
            "labs" to "لابراتوار دندان",
            "maintenance" to "تعمیر و نگهداری",
            "expenses" to "هزینه‌های جاری",
            "personal" to "حسابداری شخصی",
            "more" to "امکانات ERP",
            
            // Dashboard
            "summary" to "خلاصه وضعیت مالی و مطب",
            "total_patients" to "کل پرونده‌ها",
            "today_appointments" to "نوبت‌های امروز",
            "clinic_income" to "کل دریافتی مطب",
            "personal_balance" to "موجودی کل بانک‌ها",
            "uncleared_cheques" to "چک‌های وصول‌نشده",
            "quick_add" to "ثبت سریع اطلاعات",
            "quick_patient" to "بیمار جدید",
            "quick_appointment" to "نوبت جدید",
            "quick_expense" to "ثبت هزینه",
            "currency" to "تومان",
            
            // Patients Page
            "search_patient" to "جستجوی پرونده بیمار...",
            "add_new_patient" to "افزودن بیمار جدید",
            "name" to "نام و نام خانوادگی",
            "phone" to "شماره تماس",
            "notes" to "توضیحات و یادداشت",
            "no_patient_selected" to "لطفاً برای مشاهده جزئیات پرونده، یک بیمار را انتخاب کنید",
            "patient_file" to "پرونده تخصصی و درمان‌های بیمار",
            "financial_balance" to "وضعیت مالی بیمار",
            "balance_creditor" to "بستانکار",
            "balance_debtor" to "بدهکار",
            "balance_settled" to "بدون بدهی",
            "total_treatments" to "کل خدمات ارائه شده",
            "total_payments" to "کل مبالغ پرداختی",
            "treatments_tab" to "طرح درمان",
            "payments_tab" to "پرداختی‌ها و چک‌ها",
            "add_treatment" to "ثبت طرح درمان جدید",
            "add_payment" to "ثبت دریافتی مالی جدید",
            "treatment_title" to "نوع خدمت (مثلا ایمپلنت، پر کردن)",
            "cost" to "هزینه کل خدمت",
            "amount" to "مبلغ تراکنش",
            "payment_type" to "روش پرداخت",
            "cash" to "نقدی",
            "card_transfer" to "کارت به کارت",
            "pos" to "کارتخوان",
            "cheque" to "چک صیادی",
            "cheque_number" to "شماره چک",
            "cheque_due_date" to "تاریخ سررسید چک",
            "save" to "ذخیره اطلاعات",
            "cancel" to "انصراف",
            "delete" to "حذف",
            
            // Appointments Page
            "patient_name" to "نام بیمار",
            "treatment_type" to "نوع خدمت درخواستی",
            "date_time" to "تاریخ و ساعت نوبت",
            "no_appointments" to "نوبت فعالی در تقویم یافت نشد",
            
            // Vendors Page
            "vendor_name" to "نماینده فروشگاه",
            "company_name" to "نام شرکت / تجهیزات",
            "product_type" to "دسته اقلام خریداری شده",
            "total_purchases" to "کل مبلغ خرید",
            "paid_amount" to "مبلغ پرداخت شده",
            "vendor_debt" to "مانده بدهی ما",
            "add_vendor" to "ثبت فاکتور خرید جدید",
            
            // Labs Page
            "lab_name" to "لابراتوار",
            "tooth_num" to "شماره دندان",
            "prosthesis_type" to "پروتز درخواستی (روکش/لمینت)",
            "due_date" to "زمان تحویل کار",
            "status" to "وضعیت کار آزمایشگاهی",
            "status_ordered" to "سفارش داده شده",
            "status_received" to "تحویل گرفته شده",
            "status_fitted" to "نصب نهایی بیمار",
            "add_lab_order" to "ثبت سفارش پروتز",
            
            // Maintenance Page
            "equipment_name" to "نام دستگاه / ابزار",
            "maintenance_desc" to "گزارش تعمیر و کالیبراسیون",
            "next_service" to "سررسید سرویس بعدی",
            "technician" to "تکنسین",
            "invoice" to "فاکتور",
            "add_maintenance" to "ثبت فاکتور تعمیر و نگهداری",
            
            // Expenses Page
            "expense_category" to "دسته هزینه جاری",
            "rent" to "اجاره مطب",
            "utilities" to "قبوض خدماتی",
            "salaries" to "حقوق پرسنل مطب",
            "materials" to "مواد و ملزومات دندانپزشکی",
            "other" to "سایر هزینه‌های مطب",
            "add_expense" to "ثبت هزینه جاری مطب",
            
            // Personal Finance
            "personal_desc" to "شرح تراکنش شخصی",
            "transaction_type" to "نوع تراکنش شخصی",
            "personal_income" to "درآمد متفرقه (خارج مطب)",
            "dentist_draw" to "برداشت شخصی پزشک از صندوق مطب",
            "personal_expense" to "هزینه‌های شخصی دکتر",
            "add_personal_tx" to "ثبت تراکنش حساب شخصی"
        )

        val enMap = mapOf(
            "app_title" to "DentalPro | Comprehensive Clinic ERP",
            "dashboard" to "Dashboard",
            "patients" to "Patient Files",
            "appointments" to "Appointments & Calendar",
            "vendors" to "Vendors & Bills",
            "labs" to "Dental Labs",
            "maintenance" to "Maintenance & Repair",
            "expenses" to "Clinic Expenses",
            "personal" to "Personal Accounting",
            "more" to "ERP Features",
            
            // Dashboard
            "summary" to "Clinic Financial Summary",
            "total_patients" to "Total Files",
            "today_appointments" to "Today's Appointments",
            "clinic_income" to "Total Clinic Revenue",
            "personal_balance" to "Total Bank Balance",
            "uncleared_cheques" to "Uncleared Cheques",
            "quick_add" to "Quick Entry Panel",
            "quick_patient" to "New Patient",
            "quick_appointment" to "New Appointment",
            "quick_expense" to "Record Expense",
            "currency" to "Toman",
            
            // Patients Page
            "search_patient" to "Search patient files...",
            "add_new_patient" to "Add New Patient",
            "name" to "Full Name",
            "phone" to "Phone Number",
            "notes" to "Medical History / Note",
            "no_patient_selected" to "Please select a patient to view files",
            "patient_file" to "Clinical File & Account History",
            "financial_balance" to "Patient Financial Status",
            "balance_creditor" to "Creditor",
            "balance_debtor" to "Debtor",
            "balance_settled" to "Settled",
            "total_treatments" to "Total Procedures",
            "total_payments" to "Total Paid",
            "treatments_tab" to "Treatment Plan & Dental Chart",
            "payments_tab" to "Payments & Cheques",
            "add_treatment" to "Record Treatment Service",
            "add_payment" to "Record Patient Payment",
            "treatment_title" to "Procedure Type (e.g. Implant, Veneers)",
            "cost" to "Procedure Cost",
            "amount" to "Amount",
            "payment_type" to "Payment Method",
            "cash" to "Cash",
            "card_transfer" to "Card Transfer",
            "pos" to "POS",
            "cheque" to "Bank Cheque",
            "cheque_number" to "Cheque Number",
            "cheque_due_date" to "Cheque Due Date",
            "save" to "Save Details",
            "cancel" to "Cancel",
            "delete" to "Delete",
            
            // Appointments Page
            "patient_name" to "Patient Name",
            "treatment_type" to "Service Requested",
            "date_time" to "Scheduled Date & Time",
            "no_appointments" to "No appointments found",
            
            // Vendors Page
            "vendor_name" to "Rep Name",
            "company_name" to "Company Name",
            "product_type" to "Product Category",
            "total_purchases" to "Total Order Value",
            "paid_amount" to "Paid Amount",
            "vendor_debt" to "Our Outstanding Balance",
            "add_vendor" to "Add New Invoice",
            
            // Labs Page
            "lab_name" to "Dental Laboratory",
            "tooth_num" to "Tooth #",
            "prosthesis_type" to "Prosthesis Crown / Veneers",
            "due_date" to "Delivery Date",
            "status" to "Work Status",
            "status_ordered" to "Ordered",
            "status_received" to "Received",
            "status_fitted" to "Fitted to Patient",
            "add_lab_order" to "Add Lab Order",
            
            // Maintenance Page
            "equipment_name" to "Equipment Name",
            "maintenance_desc" to "Service & Calibration Report",
            "next_service" to "Next Service Date",
            "technician" to "Technician",
            "invoice" to "Invoice",
            "add_maintenance" to "Add Maintenance Bill",
            
            // Expenses Page
            "expense_category" to "Category",
            "rent" to "Rent Expense",
            "utilities" to "Utility Bills",
            "salaries" to "Staff Salaries",
            "materials" to "Dental Materials",
            "other" to "Other Clinic Expense",
            "add_expense" to "Record Clinic Expense",
            
            // Personal Finance
            "personal_desc" to "Transaction Description",
            "transaction_type" to "Transaction Type",
            "personal_income" to "Private Income",
            "dentist_draw" to "Doctor Drawing",
            "personal_expense" to "Doctor Personal Expense",
            "add_personal_tx" to "Add Personal Transaction"
        )

        return if (isPersian.value) {
            faMap[key] ?: key
        } else {
            enMap[key] ?: key
        }
    }

    // Seed built-in reference data only. Default catalog items are inserted directly into DAO
    // so they do NOT enqueue pending sync ops to the server unless edited or added by the user.
    private suspend fun seedReferenceData() {
        // Each catalog is guarded independently so one populated catalog cannot suppress
        // seeding a different, missing reference catalog.
        if (repository.allTemplates.first().isEmpty()) {
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Amalgam Filling", titleFa = "پرکردن آمالگام", defaultCost = 1200000L, defaultNotes = "ترمیم آمالگام (Dental Amalgam Restoration) - ترمیم دندان‌های خلفی با آلیاژ نقره مقاوم", stages = "جلسه ۱: بی‌حسی، حذف پوسیدگی، آماده‌سازی حفره با اصول بلک، کارگذاری و تراکم آمالگام، تراش اولیه، پالیش در جلسه بعد (در صورت نیاز ۲۴ ساعت بعد)", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Anterior Infected RCT", titleFa = "عصب‌کشی قدامی عفونی", defaultCost = 2800000L, defaultNotes = "درمان ریشه دندان قدامی عفونی (Anterior Infected Root Canal Treatment) – پاک‌سازی کانال دندان‌های جلو در حضور عفونت حاد یا مزمن", stages = "جلسه ۱: بی‌حسی لوکال، ایزولاسیون رابردم، حذف پوسیدگی، بازکردن پالپ‌چمبردن پالپ‌چمبر، تعیین طول الکترونیکی با اپکس‌فایندر، پاک‌سازی و شکل‌دهی با فایل‌های روتاری، شست‌وشوی فراوان با هیپوکلریت سدیم و EDTA، خشک‌کردن، قراردادن کلسیم هیدروکساید (مدیکیشن)، پانسمان موقت. - جلسه ۲: برداشتن پانسمان، شست‌وشوی نهایی با فعال‌سازی اولتراسونیک، اُبچوریشن سه‌بعدی با گوتاپرکا و سیلر، ترمیم تاجی با کامپوزیت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Anterior RCT", titleFa = "عصب‌کشی قدامی", defaultCost = 2500000L, defaultNotes = "درمان ریشه دندان قدامی غیرعفونی (Anterior Non-Infected Root Canal Treatment) – درمان پالپ زنده یا نکروز بدون آبسه فعال", stages = "جلسه ۱: بی‌حسی، ایزولاسیون، حذف پوسیدگی، خارج کردن کامل پالپ، تعیین طول، پاک‌سازی و شکل‌دهی، شست‌وشو، خشک‌کردن و اُبچوریشن در همان جلسه (در صورت عدم وجود عفونت فعال)، ترمیم دائمی کرونال. (در صورت نیاز به دو جلسه، مشابه قالب عفونی عمل می‌شود)", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Apicoectomy", titleFa = "جراحی اپکس", defaultCost = 5000000L, defaultNotes = "جراحی انتهای ریشه (Apicoectomy / Root-End Surgery) - برداشتن نوک ریشه، دبریدمان پری‌اپیکال و پرکردن انتهای کانال", stages = "جلسه ۱: بی‌حسی، فلپ موکوپریواستئال، استئوتومی برای دسترسی به اپکس، برداشتن ۳ میلی‌متر انتهای ریشه، کورتاژ بافت گرانولوماتوز، آماده‌سازی حفره رتروگراد با اولتراسونیک، پرکردن انتهای کانال با MTA یا بایوسرامیک، بخیه. - جلسه ۲: کشیدن بخیه، رادیوگرافی کنترل و بررسی التیام", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Bone Graft", titleFa = "پیوند استخوان", defaultCost = 7000000L, defaultNotes = "پیوند استخوان فک (Alveolar Ridge Augmentation / Bone Graft) - افزایش حجم استخوان با ماده پیوندی برای مهیا کردن بستر ایمپلنت", stages = "جلسه ۱: فلپ تمام ضخامت، آماده‌سازی ناحیه، دکورتیکاسیون جهت خونرسانی، جایگذاری پودر استخوان (آلوگرافت/زنوگرافت/اتوگرافت)، پوشاندن با ممبران، بخیه بدون تنشن. - جلسه ۲: کشیدن بخیه و کنترل التیام", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Ceramic Veneer", titleFa = "لمینت سرامیکی", defaultCost = 5000000L, defaultNotes = "ونیر سرامیکی (Porcelain Laminate Veneer) - پوسته‌ی نازک سرامیکی برای اصلاح طرح لبخند دندان‌های قدامی", stages = "جلسه ۱: طراحی لبخند دیجیتال، تراش مینا (حدود ۰.۳-۰.۵ میلی‌متر)، قالب‌گیری، ونیر موقت. - جلسه ۲: تست رنگ، فرم و تطابق بیسکویتی. - جلسه ۳: باندینگ نهایی با سمان رزینی لایت کیور، حذف اضافات و تنظیم بایت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Clear Aligners", titleFa = "ارتودنسی شفاف", defaultCost = 30000000L, defaultNotes = "ارتودنسی با الاینرهای شفاف (Clear Aligner Therapy) - ردیف کردن دندان‌ها با پلاک‌های نامرئی متوالی", stages = "جلسه ۱: اسکن ۳ بعدی و طراحی دیجیتال (ClinCheck). - جلسه ۲: نصب اتچمنت‌های کامپوزیتی، تحویل سری اول الاینرها و آموزش. - مراجعات دوره‌ای (هر ۶-۸ هفته): بررسی پیشرفت، تحویل الاینرهای بعدی و انجام IPR در صورت نیاز. - جلسه پایانی: اسکن نهایی و سفارش ریتینرها", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Composite Filling", titleFa = "پرکردن کامپوزیت", defaultCost = 1500000L, defaultNotes = "ترمیم کامپوزیت دندان (Composite Resin Restoration) - ترمیم مستقیم همرنگ دندان با مواد لایت کیور", stages = "جلسه ۱: بی‌حسی، حذف پوسیدگی و بافت‌های ضعیف، اچینگ مینا و عاج، اپلای باندینگ، لایه‌گذاری کامپوزیت به روش اینکرمنتال، کیورینگ، پرداخت و پالیش نهایی", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Crown Lengthening", titleFa = "جراحی افزایش طول تاج", defaultCost = 3000000L, defaultNotes = "اصلاح عرض بیولوژیک و بافت استخوان دور دندان (Crown Lengthening) - افزایش طول تاج کلینیکی با جراحی فلپ", stages = "جلسه ۱: جراحی فلپ، اصلاح استخوان (استئوتومی) و بخیه - جلسه ۲: کشیدن بخیه و ارزیابی لثه", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Cyst Enucleation", titleFa = "تخلیه کیست فکی", defaultCost = 4500000L, defaultNotes = "بیرون‌آوردن کیست ادنتوژنیک (Odontogenic Cyst Enucleation) - خارج کردن کیست فکی و کورتاژ استخوان اطراف", stages = "جلسه ۱: بی‌حسی، فلپ و استئوتومی، جدا کردن و انوکلئاسیون کامل کیست، کورتاژ استخوان، شست‌وشو، پرکردن نقص با پودر استخوان در صورت لزوم، بخیه. - جلسه ۲: کشیدن بخیه و کنترل", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Dental Bonding", titleFa = "باندینگ زیبایی", defaultCost = 1000000L, defaultNotes = "باندینگ کامپوزیت (Composite Bonding) - اصلاح جزئی شکل، فاصله یا تراش‌خوردگی دندان بدون تراش وسیع", stages = "جلسه ۱: انتخاب رنگ، اچینگ مینا، باندینگ، اعمال کامپوزیت فلو یا خمیری، شکل‌دهی و کیورینگ، پرداخت و پالیش", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Dental Implant", titleFa = "کاشت ایمپلنت", defaultCost = 12000000L, defaultNotes = "کاشت ایمپلنت دندانی (Dental Implant Placement) - جایگزینی ریشه دندان با فیکسچر تیتانیومی داخل استخوانی", stages = "جلسه ۱: مشاوره و CBCT، جراحی پانچ یا فلپ، آماده‌سازی استخوان با دریل‌های متوالی، کارگذاری فیکسچر، قرار دادن کاور اسکرو و بخیه. - جلسه ۲: کشیدن بخیه (یک هفته بعد). - جلسه ۳: پس از دوره اسئواینتگریشن (۳-۴ ماه)، نمایان‌سازی ایمپلنت و نصب هیلینگ اباتمنت. - جلسه ۴: قالب‌گیری از ایمپلنت. - جلسه ۵: تست اباتمنت و فریم. - جلسه ۶: تحویل روکش قطعی سمان‌شونده یا پیچ‌شونده", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Denture", titleFa = "پروتز متحرک", defaultCost = 8000000L, defaultNotes = "پروتز کامل یا پارسیل متحرک (Complete / Partial Removable Denture) - جایگزینی دندان‌های از دست رفته با پروتز متحرک", stages = "جلسه ۱: قالب‌گیری اولیه و ساخت تری اختصاصی. - جلسه ۲: قالب‌گیری نهایی با تری. - جلسه ۳: ثبت روابط بایت و انتخاب رنگ. - جلسه ۴: امتحان واکس. - جلسه ۵: تحویل پروتز و تنظیمات اولیه. - جلسه ۶: تنظیم نقاط فشار و زخم‌ها در جلسات پیگیری", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Expose & Bond", titleFa = "نمایان‌سازی دندان نهفته", defaultCost = 4000000L, defaultNotes = "نمایان‌سازی و باندینگ جراحی دندان نهفته (Surgical Exposure & Bonding of Impacted Tooth) - اتصال براکت جهت کشش ارتودنسی دندان کانین یا دیگر دندان‌های نهفته", stages = "جلسه ۱: بی‌حسی، فلپ باز، نمایان‌سازی تاج، هموستاز، اچ و باند براکت طلایی یا سرامیکی روی مینا، نخ لاستیکی یا زنجیر طلایی متصل به براکت، تطبیق فلپ یا پانسمان. - جلسه ۲: کشیدن بخیه و شروع کشش ارتودنسی", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Fissure Sealant", titleFa = "فیشور سیلانت", defaultCost = 1000000L, defaultNotes = "فیشور سیلانت (Pit and Fissure Sealant) - پوشش پیشگیرانه شیارهای عمیق دندان‌های آسیاب", stages = "جلسه ۱: تمیز کردن شیارها با برس و پامیس، ایزولاسیون، اچینگ اسید فسفریک، شست‌وشو و خشک کردن، اعمال رزین سیلانت فلو، کیورینگ نوری و بررسی تطابق بایت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Fixed Orthodontics", titleFa = "ارتودنسی ثابت", defaultCost = 25000000L, defaultNotes = "ارتودنسی ثابت با براکت (Fixed Orthodontic Treatment / Braces) - منظم کردن دندان‌ها با براکت‌های فلزی یا سرامیکی", stages = "جلسه ۱: ثبت مدارک تشخیصی (عکس‌ها، قالب‌ها، رادیوگرافی). - جلسه ۲: باندینگ براکت‌ها و قرار دادن سیم اولیه. - جلسات ماهانه: تنظیم سیم، لیگاچور و زنجیرهای الاستومریک. - جلسه پایانی: ده‌باندینگ، پالیش مینا، تحویل ریتینرهای ثابت و متحرک", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Flap Surgery", titleFa = "جراحی فلپ لثه", defaultCost = 4000000L, defaultNotes = "جراحی فلپ پریودنتال (Periodontal Flap Surgery / Osseous Surgery) - دسترسی مستقیم به ریشه و استخوان برای درمان پاکت‌های عمیق", stages = "جلسه ۱: بی‌حسی، اینسیژن سولکولار و پارشیل تیکنس، بلند کردن فلپ، دبریدمان کامل گرانولیشن تیشو، جرم‌گیری عمیق ریشه و تسطیح، اصلاح استخوان (استئوپلاستی/استئوتومی) در صورت وجود نقایص، شست‌وشو، تطبیق فلپ و بخیه اینترپروگزیمال. - جلسه ۲: کشیدن بخیه و ارزیابی التیام اولیه", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Fluoride Therapy", titleFa = "فلورایدتراپی", defaultCost = 700000L, defaultNotes = "فلورایدتراپی موضعی (Topical Fluoride Application) - پیشگیری و توقف پوسیدگی‌های اولیه با وارنیش فلوراید", stages = "جلسه ۱: تمیز کردن دندان‌ها، ایزولاسیون نسبی، اعمال وارنیش فلوراید (۵٪ NaF) با برس مخصوص، خشک شدن خودبه‌خود، دستور عدم خوردن تا ۳۰ دقیقه", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Frenectomy", titleFa = "فرنکتومی", defaultCost = 1500000L, defaultNotes = "بریدن فرنوم لبی یا زبانی (Labial/Lingual Frenectomy) - اصلاح بند متصل به لثه که باعث دیاستم یا محدودیت زبانی می‌شود", stages = "جلسه ۱: بی‌حسی، برش نواری فرنوم با اسکالپل یا لیزر، جدا کردن فیبرهای نفوذی تا پریواستئوم، بخیه (در صورت نیاز). - جلسه ۲: کشیدن بخیه و تمرینات زبانی", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Full Mouth Rehab", titleFa = "بازسازی کامل دهان", defaultCost = 30000000L, defaultNotes = "بازسازی کامل دندان‌ها (Full Mouth Rehabilitation / Reconstruction) - طرح درمان جامع ترمیم‌های وسیع و بازسازی بعد عمودی", stages = "طی جلسات متعدد: ثبت مدارک تشخیصی، افزایش بعد عمودی با اسپلینت، تهیه واکس‌آپ تشخیصی، آماده‌سازی دندان‌ها، ترمیم‌های موقت طولانی‌مدت، ساخت ترمیم‌های قطعی (روکش/بریج/ایمپلنت)، چسباندن قطعی و تنظیم اکلوزال", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Gingivectomy", titleFa = "جراحی لثه", defaultCost = 2000000L, defaultNotes = "جراحی برداشت لثه (Gingivectomy / Gingivoplasty) - اصلاح شکل و بلندی لثه، حذف پاکت‌های کاذب یا افزایش طول تاج بدون درگیری استخوان", stages = "جلسه ۱: بی‌حسی، مشخص‌سازی نقاط خونریزی‌دهنده با پریودنتال پروب، برداشتن بافت اضافی با چاقوی جراحی یا الکتروسرجری، پروفایل‌دهی (Gingivoplasty)، پانسمان پریودنتال (Coe-Pak). - جلسه ۲: بررسی التیام و بهداشت ناحیه", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "GTR", titleFa = "بازسازی هدایت‌شده", defaultCost = 5500000L, defaultNotes = "بازسازی هدایت‌شده بافتی (Guided Tissue Regeneration - GTR) - استفاده از ممبران برای بازسازی انساج پریودنتال از دست رفته", stages = "جلسه ۱: فلپ با ضخامت کامل، دبریدمان نقص استخوانی و سطح ریشه، جایگذاری ماده پیوندی (آلوگرافت/زنوگرافت)، پوشاندن با ممبران قابل جذب یا غیرقابل جذب، بخیه کاملاً پوشاننده. - جلسه ۲: کشیدن بخیه و کنترل التیام (در صورت وجود ممبران غیرقابل جذب، خارج کردن آن در جلسه دوم)", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Habit Breaker", titleFa = "دستگاه ترک عادت", defaultCost = 2000000L, defaultNotes = "دستگاه ضد عادت دهانی (Habit Breaking Appliance) - ترک عادت مکیدن انگشت یا اینترپوزیشن زبانی", stages = "جلسه ۱: قالب‌گیری، طراحی دستگاه (کریب فلزی یا اسپایک). - جلسه ۲: تحویل و تنظیم دستگاه، آموزش والدین. - جلسات پیگیری: بررسی زخم‌ها و ادامه درمان", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Home Bleaching", titleFa = "بلیچینگ خانگی", defaultCost = 1800000L, defaultNotes = "بلیچینگ با تری اختصاصی (Custom Tray Home Bleaching) - سفید کردن تدریجی دندان با ژل کارباماید پراکساید و تری شخصی", stages = "جلسه ۱: قالب‌گیری آلژینات، ساخت تری سفارشی نرم و شفاف، تحویل تری و ژل ۱۰-۱۶٪ کارباماید پراکساید، آموزش مدت و شیوه مصرف. - جلسه پیگیری: بررسی تغییر رنگ، حساسیت و تطابق تری", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Immediate Implant", titleFa = "ایمپلنت فوری", defaultCost = 13000000L, defaultNotes = "کاشت ایمپلنت فوری پس از کشیدن (Immediate Implant Placement) - کارگذاری ایمپلنت همزمان با کشیدن دندان و پرکردن گپ", stages = "جلسه ۱: کشیدن آتروماتیک دندان، کورتاژ و بررسی چهار دیواره سوکت، کارگذاری ایمپلنت با ثبات اولیه مناسب، قرار دادن بیومتریال گرافت در گپ بین ایمپلنت و استخوان، بخیه و گاهی تاج موقت فوری. - جلسات بعدی مشابه روند عادی ایمپلنت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "In-Office Bleaching", titleFa = "بلیچینگ مطب", defaultCost = 2500000L, defaultNotes = "بلیچینگ دندان در مطب (In-Office Tooth Whitening) - سفید کردن دندان‌ها با ژل پراکسید هیدروژن و فعال‌سازی نوری", stages = "جلسه ۱: ایزولاسیون لثه با رابردم یا ژل محافظ، اعمال ژل بلیچینگ (۳۵٪ هیدروژن پراکساید)، فعال‌سازی با نور LED یا لیزر، شست‌وشو و خنثی‌سازی، تکرار چرخه تا رسیدن به سایه دلخواه، ژل دسنسیبلایزر", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Inlay / Onlay", titleFa = "اینله / آنله", defaultCost = 3500000L, defaultNotes = "ترمیم غیرمستقیم اینله و آنله (Indirect Inlay / Onlay Restoration) - ترمیم سرامیکی یا کامپوزیتی ساخته‌شده خارج از دهان", stages = "جلسه ۱: بی‌حسی، تراش دقیق حفره، قالب‌گیری دیجیتال یا سنتی، ترمیم موقت. - جلسه ۲: حذف موقت، پروتست و تطابق، اچ و باندینگ، سمان‌کردن ترمیم قطعی با سمان رزینی", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Night Guard", titleFa = "نایت گارد", defaultCost = 3000000L, defaultNotes = "محافظ شبانه اکلوزال (Occlusal Night Guard / Splint) - اسپلینت سخت یا نرم برای درمان بروکسیسم و کاهش سایش", stages = "جلسه ۱: قالب‌گیری و ثبت بایت با موم یا جیگ. - جلسه ۲: تحویل اسپلینت سخت آکریلی، تنظیم نقاط تماس اکلوزالی، آموزش نحوه استفاده و نگهداری", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Oral Biopsy", titleFa = "بیوپسی دهانی", defaultCost = 1800000L, defaultNotes = "نمونه‌برداری از بافت دهان (Oral Mucosal Biopsy) - تشخیص ضایعات مشکوک با برداشت نمونه", stages = "جلسه ۱: بی‌حسی، تثبیت بافت، بیوپسی اکسیژنال یا پانچ، هموستاز، بخیه. ارسال نمونه به پاتولوژی. - جلسه ۲: کشیدن بخیه و تفسیر جواب پاتولوژی", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Pediatric Pulpotomy", titleFa = "پالپوتومی اطفال", defaultCost = 2500000L, defaultNotes = "پالپوتومی دندان شیری (Pediatric Pulpotomy / Partial Pulpectomy) - حفظ دندان شیری عفونی‌شده تا زمان رویش دندان دائمی", stages = "جلسه ۱: بی‌حسی، ایزولاسیون رابردم، حذف پوسیدگی، باز کردن پالپ‌چمبر، قطع پالپ تاجی و کنترل خونریزی، قرار دادن ماده دارویی (فرموکرزول یا MTA)، ترمیم تاج با کامپوزیت یا روکش استیل", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "PFM Bridge", titleFa = "روکش بریج پرسلن", defaultCost = 11000000L, defaultNotes = "بریج فلز-سرامیک (Porcelain Fused to Metal Bridge) – جایگزینی دندان‌ها با فریم فلزی و پوشش پرسلن لایه‌ای", stages = "جلسه ۱: تراش دندان‌های پایه با مارجین شولدر باول، قالب‌گیری نهایی، روکش‌های موقت. - جلسه ۲: تست فریم فلزی (بررسی نشست کامل و مارجین). - جلسه ۳: تست بیسکویتی (بررسی رنگ، فرم و کانتاکت‌های پروگزیمال). - جلسه ۴: چسباندن دائم با سمان گلاس‌آینومر یا رزینی، پالیش و تنظیم بایت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "PFM Crown", titleFa = "روکش پرسلن تک‌واحدی", defaultCost = 4200000L, defaultNotes = "روکش تک واحدی فلز-سرامیک (Single-Unit Porcelain Fused to Metal Crown) – روکش استاندارد با فریم فلزی و پرسلن فلدسپاتیک", stages = "جلسه ۱: بی‌حسی، تراش دندان، قالب‌گیری، ساخت روکش موقت. - جلسه ۲: تست فریم فلزی (تطابق مارجین). - جلسه ۳: تست بیسکویتی و تأیید نهایی رنگ. - جلسه ۴: چسباندن دائم با سمان دائمی مناسب، تنظیم اکلوزن و پالیش حاشیه‌ها", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Post & Core", titleFa = "پست و کور", defaultCost = 2000000L, defaultNotes = "پایه و هسته داخل ریشه (Post and Core Build-up) - بازسازی تاج دندان درمان‌ریشه شده با فایبر پست و کامپوزیت", stages = "جلسه ۱: تخلیه بخشی از گوتاپرکای کانال، آماده‌سازی فضای پست، اچ و سمان فایبر پست، کور کامپوزیتی مستقیم. (یا قالب‌گیری برای کور ریختگی غیرمستقیم در جلسه جداگانه)", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Posterior Infected RCT", titleFa = "عصب‌کشی خلفی عفونی", defaultCost = 4200000L, defaultNotes = "درمان ریشه دندان خلفی عفونی (Posterior Infected Root Canal Treatment) – درمان کانال‌های متعدد مولر/پرمولر با عفونت فعال", stages = "جلسه ۱: بی‌حسی، ایزولاسیون، حذف ترمیم قدیمی و پوسیدگی، لوکالیز کردن تمامی اوریفیس‌ها (MB, ML, D, P)، تعیین طول کانال‌ها، تمیزکردن مکانیکی با فایل‌های NiTi، شست‌وشو با سدیم هیپوکلریت ۵.۲۵٪ و EDTA 17%، خشک‌کردن، مدیکیشن کلسیم هیدروکساید، پانسمان موقت. - جلسه ۲: تأیید عدم وجود علائم، شست‌وشوی نهایی و اُبچوریشن سه‌بعدی با گوتاپرکا و سیلر رزینی/بایوسرامیک، ترمیم کرونال", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Posterior RCT", titleFa = "عصب‌کشی خلفی", defaultCost = 3800000L, defaultNotes = "درمان ریشه دندان خلفی غیرعفونی (Posterior Non-Infected Root Canal Treatment) – پالپکتومی مولر/پرمولر در غیاب علائم عفونی حاد", stages = "جلسه ۱: بی‌حسی، ایزولاسیون، خارج‌کردن پالپ، پاک‌سازی و شکل‌دهی تمام کانال‌ها، شست‌وشو و در صورت امکان اُبچوریشن در همان جلسه (Single-visit RCT). در صورت وقت‌گیر بودن یا خستگی بیمار: مدیکیشن، پانسمان و جلسه دوم برای اُبچوریشن", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Re-RCT", titleFa = "درمان مجدد ریشه", defaultCost = 6000000L, defaultNotes = "درمان مجدد ریشه (Root Canal Retreatment) - تخلیه مواد پرکننده قدیمی و پاک‌سازی مجدد کانال‌ها پس از شکست درمان اولیه", stages = "جلسه ۱: بی‌حسی، ایزولاسیون، تخلیه ترمیم تاجی، حذف گوتاپرکا و سیلر با حلال و فایل‌های روتاری، پاک‌سازی مضاعف، شناسایی کانال‌های از دست رفته، شست‌وشو با هیپوکلریت و EDTA، مدیکیشن کلسیم هیدروکساید، پانسمان موقت. - جلسه ۲: تأیید عدم وجود علائم بالینی، شست‌وشوی نهایی، ابچوریشن با گوتاپرکا و سیلر بایوسرامیک، ترمیم کرونال", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Root Canal Treatment", titleFa = "عصب‌کشی", defaultCost = 3500000L, defaultNotes = "درمان ریشه (Root Canal Therapy / RCT) - حذف پالپ عفونی، پاک‌سازی کانال‌ها و پرکردن سه‌بعدی فضای کانال", stages = "جلسه ۱: بی‌حسی، ایزولاسیون رابردم، حذف پوسیدگی و پالپ‌چمبر، کارگذاری فایل‌های روتاری برای تعیین طول الکترونیکی، پاک‌سازی و شکل‌دهی (Cleaning & Shaping)، شست‌وشوی فراوان با هیپوکلریت سدیم و EDTA، خشک‌کردن، قرار دادن مدیکیشن کلسیم هیدروکساید، پانسمان موقت. - جلسه ۲: بی‌حسی، برداشتن پانسمان، شست‌وشوی نهایی و فعال‌سازی التراسونیک، خشک کردن با پیپرپوینت، پرکردن کانال‌ها با گوتاپرکا و سیلر (اُبچوریشن)، ترمیم کرونال با کامپوزیت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Scaling & Polishing", titleFa = "جرم‌گیری و بروساژ", defaultCost = 1200000L, defaultNotes = "جرم‌گیری و پالیش دندان (Prophylaxis / Scaling & Polishing) - پاک‌سازی جرم‌های فوق و زیر لثه‌ای و پلاک", stages = "جلسه ۱: جرم‌گیری التراسونیک (کاویترون)، کورتاژ دستی با کورت‌های گریسی برای نواحی زیر لثه‌ای سطحی، بروساژ با خمیر پروفیلاکسی و رابردام، فلورایدتراپی انتهایی", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Scaling & Root Planing", titleFa = "جرم‌گیری عمیق و تسطیح سطح ریشه", defaultCost = 2500000L, defaultNotes = "جرم‌گیری و تسطیح سطح ریشه (Scaling and Root Planing - SRP) - درمان غیرجراحی بیماری‌های پریودنتال و کاهش عمق پاکت", stages = "جلسه ۱: بی‌حسی موضعی، جرم‌گیری کامل دو کوادرانت با اولتراسونیک و کورت‌های گریسی مخصوص عمق، تسطیح سطح ریشه تا رسیدن به سطح صاف شیشه‌ای. - جلسه ۲: تکرار برای دو کوادرانت مقابل. - جلسه ۳ (بعد از ۴-۶ هفته): ارزیابی مجدد عمق پاکت‌ها و خونریزی (Periodontal Re-evaluation)", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Simple Extraction", titleFa = "کشیدن دندان ساده", defaultCost = 800000L, defaultNotes = "کشیدن دندان ساده (Simple Tooth Extraction) - خارج کردن دندان سالم یا پوسیده با حرکت لوکسیشن و الوئیشن", stages = "جلسه ۱: بی‌حسی، لوکسیشن با الواتور، خارج کردن با فورسپس، کورتاژ سوکت، گاز استریل. دستورات پس از کشیدن", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Sinus Lift", titleFa = "سینوس لیفت", defaultCost = 8000000L, defaultNotes = "سینوس لیفت و پیوند استخوان (Sinus Floor Elevation & Bone Graft) - بالا بردن غشای سینوس ماگزیلاری و بازسازی استخوان", stages = "جلسه ۱: فلپ لترال، استئوتومی پنجره استخوانی، جدا کردن و بالا بردن دقیق غشای اشنایدریان، پر کردن فضای ایجاد شده با پودر استخوان، پوشاندن پنجره با ممبران، بخیه. - جلسه ۲: کشیدن بخیه و رادیوگرافی کنترل", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Socket Preservation", titleFa = "حفظ ساکت", defaultCost = 3000000L, defaultNotes = "حفظ استخوان ساکت دندانی (Alveolar Ridge Preservation / Socket Grafting) - جلوگیری از تحلیل استخوان پس از کشیدن دندان برای ایمپلنت آینده", stages = "جلسه ۱: کشیدن آتروماتیک و دبریدمان سوکت، جایگذاری پودر استخوان در سوکت، پوشاندن با ممبران کلاژنی یا PRF، بخیه. - جلسه ۲: کشیدن بخیه و ارزیابی", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Soft Tissue Graft", titleFa = "پیوند بافت نرم", defaultCost = 4500000L, defaultNotes = "پیوند لثه (Soft Tissue Graft / Connective Tissue Graft) - پوشش ریشه‌های عریان و افزایش لثه چسبنده", stages = "جلسه ۱: بی‌حسی، آماده‌سازی بستر گیرنده و دبریدمان سطح ریشه، برداشت گرافت بافت همبند از کام (Trap-door technique)، بخیه در ناحیه دهنده و بخیه تثبیت گرافت در ناحیه گیرنده با تکنیک تونل یا پاکت. - جلسه ۲: کشیدن بخیه و کنترل تغذیه گرافت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Space Maintainer", titleFa = "نگهدارنده فضا", defaultCost = 1800000L, defaultNotes = "فضانگهدار دندان شیری (Space Maintainer Appliance) - حفظ فضای دندان شیری از دست رفته برای رویش دندان دائمی", stages = "جلسه ۱: قالب‌گیری و سفارش دستگاه (بند و لوپ یا دیستال شو). - جلسه ۲: تحویل و سمان‌کردن دستگاه، آموزش بهداشت. - جلسات پیگیری: کنترل رویش و اپن‌بایت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "SSC Crown", titleFa = "روکش استیل شیری", defaultCost = 2000000L, defaultNotes = "روکش استیل دندان شیری (Stainless Steel Crown - SSC) - بازسازی دندان شیری خلفی دارای تخریب وسیع", stages = "جلسه ۱: بی‌حسی، ایزولاسیون، آماده‌سازی دندان و کاهش ابعاد، انتخاب سایز روکش، تطابق حاشیه و کانتورینگ، سمنتاسیون با گلاس‌آینومر، کنترل بایت", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "TMD Splint", titleFa = "اسپلینت TMD", defaultCost = 3500000L, defaultNotes = "اسپلینت درمانی مفصل گیجگاهی فکی (Temporomandibular Joint Stabilization Splint) - کاهش درد و اختلالات عضلانی-مفصلی", stages = "جلسه ۱: معاینه جامع TMD، قالب‌گیری و ثبت بایت در رابطه مرکزی. - جلسه ۲: تحویل اسپلینت ریپوزیشنینگ یا تثبیتی، تنظیم دقیق راهنماها و تماس‌ها. - جلسات پیگیری منظم جهت تنظیم", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Wisdom Surgery", titleFa = "جراحی دندان عقل", defaultCost = 1200000L, defaultNotes = "کشیدن دندان عقل نهفته / نیمه‌نهفته (Surgical Extraction of Impacted Wisdom Tooth) - خارج کردن دندان عقل با فلپ و استئوتومی", stages = "جلسه ۱: بی‌حسی، فلپ پاکتی مثلثی، استئوتومی در صورت نیاز، قطعه‌قطعه کردن دندان با هندپیس جراحی، لوکسیشن و خروج قطعات، کورتاژ و شست‌وشو، بخیه. - جلسه ۲: کشیدن بخیه و کنترل ترمیم", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Zirconia Bridge", titleFa = "روکش بریج زیرکونیا", defaultCost = 14000000L, defaultNotes = "بریج تمام زیرکونیا (Full-Contour Zirconia Fixed Bridge) – جایگزینی دندان‌های از دست رفته با فریم ورک زیرکونیایی مونولیتیک با استحکام بالا", stages = "جلسه ۱: بی‌حسی، تراش دندان‌های پایه با طراحی شولدر یا چمفر عمیق، نخ‌گذاری، قالب‌گیری دیجیتال (اسکن داخل دهانی) یا سیلیکونی سنگین-سبک، ثبت بایت و شید، بریج موقت. - جلسه ۲: تست فریم زیرکونیا (بررسی تطابق مارجینال و کانتور). - جلسه ۳: تحویل بریج قطعی، سمان‌کردن با سمان رزینی (پس از پروتکل باندینگ زیرکونیا)، حذف اضافات و تنظیم اکلوزن", isStarred = true))
        database.treatmentTemplateDao().insertTemplate(TreatmentTemplate(title = "Zirconia Crown", titleFa = "روکش زیرکونیا تک‌واحدی", defaultCost = 5500000L, defaultNotes = "روکش تک واحدی زیرکونیا (Single-Unit Full Zirconia Crown) – روکش تمام زیرکونیا برای دندان‌های خلفی با نیاز به استحکام بالا", stages = "جلسه ۱: بی‌حسی، تراش دندان با کاهش اکلوزال ۱.۵-۲ میلی‌متر، چمفر ۰.۵ میلی‌متر، اسکن دیجیتال یا قالب‌گیری سنتی، روکش موقت. - جلسه ۲: تست روکش (بررسی تطابق، کانتاکت‌ها و رنگ)، سمان‌کردن قطعی با سمان رزینی Dual-Cure پس از سندبلاست و پرایمر MDP", isStarred = true))

        }

        // صندوق‌های پیش‌فرض مطب: فقط «تنخواه گردان مطب» و «کیف پول».
        repository.allTemplates.first().forEach { repository.enqueueSyncOp("treatment_templates", it.syncId) }
    }
    // Lazily (نه Eagerly): از استارت بی‌جهت جمع‌آوری نمی‌شود، ولی چون در چند جا
    // .value به‌صورت همگام خوانده می‌شود، از WhileSubscribed استفاده نمی‌کنیم تا
    // پس از اولین فعال‌شدن، آخرین مقدار همیشه در دسترس بماند.
    val allCalendarEvents = repository.allCalendarEvents.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 5 Separate Switches for calendar occasion categories
    val showShamsiEvents = MutableStateFlow(prefs.getBoolean("show_shamsi_events", true))
    val showQamariEvents = MutableStateFlow(prefs.getBoolean("show_qamari_events", true))
    val showGlobalEvents = MutableStateFlow(prefs.getBoolean("show_global_events", true))
    val showPersonalEvents = MutableStateFlow(prefs.getBoolean("show_personal_events", true))
    val showOfficialHolidays = MutableStateFlow(prefs.getBoolean("show_official_holidays", true))

    fun setShowShamsiEvents(show: Boolean) {
        showShamsiEvents.value = show
        prefs.edit().putBoolean("show_shamsi_events", show).apply()
    }

    fun setShowQamariEvents(show: Boolean) {
        showQamariEvents.value = show
        prefs.edit().putBoolean("show_qamari_events", show).apply()
    }

    fun setShowGlobalEvents(show: Boolean) {
        showGlobalEvents.value = show
        prefs.edit().putBoolean("show_global_events", show).apply()
    }

    fun setShowPersonalEvents(show: Boolean) {
        showPersonalEvents.value = show
        prefs.edit().putBoolean("show_personal_events", show).apply()
    }

    fun setShowOfficialHolidays(show: Boolean) {
        showOfficialHolidays.value = show
        prefs.edit().putBoolean("show_official_holidays", show).apply()
    }

    private suspend fun seedDefaultCalendarEvents() {
        // رفعِ باگِ «هر رویداد چند بار تکرار شده»: هر بار این تابع دوباره اجرا شود (نصب مجدد،
        // دستگاه دوم روی همان حساب، پاک کردن کامل داده، یا اجرای مجدد گیت نسخه‌ی
        // calendar_events_1405_pdf_v3 روی یک DB تازه)، لیست رویدادهای مرجع از نو ساخته و
        // insert می‌شود. AppCalendarEvent هیچ ایندکس/محدودیت یکتایی روی syncId ندارد و Room
        // با OnConflictStrategy.REPLACE فقط روی primary key (id) دیدوپ می‌کند، پس این insert
        // همیشه ردیف‌های محلی تازه می‌سازد؛ SyncEngine هم همین ردیف‌ها را (even پس از رفع
        // تصادفی‌بودن syncId) به‌عنوان رکورد جدید push می‌کرد چون در سمت سرور رکورد قبلی
        // toposn نداشت که پاک/جایگزین شود. راه‌حل: قبل از هر seed، ابتدا رویدادهای غیرشخصی
        // قبلی (هم محلی و هم صف حذف سمت سرور از طریق enqueueSyncOp DELETE در
        // deleteNonPersonalCalendarEvents) پاک می‌شوند، سپس مجموعه‌ی مرجع دوباره درج می‌شود.
        // این تضمین می‌کند که seed مجدد همیشه دقیقاً یک نسخه از هر رویداد باقی می‌گذارد، نه
        // انباشتن نسخه‌های تکراری از هر بار اجرا.
        val owner = com.example.util.Supabase.ensureAccountId()
        val events = com.example.util.DefaultCalendarEvents.parseCsvToCalendarEvents(
            com.example.util.DefaultCalendarEvents.DEFAULT_CSV
        )
        repository.insertCalendarEvents(events.map { it.copy(syncId = "$owner:${it.syncId}") })
    }

    fun importCalendarEventsFromCsv(csvContent: String, replace: Boolean = true): Boolean {
        return try {
            val events = com.example.util.DefaultCalendarEvents.parseCsvToCalendarEvents(csvContent)
            if (events.isEmpty()) return false
            viewModelScope.launch {
                if (replace) repository.deleteNonPersonalCalendarEvents()
                repository.insertCalendarEvents(events)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun exportCalendarEventsToCsv(): String {
        val list = allCalendarEvents.value
        val sb = java.lang.StringBuilder()
        sb.append("دسته‌بندی,تاریخ شمسی (روز/ماه),تاریخ قمری (روز/ماه),تاریخ میلادی (روز/ماه),مناسبت,تعطیل رسمی در ایران,توضیحات,مناسبت شخصی\n")
        for (event in list) {
            val category = event.category
            val shamsiStr = if (event.shamsiDay != null && event.shamsiMonth != null) {
                if (event.shamsiYear != null) {
                    "${event.shamsiYear}/${event.shamsiMonth.toString().padStart(2, '0')}/${event.shamsiDay.toString().padStart(2, '0')}"
                } else {
                    "${event.shamsiDay.toString().padStart(2, '0')}/${event.shamsiMonth.toString().padStart(2, '0')}"
                }
            } else ""
            
            val qamariStr = if (event.qamariDay != null && event.qamariMonth != null) {
                "${event.qamariDay.toString().padStart(2, '0')}/${event.qamariMonth.toString().padStart(2, '0')}"
            } else ""
            
            val miladiStr = if (event.miladiDay != null && event.miladiMonth != null) {
                "${event.miladiDay.toString().padStart(2, '0')}/${event.miladiMonth.toString().padStart(2, '0')}"
            } else ""
            
            val title = if (event.title.contains(",") || event.title.contains("\"")) {
                "\"${event.title.replace("\"", "\"\"")}\""
            } else {
                event.title
            }
            
            val isHoliday = if (event.isHolidayInIran) "بله" else "خیر"
            
            val desc = if (event.description.contains(",") || event.description.contains("\"")) {
                "\"${event.description.replace("\"", "\"\"")}\""
            } else {
                event.description
            }
            
            val personal = event.personalType
            
            sb.append("$category,$shamsiStr,$qamariStr,$miladiStr,$title,$isHoliday,$desc,$personal\n")
        }
        return sb.toString()
    }

    fun getCustomOccasionsForDay(jy: Int, jm: Int, jd: Int): List<com.example.util.CalendarOccasion> {
        val list = allCalendarEvents.value
        val currentLang = selectedLanguage.value.lowercase()

        var (gy, gm, gd) = com.example.util.PersianCalendarHelper.jalaliToGregorian(jy, jm, jd)
        var qDay: Int? = null
        var qMonth: Int? = null

        if (jy == 1405) {
            val threeCal = com.example.util.PersianCalendarHelper.get3CalendarsFor1405(jm, jd)
            qDay = threeCal.qamari.day
            qMonth = threeCal.qamari.month
            gd = threeCal.miladi.day
            gm = threeCal.miladi.month
            gy = threeCal.miladi.year
        } else {
            val timestamp = com.example.util.PersianCalendarHelper.jalaliToTimestamp(jy, jm, jd)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val ld = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    val hijriDate = java.time.chrono.Chronology.of("Hijrah-umalqura").date(ld)
                    qMonth = hijriDate.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
                    qDay = hijriDate.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
                }
            } catch (e: Exception) {}
        }

        // If language is NOT Persian, show regional occasions for that specific language + personal occasions
        if (currentLang != "fa") {
            val regionalList = com.example.util.RegionalOccasionsProvider.getDayOccasionsForLanguage(
                langCode = currentLang,
                gd = gd, gm = gm, gy = gy,
                qd = qDay, qm = qMonth
            )
            val result = mutableListOf<com.example.util.CalendarOccasion>()
            for (reg in regionalList) {
                result.add(
                    com.example.util.CalendarOccasion(
                        titleFa = reg.title,
                        isHoliday = reg.isHoliday,
                        isPersonal = false
                    )
                )
            }
            // Add any user-defined personal events
            val personalEvents = list.filter { it.category == "شخصی" || it.personalType.isNotEmpty() }
            for (event in personalEvents) {
                if ((event.shamsiDay == jd && event.shamsiMonth == jm) || (event.miladiDay == gd && event.miladiMonth == gm)) {
                    val prefix = when (event.personalType) {
                        "تولد" -> "🎂 "
                        "فوت" -> "🕯️ "
                        "طلاق" -> "💔 "
                        "ازدواج" -> "💍 "
                        else -> "📌 "
                    }
                    result.add(
                        com.example.util.CalendarOccasion(
                            titleFa = "$prefix${event.title}",
                            isHoliday = event.isHolidayInIran,
                            isPersonal = true
                        )
                    )
                }
            }
            return result
        }

        if (list.isEmpty()) return emptyList()

        val matched = list.filter { event ->
            var isMatch = false
            
            if (event.shamsiDay == jd && event.shamsiMonth == jm) {
                if (event.shamsiYear != null) {
                    if (event.shamsiYear == jy) {
                        isMatch = true
                    }
                } else {
                    isMatch = true
                }
            }
            
            if (event.miladiDay == gd && event.miladiMonth == gm) {
                isMatch = true
            }
            
            if (qDay != null && qMonth != null && event.qamariDay == qDay && event.qamariMonth == qMonth) {
                isMatch = true
            }
            
            if (!isMatch) return@filter false

            val isReligious = event.category.contains("مذهب") || event.category.contains("قمر")
            val isIranian = event.category.contains("ملی") || event.category.contains("ایران") || event.category.contains("باستان") || event.category.contains("معاصر") || event.category.contains("شمسی")
            val isGlobal = event.category.contains("جهان") || event.category.contains("میلاد") || event.category.contains("miladi")
            val isPersonal = event.category == "شخصی" || event.personalType.isNotEmpty()

            if (event.isHolidayInIran && !showOfficialHolidays.value) {
                return@filter false
            }

            if (isPersonal) {
                showPersonalEvents.value
            } else if (isReligious) {
                showQamariEvents.value
            } else if (isIranian) {
                showShamsiEvents.value
            } else if (isGlobal) {
                showGlobalEvents.value
            } else {
                true
            }
        }

        return matched.map { event ->
            var displayName = event.title
            if (event.category == "شخصی" && event.personalType.isNotEmpty()) {
                val prefix = when (event.personalType) {
                    "تولد" -> "🎂 تولد: "
                    "فوت" -> "🕯️ سالگرد فوت: "
                    "طلاق" -> "💔 سالگرد طلاق: "
                    "ازدواج" -> "💍 سالگرد ازدواج: "
                    else -> "📌 ${event.personalType}: "
                }
                displayName = prefix + event.title
                if (event.shamsiYear != null) {
                    val age = jy - event.shamsiYear
                    if (age > 0) {
                        displayName += " ($age سال)"
                    }
                }
            }
            val isPersonal = event.category == "شخصی" || event.personalType.isNotEmpty()
            com.example.util.CalendarOccasion(
                titleFa = displayName,
                isHoliday = event.isHolidayInIran,
                isPersonal = isPersonal
            )
        }
    }

    fun isCustomHoliday(jy: Int, jm: Int, jd: Int): Boolean {
        if (com.example.util.PersianCalendarHelper.getDayOfWeekIndex(jy, jm, jd) == 6) return true
        val custom = getCustomOccasionsForDay(jy, jm, jd)
        return custom.any { it.isHoliday }
    }

    fun deleteCalendarEvent(event: AppCalendarEvent) {
        if (!authorizeDomain("calendar", "DELETE_CALENDAR_EVENT", isDelete = true)) return
        viewModelScope.launch {
            repository.deleteCalendarEvent(event)
        }
    }

    fun addPersonalOccasion(
        title: String,
        day: Int,
        month: Int,
        year: Int?,
        personalType: String,
        isHoliday: Boolean = false,
        description: String = ""
    ) {
        if (!authorizeDomain("calendar", "ADD_CALENDAR_EVENT")) return
        viewModelScope.launch {
            try {
                val event = AppCalendarEvent(
                    category = "شخصی",
                    shamsiDay = day,
                    shamsiMonth = month,
                    shamsiYear = year,
                    title = title,
                    isHolidayInIran = isHoliday,
                    description = description,
                    personalType = personalType
                )
                repository.insertCalendarEvent(event)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addPersonalOccasion(title: String, shamsiDateStr: String, personalType: String, isHoliday: Boolean = false, description: String = "") {
        if (!authorizeDomain("calendar", "ADD_CALENDAR_EVENT")) return
        val parsed = com.example.util.DefaultCalendarEvents.parseShamsiDateString(shamsiDateStr)
        if (parsed != null) {
            val (day, month, year) = parsed
            addPersonalOccasion(title, day, month, year, personalType, isHoliday, description)
        }
    }

    fun getPrescriptionsByPatient(patientId: Long) = repository.prescriptionsForPatient(patientId)
    fun insertPrescription(prescription: com.example.data.Prescription) = viewModelScope.launch {
        val patientSyncId = patients.value.find { it.id == prescription.patientId }?.syncId ?: ""
        val treatmentSyncId = allTreatments.value.find { it.id == prescription.treatmentId }?.syncId ?: ""
        repository.insertPrescription(prescription.copy(patientSyncId = patientSyncId, treatmentSyncId = treatmentSyncId))
    }
    fun deletePrescription(prescription: com.example.data.Prescription) = viewModelScope.launch { repository.deletePrescription(prescription) }

    
    // Staff Members (Clinical Staff & Profiles)
    val allStaffMembers = repository.allStaffMembers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ————————————————————————————————————————————————
    // پرسنل فعال: «یک پزشک فعال» و «یک دستیار فعال» — منبع واحد نام‌ها جدول staff_members است
    // (doctorName/staffNames قدیمی فقط برای سازگاریِ نمایشِ مقادیر قدیمی نگه داشته شده‌اند)
    // ————————————————————————————————————————————————
    val activeDoctor = combine(settings, allStaffMembers) { s, staff ->
        staff.firstOrNull { it.syncId == s.activeDoctorSyncId && !it.isDeleted }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeAssistant = combine(settings, allStaffMembers) { s, staff ->
        staff.firstOrNull { it.syncId == s.activeAssistantSyncId && !it.isDeleted }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** syncId دستیار فعال — برای پیش‌فرضِ انتخاب دستیار در تمام فعالیت‌ها (درخواست ۶) */
    val activeAssistantSyncIdsFlow = settings.map { s ->
        if (s.activeAssistantSyncId.isNotBlank()) listOf(s.activeAssistantSyncId) else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setActiveDoctor(staff: com.example.data.StaffMember) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = settings.value
            repository.insertSettings(
                current.copy(activeDoctorSyncId = staff.syncId, doctorName = staff.fullName)
            )
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک بعداً تلاش می‌کند */ }
        }
    }

    fun setActiveAssistant(staff: com.example.data.StaffMember) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = settings.value
            repository.insertSettings(
                current.copy(activeAssistantSyncId = staff.syncId, staffNames = staff.fullName)
            )
            try { syncEngine.pushPending() } catch (e: Exception) { /* سینک بعداً تلاش می‌کند */ }
        }
    }

    fun clearActiveDoctor() {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSettings(settings.value.copy(activeDoctorSyncId = ""))
        }
    }

    fun clearActiveAssistant() {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSettings(settings.value.copy(activeAssistantSyncId = ""))
        }
    }

    /** چیدمان کارت‌های داشبورد (کلیدهای جدا شده با کاما) — ذخیره در ClinicSettings.dashboardCardOrder */
    fun updateDashboardCardOrder(orderCsv: String) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSettings(settings.value.copy(dashboardCardOrder = orderCsv))
        }
    }

    /** نمایش کارت‌های تنظیمات — JSON {"key":"NORMAL|HIDDEN"}؛ کارت‌های HIDDEN فقط از منوی مخفی دیده می‌شوند */
    fun updateSettingsCardVisibility(json: String) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSettings(settings.value.copy(settingsCardVisibility = json))
        }
    }

    fun updateSettingsExpandedCards(csv: String) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSettings(settings.value.copy(settingsExpandedCards = csv))
        }
    }

    /**
     * لود اولیهٔ آواتار از گوگل‌اکانت — به‌درخواست کاربر:
     * اگر هنوز آواتاری ذخیره نشده باشد، عکس پروفایل گوگل دانلود، از مسیر
     * استاندارد [updateClinicAvatar] در سرور ذخیره و سینک می‌شود؛ بعداً با
     * لمس طولانی قابل جایگزینی است (و جایگزینِ کاربر هرگز بازنویسی نمی‌شود).
     */
    fun seedAvatarFromGoogleIfEmpty(avatarUrl: String) {
        if (avatarUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // فقط وقتی هیچ آواتاری ثبت نشده — از دیتابیس تازه می‌خوانیم نه StateFlow
                val current = repository.getSettingsOnce()
                if (!current?.avatarFileId.isNullOrBlank()) return@launch
                val conn = java.net.URL(avatarUrl).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = true
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.isEmpty()) return@launch
                val file = java.io.File(appContext.cacheDir, "google_avatar_${System.currentTimeMillis()}.jpg")
                file.writeBytes(bytes)
                // دوباره‌چک (شرایط رقابتی: شاید همین لحظه کاربر آواتار گذاشته باشد)
                val fresh = repository.getSettingsOnce()
                if (!fresh?.avatarFileId.isNullOrBlank()) {
                    file.delete()
                    return@launch
                }
                updateClinicAvatar(file.absolutePath)
            } catch (e: Exception) {
                // آفلاین یا خطای شبکه — دفعهٔ بعدِ باز شدن صفحهٔ حساب دوباره تلاش می‌شود
            }
        }
    }

    /** آواتار حساب کاربری — مجزا از لوگوی مطب؛ بلافاصله آپلود می‌شود (همان الگوی updateClinicLogo) */
    fun updateClinicAvatar(localFilePath: String) {
        if (!authorizeDomain("settings", "UPDATE_CLINIC_SETTINGS", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            isMediaUploading.value = true
            try {
                // مثل لوگو: خواندن تازه از دیتابیس + آپلود + سینک (رفع باگ ۲-۲-۱)
                val current = repository.getSettingsOnce() ?: settings.value
                repository.insertSettings(current.copy(avatarFileId = localFilePath))
                val uploadedFileId = uploadLocalFileOrNull(localFilePath, com.example.util.StorageCategory.CLINIC)
                if (uploadedFileId != null) {
                    val fresh = repository.getSettingsOnce() ?: current
                    repository.insertSettings(fresh.copy(avatarFileId = uploadedFileId))
                    com.example.ui.screens.deleteLocalStagingFileQuietly(localFilePath)
                }
                // در حالت آفلاین، مسیر محلی در تنظیمات می‌ماند و عملیات سینک در صف است؛
                // دفعه‌ی بعد که اتصال برقرار شود، SyncEngine فایل را آپلود می‌کند تا بعد از
                // خروج/ورود، آواتار از سرور ریستور شود.
                try {
                    syncEngine.pushPending()
                } catch (e: Exception) { /* سینک بعداً تلاش می‌کند */ }
            } finally {
                isMediaUploading.value = false
            }
        }
    }

    fun insertStaffMember(staff: com.example.data.StaffMember) {
        if (!authorizeDomain("settings", "ADD_STAFF_MEMBER", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertStaffMember(staff)
        }
    }

    fun updateStaffMember(staff: com.example.data.StaffMember) {
        if (!authorizeDomain("settings", "UPDATE_STAFF_MEMBER", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateStaffMember(staff)
        }
    }

    fun deleteStaffMember(staff: com.example.data.StaffMember) {
        if (!authorizeDomain("settings", "DELETE_STAFF_MEMBER", isDelete = true, doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteStaffMember(staff)
        }
    }

    /** Uploads a freshly picked local staff photo immediately and stores the resulting fileId — with instant local fallback. */
    fun updateStaffPhoto(staff: com.example.data.StaffMember, localFilePath: String) {
        if (!authorizeDomain("settings", "UPDATE_STAFF_MEMBER", doctorOnly = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            isMediaUploading.value = true
            try {
                repository.updateStaffMember(staff.copy(photoFileId = localFilePath))
                val uploadedFileId = uploadLocalFileOrNull(localFilePath, com.example.util.StorageCategory.CLINIC)
                if (uploadedFileId != null) {
                    repository.updateStaffMember(staff.copy(photoFileId = uploadedFileId))
                    com.example.ui.screens.deleteLocalStagingFileQuietly(localFilePath)
                }
            } finally {
                isMediaUploading.value = false
            }
        }
    }

    suspend fun bootstrapAfterLogin(onProgress: (Float) -> Unit = {}) {
        // قفل سراسری: از اجرای همزمان bootstrap/pullAll در اثر رقابت LaunchedEffect auto-login
        // و callback بازگشت از OAuth (گوگل) یا دکمه لاگین جلوگیری می‌کند.
        bootstrapMutex.withLock {
            // اگر همین حساب قبلاً در این اجرای اپ بوت‌استرپش کامل شده، دوباره کاری نکن.
            val resolvedAccountId = try {
                com.example.util.Supabase.resetAccountIdCache()
                com.example.util.Supabase.ensureAccountId()
            } catch (e: Exception) {
                null
            }
            if (resolvedAccountId != null && bootstrapCompletedForAccount == resolvedAccountId) {
                onProgress(1f)
                return@withLock
            }

            // نکته‌ی حیاتیِ امنیتی: بررسی تطابق حساب و پاکسازی ایزوله در صورت تعویض حساب کاربری
            val previousAccountId = prefs.getString("auth_account_id", "") ?: ""

            // اگر کاربری با حسابی متفاوت از حساب قبلی لاگین کرده باشد، پایگاه داده محلی را به صورت کامل پاکسازی می‌کنیم
            check(!resolvedAccountId.isNullOrBlank()) { "Sign-in is required before syncing" }
            if (previousAccountId.isNotBlank() && previousAccountId != resolvedAccountId) {
                if (database.pendingSyncOpDao().countOnce() != 0) throw com.example.sync.AccountMismatchException()
                repository.clearAllData()
                File(appContext.filesDir, "images").deleteRecursively()
                File(appContext.cacheDir, "camera").deleteRecursively()
                bootstrapCompletedForAccount = null
            }
            if (resolvedAccountId != null) {
                prefs.edit().putString("auth_account_id", resolvedAccountId).apply()
            }

            // کش isGuestMode را هم بر اساس مقدار واقعی جریان همگام‌سازی نگه داریم:
            // در یک لاگین واقعی OAuth/email هرگز حالت مهمان نیستیم.
            isGuestMode.value = false
            syncEngine.isGuestMode = false

            syncEngine.pullAll(onProgress)

            // رفعِ باگِ «عکسِ ثبت‌شده قابل فراخوانی نیست»: رکوردهایی که فایلشان به‌خاطر خطای قبلیِ
            // سرور (نبودِ ایندکس/documentSecurity) هرگز آپلود نشد و از صفِ سینک هم افتاد بیرون
            // (بعد از ۵ بار شکست)، اینجا دوباره به صف اضافه می‌شوند و بلافاصله یک‌بار دیگر تلاش
            // برای آپلود انجام می‌شود.
            try {
                val requeued = repository.requeueStuckLocalFiles()
                if (requeued > 0) {
                    syncEngine.pushPending()
                }
            } catch (e: Exception) {
                // بدون توقفِ ورود کاربر؛ دفعه‌ی بعد دوباره امتحان می‌شود
            }

            check(syncEngine.failedCollections.value.isEmpty() && syncEngine.lastError.value.isNullOrBlank()) { "Initial sync is incomplete; retry before opening records" }
            if (repository.allTemplates.first().isEmpty()) seedReferenceData()
            if (repository.allCalendarEvents.first().isEmpty()) seedDefaultCalendarEvents()
            bootstrapCompletedForAccount = resolvedAccountId
            onProgress(1f)
        }
    }

    /**
     * true once a doctor has completed the first-login clinic setup wizard —
     * either on this device, or on another device and then pulled down by
     * [bootstrapAfterLogin]. Call this AFTER bootstrapAfterLogin so a device
     * joining an already-configured clinic doesn't re-run the wizard.
     */
    suspend fun isOnboardingCompleted(): Boolean =
        repository.getSettingsOnce()?.onboardingCompleted == true

    suspend fun ensureOnboardingCompleted() {
        val current = repository.getSettingsOnce() ?: com.example.data.ClinicSettings()
        if (!current.onboardingCompleted) {
            repository.insertSettings(current.copy(onboardingCompleted = true, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Saves the first-login setup wizard's clinic info, configures the
     * doctor's PIN, and marks onboarding complete (synced via clinic_settings
     * so other devices skip the wizard once they pull this record).
     */
    suspend fun completeOnboarding(
        clinicName: String,
        clinicPhone: String,
        clinicAddress: String,
        doctorName: String,
        doctorLicenseNo: String,
        doctorPin: String
    ) {
        val currentUserId = try {
            com.example.util.Supabase.currentUserIdOrNull() ?: ""
        } catch (e: Exception) {
            ""
        }
        val userSyncId = if (currentUserId.isNotBlank()) "CLINIC_SETTINGS_$currentUserId" else java.util.UUID.randomUUID().toString()
        val existing = repository.getSettingsOnce() ?: com.example.data.ClinicSettings()
        // منبع واحد نام پزشک: ابتدا رکورد پرسنل ساخته می‌شود، سپس به‌عنوان «پزشک فعال» به
        // تنظیمات معرفی می‌شود تا تمام بخش‌ها از همان یک منبع (staff_members) نام بگیرند.
        val doctorStaff = com.example.data.StaffMember(fullName = doctorName, role = "DOCTOR", phone = clinicPhone)
        val doctorSyncId = doctorStaff.syncId
        repository.insertStaffMember(doctorStaff)
        repository.insertSettings(
            existing.copy(
                clinicName = clinicName,
                clinicPhone = clinicPhone,
                clinicAddress = clinicAddress,
                doctorName = doctorName,
                doctorLicenseNo = doctorLicenseNo,
                activeDoctorSyncId = doctorSyncId,
                onboardingCompleted = true,
                syncId = userSyncId,
                updatedAt = System.currentTimeMillis()
            )
        )
        configureDoctorPin(doctorPin)
        currentUserRole.value = com.example.feature.auth.UserRole.DOCTOR
        prefs.edit().putString("current_user_role", com.example.feature.auth.UserRole.DOCTOR.code).apply()
        isAppUnlocked.value = true
        try {
            syncEngine.pushPending()
        } catch (e: Exception) {
            // Background sync push retry handled
        }
    }

    /** Adds one assistant/secretary during the onboarding wizard's optional staff step. */
    suspend fun addStaffDuringOnboarding(fullName: String, phone: String, pin: String?) {
        val assistant = com.example.data.StaffMember(fullName = fullName, role = "ASSISTANT", phone = phone)
        repository.insertStaffMember(assistant)
        // اگر هنوز دستیار فعالی تعیین نشده، اولین دستیار ثبت‌شده «دستیار فعال» می‌شود
        val current = repository.getSettingsOnce()
        if (current != null && current.activeAssistantSyncId.isBlank()) {
            repository.insertSettings(current.copy(activeAssistantSyncId = assistant.syncId, staffNames = fullName))
        }
        if (!pin.isNullOrBlank()) configureRolePin(com.example.feature.auth.UserRole.ASSISTANT, pin)
    }

    /**
     * کدِ بازگشتی از OAuth گوگل (dental://app?code=...) — MainActivity آن را اینجا
     * می‌گذارد و AuthScreen با تعویض آن (PKCE) نشست را کامل می‌کند.
     */
    val pendingOAuthCode = MutableStateFlow<String?>(null)

    fun handleOAuthCallback(code: String?) {
        if (!code.isNullOrBlank()) pendingOAuthCode.value = code
    }

    val logoutError = MutableStateFlow<String?>(null)
    fun logout(onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            logoutError.value = null
            try { syncEngine.pushPending() } catch (e: Exception) {
                logoutError.value = "خروج انجام نشد؛ تغییرات شما هنوز ارسال نشده‌اند. اتصال را بررسی و دوباره تلاش کنید."
                return@launch
            }
            if (database.pendingSyncOpDao().countOnce() > 0) {
                logoutError.value = "خروج انجام نشد؛ تغییرات همگام‌نشده روی این دستگاه باقی مانده است. ابتدا همگام‌سازی را تکمیل کنید."
                return@launch
            }
            try {
                com.example.util.Supabase.signOut()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            com.example.util.Supabase.resetAccountIdCache()
            bootstrapCompletedForAccount = null
            repository.clearAllData()
            // رفع باگ: isGuestMode و flag داخل syncEngine را هم ریست می‌کنیم تا لاگین بعدی
            // با اکانت واقعی، pullAll/pushPending به‌اشتباه به‌خاطر حالت مهمان خاموش نمانند.
            isGuestMode.value = false
            syncEngine.isGuestMode = false
            prefs.edit().clear().apply()
            SecureSecretStore.clearAll(appContext)
            try {
                File(appContext.filesDir, "images").deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                File(appContext.cacheDir, "camera").deleteRecursively()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            currentUserRole.value = com.example.feature.auth.UserRole.ASSISTANT
            isAppUnlocked.value = false
            com.example.widget.AppointmentsWidgetProvider.requestUpdate(appContext)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    private val _deleteAccountState = MutableStateFlow<String?>(null)
    val deleteAccountState: StateFlow<String?> = _deleteAccountState.asStateFlow()

    /**
     * حذف کامل و برگشت‌ناپذیر حساب: همه‌ی رکوردهای این حساب (بیماران، پرونده‌ها، عکس‌ها، امضاها،
     * تنظیمات، پرسنل و ...) از سرور پاک می‌شود، سپس همان پاک‌سازی محلیِ logout() اجرا می‌شود.
     * UI باید پیش از صدا زدن این تابع، تاییدِ صریح کاربر را گرفته باشد.
     */
    fun deleteAccount(onDone: (success: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _deleteAccountState.value = "در حال حذف اطلاعات از سرور..."
            val success = try {
                syncEngine.deleteAccountAndAllServerData { collection ->
                    _deleteAccountState.value = "در حال حذف $collection ..."
                }
            } catch (e: Exception) {
                false
            }
            if (success) {
                repository.clearAllData()
                prefs.edit().clear().apply()
                SecureSecretStore.clearAll(appContext)
                try {
                    File(appContext.filesDir, "images").deleteRecursively()
                } catch (e: Exception) {
                }
                try {
                    File(appContext.cacheDir, "camera").deleteRecursively()
                } catch (e: Exception) {
                }
                com.example.util.Supabase.resetAccountIdCache()
                bootstrapCompletedForAccount = null
                currentUserRole.value = com.example.feature.auth.UserRole.ASSISTANT
                isAppUnlocked.value = false
            }
            _deleteAccountState.value = null
            onDone(success)
        }
    }

    fun syncWithCloud() {
        viewModelScope.launch(Dispatchers.IO) {
            syncEngine.pushPending()
            if (database.pendingSyncOpDao().countOnce() == 0) syncEngine.pullAll()
        }
    }

    private val _requeueResultMessage = MutableStateFlow<String?>(null)
    val requeueResultMessage: StateFlow<String?> = _requeueResultMessage.asStateFlow()

    /**
     * تلاشِ مجددِ دستی برای آپلودِ فایل‌های ناقص (عکس/امضا/چکِ قدیمی که قبلاً
     * به‌خاطر خطای سرور آپلود نشده‌اند) بدونِ نیاز به خروج و ورودِ دوباره.
     */
    fun retryStuckUploads() {
        viewModelScope.launch(Dispatchers.IO) {
            _requeueResultMessage.value = null
            val count = try {
                repository.requeueStuckLocalFiles()
            } catch (e: Exception) {
                -1
            }
            if (count > 0) {
                syncEngine.pushPending()
            }
            _requeueResultMessage.value = when {
                count < 0 -> "خطا در بررسی فایل‌های ناقص"
                count == 0 -> "هیچ فایل ناقصی پیدا نشد — همه‌چیز از قبل آپلود شده"
                else -> "$count فایل دوباره برای آپلود صف‌بندی شد"
            }
        }
    }

        suspend fun pingServer(): String = withContext(Dispatchers.IO) {
        try {
            if (syncEngine.isOnline.value) "اتصال به اینترنت برقرار است (Online)"
            else "عدم دسترسی به اینترنت (Offline)"
        } catch (e: Exception) {
            "خطا در بررسی اتصال: ${e.localizedMessage ?: "نامشخص"}"
        }
    }
}
