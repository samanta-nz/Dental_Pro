package com.example.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.DentalRepository
import com.example.util.Supabase
import com.example.util.SyncCollections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import androidx.room.withTransaction
import kotlinx.coroutines.withContext
import java.io.File

class SyncEngine(
    var isGuestMode: Boolean = false,

    private val context: Context,
    private val repository: DentalRepository,
    private val database: AppDatabase,
    private val observeNetwork: Boolean = true,
    private val transport: SyncTransport = SupabaseSyncTransport
) {
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _lastSyncedAt = MutableStateFlow<Long?>(null)
    val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    // رفع باگ: قبلاً وقتی pull یک کالکشن (مثلاً patients) به‌خاطر خطای گذرا شکست
    // می‌خورد، فقط Log.e ثبت می‌شد و کاربر هیچ نشانه‌ای نمی‌دید — درحالی‌که رکوردهای
    // وابسته از کالکشن‌های دیگر (مثلاً payments) که pull مستقل و موفقی داشتند، با
    // patientId=0 (شکسته) درج می‌شدند. این StateFlow نام کالکشن‌هایی که حتی بعد از
    // retry هم شکست خوردند را نگه می‌دارد تا UI بتواند به کاربر نشان دهد.
    private val _failedCollections = MutableStateFlow<Set<String>>(emptySet())
    val failedCollections: StateFlow<Set<String>> = _failedCollections.asStateFlow()

    // ردیف‌هایی که HTTP موفق بود ولی insert محلی به‌خاطر Gson/NOT NULL/قید یکتا skip شد.
    // کلید = نام جدول، مقدار = تعداد ردیف ردشده در همین pullAll.
    private val skippedRowsByCollection = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    init {
        if (observeNetwork) registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            val cm = connectivityManager
            if (cm != null) {
                val activeNetwork = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(activeNetwork)
                _isOnline.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true

                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        _isOnline.value = true
                    }

                    override fun onLost(network: Network) {
                        _isOnline.value = false
                    }
                })
            } else {
                _isOnline.value = true
            }
        } catch (e: Exception) {
            _isOnline.value = true // Fallback to optimistic online
        }
    }

    /**
     * چکِ زندهٔ وضعیتِ شبکه مستقیم از ConnectivityManager. برای تصمیم‌های واقعیِ
     * sync باید این استفاده شود، نه [_isOnline]؛ آن StateFlowِ کش‌شده ممکن است
     * هنگامِ لاگین هنوز به‌روز نشده باشد و فقط برای نمایش در UI است.
     */
    private fun isOnlineNow(): Boolean {
        val cm = connectivityManager ?: return true
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
        } catch (e: Exception) {
            true // فرض خوش‌بینانه؛ خطای واقعی شبکه توسط خودِ تلاش کوئری بعدی گرفته می‌شود
        }
    }

    /**
     * ارسالِ همهٔ عملیاتِ معلقِ صف به Supabase. batchها پشتِ‌سرِ‌هم پردازش می‌شوند تا
     * صف کاملاً خالی شود (نه فقط batchِ ۵۰تاییِ اول)، چون logout() بلافاصله بعد از
     * این clearAllData() را صدا می‌زند و هر عملیاتِ جامانده برای همیشه از بین می‌رفت.
     */
    suspend fun pushPending() = withContext(Dispatchers.IO) { syncMutex.withLock {
        if (isGuestMode) return@withContext

        if (!isOnlineNow()) return@withContext

        val syncOwner = requireMatchingAccount()
        var afterId = 0L
        var safetyBatchCounter = 0
        batchLoop@ while (true) {
            if (!isOnlineNow()) break@batchLoop
            val pendingOps = database.pendingSyncOpDao().getPendingOpsAfter(afterId, 50)
            if (pendingOps.isEmpty()) break@batchLoop

            var processedInBatch = 0
            for (op in pendingOps) {
            afterId = op.id
            try {
                if (requireMatchingAccount() != syncOwner) throw AccountMismatchException()
                if (op.operation == "DELETE") {
                    deleteRemoteDocument(op.collectionTable, op.recordSyncId)
                    if (requireMatchingAccount() != syncOwner) throw AccountMismatchException()
                    database.pendingSyncOpDao().deleteById(op.id)
                    processedInBatch++
                    continue
                }
                when (op.collectionTable) {
                    "patients" -> {
                        val record = database.patientDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            var toUpload = record
                            if (!record.photoFileId.isNullOrBlank() && record.photoFileId.startsWith("/")) {
                                val file = java.io.File(record.photoFileId)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_PATIENT_MEDIA, com.example.util.StorageCategory.PATIENTS)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(photoFileId = uploadedId, photoLocalPath = file.absolutePath)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            val mutableData = com.example.util.EntityMapper.toMap(toUpload).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_PATIENTS, record.syncId, mutableData)
                        } else if (op.operation == "UPSERT") {
                            // Local record gone before upsert?
                        }
                    }
                    "patient_documents" -> {
                        val record = database.patientDocumentDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            // Normally fileId is already set — addPatientDocument uploads
                            // immediately. This is a retry fallback for the rare case where
                            // that immediate upload failed (e.g. offline) and only the local
                            // cache path (uri) was saved.
                            var toUpload = record
                            if (record.fileId.isNullOrBlank() && !record.uri.isNullOrBlank() && record.uri.startsWith("/")) {
                                val file = java.io.File(record.uri)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_PATIENT_MEDIA, com.example.util.StorageCategory.PATIENTS)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(fileId = uploadedId, localCachePath = file.absolutePath)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            val mutableData = com.example.util.EntityMapper.toMap(toUpload).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_PATIENT_DOCUMENTS, record.syncId, mutableData)
                        }
                    }
                    "payments" -> {
                        val record = database.paymentDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            var toUpload = record
                            if (!record.chequePhotoFileId.isNullOrBlank() && record.chequePhotoFileId.startsWith("/")) {
                                val file = java.io.File(record.chequePhotoFileId)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_PATIENT_MEDIA, com.example.util.StorageCategory.CHEQUES)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(chequePhotoFileId = uploadedId)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            val mutableData = com.example.util.EntityMapper.toMap(toUpload).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_PAYMENTS, record.syncId, mutableData)
                        }
                    }
                    "vendor_invoices" -> {
                        val record = database.vendorInvoiceDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            var toUpload = record
                            if (!record.documentFileId.isNullOrBlank() && record.documentFileId.startsWith("/")) {
                                val file = java.io.File(record.documentFileId)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_CLINIC_ASSETS, com.example.util.StorageCategory.MISC)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(documentFileId = uploadedId)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            val mutableData = com.example.util.EntityMapper.toMap(toUpload).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_VENDOR_INVOICES, record.syncId, mutableData)
                        }
                    }
                    "clinic_expenses" -> {
                        val record = database.clinicExpenseDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            var toUpload = record
                            if (!record.invoicePhotoFileId.isNullOrBlank() && record.invoicePhotoFileId.startsWith("/")) {
                                val file = java.io.File(record.invoicePhotoFileId)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_CLINIC_ASSETS, com.example.util.StorageCategory.MISC)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(invoicePhotoFileId = uploadedId)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            val mutableData = com.example.util.EntityMapper.toMap(toUpload).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_CLINIC_EXPENSES, record.syncId, mutableData)
                        }
                    }
                    "expense_payments" -> {
                        val record = database.expensePaymentDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_EXPENSE_PAYMENTS, record.syncId, mutableData)
                        }
                    }
                    "party_accounts" -> {
                        val record = database.partyAccountDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_PARTY_ACCOUNTS, record.syncId, mutableData)
                        }
                    }
                    "clinic_settings" -> {
                        val record = database.clinicSettingsDao().getSettingsOnce()
                        if (record != null) {
                            var toUpload = record
                            if (!record.logoFileId.isNullOrBlank() && record.logoFileId.startsWith("/")) {
                                val file = java.io.File(record.logoFileId)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_CLINIC_ASSETS, com.example.util.StorageCategory.CLINIC)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(logoFileId = uploadedId)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            // رفع باگ ۲-۲-۱: آواتار مطب هم باید آپلود شود — قبلاً فقط logoFileId
                            // آپلود می‌شد و avatarFileId همیشه مسیر محلی باقی می‌ماند؛ بعد از
                            // خروج/ورود، چون فایل روی سرور نبود، آواتار ریستور نمی‌شد.
                            if (!toUpload.avatarFileId.isNullOrBlank() && toUpload.avatarFileId.startsWith("/")) {
                                val file = java.io.File(toUpload.avatarFileId)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_CLINIC_ASSETS, com.example.util.StorageCategory.CLINIC)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(avatarFileId = uploadedId)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            val mutableData = com.example.util.EntityMapper.toMap(toUpload).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_CLINIC_SETTINGS, record.syncId, mutableData)
                        }
                    }
                    "staff_members" -> {
                        val record = database.staffMemberDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            var toUpload = record
                            if (!record.photoFileId.isNullOrBlank() && record.photoFileId.startsWith("/")) {
                                val file = java.io.File(record.photoFileId)
                                if (file.exists()) {
                                    val uploadedId = uploadFile(file, Supabase.BUCKET_CLINIC_ASSETS, com.example.util.StorageCategory.CLINIC)
                                    if (uploadedId != null) {
                                        toUpload = toUpload.copy(photoFileId = uploadedId)
                                        persistUploadedMedia(op.collectionTable, record, toUpload)
                                    }
                                }
                            }
                            val mutableData = com.example.util.EntityMapper.toMap(toUpload).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_STAFF_MEMBERS, record.syncId, mutableData)
                        }
                    }
                    "treatments" -> {
                        val record = database.treatmentDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_TREATMENTS, record.syncId, mutableData)
                        }
                    }
                    "appointments" -> {
                        val record = database.appointmentDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_APPOINTMENTS, record.syncId, mutableData)
                        }
                    }
                    "vendors" -> {
                        val record = database.vendorDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_VENDORS, record.syncId, mutableData)
                        }
                    }
                    "lab_orders" -> {
                        val record = database.labOrderDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_LAB_ORDERS, record.syncId, mutableData)
                        }
                    }
                    "equipment_maintenance" -> {
                        val record = database.equipmentMaintenanceDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_EQUIPMENT_MAINTENANCES, record.syncId, mutableData)
                        }
                    }
                    "personal_transactions" -> {
                        val record = database.personalTransactionDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_PERSONAL_TRANSACTIONS, record.syncId, mutableData)
                        }
                    }
                    "treatment_templates" -> {
                        val record = database.treatmentTemplateDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_TREATMENT_TEMPLATES, record.syncId, mutableData)
                        }
                    }
                    "prescriptions" -> {
                        val record = database.prescriptionDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_PRESCRIPTIONS, record.syncId, mutableData)
                        }
                    }
                    "bank_accounts" -> {
                        val record = database.bankAccountDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_BANK_ACCOUNTS, record.syncId, mutableData)
                        }
                    }
                    "personal_sub_accounts" -> {
                        val record = database.personalSubAccountDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_PERSONAL_SUB_ACCOUNTS, record.syncId, mutableData)
                        }
                    }
                    "children_expenses" -> {
                        val record = database.childrenExpenseDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_CHILDREN_EXPENSES, record.syncId, mutableData)
                        }
                    }
                    "unregistered_bank_sms" -> {
                        val record = database.unregisteredBankSmsDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_UNREGISTERED_BANK_SMS, record.syncId, mutableData)
                        }
                    }
                    "lab_services" -> {
                        val record = database.labServiceDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_LAB_SERVICES, record.syncId, mutableData)
                        }
                    }
                    "fixed_incomes" -> {
                        val record = database.fixedIncomeDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_FIXED_INCOMES, record.syncId, mutableData)
                        }
                    }
                    "treatment_stages" -> {
                        val record = database.treatmentStageDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_TREATMENT_STAGES, record.syncId, mutableData)
                        }
                    }
                    "installments" -> {
                        val record = database.installmentDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_INSTALLMENTS, record.syncId, mutableData)
                        }
                    }
                    "app_calendar_events" -> {
                        val record = database.appCalendarEventDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_APP_CALENDAR_EVENTS, record.syncId, mutableData)
                        }
                    }
                    "financial_ledger" -> Unit // Legacy outbox item; server derives this from its source.
                    "audit_events" -> {
                        val record = database.auditEventDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_AUDIT_EVENTS, record.syncId, mutableData)
                        }
                    }
                    "drug_templates" -> {
                        val record = database.drugTemplateDao().getBySyncId(op.recordSyncId)
                        if (record != null) {
                            val mutableData = com.example.util.EntityMapper.toMap(record).toMutableMap()
                            upsertRemoteDocument(Supabase.TABLE_DRUG_TEMPLATES, record.syncId, mutableData)
                        }
                    }
                }
                if (requireMatchingAccount() != syncOwner) throw AccountMismatchException()
                database.pendingSyncOpDao().deleteById(op.id)
                processedInBatch++
            } catch (e: Exception) {
                val errorDesc = parseSyncError(e, op.collectionTable)
                _lastError.value = errorDesc
                Log.e("SyncEngine", "Sync error on ${op.collectionTable}:${op.recordSyncId} -> $errorDesc", e)
                if (e is CancellationException) throw e
                database.pendingSyncOpDao().recordFailure(op.id, errorDesc)
                continue // Try the next item instead of aborting the whole queue
            }
            }

            safetyBatchCounter++
            // اگر در یک batch کامل هیچ عملیاتی پیشرفت نکرد (مثلاً قطع لحظه‌ای شبکه در
            // میانه‌ی کار)، حلقه را متوقف کن تا loop بی‌نهایت رخ ندهد؛ خطا/attemptCount
            // هر op برای تلاش بعدی pushPending() حفظ شده است.
            // afterId advances even when a batch fails; later records must get a chance.
            // سقف مطلق ایمنی برای صف‌های بسیار بزرگ (۲۰۰ batch × ۵۰ = تا ۱۰٬۰۰۰ عملیات
            // در یک فراخوانی) تا در حالت‌های غیرمنتظره هم اجرا برای همیشه گیر نکند.
            if (safetyBatchCounter > 200) break@batchLoop
        }

        val remaining = repository.getPendingOps(1)
        if (remaining.isEmpty()) {
            _lastError.value = null
        }
        if (remaining.isEmpty()) _lastSyncedAt.value = System.currentTimeMillis()
    } }

    // --- Foreign-key resolution for pull ------------------------------------
    // A pulled document's raw xId (e.g. patientId) is the ORIGINAL device's
    // local Room id — meaningless (or, worse, coincidentally colliding with an
    // unrelated row) on this device. The xSyncId shadow field is the stable,
    // cross-device identity; resolve it to this device's local id before
    // persisting. If the shadow field is present but no matching local row
    // exists, return null/0L to avoid incorrectly referencing an unrelated local row.
    private suspend fun resolvePatientId(rawId: Long, syncId: String): Long =
        if (syncId.isNotBlank()) database.patientDao().getBySyncId(syncId)?.id ?: 0L else rawId

    private suspend fun resolveTreatmentId(rawId: Long, syncId: String): Long =
        if (syncId.isNotBlank()) database.treatmentDao().getBySyncId(syncId)?.id ?: 0L else rawId

    private suspend fun resolveTreatmentIdOrNull(rawId: Long?, syncId: String): Long? =
        if (syncId.isNotBlank()) database.treatmentDao().getBySyncId(syncId)?.id else rawId

    private suspend fun resolveBankAccountId(rawId: Long?, syncId: String): Long? =
        if (syncId.isNotBlank()) database.bankAccountDao().getBySyncId(syncId)?.id else rawId

    private suspend fun resolveVendorId(rawId: Long?, syncId: String): Long? =
        if (syncId.isNotBlank()) database.vendorDao().getBySyncId(syncId)?.id else rawId

    private suspend fun resolveVendorIdRequired(rawId: Long, syncId: String): Long =
        if (syncId.isNotBlank()) database.vendorDao().getBySyncId(syncId)?.id ?: 0L else rawId

    private suspend fun resolveLabOrderId(rawId: Long?, syncId: String): Long? =
        if (syncId.isNotBlank()) database.labOrderDao().getBySyncId(syncId)?.id else rawId

    private suspend fun resolveMaintenanceId(rawId: Long?, syncId: String): Long? =
        if (syncId.isNotBlank()) database.equipmentMaintenanceDao().getBySyncId(syncId)?.id else rawId

    private suspend fun resolvePartyAccountId(rawId: Long?, syncId: String): Long? {
        if (rawId == null || rawId <= 0L) return null
        if (syncId.isNotBlank()) {
            database.partyAccountDao().getBySyncId(syncId)?.let { return it.id }
        }
        return database.partyAccountDao().getById(rawId)?.id
    }

    private suspend fun resolveClinicExpenseId(rawId: Long, syncId: String): Long =
        if (syncId.isNotBlank()) database.clinicExpenseDao().getBySyncId(syncId)?.id ?: 0L else rawId

    suspend fun pullAll(onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) { syncMutex.withLock {
        if (isGuestMode) return@withContext
        if (!isOnlineNow()) return@withContext
        requireMatchingAccount()

        skippedRowsByCollection.clear()
        _lastError.value = null

        // حالت تک‌کاربره / کلینیکال یکپارچه: تمام رکوردهای سرور بدون نیاز به فیلتر ownerId
        // برای همه کاربران مجاز سینک می‌شوند.

        // رفع باگ اصلی: دانلود را به‌صورت موج‌های لایه‌ای انجام می‌دهیم تا هنگام درج رکوردهای
        // وابسته (مثلاً Treatment که به Patient وابسته است، یا Payment که به Patient و BankAccount
        // وابسته است)، رکورد والد قطعاً قبل از فرزند در Room وجود داشته باشد و resolve*Id
        // ها مقدار ۰ برنگردانند. کالکشن‌های درون هر موج با هم موازی دانلود می‌شوند اما موج‌ها
        // پشت سر هم (سریال) اجرا می‌گردند.
        val waves: List<List<String>> = listOf(
            // موج ۱ — مستقل‌ها (بدون foreign key به سایر entityها)
            listOf(
                Supabase.TABLE_CLINIC_SETTINGS,
                Supabase.TABLE_STAFF_MEMBERS,
                Supabase.TABLE_BANK_ACCOUNTS,
                Supabase.TABLE_PERSONAL_SUB_ACCOUNTS,
                Supabase.TABLE_TREATMENT_TEMPLATES,
                Supabase.TABLE_DRUG_TEMPLATES,
                Supabase.TABLE_LAB_SERVICES,
                Supabase.TABLE_APP_CALENDAR_EVENTS,
                Supabase.TABLE_PARTY_ACCOUNTS
            ),
            // موج ۲ — والدین ریشه
            listOf(
                Supabase.TABLE_PATIENTS,
                Supabase.TABLE_VENDORS,
                Supabase.TABLE_LAB_ORDERS,
                Supabase.TABLE_EQUIPMENT_MAINTENANCES
            ),
            // موج ۳ — وابسته‌های سطح ۱ (والدشان از موج ۱ یا ۲ است)
            // fixed_incomes به bank_accounts وابسته است؛ نباید با موج ۱ موازی باشد.
            listOf(
                Supabase.TABLE_TREATMENTS,
                Supabase.TABLE_CLINIC_EXPENSES,
                Supabase.TABLE_VENDOR_INVOICES,
                Supabase.TABLE_APPOINTMENTS,
                Supabase.TABLE_FIXED_INCOMES
            ),
            // موج ۴ — وابسته‌های سطح ۲ (والدشان از موج‌های قبل است)
            // رفع باگ اصلی (ریستور پیامک‌های بانکی): جدول unregistered_bank_sms قبلاً فقط push
            // می‌شد ولی هیچ‌وقت در pullAll دانلود نمی‌شد — در نتیجه بعد از خروج و ورود دوباره،
            // لیست پیامک‌ها (ثبت‌نشده/ثبت‌شده/نادیده‌گرفته/سطل آشغال) خالی برمی‌گشت درحالی‌که
            // خود تراکنش‌ها در حساب‌ها حضور داشتند. حالا این جدول هم در موج ۴ (بعد از حساب‌های
            // بانکی، برای resolve شدن assignedAccountId) دانلود و با رکوردهای محلی ادغام می‌شود.
            listOf(
                Supabase.TABLE_TREATMENT_STAGES,
                Supabase.TABLE_PRESCRIPTIONS,
                Supabase.TABLE_PATIENT_DOCUMENTS,
                Supabase.TABLE_INSTALLMENTS,
                Supabase.TABLE_EXPENSE_PAYMENTS,
                Supabase.TABLE_PERSONAL_TRANSACTIONS,
                Supabase.TABLE_CHILDREN_EXPENSES,
                Supabase.TABLE_AUDIT_EVENTS,
                Supabase.TABLE_UNREGISTERED_BANK_SMS
            ),
            // Ledger sources must exist locally before their device-local IDs can be resolved.
            listOf(Supabase.TABLE_PAYMENTS),
            listOf(Supabase.TABLE_FINANCIAL_LEDGER)
        )

        val collections = waves.flatten()
        val total = collections.size.toFloat()

        val pendingSyncIds = database.pendingSyncOpDao().getPendingOps(Int.MAX_VALUE)
            .map { it.recordSyncId }
            .toSet()

        var completedCount = 0

        // رفع باگ اصلی: قبلاً یک شکست گذرای شبکه در pullCollectionPages(coll) باعث
        // می‌شد کل جدول «خالی» تلقی شود (emptySet to 0L) — نه فقط یک رکورد. چون
        // resolvePatientId/resolve*Id وقتی رکورد والد را در Room پیدا نکنند مقدار ۰
        // برمی‌گردانند، کالکشن‌های وابسته (مثلاً payments که در موج بعدی pull می‌شود)
        // با foreign key شکسته درج می‌شدند، درحالی‌که خودِ آن‌ها موفق pull شده بودند.
        // این دقیقاً همان چیزی است که به‌صورت «پرداخت هست ولی بیمار نیست» دیده می‌شد.
        val failedThisRun = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // اجرای موج‌ها به ترتیب — درون هر موج async و در انتها awaitAll
        for (wave in waves) {
            coroutineScope {
                wave.map { coll ->
                    async(Dispatchers.IO) {
                        val result = pullCollectionPagesWithRetry(coll)
                        if (result == null) {
                            // شکست واقعی حتی بعد از retry — این کالکشن را برای پاس دوم
                            // نگه می‌داریم و reconcile را برایش اجرا نمی‌کنیم تا رکوردهای
                            // محلی معتبر به‌اشتباه به‌عنوان «حذف‌شده روی سرور» پاک نشوند.
                            failedThisRun.add(coll)
                        } else {
                            val (serverSyncIds, maxRemoteUpdatedAt) = result
                            try {
                                Unit // Durable tombstones, applied after all collections, determine deletions.
                            } catch (e: Exception) {
                                Log.w("SyncEngine", "Reconcile failed for $coll: ${e.message}")
                            }
                        }
                        synchronized(this@SyncEngine) {
                            completedCount++
                            onProgress(completedCount.toFloat() / total)
                        }
                    }
                }.awaitAll()
            }
        }

        // پاس دوم: کالکشن‌هایی که حتی بعد از retry هم شکست خوردند را یک‌بار دیگر و
        // این‌بار متوالی (نه موازی، تا فشار کمتری روی شبکه‌ی احتمالاً ناپایدار باشد)
        // امتحان می‌کنیم. تا این مرحله، کالکشن‌های موج‌های قبلی که موفق بودند از قبل
        // در Room نشسته‌اند، پس اگر این پاس دوم موفق شود، pullDocument برای رکوردهای
        // وابسته‌ای که قبلاً با FK=0 درج شده بودند دوباره اجرا و اصلاح می‌شود (چون
        // remoteUpdatedAt همان مقدار قبلی است و شرط >= برقرار می‌ماند).
        if (failedThisRun.isNotEmpty()) {
            Log.w("SyncEngine", "Retrying failed collections in a second pass: ${failedThisRun.joinToString()}")
            val stillFailed = mutableSetOf<String>()
            for (coll in failedThisRun.toList()) {
                val result = pullCollectionPagesWithRetry(coll, maxAttempts = 2)
                if (result == null) {
                    stillFailed.add(coll)
                } else {
                    val (serverSyncIds, maxRemoteUpdatedAt) = result
                    try {
                        Unit // Durable tombstones, applied after all collections, determine deletions.
                    } catch (e: Exception) {
                        Log.w("SyncEngine", "Reconcile failed for $coll (retry pass): ${e.message}")
                    }
                }
            }
            _failedCollections.value = stillFailed
            _lastError.value = if (stillFailed.isNotEmpty()) {
                "همگام‌سازی این بخش‌ها ناموفق بود و در sync بعدی دوباره تلاش می‌شود: ${stillFailed.joinToString()}"
            } else {
                null
            }
        } else {
            _failedCollections.value = emptySet()
        }

        // پس از اینکه بیماران (و بقیه والدها) در Room نشسته‌اند، FKهای شکستهٔ باقیمانده از
        // pullهای قبلی — که به‌خاطر skip شدن insert بیمار با patientId=0 درج شده بودند —
        // را با patientSyncId دوباره resolve می‌کنیم تا نوبت/پرداخت به پروندهٔ درست وصل شوند.
        try {
            repairBrokenForeignKeys()
        } catch (e: Exception) {
            Log.w("SyncEngine", "repairBrokenForeignKeys failed: ${e.message}")
        }

        val skippedSummary = skippedRowsByCollection.entries
            .filter { it.value > 0 }
            .joinToString { "${it.key}=${it.value}" }
        if (skippedSummary.isNotBlank()) {
            val prev = _lastError.value
            val skipMsg = "ردیف‌های ردشده در درج محلی: $skippedSummary"
            _lastError.value = if (prev.isNullOrBlank()) skipMsg else "$prev\n$skipMsg"
        }

        if (_failedCollections.value.isEmpty() && skippedSummary.isBlank()) {
            applyRemoteDeletions()
            repository.reconcileBankBalances()
            _lastSyncedAt.value = System.currentTimeMillis()
        }
    } }

    /**
     * pullCollectionPages را با تلاش مجدد (retry + backoff نمایی ساده) اجرا می‌کند تا
     * خطاهای گذرای شبکه/HTTP باعث نشوند کل جدول به‌اشتباه «خالی» تلقی شود. در صورت
     * شکست همه‌ی تلاش‌ها null برمی‌گرداند تا فراخوان بتواند بین «جدول واقعاً خالی است»
     * و «pull این جدول شکست خورد» تمایز قائل شود.
     */
    private suspend fun pullCollectionPagesWithRetry(
        coll: String,
        maxAttempts: Int = 3
    ): Pair<Set<String>, Long>? {
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                return pullCollectionPages(coll)
            } catch (e: Exception) {
                lastException = e
                Log.w("SyncEngine", "Pull attempt ${attempt + 1}/$maxAttempts for $coll failed: ${e.message}")
                if (attempt < maxAttempts - 1) {
                    delay(500L * (attempt + 1)) // backoff ساده: 500ms, 1000ms, ...
                }
            }
        }
        Log.e("SyncEngine", "Pull for $coll failed after $maxAttempts attempts: ${lastException?.message}", lastException)
        _lastError.value = lastException?.message
        return null
    }

    /**
     * دانلود تمام صفحات یک جدول — PostgREST با صفحه‌بندی offset و مرتب‌سازی
     * قطعی روی sync_id. RLS روی سرور تضمین می‌کند که فقط رکوردهای همین حساب
     * برگردانده می‌شوند.
     */
    private suspend fun pullCollectionPages(coll: String): Pair<Set<String>, Long> {
        val serverSyncIds = mutableSetOf<String>()
        var maxRemoteUpdatedAt = 0L
        var offset = 0
        val pageSize = 1000

        while (true) {
            val rows = Supabase.fetchPage(coll, offset, pageSize)
            if (rows.size() == 0) break

            val gson = com.google.gson.Gson()
            for (rowEl in rows) {
                try {
                    val row = rowEl.asJsonObject
                    // کلیدهای snake_case پاسخ PostgREST → camelCase برای Gson→Room
                    val camelRow = Supabase.jsonKeysToCamel(row)
                    val syncIdEl = camelRow.get("syncId")
                    val docId = syncIdEl?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                    serverSyncIds.add(docId)
                    val remoteUpdatedAt = camelRow.get("updatedAt")
                        ?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                    if (remoteUpdatedAt > maxRemoteUpdatedAt) {
                        maxRemoteUpdatedAt = remoteUpdatedAt
                    }
                    val json = gson.toJson(camelRow)
                    try {
                        pullDocument(coll, docId, json, remoteUpdatedAt, gson)
                    } catch (e: Exception) {
                        Log.w("SyncEngine", "Skipped bad row $docId in $coll during pull: ${e.message}")
                        skippedRowsByCollection.merge(coll, 1) { a, b -> a + b }
                        _lastError.value = "ردیف $coll در درج محلی رد شد: ${e.message}"
                    }
                } catch (e: Exception) {
                    Log.w("SyncEngine", "Skipped malformed row in $coll during pull: ${e.message}")
                }
            }
            if (rows.size() < pageSize) break
            offset += pageSize
        }
        return serverSyncIds to maxRemoteUpdatedAt
    }

    /**
     * Gson→entity برای ردیف pullشدهٔ هر ۲۷ جدول.
     * [EntityMapper.preparePulledJson] فیلدهای غایب/null را برای Gson/Room امن می‌کند
     * و `id` را حذف می‌کند. اگر ردیف محلی از قبل باشد، فیلدهای فقط‌محلی
     * (documentUris، cvv2، مسیر فایل) حفظ می‌شوند.
     */
    private fun <T : Any> parsePulledEntity(
        json: String,
        clazz: Class<T>,
        gson: com.google.gson.Gson,
        local: T? = null
    ): T {
        val prepared = com.example.util.EntityMapper.preparePulledJson(json, clazz)
        val obj = gson.fromJson(prepared, clazz)
        if (local != null) {
            com.example.util.EntityMapper.overlayLocalOnlyFields(obj, local)
        }
        return obj
    }

    internal suspend fun pullDocument(
        coll: String,
        docId: String,
        json: String,
        remoteUpdatedAt: Long,
        gson: com.google.gson.Gson
    ) = database.withTransaction {
        if (database.pendingSyncOpDao().hasPending(coll, docId)) return@withTransaction
        val expectedOwner = context.getSharedPreferences("dental_pro_prefs", Context.MODE_PRIVATE).getString("auth_account_id", "").orEmpty()
        val rowOwner = com.google.gson.JsonParser.parseString(json).asJsonObject.get("ownerId")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        if (expectedOwner.isNotBlank() && rowOwner.isNotBlank() && rowOwner != expectedOwner) throw AccountMismatchException()
        when (coll) {
            Supabase.TABLE_PATIENTS -> {
                val local = database.patientDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.Patient::class.java, gson, local)
                if (local == null) database.patientDao().insertPatient(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.patientDao().insertPatient(obj.copy(id = local.id))
            }
            Supabase.TABLE_TREATMENTS -> {
                val local = database.treatmentDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.Treatment::class.java, gson, local)
                val obj = raw.copy(patientId = resolvePatientId(raw.patientId, raw.patientSyncId))
                if (local == null) database.treatmentDao().insertTreatment(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.treatmentDao().insertTreatment(obj.copy(id = local.id))
            }
            Supabase.TABLE_PAYMENTS -> {
                val local = database.paymentDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.Payment::class.java, gson, local)
                val obj = raw.copy(
                    patientId = resolvePatientId(raw.patientId, raw.patientSyncId),
                    bankAccountId = resolveBankAccountId(raw.bankAccountId, raw.bankAccountSyncId),
                    transferExpensePaymentId = raw.transferExpensePaymentSyncId.takeIf { it.isNotBlank() }?.let { database.expensePaymentDao().getBySyncId(it)?.id },
                    transferExpenseId = raw.transferExpenseSyncId.takeIf { it.isNotBlank() }?.let { database.clinicExpenseDao().getBySyncId(it)?.id },
                    transferTransactionId = raw.transferTransactionSyncId.takeIf { it.isNotBlank() }?.let { database.personalTransactionDao().getBySyncId(it)?.id },
                    transferChildrenExpenseId = raw.transferChildrenExpenseSyncId.takeIf { it.isNotBlank() }?.let { database.childrenExpenseDao().getBySyncId(it)?.id },
                    transferPartyPaymentId = raw.transferPartyPaymentSyncId.takeIf { it.isNotBlank() }?.let { database.expensePaymentDao().getBySyncId(it)?.id }
                )
                if (local == null) database.paymentDao().insertPayment(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.paymentDao().insertPayment(obj.copy(id = local.id))
            }
            Supabase.TABLE_APPOINTMENTS -> {
                val local = database.appointmentDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.Appointment::class.java, gson, local)
                val obj = raw.copy(patientId = resolvePatientId(raw.patientId, raw.patientSyncId))
                if (local == null) database.appointmentDao().insertAppointment(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.appointmentDao().insertAppointment(obj.copy(id = local.id))
            }
            Supabase.TABLE_STAFF_MEMBERS -> {
                val local = database.staffMemberDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.StaffMember::class.java, gson, local)
                if (local == null) database.staffMemberDao().insertStaff(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.staffMemberDao().insertStaff(obj.copy(id = local.id))
            }
            Supabase.TABLE_VENDORS -> {
                val local = database.vendorDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.Vendor::class.java, gson, local)
                if (local == null) database.vendorDao().insertVendor(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.vendorDao().insertVendor(obj.copy(id = local.id))
            }
            Supabase.TABLE_LAB_ORDERS -> {
                val local = database.labOrderDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.LabOrder::class.java, gson, local)
                if (local == null) database.labOrderDao().insertLabOrder(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.labOrderDao().insertLabOrder(obj.copy(id = local.id))
            }
            Supabase.TABLE_EQUIPMENT_MAINTENANCES -> {
                val local = database.equipmentMaintenanceDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.EquipmentMaintenance::class.java, gson, local)
                if (local == null) database.equipmentMaintenanceDao().insertMaintenance(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.equipmentMaintenanceDao().insertMaintenance(obj.copy(id = local.id))
            }
            Supabase.TABLE_CLINIC_EXPENSES -> {
                val local = database.clinicExpenseDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.ClinicExpense::class.java, gson, local)
                val obj = raw.copy(
                    labOrderId = resolveLabOrderId(raw.labOrderId, raw.labOrderSyncId),
                    vendorId = resolveVendorId(raw.vendorId, raw.vendorSyncId),
                    maintenanceId = resolveMaintenanceId(raw.maintenanceId, raw.maintenanceSyncId),
                    partyAccountId = resolvePartyAccountId(raw.partyAccountId, raw.partyAccountSyncId)
                )
                if (local == null) database.clinicExpenseDao().insertExpense(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.clinicExpenseDao().insertExpense(obj.copy(id = local.id))
            }
            Supabase.TABLE_EXPENSE_PAYMENTS -> {
                val local = database.expensePaymentDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.ExpensePayment::class.java, gson, local)
                val obj = raw.copy(
                    clinicExpenseId = resolveClinicExpenseId(raw.clinicExpenseId, raw.clinicExpenseSyncId),
                    bankAccountId = resolveBankAccountId(raw.bankAccountId, raw.bankAccountSyncId),
                    partyAccountId = resolvePartyAccountId(raw.partyAccountId, raw.partyAccountSyncId)
                )
                if (local == null) database.expensePaymentDao().insertExpensePayment(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.expensePaymentDao().insertExpensePayment(obj.copy(id = local.id))
            }
            Supabase.TABLE_PERSONAL_TRANSACTIONS -> {
                val local = database.personalTransactionDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.PersonalTransaction::class.java, gson, local)
                val obj = raw.copy(bankAccountId = resolveBankAccountId(raw.bankAccountId, raw.bankAccountSyncId))
                if (local == null) database.personalTransactionDao().insertTransaction(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.personalTransactionDao().insertTransaction(obj.copy(id = local.id))
            }
            Supabase.TABLE_TREATMENT_TEMPLATES -> {
                val local = database.treatmentTemplateDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.TreatmentTemplate::class.java, gson, local)
                if (local == null) database.treatmentTemplateDao().insertTemplate(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.treatmentTemplateDao().insertTemplate(obj.copy(id = local.id))
            }
            Supabase.TABLE_PRESCRIPTIONS -> {
                val local = database.prescriptionDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.Prescription::class.java, gson, local)
                val obj = raw.copy(
                    patientId = resolvePatientId(raw.patientId, raw.patientSyncId),
                    treatmentId = resolveTreatmentId(raw.treatmentId, raw.treatmentSyncId)
                )
                if (local == null) database.prescriptionDao().insertPrescription(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.prescriptionDao().insertPrescription(obj.copy(id = local.id))
            }
            Supabase.TABLE_BANK_ACCOUNTS -> {
                val local = database.bankAccountDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.BankAccount::class.java, gson, local)
                if (local == null) database.bankAccountDao().insertBankAccount(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.bankAccountDao().insertBankAccount(obj.copy(id = local.id))
            }
            Supabase.TABLE_PERSONAL_SUB_ACCOUNTS -> {
                val local = database.personalSubAccountDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.PersonalSubAccount::class.java, gson, local)
                if (local == null) database.personalSubAccountDao().insertSubAccount(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.personalSubAccountDao().insertSubAccount(obj.copy(id = local.id))
            }
            Supabase.TABLE_CHILDREN_EXPENSES -> {
                val local = database.childrenExpenseDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.ChildrenExpense::class.java, gson, local)
                if (local == null) database.childrenExpenseDao().insertChildrenExpense(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.childrenExpenseDao().insertChildrenExpense(obj.copy(id = local.id))
            }
            Supabase.TABLE_UNREGISTERED_BANK_SMS -> {
                val bySync = database.unregisteredBankSmsDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.UnregisteredBankSms::class.java, gson, bySync)
                // dedup اضافه بر syncId: اگر همین پیامک (smsNativeId یکسان) قبلاً از مسیر
                // اسکن/زنده روی این دستگاه درج شده، همان ردیف به‌روزرسانی می‌شود نه ردیف تکراری
                val local = bySync ?: database.unregisteredBankSmsDao().getByNativeId(raw.smsNativeId)
                val obj = raw.copy(assignedAccountId = resolveBankAccountId(raw.assignedAccountId, raw.assignedAccountSyncId))
                if (local == null) database.unregisteredBankSmsDao().insertOrReplace(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.unregisteredBankSmsDao().insertOrReplace(obj.copy(id = local.id))
            }
            Supabase.TABLE_LAB_SERVICES -> {
                val local = database.labServiceDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.LabService::class.java, gson, local)
                if (local == null) database.labServiceDao().insertLabService(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.labServiceDao().insertLabService(obj.copy(id = local.id))
            }
            Supabase.TABLE_PARTY_ACCOUNTS -> {
                val local = database.partyAccountDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.PartyAccount::class.java, gson, local)
                if (local == null) database.partyAccountDao().insertPartyAccount(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.partyAccountDao().insertPartyAccount(obj.copy(id = local.id))
            }
            Supabase.TABLE_FIXED_INCOMES -> {
                val local = database.fixedIncomeDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.FixedIncome::class.java, gson, local)
                val obj = raw.copy(bankAccountId = resolveBankAccountId(raw.bankAccountId, raw.bankAccountSyncId))
                if (local == null) database.fixedIncomeDao().insertFixedIncome(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.fixedIncomeDao().insertFixedIncome(obj.copy(id = local.id))
            }
            Supabase.TABLE_TREATMENT_STAGES -> {
                val local = database.treatmentStageDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.TreatmentStage::class.java, gson, local)
                val obj = raw.copy(treatmentId = resolveTreatmentId(raw.treatmentId, raw.treatmentSyncId))
                if (local == null) database.treatmentStageDao().insertStage(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.treatmentStageDao().insertStage(obj.copy(id = local.id))
            }
            Supabase.TABLE_PATIENT_DOCUMENTS -> {
                val local = database.patientDocumentDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.PatientDocument::class.java, gson, local)
                val obj = raw.copy(patientId = resolvePatientId(raw.patientId, raw.patientSyncId))
                if (local == null) database.patientDocumentDao().insertDocument(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.patientDocumentDao().insertDocument(obj.copy(id = local.id))
            }
            Supabase.TABLE_VENDOR_INVOICES -> {
                val local = database.vendorInvoiceDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.VendorInvoice::class.java, gson, local)
                val obj = raw.copy(vendorId = resolveVendorIdRequired(raw.vendorId, raw.vendorSyncId))
                if (local == null) database.vendorInvoiceDao().insertInvoice(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.vendorInvoiceDao().insertInvoice(obj.copy(id = local.id))
            }
            Supabase.TABLE_INSTALLMENTS -> {
                val local = database.installmentDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.Installment::class.java, gson, local)
                val obj = raw.copy(
                    patientId = resolvePatientId(raw.patientId, raw.patientSyncId),
                    treatmentId = resolveTreatmentIdOrNull(raw.treatmentId, raw.treatmentSyncId)
                )
                if (local == null) database.installmentDao().insertInstallment(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.installmentDao().insertInstallment(obj.copy(id = local.id))
            }
            Supabase.TABLE_APP_CALENDAR_EVENTS -> {
                val local = database.appCalendarEventDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.AppCalendarEvent::class.java, gson, local)
                if (local == null) database.appCalendarEventDao().insertCalendarEvent(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.appCalendarEventDao().insertCalendarEvent(obj.copy(id = local.id))
            }
            Supabase.TABLE_FINANCIAL_LEDGER -> {
                val local = database.financialLedgerDao().getBySyncId(docId)
                val raw = parsePulledEntity(json, com.example.data.FinancialLedgerEntry::class.java, gson, local)
                if (raw.sourceSyncId.isBlank()) return@withTransaction
                val sourceId = when (raw.sourceType) {
                    "PATIENT_PAYMENT" -> database.paymentDao().getBySyncId(raw.sourceSyncId)?.id
                    "EXPENSE_PAYMENT" -> database.expensePaymentDao().getBySyncId(raw.sourceSyncId)?.id
                    "PERSONAL_TRANSACTION" -> database.personalTransactionDao().getBySyncId(raw.sourceSyncId)?.id
                    else -> null
                } ?: if (raw.sourceSyncId.isBlank()) raw.sourceId else error("Ledger source has not synced")
                val obj = raw.copy(
                    sourceId = sourceId,
                    patientId = if (!raw.patientSyncId.isNullOrBlank()) resolvePatientId(raw.patientId ?: 0L, raw.patientSyncId) else raw.patientId,
                    bankAccountId = resolveBankAccountId(raw.bankAccountId, raw.bankAccountSyncId)
                )
                if (local == null) database.financialLedgerDao().upsert(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.financialLedgerDao().upsert(obj.copy(id = local.id))
            }
            Supabase.TABLE_AUDIT_EVENTS -> {
                val local = database.auditEventDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.AuditEvent::class.java, gson, local)
                if (local == null) database.auditEventDao().insert(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.auditEventDao().insert(obj.copy(id = local.id))
            }
            Supabase.TABLE_DRUG_TEMPLATES -> {
                val local = database.drugTemplateDao().getBySyncId(docId)
                val obj = parsePulledEntity(json, com.example.data.DrugTemplate::class.java, gson, local)
                if (local == null) database.drugTemplateDao().insert(obj)
                else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) database.drugTemplateDao().insert(obj.copy(id = local.id))
            }
            Supabase.TABLE_CLINIC_SETTINGS -> {
                val local = database.clinicSettingsDao().getSettingsOnce()
                val obj = parsePulledEntity(json, com.example.data.ClinicSettings::class.java, gson, local)
                if (local == null) {
                    database.clinicSettingsDao().insertSettings(obj.copy(id = 1))
                } else if (remoteUpdatedAt >= local.updatedAt || local.updatedAt == 0L || remoteUpdatedAt == 0L) {
                    database.clinicSettingsDao().insertSettings(obj.copy(id = 1))
                } else {
                    // هویت مطب/آواتار اگر روی دستگاه خالی است از سرور پر شود (خروج/ورود دوباره)
                    val patched = local.copy(
                        clinicName = local.clinicName.ifBlank { obj.clinicName },
                        doctorName = local.doctorName.ifBlank { obj.doctorName },
                        logoFileId = local.logoFileId?.takeIf { it.isNotBlank() } ?: obj.logoFileId,
                        avatarFileId = local.avatarFileId?.takeIf { it.isNotBlank() } ?: obj.avatarFileId
                    )
                    if (patched != local) database.clinicSettingsDao().insertSettings(patched.copy(id = 1))
                }
            }
        }
    }

    /**
     * وصل کردن دوبارهٔ FKهای شکسته بعد از pull موفق والدها.
     * شامل همهٔ وابستگی‌های ۲۷ جدول: بیمار، درمان، حساب بانکی، تأمین‌کننده،
     * هزینه مطب، سفارش لابراتوار، تعمیرات. DAO مستقیم صدا زده می‌شود تا
     * عملیات جدیدی به صف سینک اضافه نشود (updatedAt هم عوض نمی‌شود).
     */
    private suspend fun repairBrokenForeignKeys() {
        fun Long?.isMissing(): Boolean = this == null || this == 0L

        for (p in database.paymentDao().getAllList()) {
            var next = p
            if (p.patientId == 0L && p.patientSyncId.isNotBlank()) {
                val resolved = database.patientDao().getBySyncId(p.patientSyncId)?.id ?: 0L
                if (resolved != 0L) next = next.copy(patientId = resolved)
            }
            if (p.bankAccountId.isMissing() && p.bankAccountSyncId.isNotBlank()) {
                val resolved = database.bankAccountDao().getBySyncId(p.bankAccountSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(bankAccountId = resolved)
            }
            if (next !== p) database.paymentDao().insertPayment(next)
        }
        for (a in database.appointmentDao().getAllList()) {
            if (a.patientId == 0L && a.patientSyncId.isNotBlank()) {
                val resolved = database.patientDao().getBySyncId(a.patientSyncId)?.id ?: 0L
                if (resolved != 0L) database.appointmentDao().insertAppointment(a.copy(patientId = resolved))
            }
        }
        for (t in database.treatmentDao().getAllList()) {
            if (t.patientId == 0L && t.patientSyncId.isNotBlank()) {
                val resolved = database.patientDao().getBySyncId(t.patientSyncId)?.id ?: 0L
                if (resolved != 0L) database.treatmentDao().insertTreatment(t.copy(patientId = resolved))
            }
        }
        for (i in database.installmentDao().getAllList()) {
            var next = i
            if (i.patientId == 0L && i.patientSyncId.isNotBlank()) {
                val resolved = database.patientDao().getBySyncId(i.patientSyncId)?.id ?: 0L
                if (resolved != 0L) next = next.copy(patientId = resolved)
            }
            if (i.treatmentId.isMissing() && i.treatmentSyncId.isNotBlank()) {
                val resolved = database.treatmentDao().getBySyncId(i.treatmentSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(treatmentId = resolved)
            }
            if (next !== i) database.installmentDao().insertInstallment(next)
        }
        for (rx in database.prescriptionDao().getAllList()) {
            var next = rx
            if (rx.patientId == 0L && rx.patientSyncId.isNotBlank()) {
                val resolved = database.patientDao().getBySyncId(rx.patientSyncId)?.id ?: 0L
                if (resolved != 0L) next = next.copy(patientId = resolved)
            }
            if (rx.treatmentId == 0L && rx.treatmentSyncId.isNotBlank()) {
                val resolved = database.treatmentDao().getBySyncId(rx.treatmentSyncId)?.id ?: 0L
                if (resolved != 0L) next = next.copy(treatmentId = resolved)
            }
            if (next !== rx) database.prescriptionDao().insertPrescription(next)
        }
        for (d in database.patientDocumentDao().getAllList()) {
            if (d.patientId == 0L && d.patientSyncId.isNotBlank()) {
                val resolved = database.patientDao().getBySyncId(d.patientSyncId)?.id ?: 0L
                if (resolved != 0L) database.patientDocumentDao().insertDocument(d.copy(patientId = resolved))
            }
        }
        for (s in database.treatmentStageDao().getAllList()) {
            if (s.treatmentId == 0L && s.treatmentSyncId.isNotBlank()) {
                val resolved = database.treatmentDao().getBySyncId(s.treatmentSyncId)?.id ?: 0L
                if (resolved != 0L) database.treatmentStageDao().insertStage(s.copy(treatmentId = resolved))
            }
        }
        for (v in database.vendorInvoiceDao().getAllList()) {
            if (v.vendorId == 0L && v.vendorSyncId.isNotBlank()) {
                val resolved = database.vendorDao().getBySyncId(v.vendorSyncId)?.id ?: 0L
                if (resolved != 0L) database.vendorInvoiceDao().insertInvoice(v.copy(vendorId = resolved))
            }
        }
        for (e in database.clinicExpenseDao().getAllList()) {
            var next = e
            if (e.labOrderId.isMissing() && e.labOrderSyncId.isNotBlank()) {
                val resolved = database.labOrderDao().getBySyncId(e.labOrderSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(labOrderId = resolved)
            }
            if (e.vendorId.isMissing() && e.vendorSyncId.isNotBlank()) {
                val resolved = database.vendorDao().getBySyncId(e.vendorSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(vendorId = resolved)
            }
            if (e.maintenanceId.isMissing() && e.maintenanceSyncId.isNotBlank()) {
                val resolved = database.equipmentMaintenanceDao().getBySyncId(e.maintenanceSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(maintenanceId = resolved)
            }
            if (next !== e) database.clinicExpenseDao().insertExpense(next)
        }
        for (ep in database.expensePaymentDao().getAllList()) {
            var next = ep
            if (ep.clinicExpenseId == 0L && ep.clinicExpenseSyncId.isNotBlank()) {
                val resolved = database.clinicExpenseDao().getBySyncId(ep.clinicExpenseSyncId)?.id ?: 0L
                if (resolved != 0L) next = next.copy(clinicExpenseId = resolved)
            }
            if (ep.bankAccountId.isMissing() && ep.bankAccountSyncId.isNotBlank()) {
                val resolved = database.bankAccountDao().getBySyncId(ep.bankAccountSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(bankAccountId = resolved)
            }
            if (next !== ep) database.expensePaymentDao().insertExpensePayment(next)
        }
        for (tx in database.personalTransactionDao().getAllList()) {
            if (tx.bankAccountId.isMissing() && tx.bankAccountSyncId.isNotBlank()) {
                val resolved = database.bankAccountDao().getBySyncId(tx.bankAccountSyncId)?.id
                if (resolved != null && resolved != 0L) database.personalTransactionDao().insertTransaction(tx.copy(bankAccountId = resolved))
            }
        }
        for (fi in database.fixedIncomeDao().getAllList()) {
            if (fi.bankAccountId.isMissing() && fi.bankAccountSyncId.isNotBlank()) {
                val resolved = database.bankAccountDao().getBySyncId(fi.bankAccountSyncId)?.id
                if (resolved != null && resolved != 0L) database.fixedIncomeDao().insertFixedIncome(fi.copy(bankAccountId = resolved))
            }
        }
        for (e in database.financialLedgerDao().getAllList()) {
            var next = e
            if (e.patientId.isMissing() && e.patientSyncId.isNotBlank()) {
                val resolved = database.patientDao().getBySyncId(e.patientSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(patientId = resolved)
            }
            if (e.bankAccountId.isMissing() && e.bankAccountSyncId.isNotBlank()) {
                val resolved = database.bankAccountDao().getBySyncId(e.bankAccountSyncId)?.id
                if (resolved != null && resolved != 0L) next = next.copy(bankAccountId = resolved)
            }
            if (next !== e) database.financialLedgerDao().upsert(next)
        }
    }

        suspend fun uploadFile(
        localFile: File,
        bucketId: String = Supabase.BUCKET_PATIENT_MEDIA,
        category: com.example.util.StorageCategory = com.example.util.StorageCategory.MISC
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!localFile.exists() || localFile.length() == 0L) return@withContext null
            // مسیر برگشتی ({uid}/{category}/{sha256}.ext) همان fileId ذخیره‌شده در
            // رکوردهاست. RLS روی Storage اجازه‌ی نوشتن فقط در پوشه‌ی خودِ کاربر را
            // می‌دهد. نامِ محتوامحور یعنی آپلود دوبارهٔ همان فایل شیء تکراری نمی‌سازد.
            val objectId = transport.upload(localFile, bucketId, category) ?: return@withContext null
            try {
                val dir = File(context.filesDir, "images")
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, Supabase.cacheFileNameFor(objectId))
                // نامِ کش هم از همان شناسهٔ محتوامحور می‌آید، پس اگر این فایل قبلاً
                // کش شده باشد دوباره کپی نمی‌شود.
                if (!dest.exists() || dest.length() == 0L) {
                    localFile.copyTo(dest, overwrite = true)
                }
            } catch (e: Exception) {
                // Ignore local caching copy error
            }
            objectId
        } catch (e: Exception) {
            Log.e("SyncEngine", "Failed to upload file to Supabase: ${e.message}")
            null
        }
    }

    companion object {
        private val syncMutex = Mutex()
        internal fun isValidCachedFile(file: File, objectPath: String): Boolean {
            if (!file.isFile || file.length() <= 0) return false
            val expected = objectPath.substringAfterLast('/').substringBeforeLast('.')
            return !Regex("[a-fA-F0-9]{64}").matches(expected) || com.example.util.StorageCategory.contentHash(file).equals(expected, true)
        }
        suspend fun downloadFile(context: android.content.Context, fileId: String, bucketId: String = Supabase.BUCKET_PATIENT_MEDIA): java.io.File? = withContext(Dispatchers.IO) {
            try {
                if (fileId.isBlank()) return@withContext null

                // 1. Direct file path check
                val directFile = java.io.File(fileId)
                if (directFile.exists() && directFile.length() > 0) return@withContext directFile

                // 2. Local cache directory check — نام کش سانیتایز شده (uid/xxx.jpg → uid_xxx.jpg)
                val dir = java.io.File(context.filesDir, "images")
                if (!dir.exists()) dir.mkdirs()
                val dest = java.io.File(dir, Supabase.cacheFileNameFor(fileId))
                if (isValidCachedFile(dest, fileId)) return@withContext dest
                if (dest.exists()) dest.delete()

                // 3. Attempt download across buckets
                val buckets = listOf(bucketId, Supabase.BUCKET_PATIENT_MEDIA, Supabase.BUCKET_CLINIC_ASSETS).distinct()
                for (b in buckets) {
                    try {
                        val bytes = Supabase.downloadFromBucket(b, fileId)
                        if (bytes != null && bytes.isNotEmpty()) {
                            val temp = File.createTempFile("media-", ".part", dir)
                            try {
                                temp.writeBytes(bytes)
                                check(isValidCachedFile(temp, fileId)) { "Downloaded image checksum mismatch" }
                                check(temp.renameTo(dest)) { "Could not commit downloaded image" }
                            } finally { temp.delete() }
                            return@withContext dest
                        }
                    } catch (e: Exception) {
                        // try next bucket
                    }
                }
                null
            } catch (e: Exception) {
                Log.e("SyncEngine", "Failed to download $fileId: ${e.message}")
                null
            }
        }
    }

    /**
     * حذف کامل حساب: تابعِ امنِ سمتِ سرورِ delete_my_account (SECURITY DEFINER،
     * تعریف‌شده در supabase/migrations/0001_dentalpro_init.sql) به‌صورت اتمیک همه‌ی
     * رکوردهای این حساب از ۲۸ جدول + فایل‌های Storage + خودِ حساب auth را پاک
     * می‌کند و نشست‌ها باطل می‌شوند. دیتای محلی را صدا نمی‌زند — logout()/
     * clearAllData() مسئول پاک‌سازی محلی است و باید پس از فراخوانی موفق این
     * تابع، جداگانه صدا زده شود.
     */
    suspend fun deleteAccountAndAllServerData(onProgress: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        onProgress("در حال حذف همه‌ی داده‌ها و فایل‌ها از سرور...")
        try {
            Supabase.invokeEdgeFunction("delete-account", null)
            true
        } catch (e: Exception) {
            Log.e("SyncEngine", "خطا در حذف حساب از سرور: ${e.message}", e)
            false
        }
    }

    private suspend fun deleteRemoteDocument(collectionId: String, documentId: String) {
        if (collectionId != Supabase.TABLE_FINANCIAL_LEDGER) transport.delete(collectionId, documentId)
    }

    private fun parseSyncError(e: Exception, table: String): String {
        val msg = e.message ?: e.localizedMessage ?: "Unknown error"
        val status = (e as? com.example.util.Supabase.SupabaseApiException)?.status
        return when {
            status == 401 || msg.contains("401") || msg.contains("نشست منقضی") ->
                "خطای احراز هویت (401): نشست کاربر منقضی شده است. دوباره وارد شوید."
            status == 403 || msg.contains("403") || msg.contains("forbidden", ignoreCase = true) || msg.contains("row-level security", ignoreCase = true) ->
                "خطای مجوز (403): دسترسی RLS به جدول $table ندارید (owner_id با حساب فعلی نمی‌خواند)."
            msg.contains("PGRST202", ignoreCase = true) || msg.contains("404") || msg.contains("not found", ignoreCase = true) || msg.contains("does not exist", ignoreCase = true) ->
                "خطای ساختار (404): جدول $table در سرور ابری ساخته نشده است (اسکریپت SQL را اجرا کنید)."
            msg.contains("PGRST204", ignoreCase = true) || msg.contains("Could not find the", ignoreCase = true) || status == 400 ->
                "خطای ساختار داده (400): ستون‌های جدول $table در سرور با داده‌های ارسالی همخوانی ندارد."
            msg.contains("timeout", ignoreCase = true) || msg.contains("connect", ignoreCase = true) || msg.contains("Failed to connect", ignoreCase = true) ->
                "عدم برقراری ارتباط با سرور: لطفاً اتصال اینترنت خود را بررسی کنید."
            else -> msg
        }
    }

    suspend fun runDiagnostic(): DiagnosticReport = withContext(Dispatchers.IO) {
        val items = mutableListOf<DiagnosticItem>()

        // 1. Network Status
        val isNet = _isOnline.value
        items.add(
            DiagnosticItem(
                title = "اتصال به شبکه اینترنت",
                isSuccess = isNet,
                detail = if (isNet) "دستگاه به اینترنت متصل است." else "دستگاه در حالت آفلاین است."
            )
        )

        // 2. Server Ping
        var pingOk = false
        var pingDetail = ""
        try {
            val pingRes = Supabase.ping()
            pingOk = pingRes == "ok"
            pingDetail = if (pingOk) "سرور ابری در دسترس است" else "خطا در پینگ سرور: $pingRes"
        } catch (e: Exception) {
            pingDetail = "خطا در برقراری ارتباط با سرور: ${e.message}"
        }
        items.add(DiagnosticItem("دسترس‌پذیری سرور ابری", pingOk, pingDetail))

        // 3. User Session
        var sessionOk = false
        var sessionDetail = ""
        try {
            val user = Supabase.currentUser()
            sessionOk = user.id.isNotBlank()
            // درخواست کاربر: شناسه‌ی نشست فعال نباید نمایش داده شود — فقط نام/ایمیل حساب.
            sessionDetail = "نشست فعال با حساب کاربر: ${user.name.ifBlank { user.email }}"
        } catch (e: Exception) {
            sessionDetail = "نشست فعال یافت نشد یا منقضی شده است"
        }
        items.add(DiagnosticItem("احراز هویت و سشن کاربر", sessionOk, sessionDetail))

        // 4. Database & RLS — نمونه‌خوانی از clinic_settings
        var dbOk = false
        var dbDetail = ""
        try {
            val rows = Supabase.fetchPage(Supabase.TABLE_CLINIC_SETTINGS, 0, 1)
            dbOk = true
            dbDetail = "دسترسی به دیتابیس و RLS تایید شد"
        } catch (e: Exception) {
            val msg = e.message ?: ""
            dbDetail = when {
                msg.contains("403") || msg.contains("row-level security", ignoreCase = true) ->
                    "خطای 403: خطای RLS — جدول‌ها ساخته شده‌اند اما Policy برای این حساب اجازه نمی‌دهد."
                msg.contains("404") || msg.contains("PGRST202", ignoreCase = true) || msg.contains("does not exist", ignoreCase = true) ->
                    "خطای 404: جدول‌ها هنوز در سرور ساخته نشده‌اند — اسکریپت SQL مهاجرت را اجرا کنید."
                else -> "خطای دیتابیس: $msg"
            }
        }
        items.add(DiagnosticItem("مجوز جدول‌ها و دیتابیس (RLS)", dbOk, dbDetail))

        // 4b. شمارش سرور در برابر Room برای هر ۲۷ جدول سینک
        val inventory = try { localInventory() } catch (_: Exception) { emptyMap() }
        val gsonProbe = com.google.gson.Gson()
        val tableFa = mapOf(
            Supabase.TABLE_CLINIC_SETTINGS to "تنظیمات مطب",
            Supabase.TABLE_STAFF_MEMBERS to "پرسنل",
            Supabase.TABLE_BANK_ACCOUNTS to "حساب بانکی",
            Supabase.TABLE_PERSONAL_SUB_ACCOUNTS to "زیرحساب شخصی",
            Supabase.TABLE_FIXED_INCOMES to "درآمد ثابت",
            Supabase.TABLE_TREATMENT_TEMPLATES to "قالب درمان",
            Supabase.TABLE_DRUG_TEMPLATES to "قالب دارو",
            Supabase.TABLE_LAB_SERVICES to "خدمات لابراتوار",
            Supabase.TABLE_APP_CALENDAR_EVENTS to "مناسبت تقویم",
            Supabase.TABLE_PATIENTS to "بیمار",
            Supabase.TABLE_VENDORS to "تأمین‌کننده",
            Supabase.TABLE_LAB_ORDERS to "سفارش لابراتوار",
            Supabase.TABLE_EQUIPMENT_MAINTENANCES to "تعمیر تجهیزات",
            Supabase.TABLE_TREATMENTS to "درمان",
            Supabase.TABLE_CLINIC_EXPENSES to "هزینه مطب",
            Supabase.TABLE_VENDOR_INVOICES to "فاکتور تأمین‌کننده",
            Supabase.TABLE_APPOINTMENTS to "نوبت",
            Supabase.TABLE_TREATMENT_STAGES to "مرحله درمان",
            Supabase.TABLE_PRESCRIPTIONS to "نسخه",
            Supabase.TABLE_PATIENT_DOCUMENTS to "مدارک بیمار",
            Supabase.TABLE_INSTALLMENTS to "قسط",
            Supabase.TABLE_PAYMENTS to "پرداخت",
            Supabase.TABLE_EXPENSE_PAYMENTS to "پرداخت هزینه",
            Supabase.TABLE_PERSONAL_TRANSACTIONS to "تراکنش شخصی",
            Supabase.TABLE_CHILDREN_EXPENSES to "هزینه فرزند",
            Supabase.TABLE_UNREGISTERED_BANK_SMS to "پیامک بانکی",
            Supabase.TABLE_FINANCIAL_LEDGER to "دفتر مالی",
            Supabase.TABLE_AUDIT_EVENTS to "رویداد حسابرسی"
        )
        // درخواست کاربر: نتیجه «خیلی خلاصه و بدون جزییات» اعلام شود — فقط جمع کل سرور در برابر
        // جمع کل Room و فهرست کوتاه جدول‌های مشکل‌دار؛ ریزِ جدول‌به‌جدول دیگر نمایش داده نمی‌شود.
        val lines = mutableListOf<String>()
        var tablesOk = true
        var tablesDetail = ""
        try {
            var remoteTotal = 0
            var localTotal = 0
            val problemTables = mutableListOf<String>()
            for (coll in SyncCollections.ALL) {
                val remote = try {
                    Supabase.fetchPage(coll, 0, 1).size()
                } catch (e: Exception) {
                    tablesOk = false
                    problemTables.add("${tableFa[coll] ?: coll} (خطا سرور)")
                    continue
                }
                val local = inventory[coll] ?: 0
                remoteTotal += remote
                localTotal += local
                if (remote > 0 && local == 0) {
                    tablesOk = false
                    problemTables.add(tableFa[coll] ?: coll)
                }
            }
            if (tablesOk) {
                tablesDetail = "سالم — سرور $remoteTotal رکورد / Room $localTotal رکورد (${SyncCollections.ALL.size} جدول)"
            } else {
                tablesDetail = "نیاز به توجه — سرور $remoteTotal / Room $localTotal؛ مشکل: ${problemTables.joinToString("، ")}"
            }
        } catch (e: Exception) {
            tablesOk = false
            tablesDetail = "بررسی جدول‌ها ناموفق بود"
        }
        items.add(DiagnosticItem("وضعیت سینک جداول (Server - Room)", tablesOk, tablesDetail))

                // 5. Storage Bucket — آپلود آزمایشی یک بایت کوچک به‌جای list (با RLS محافظت می‌شود)
        // درخواست کاربر: اعلام نتیجه خیلی خلاصه و بدون جزییات.
        var storageOk = false
        var storageDetail = ""
        try {
            val probe = java.io.File(context.cacheDir, "supabase_probe_${System.currentTimeMillis()}.jpg")
            probe.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            try {
                val path = Supabase.uploadToBucket(probe, Supabase.BUCKET_CLINIC_ASSETS, com.example.util.StorageCategory.MISC)
                if (path != null) {
                    Supabase.deleteStorageObject(Supabase.BUCKET_CLINIC_ASSETS, path)
                    storageOk = true
                    storageDetail = "باکت فایل‌ها در دسترس است"
                } else {
                    storageDetail = "باکت فایل‌ها در دسترس نیست"
                }
            } finally {
                probe.delete()
            }
        } catch (e: Exception) {
            storageDetail = "باکت فایل‌ها در دسترس نیست"
        }
        items.add(DiagnosticItem("دسترسی به باکت فایل‌ها (Storage)", storageOk, storageDetail))

        val allOk = items.all { it.isSuccess }
        DiagnosticReport(overallSuccess = allOk, items = items)
    }

    suspend fun localInventory(): Map<String, Int> = mapOf(
        Supabase.TABLE_CLINIC_SETTINGS to (if (database.clinicSettingsDao().getSettingsOnce() != null) 1 else 0),
        Supabase.TABLE_STAFF_MEMBERS to database.staffMemberDao().getAllList().size,
        Supabase.TABLE_BANK_ACCOUNTS to database.bankAccountDao().getAllList().size,
        Supabase.TABLE_PERSONAL_SUB_ACCOUNTS to database.personalSubAccountDao().getAllList().size,
        Supabase.TABLE_FIXED_INCOMES to database.fixedIncomeDao().getAllList().size,
        Supabase.TABLE_TREATMENT_TEMPLATES to database.treatmentTemplateDao().getAllList().size,
        Supabase.TABLE_DRUG_TEMPLATES to database.drugTemplateDao().getAllList().size,
        Supabase.TABLE_LAB_SERVICES to database.labServiceDao().getAllList().size,
        Supabase.TABLE_APP_CALENDAR_EVENTS to database.appCalendarEventDao().getAllList().size,
        Supabase.TABLE_PATIENTS to database.patientDao().getAllList().size,
        Supabase.TABLE_VENDORS to database.vendorDao().getAllList().size,
        Supabase.TABLE_LAB_ORDERS to database.labOrderDao().getAllList().size,
        Supabase.TABLE_EQUIPMENT_MAINTENANCES to database.equipmentMaintenanceDao().getAllList().size,
        Supabase.TABLE_TREATMENTS to database.treatmentDao().getAllList().size,
        Supabase.TABLE_CLINIC_EXPENSES to database.clinicExpenseDao().getAllList().size,
        Supabase.TABLE_VENDOR_INVOICES to database.vendorInvoiceDao().getAllList().size,
        Supabase.TABLE_APPOINTMENTS to database.appointmentDao().getAllList().size,
        Supabase.TABLE_TREATMENT_STAGES to database.treatmentStageDao().getAllList().size,
        Supabase.TABLE_PRESCRIPTIONS to database.prescriptionDao().getAllList().size,
        Supabase.TABLE_PATIENT_DOCUMENTS to database.patientDocumentDao().getAllList().size,
        Supabase.TABLE_INSTALLMENTS to database.installmentDao().getAllList().size,
        Supabase.TABLE_PAYMENTS to database.paymentDao().getAllList().size,
        Supabase.TABLE_EXPENSE_PAYMENTS to database.expensePaymentDao().getAllList().size,
        Supabase.TABLE_PERSONAL_TRANSACTIONS to database.personalTransactionDao().getAllList().size,
        Supabase.TABLE_CHILDREN_EXPENSES to database.childrenExpenseDao().getAllList().size,
        Supabase.TABLE_UNREGISTERED_BANK_SMS to database.unregisteredBankSmsDao().getAllList().size,
        Supabase.TABLE_FINANCIAL_LEDGER to database.financialLedgerDao().getAllList().size,
        Supabase.TABLE_AUDIT_EVENTS to database.auditEventDao().getAllList().size
    )

    /** خلاصهٔ تعداد محلی هر ۲۷ جدول برای بنر صف دریافت. */
    suspend fun localInventorySummary(): String {
        val labels = listOf(
            Supabase.TABLE_PATIENTS to "بیمار",
            Supabase.TABLE_APPOINTMENTS to "نوبت",
            Supabase.TABLE_PAYMENTS to "پرداخت",
            Supabase.TABLE_TREATMENTS to "درمان",
            Supabase.TABLE_BANK_ACCOUNTS to "حساب بانکی",
            Supabase.TABLE_STAFF_MEMBERS to "پرسنل",
            Supabase.TABLE_VENDORS to "تأمین‌کننده",
            Supabase.TABLE_LAB_ORDERS to "لابراتوار",
            Supabase.TABLE_CLINIC_EXPENSES to "هزینه",
            Supabase.TABLE_INSTALLMENTS to "قسط",
            Supabase.TABLE_PRESCRIPTIONS to "نسخه",
            Supabase.TABLE_TREATMENT_TEMPLATES to "قالب درمان",
            Supabase.TABLE_DRUG_TEMPLATES to "قالب دارو",
            Supabase.TABLE_PATIENT_DOCUMENTS to "مدارک",
            Supabase.TABLE_TREATMENT_STAGES to "مرحله",
            Supabase.TABLE_VENDOR_INVOICES to "فاکتور",
            Supabase.TABLE_EXPENSE_PAYMENTS to "پرداخت هزینه",
            Supabase.TABLE_PERSONAL_TRANSACTIONS to "تراکنش",
            Supabase.TABLE_PERSONAL_SUB_ACCOUNTS to "زیرحساب",
            Supabase.TABLE_FIXED_INCOMES to "درآمد ثابت",
            Supabase.TABLE_CHILDREN_EXPENSES to "هزینه فرزند",
            Supabase.TABLE_UNREGISTERED_BANK_SMS to "پیامک بانکی",
            Supabase.TABLE_LAB_SERVICES to "خدمات لب",
            Supabase.TABLE_EQUIPMENT_MAINTENANCES to "تعمیر",
            Supabase.TABLE_APP_CALENDAR_EVENTS to "تقویم",
            Supabase.TABLE_FINANCIAL_LEDGER to "دفتر",
            Supabase.TABLE_AUDIT_EVENTS to "حسابرسی",
            Supabase.TABLE_CLINIC_SETTINGS to "تنظیمات"
        )
        val inv = localInventory()
        return labels.joinToString(" | ") { (k, fa) -> "$fa ${inv[k] ?: 0}" }
    }

    /**
     * خلاصهٔ خیلی کوتاهِ موجودی محلی برای پیامِ «صف دریافت» — فقط جمع کل رکوردها به‌جای
     * ریزِ جدول‌به‌جدول (درخواست کاربر: اعلام نتیجه خیلی خلاصه و بدون جزییات).
     */
    suspend fun localInventoryCompact(): String {
        val inv = localInventory()
        val total = inv.values.sum()
        val emptyTables = inv.filterValues { it == 0 }.keys.size
        return "جمع رکوردهای محلی: $total در ${inv.size} جدول" +
            if (emptyTables > 0) " ($emptyTables جدول خالی)" else " — همهٔ جدول‌ها دارای داده"
    }

        suspend fun enqueueAllLocalData(): Unit = withContext(Dispatchers.IO) {
        // 1. Settings
        val settings = database.clinicSettingsDao().getSettingsOnce()
        if (settings != null) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "clinic_settings",
                    recordSyncId = settings.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 2. Patients
        val patients = database.patientDao().getAllList()
        for (it in patients) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "patients",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 3. Treatments
        val treatments = database.treatmentDao().getAllList()
        for (it in treatments) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "treatments",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 4. Payments
        val payments = database.paymentDao().getAllList()
        for (it in payments) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "payments",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 5. Appointments
        val appointments = database.appointmentDao().getAllList()
        for (it in appointments) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "appointments",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 6. Staff
        val staffList = database.staffMemberDao().getChangedSince(0)
        for (it in staffList) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "staff_members",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 7. Vendors
        val vendors = database.vendorDao().getAllVendors().first()
        for (it in vendors) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "vendors",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 8. Lab Orders
        val labOrders = database.labOrderDao().getAllLabOrders().first()
        for (it in labOrders) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "lab_orders",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
        // 9. Drug Templates
        val drugTemplates = database.drugTemplateDao().getAll().first()
        for (it in drugTemplates) {
            database.pendingSyncOpDao().insert(
                com.example.data.PendingSyncOp(
                    collectionTable = "drug_templates",
                    recordSyncId = it.syncId,
                    operation = "UPSERT"
                )
            )
        }
    }

    private suspend fun requireMatchingAccount(): String {
        val owner = transport.ensureAccountId()
        val expected = context.getSharedPreferences("dental_pro_prefs", Context.MODE_PRIVATE).getString("auth_account_id", "").orEmpty()
        if (expected.isNotBlank() && expected != owner) throw AccountMismatchException()
        return owner
    }

    private suspend fun applyRemoteDeletions() {
        var offset = 0
        val knownTables = setOf("patients", "treatments", "payments", "appointments", "treatment_stages", "patient_documents", "vendors", "vendor_invoices", "lab_orders", "equipment_maintenance", "clinic_expenses", "expense_payments", "party_accounts", "personal_transactions", "lab_services", "installments", "treatment_templates", "prescriptions", "bank_accounts", "personal_sub_accounts", "fixed_incomes", "children_expenses", "clinic_settings", "app_calendar_events", "financial_ledger", "audit_events", "drug_templates", "staff_members", "unregistered_bank_sms")
        while (true) {
            val rows = Supabase.fetchPage("sync_tombstones", offset, 1000)
            for (entry in rows) {
                val row = entry.asJsonObject
                val table = row.get("collection_table").asString
                val key = row.get("record_sync_id").asString
                if (table !in knownTables) continue
                database.withTransaction {
                    if (!database.pendingSyncOpDao().hasPending(table, key)) {
                        database.openHelper.writableDatabase.execSQL("DELETE FROM `$table` WHERE syncId = ?", arrayOf(key))
                    }
                }
            }
            if (rows.size() < 1000) break
            offset += 1000
        }
    }

    private fun persistUploadedMedia(table: String, before: Any, after: Any) {
        val gson = com.google.gson.Gson()
        val old = gson.toJsonTree(before).asJsonObject
        val next = gson.toJsonTree(after).asJsonObject
        val fields = setOf("photoFileId", "photoLocalPath", "fileId", "localCachePath", "chequePhotoFileId", "documentFileId", "invoicePhotoFileId", "logoFileId", "avatarFileId", "signatureFileId")
        val changed = fields.filter { old.get(it) != next.get(it) && next.get(it)?.isJsonPrimitive == true }
        if (changed.isEmpty()) return
        val references = changed.filter { it.endsWith("FileId") || it == "fileId" }
        fun column(field: String): String = when ("$table:$field") {
            "payments:chequePhotoFileId" -> "chequePhotoBase64"
            "vendors:invoicePhotoFileId", "clinic_expenses:invoicePhotoFileId" -> "invoicePhotoBase64"
            "vendor_invoices:documentFileId" -> "photoBase64"
            else -> field
        }
        val where = references.joinToString(" AND ") { if (old.get(it)?.isJsonNull != false) "`${column(it)}` IS NULL" else "`${column(it)}` = ?" }
        val args = changed.map { next.get(it).asString } + old.get("syncId").asString + references.mapNotNull { old.get(it)?.takeIf { el -> !el.isJsonNull }?.asString }
        val sql = "UPDATE `$table` SET " + changed.joinToString { "`${column(it)}` = ?" } + " WHERE syncId = ?" + if (where.isBlank()) "" else " AND $where"
        database.openHelper.writableDatabase.execSQL(sql, args.toTypedArray())
    }

    private suspend fun upsertRemoteDocument(collectionId: String, documentId: String, data: Map<String, Any>) {
        // Supabase.upsertRow: تبدیل camelCase→snake_case، ستِ اجباری owner_id
        // (الزام RLS)، upsert با on_conflict=sync_id و هرسِ خودکار ستون ناشناخته
        // (PGRST204) با تلاش مجدد.
        val payload = data.toMutableMap()
        if (collectionId == Supabase.TABLE_PATIENT_DOCUMENTS) {
            check(!(payload["fileId"] as? String).isNullOrBlank()) { "Document image upload is incomplete; kept in sync queue" }
        }
        val mediaFields = setOf("photoFileId", "fileId", "chequePhotoFileId", "documentFileId", "invoicePhotoFileId", "logoFileId", "avatarFileId", "signatureFileId")
        for (field in mediaFields) {
            val value = payload[field] as? String ?: continue
            if (value.startsWith("/") || value.startsWith("content:") || value.startsWith("file:")) {
                val file = File(value)
                check(file.isFile && file.length() > 0L) { "Local image is missing for $collectionId/$field" }
                val clinic = collectionId in setOf("clinic_settings", "staff_members", "vendor_invoices", "vendors", "clinic_expenses")
                val category = when {
                    field.contains("cheque", true) -> com.example.util.StorageCategory.CHEQUES
                    clinic -> com.example.util.StorageCategory.CLINIC
                    else -> com.example.util.StorageCategory.PATIENTS
                }
                val bucket = if (clinic) Supabase.BUCKET_CLINIC_ASSETS else Supabase.BUCKET_PATIENT_MEDIA
                payload[field] = checkNotNull(uploadFile(file, bucket, category)) { "Image upload incomplete; kept in sync queue" }
            }
        }
        persistUploadedMedia(collectionId, data, payload)
        transport.upsert(collectionId, documentId, payload)
    }
}

data class DiagnosticItem(
    val title: String,
    val isSuccess: Boolean,
    val detail: String
)

data class DiagnosticReport(
    val overallSuccess: Boolean,
    val items: List<DiagnosticItem>
)