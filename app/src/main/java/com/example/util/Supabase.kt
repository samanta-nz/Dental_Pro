package com.example.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * کلاینتِ Supabase (REST: GoTrue/PostgREST/Storage/Edge Functions) روی OkHttp+Gson.
 * امنیت: کلیدِ anon برای کلاینت طراحی شده و با RLS محافظت می‌شود (قرارش در Git بی‌خطر
 * است)؛ کلیدِ service_role هرگز نباید وارد اپ/Git شود — فقط Secretِ Edge Functionها.
 */
object Supabase {
    // ------------------------------------------------------------------
    // پیکربندی پروژه
    // ------------------------------------------------------------------
    const val SUPABASE_URL = "https://geifrkzmiutpkbwejdms.supabase.co"
    const val ANON_KEY = "sb_publishable_9m6jSn9GeB3x86Ma14YtRA_gOvItYZ-"

    /** دامنه‌ی ایمیلِ قطعی که برای حساب‌های «ورود با موبایل» ساخته می‌شود. */
    const val PHONE_EMAIL_DOMAIN = "phone.dentalpro.app"

    /** آدرس بازگشت (Deep Link) اپ پس از OAuth گوگل / لینک‌های ایمیلی. */
    const val APP_REDIRECT_URL = "dental://app"

    // جدول‌های Postgres — یک‌به‌یک با کالکشن‌های Appwrite قبلی
    const val TABLE_USERS = "users"
    const val TABLE_PATIENTS = "patients"
    const val TABLE_TREATMENTS = "treatments"
    const val TABLE_PAYMENTS = "payments"
    const val TABLE_APPOINTMENTS = "appointments"
    const val TABLE_TREATMENT_STAGES = "treatment_stages"
    const val TABLE_PATIENT_DOCUMENTS = "patient_documents"
    const val TABLE_VENDORS = "vendors"
    const val TABLE_VENDOR_INVOICES = "vendor_invoices"
    const val TABLE_LAB_ORDERS = "lab_orders"
    const val TABLE_EQUIPMENT_MAINTENANCES = "equipment_maintenance"
    const val TABLE_CLINIC_EXPENSES = "clinic_expenses"
    const val TABLE_EXPENSE_PAYMENTS = "expense_payments"
    const val TABLE_PARTY_ACCOUNTS = "party_accounts"
    const val TABLE_PERSONAL_TRANSACTIONS = "personal_transactions"
    const val TABLE_LAB_SERVICES = "lab_services"
    const val TABLE_INSTALLMENTS = "installments"
    const val TABLE_TREATMENT_TEMPLATES = "treatment_templates"
    const val TABLE_PRESCRIPTIONS = "prescriptions"
    const val TABLE_BANK_ACCOUNTS = "bank_accounts"
    const val TABLE_UNREGISTERED_BANK_SMS = "unregistered_bank_sms"
    const val TABLE_APP_UPDATES = "app_updates"
    const val TABLE_PERSONAL_SUB_ACCOUNTS = "personal_sub_accounts"
    const val TABLE_FIXED_INCOMES = "fixed_incomes"
    const val TABLE_CHILDREN_EXPENSES = "children_expenses"
    const val TABLE_CLINIC_SETTINGS = "clinic_settings"
    const val TABLE_APP_CALENDAR_EVENTS = "app_calendar_events"
    const val TABLE_AUDIT_EVENTS = "audit_events"
    const val TABLE_DRUG_TEMPLATES = "drug_templates"
    const val TABLE_STAFF_MEMBERS = "staff_members"
    const val TABLE_FINANCIAL_LEDGER = "financial_ledger"
    const val TABLE_API_SETTINGS = "api_settings"

    // باکت‌های Storage (در SQL ساخته می‌شوند)
    const val BUCKET_PATIENT_MEDIA = "patient_media"
    const val BUCKET_CLINIC_ASSETS = "clinic_assets"

    // ------------------------------------------------------------------
    // مدل‌ها
    // ------------------------------------------------------------------
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long, // epoch seconds
        val userId: String?,
        val email: String?
    )

    data class UserInfo(
        val id: String,
        val email: String,
        val name: String,
        val phone: String,
        val emailVerified: Boolean,
        /** آدرس عکس پروفایل گوگل‌اکانت (user_metadata.avatar_url / picture) — برای لود اولیهٔ آواتار. */
        val avatarUrl: String = ""
    )

    class SupabaseApiException(
        val status: Int,
        val errorCode: String? = null,
        message: String
    ) : Exception(message)

    // ------------------------------------------------------------------
    // وضعیت
    // ------------------------------------------------------------------
    private const val PREFS_NAME = "supabase_auth"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "user_email"
    private const val KEY_OAUTH_VERIFIER = "pkce_verifier"

    private val gson = Gson()
    lateinit var appContext: Context
        private set

    /**
     * شناسه‌ی حساب کاربر لاگین‌شده (auth.uid) — بلافاصله پس از هر لاگین موفق ست
     * می‌شود و پایه‌ی ایزوله‌سازی داده بین حساب‌هاست (ستون owner_id + RLS).
     */
    var currentAccountId: String? = null
        private set

    @Volatile
    var currentSession: Session? = null
        private set

    /** verifier جریان PKCE در حال انجام (بین باز شدن مرورگر و بازگشت Deep Link). */
    var pendingPkceVerifier: String? = null
        private set

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secure = com.example.security.SecureSecretStore.get(appContext, "supabase_session")
        currentSession = secure?.let { gson.fromJson(it, Session::class.java) }
        if (currentSession == null) {
            val access = prefs.getString(KEY_ACCESS, null)
            val refresh = prefs.getString(KEY_REFRESH, null)
            if (!access.isNullOrBlank() && !refresh.isNullOrBlank()) {
                saveSession(Session(access, refresh, prefs.getLong(KEY_EXPIRES, 0L), prefs.getString(KEY_USER_ID, null), prefs.getString(KEY_EMAIL, null)))
            }
        }
        currentAccountId = currentSession?.userId
        pendingPkceVerifier = com.example.security.SecureSecretStore.get(appContext, "pkce_verifier")
            ?: prefs.getString(KEY_OAUTH_VERIFIER, null)?.also { savePkceVerifier(it) }
        prefs.edit().clear().commit()
    }

    // ------------------------------------------------------------------
    // تبدیل camelCase ↔ snake_case (بین Room و Postgres)
    // ------------------------------------------------------------------
    private val camelRegex = Regex("([a-z0-9])([A-Z])")
    private val snakeRegex = Regex("_([a-z])")

    fun toSnakeCase(key: String): String =
        if (key == "sentWhatsApp") "sent_whatsapp" else camelRegex.replace(key) { m ->
            "${m.groupValues[1]}_${m.groupValues[2].lowercase()}"
        }

    fun toCamelCase(key: String): String =
        if (key == "sent_whatsapp") "sentWhatsApp" else snakeRegex.replace(key) { it.groupValues[1].uppercase() }

    /** کلیدهای یک JsonObject را به snake_case تبدیل می‌کند (برای ارسال به PostgREST). */
    fun jsonKeysToSnake(obj: JsonObject): JsonObject {
        val out = JsonObject()
        for ((k, v) in obj.entrySet()) out.add(toSnakeCase(k), v)
        return out
    }

    /** کلیدهای یک JsonObject را به camelCase تبدیل می‌کند (برای Gson→Room). */
    fun jsonKeysToCamel(obj: JsonObject): JsonObject {
        val out = JsonObject()
        for ((k, v) in obj.entrySet()) out.add(toCamelCase(k), v)
        return out
    }

    // ------------------------------------------------------------------
    // لایه‌ی HTTP مشترک
    // ------------------------------------------------------------------
    private fun buildHeaders(token: String?, contentType: String? = null): Headers {
        val pairs = mutableListOf("apikey" to ANON_KEY)
        if (token != null) pairs.add("Authorization" to "Bearer $token")
        if (contentType != null) pairs.add("Content-Type" to contentType)
        return Headers.headersOf(*pairs.flatMap { listOf(it.first, it.second) }.toTypedArray())
    }

    private suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        http.newCall(request).execute()
    }

    private suspend fun executeBody(request: Request): String {
        val response = execute(request)
        response.use {
            if (!it.isSuccessful) throw errorFrom(it)
            return it.body?.string().orEmpty()
        }
    }

    /** اجرای یک فراخوانیِ احراز‌هویت‌شده با تمدید خودکار توکن در صورت انقضا/401. */
    private suspend fun <T> withAuth(block: suspend (accessToken: String) -> T): T {
        var session = ensureFreshSession()
        try {
            return block(session.accessToken)
        } catch (e: SupabaseApiException) {
            if (e.status == 401) {
                session = refreshSession(session.refreshToken)
                    ?: throw SupabaseApiException(401, null, "نشست منقضی شده است. دوباره وارد شوید.")
                return block(session.accessToken)
            }
            throw e
        }
    }

    private suspend fun ensureFreshSession(): Session {
        val s = currentSession
            ?: throw SupabaseApiException(401, null, "کاربر وارد نشده است")
        // اگر کمتر از ۶۰ ثانیه تا انقضا مانده، از قبل تمدید کن
        if (s.expiresAt - 60 <= System.currentTimeMillis() / 1000) {
            return refreshSession(s.refreshToken) ?: s
        }
        return s
    }

    private fun saveSession(session: Session?) {
        if (session == null) com.example.security.SecureSecretStore.remove(appContext, "supabase_session")
        else com.example.security.SecureSecretStore.put(appContext, "supabase_session", gson.toJson(session))
        currentSession = session
        currentAccountId = session?.userId
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    fun savePkceVerifier(verifier: String?) {
        if (verifier == null) com.example.security.SecureSecretStore.remove(appContext, "pkce_verifier")
        else com.example.security.SecureSecretStore.put(appContext, "pkce_verifier", verifier)
        pendingPkceVerifier = verifier
    }

    fun clearPkceVerifier() = savePkceVerifier(null)

    private fun parseSession(body: String): Session? {
        val obj = JsonParser.parseString(body).asJsonObject
        val access = obj.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val refresh = obj.get("refresh_token")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val expiresAt = obj.get("expires_at")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: (System.currentTimeMillis() / 1000 +
                (obj.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong ?: 3600))
        val user = obj.getAsJsonObject("user")
        val uid = user?.get("id")?.takeIf { it.isJsonPrimitive }?.asString
        val email = user?.get("email")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString
        return Session(access, refresh, expiresAt, uid, email)
    }

    private fun parseUser(obj: JsonObject): UserInfo {
        val metadata = obj.get("user_metadata")?.takeIf { it.isJsonObject }?.asJsonObject
        fun meta(key: String): String =
            metadata?.get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString ?: ""
        return UserInfo(
            id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
            email = obj.get("email")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString ?: "",
            name = meta("name"),
            phone = obj.get("phone")?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asString ?: "",
            emailVerified = obj.has("email_confirmed_at") &&
                obj.get("email_confirmed_at")?.isJsonNull == false,
            // گوگل هر دو کلید avatar_url و picture را در متادیتا می‌گذارد
            avatarUrl = meta("avatar_url").ifBlank { meta("picture") }
        )
    }

    private fun errorFrom(response: Response): SupabaseApiException {
        val body = try {
            response.body?.string().orEmpty()
        } catch (e: Exception) {
            ""
        }
        val msg = try {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("msg")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("error_description")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                ?: body.take(300)
        } catch (e: Exception) {
            body.take(300).ifBlank { "HTTP ${response.code}" }
        }
        return SupabaseApiException(response.code, null, msg)
    }

    private fun String.jsonBody(): RequestBody = toRequestBody("application/json".toMediaType())

    // ==================================================================
    // AUTH — GoTrue
    // ==================================================================

    /** ثبت‌نام با ایمیل و رمز. اگر «تایید ایمیل» فعال باشد session برنمی‌گردد. */
    suspend fun signUpWithEmail(
        email: String,
        password: String,
        metadata: Map<String, String> = emptyMap()
    ): Session? {
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
            add("data", JsonObject().apply {
                for ((k, v) in metadata) addProperty(k, v)
            })
        }
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/signup")
                .headers(buildHeaders(ANON_KEY))
                .post(body.toString().jsonBody())
                .build()
        )
        val session = parseSession(text)
        if (session != null) saveSession(session)
        return session
    }

    /** ورود با ایمیل (یا ایمیلِ قطعیِ موبایل) و رمز عبور. */
    suspend fun signInWithPassword(email: String, password: String): Session {
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("password", password)
        }
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/token?grant_type=password")
                .headers(buildHeaders(ANON_KEY))
                .post(body.toString().jsonBody())
                .build()
        )
        val session = parseSession(text)
            ?: throw SupabaseApiException(0, null, "پاسخ نشست از سرور دریافت نشد")
        saveSession(session)
        return session
    }

    /** تعویض توکن refresh با نشست تازه؛ null یعنی نشست کاملاً باطل شده. */
    suspend fun refreshSession(refreshToken: String): Session? {
        val body = JsonObject().apply { addProperty("refresh_token", refreshToken) }
        return try {
            val res = execute(
                Request.Builder()
                    .url("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token")
                    .headers(buildHeaders(ANON_KEY))
                    .post(body.toString().jsonBody())
                    .build()
            )
            res.use {
                if (!it.isSuccessful) {
                    if (it.code == 400) saveSession(null) // refresh token نامعتبر — خروج اجباری
                    null
                } else {
                    parseSession(it.body?.string().orEmpty())?.also { s -> saveSession(s) }
                }
            }
        } catch (e: Exception) {
            Log.w("Supabase", "Session refresh failed: ${e.message}")
            null
        }
    }

    /** اطلاعات کاربرِ نشستِ فعلی. */
    suspend fun currentUser(): UserInfo = withAuth { token ->
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/user")
                .headers(buildHeaders(token))
                .get()
                .build()
        )
        parseUser(JsonParser.parseString(text).asJsonObject)
    }

    /** به‌روزرسانی پروفایل/رمز کاربر جاری (name، phone، password، ...). */
    suspend fun updateUser(fields: Map<String, String>): UserInfo = withAuth { token ->
        val body = JsonObject().apply {
            val metadata = JsonObject()
            var hasMetadata = false
            for ((k, v) in fields) {
                if (k == "password" || k == "email") addProperty(k, v)
                else {
                    metadata.addProperty(k, v)
                    hasMetadata = true
                }
            }
            if (hasMetadata) add("data", metadata)
        }
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/user")
                .headers(buildHeaders(token))
                .put(body.toString().jsonBody())
                .build()
        )
        parseUser(JsonParser.parseString(text).asJsonObject)
    }

    suspend fun signOut() {
        val s = currentSession
        try {
            if (s != null) {
                execute(
                    Request.Builder()
                        .url("$SUPABASE_URL/auth/v1/logout")
                        .headers(buildHeaders(s.accessToken))
                        .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                        .build()
                ).use { /* نتیجه مهم نیست */ }
            }
        } catch (e: Exception) {
            Log.w("Supabase", "Logout call failed (continuing): ${e.message}")
        } finally {
            saveSession(null)
            clearPkceVerifier()
        }
    }

    suspend fun requestPasswordReset(email: String, redirectTo: String = APP_REDIRECT_URL) {
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("redirect_to", redirectTo)
        }
        executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/recover")
                .headers(buildHeaders(ANON_KEY))
                .post(body.toString().jsonBody())
                .build()
        )
    }

    suspend fun resendVerificationEmail(email: String) {
        val body = JsonObject().apply {
            addProperty("email", email.trim())
            addProperty("type", "signup")
        }
        executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/resend")
                .headers(buildHeaders(ANON_KEY))
                .post(body.toString().jsonBody())
                .build()
        )
    }

    /**
     * تایید توکن magic-link (ورود پیامکی): با توکنی که verify-otp برمی‌گرداند
     * یک نشست واقعی می‌سازد.
     */
    suspend fun verifyMagicToken(token: String, redirectTo: String = APP_REDIRECT_URL): Session {
        val body = JsonObject().apply {
            addProperty("token_hash", token)
            addProperty("type", "magiclink")
        }
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/verify")
                .headers(buildHeaders(ANON_KEY))
                .post(body.toString().jsonBody())
                .build()
        )
        val session = parseSession(text)
            ?: throw SupabaseApiException(0, null, "توکن تایید نامعتبر است")
        saveSession(session)
        return session
    }

    // ------------------------------------------------------------------
    // OAuth گوگل با PKCE (بدون نیاز به Google Play Services — مناسب ایران)
    // ------------------------------------------------------------------

    fun generatePkceVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** URL صفحه‌ی ورود گوگل — با Custom Tab / مرورگر باز می‌شود. */
    fun buildGoogleAuthorizeUrl(verifier: String): String {
        savePkceVerifier(verifier)
        val challenge = pkceChallenge(verifier)
        return "$SUPABASE_URL/auth/v1/authorize?provider=google" +
            "&redirect_to=${URLEncoder.encode(APP_REDIRECT_URL, "UTF-8")}" +
            "&code_challenge=$challenge&code_challenge_method=s256&prompt=select_account"
    }

    /** تعویض کدِ بازگشتی از Deep Link (dental://app?code=...) با نشست. */
    suspend fun exchangeOAuthCode(code: String): Session {
        val verifier = pendingPkceVerifier
            ?: throw SupabaseApiException(400, null, "جریان ورود گوگل یافت نشد؛ دوباره تلاش کنید")
        val body = JsonObject().apply {
            addProperty("auth_code", code)
            addProperty("code_verifier", verifier)
        }
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/auth/v1/token?grant_type=pkce")
                .headers(buildHeaders(ANON_KEY))
                .post(body.toString().jsonBody())
                .build()
        )
        val session = parseSession(text)
            ?: throw SupabaseApiException(0, null, "کد ورود گوگل نامعتبر است")
        clearPkceVerifier()
        saveSession(session)
        return session
    }

    // ==================================================================
    // PostgREST — داده
    // ==================================================================

    /**
     * درج/به‌روزرسانی رکورد (upsert). payload با کلیدهای camelCase داده می‌شود و
     * همین‌جا به snake_case تبدیل و owner_id به‌صورت اجباری روی حساب فعلی ست
     * می‌شود (الزام RLS). در صورت خطای ستونِ ناشناخته (PGRST204) ستون حذف و
     * دوباره تلاش می‌شود — سازگار با اسکیماهای در حال تکامل.
     */
    suspend fun upsertRow(
        table: String,
        recordSyncId: String,
        camelPayload: Map<String, Any>,
        conflictColumn: String = "sync_id"
    ) {
        val ownerId = try {
            ensureAccountId()
        } catch (e: Exception) {
            throw SupabaseApiException(401, null, "برای سینک باید وارد حساب شوید")
        }
        val originalOwner = camelPayload["ownerId"] as? String
        if (!originalOwner.isNullOrBlank() && originalOwner != ownerId) throw com.example.sync.AccountMismatchException()
        val payload = camelPayload.toMutableMap()
        payload["ownerId"] = ownerId
        payload["syncId"] = recordSyncId

        val body = JsonObject().apply {
            for ((k, v) in payload) add(toSnakeCase(k), gson.toJsonTree(v))
        }
        withAuth { token ->
            executeBody(Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$table?on_conflict=$conflictColumn")
                .headers(buildHeaders(token))
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toString().jsonBody()).build())
        }
    }

    /** یک صفحه از جدول را می‌خواند (مرتب بر اساس sync_id صعودی). */
    suspend fun fetchPage(table: String, offset: Int, limit: Int = 1000): JsonArray = withAuth { token ->
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$table?select=*&order=sync_id.asc&limit=$limit&offset=$offset")
                .headers(buildHeaders(token))
                .get()
                .build()
        )
        val el = JsonParser.parseString(text)
        if (el.isJsonArray) el.asJsonArray else JsonArray()
    }

    /** یک رکورد را با sync_id می‌خواند (برای حذفِ فایل‌های وابسته قبل از DELETE). */
    suspend fun fetchRow(table: String, recordSyncId: String): JsonObject? =
        fetchRowByColumn(table, "sync_id", recordSyncId)

    /**
     * آخرین اعلان بروزرسانی فعال اپ از سرور — عمداً بدون توکن احرازهویت خوانده می‌شود
     * (جدول app_updates policy خواندن عمومی anon دارد) تا حتی قبل از لاگین هم
     * چک بروزرسانی کار کند.
     */
    suspend fun fetchLatestAppUpdate(): JsonObject? {
        val text = executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$TABLE_APP_UPDATES?is_active=eq.true&select=*&order=version_code.desc&limit=1")
                .headers(buildHeaders(null))
                .get()
                .build()
        )
        val el = JsonParser.parseString(text)
        if (!el.isJsonArray) return null
        val arr = el.asJsonArray
        return if (arr.size() > 0) arr[0].asJsonObject else null
    }

    /** یک رکورد را با یک ستون دلخواه می‌خواند (مثل users با id). */
    suspend fun fetchRowByColumn(table: String, column: String, value: String): JsonObject? = withAuth { token ->
        val text = executeBody(
            Request.Builder()
                .url(
                    "$SUPABASE_URL/rest/v1/$table?$column=eq." +
                        URLEncoder.encode(value, "UTF-8") + "&select=*&limit=1"
                )
                .headers(buildHeaders(token))
                .get()
                .build()
        )
        val arr = JsonParser.parseString(text).asJsonArray
        if (arr.size() > 0) arr[0].asJsonObject else null
    }

    /** باطل‌کردن کش شناسه‌ی حساب — هنگام خروج/تعویض حساب صدا زده می‌شود. */
    fun resetAccountIdCache() {
        currentAccountId = null
    }

    suspend fun deleteRow(table: String, recordSyncId: String) = withAuth { token ->
        executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/rest/v1/$table?sync_id=eq.${URLEncoder.encode(recordSyncId, "UTF-8")}")
                .headers(buildHeaders(token))
                .header("Prefer", "return=minimal")
                .delete()
                .build()
        )
        Unit
    }

    /** فراخوانی RPC (مثل delete_my_account). */
    suspend fun rpc(functionName: String, params: Map<String, Any> = emptyMap()): String = withAuth { token ->
        val body = JsonObject().apply {
            for ((k, v) in params) add(k, gson.toJsonTree(v))
        }
        executeBody(
            Request.Builder()
                .url("$SUPABASE_URL/rest/v1/rpc/$functionName")
                .headers(buildHeaders(token))
                .post(body.toString().jsonBody())
                .build()
        )
    }

    // ==================================================================
    // Storage — فایل‌ها
    // ==================================================================

    /**
     * آپلودِ فایل به باکت؛ مسیرِ برگشتی همان fileId ذخیره‌شده در رکوردهاست. مسیر
     * `{uid}/{category}/{sha256}.{ext}`: uid برای جداسازیِ کاربر (RLS)، category
     * برای پوشه‌بندی، و sha256 نامِ محتوامحور — آپلودِ دوبارهٔ همان فایل با x-upsert
     * روی همان شیء می‌نویسد، پس نسخهٔ تکراری ساخته نمی‌شود.
     */
    suspend fun uploadToBucket(
        localFile: File,
        bucketId: String,
        category: com.example.util.StorageCategory = com.example.util.StorageCategory.MISC
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!localFile.exists() || localFile.length() == 0L) return@withContext null
            val uid = ensureAccountId()
            val expectedOwner = appContext.getSharedPreferences("dental_pro_prefs", Context.MODE_PRIVATE).getString("auth_account_id", "").orEmpty()
            if (expectedOwner.isNotBlank() && expectedOwner != uid) throw com.example.sync.AccountMismatchException()
            val ext = localFile.extension.ifBlank {
                if (localFile.name.endsWith(".png", true)) "png" else "jpg"
            }
            val objectPath = com.example.util.StorageCategory.objectPathFor(uid, category, localFile)
            val mediaType = if (ext.equals("png", true)) "image/png".toMediaType() else "image/jpeg".toMediaType()
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", objectPath.substringAfterLast('/'), localFile.asRequestBody(mediaType))
                .build()
            val res = withAuth { token ->
                execute(
                    Request.Builder()
                        .url("$SUPABASE_URL/storage/v1/object/$bucketId/$objectPath")
                        .headers(buildHeaders(token))
                        .header("x-upsert", "true")
                        .post(multipart)
                        .build()
                )
            }
            res.use {
                if (!it.isSuccessful) {
                    Log.e("Supabase", "Upload to $bucketId failed: ${it.code}")
                    return@withContext null
                }
                objectPath
            }
        } catch (e: Exception) {
            Log.e("Supabase", "Upload to $bucketId failed: ${e.message}")
            null
        }
    }

    /** دانلود بایت‌های یک شیء — اول مسیر عمومی، بعد authenticated. */
    suspend fun downloadFromBucket(bucketId: String, objectPath: String): ByteArray? = withContext(Dispatchers.IO) {
        if (objectPath.isBlank()) return@withContext null
        val encoded = objectPath.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        // ۲) مسیر احراز‌هویت‌شده
        try {
            withAuth { token ->
                execute(
                    Request.Builder()
                        .url("$SUPABASE_URL/storage/v1/object/authenticated/$bucketId/$encoded")
                        .headers(buildHeaders(token))
                        .get()
                        .build()
                ).use {
                    if (it.isSuccessful) it.body?.bytes() else null
                }
            }
        } catch (e: Exception) {
            Log.e("Supabase", "Download $objectPath from $bucketId failed: ${e.message}")
            null
        }
    }

    suspend fun deleteStorageObject(bucketId: String, objectPath: String) {
        if (objectPath.isBlank()) return
        try {
            val encoded = objectPath.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
            withAuth { token ->
                execute(
                    Request.Builder()
                        .url("$SUPABASE_URL/storage/v1/object/$bucketId/$encoded")
                        .headers(buildHeaders(token))
                        .delete()
                        .build()
                ).use { /* نتیجه مهم نیست — فایل ممکن است از قبل حذف شده باشد */ }
            }
        } catch (e: Exception) {
            Log.w("Supabase", "Delete $objectPath from $bucketId failed: ${e.message}")
        }
    }

    // ==================================================================
    // Edge Functions
    // ==================================================================

    /**
     * فراخوانی Edge Function با بدنه‌ی JSON. متن پاسخ را برمی‌گرداند.
     * در صورت خطا، پیامِ فیلد error پاسخ را در Exception می‌گذارد.
     */
    suspend fun invokeEdgeFunction(name: String, jsonBody: JsonObject?): String {
        return withAuth { token ->
        val res = execute(
            Request.Builder()
                .url("$SUPABASE_URL/functions/v1/$name")
                .headers(buildHeaders(token))
                .post((jsonBody?.toString() ?: "{}").jsonBody())
                .build()
        )
        res.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val friendly = try {
                    JsonParser.parseString(text).asJsonObject.get("error")?.asString
                } catch (e: Exception) {
                    null
                }
                throw SupabaseApiException(it.code, null, friendly ?: "خطای سرور (${it.code})")
            }
            text
        }
        }
    }

    // ==================================================================
    // ابزارهای مشترک
    // ==================================================================

    /** شناسه‌ی حساب؛ اگر کش نشده از سرور می‌خواند (با تمدید خودکار نشست). */
    suspend fun ensureAccountId(): String {
        currentAccountId?.takeIf { it.isNotBlank() }?.let { return it }
        val session = ensureFreshSession()
        session.userId?.takeIf { it.isNotBlank() }?.let {
            currentAccountId = it
            return it
        }
        val user = currentUser()
        currentAccountId = user.id
        return user.id
    }

    suspend fun currentUserIdOrNull(): String? = try {
        currentAccountId ?: ensureAccountId()
    } catch (e: Exception) {
        null
    }

    /** ایمیلِ قطعیِ حسابِ یک شماره‌ی موبایل (+989xxxxxxxxx → 989xxxxxxxxx@...). */
    fun phoneToDeterministicEmail(fullPhone: String): String {
        val local = fullPhone.replace(Regex("[^\\d]"), "")
        return "$local@$PHONE_EMAIL_DOMAIN"
    }

    /** تست دسترس‌پذیری سرور. */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        try {
            val res = execute(
                Request.Builder()
                    .url("$SUPABASE_URL/auth/v1/settings")
                    .headers(buildHeaders(ANON_KEY))
                    .get()
                    .build()
            )
            res.use {
                if (it.isSuccessful) "ok" else "failed (${it.code})"
            }
        } catch (e: Exception) {
            e.message ?: "Ping failed"
        }
    }

    /** نام فایل کش محلی برای یک مسیر شیء Storage (uid/xxx.jpg → uid_xxx.jpg). */
    fun cacheFileNameFor(objectId: String): String = objectId.replace("/", "_")
}

/** جدول‌های سینک به‌همراه ترتیب موج‌ها (وابسته‌ها بعد از والدشان). */
object SyncCollections {
    val ALL = listOf(
        // 1. مستقل‌ها
        Supabase.TABLE_CLINIC_SETTINGS,
        Supabase.TABLE_STAFF_MEMBERS,
        Supabase.TABLE_BANK_ACCOUNTS,
        Supabase.TABLE_PERSONAL_SUB_ACCOUNTS,
        Supabase.TABLE_FIXED_INCOMES,
        Supabase.TABLE_TREATMENT_TEMPLATES,
        Supabase.TABLE_DRUG_TEMPLATES,
        Supabase.TABLE_LAB_SERVICES,
        Supabase.TABLE_APP_CALENDAR_EVENTS,
        Supabase.TABLE_PARTY_ACCOUNTS,
        // 2. والدین ریشه
        Supabase.TABLE_PATIENTS,
        Supabase.TABLE_VENDORS,
        Supabase.TABLE_LAB_ORDERS,
        Supabase.TABLE_EQUIPMENT_MAINTENANCES,
        // 3. وابسته‌های سطح ۱
        Supabase.TABLE_TREATMENTS,
        Supabase.TABLE_CLINIC_EXPENSES,
        Supabase.TABLE_VENDOR_INVOICES,
        Supabase.TABLE_APPOINTMENTS,
        // 4. وابسته‌های سطح ۲
        Supabase.TABLE_TREATMENT_STAGES,
        Supabase.TABLE_PRESCRIPTIONS,
        Supabase.TABLE_PATIENT_DOCUMENTS,
        Supabase.TABLE_INSTALLMENTS,
        Supabase.TABLE_PAYMENTS,
        Supabase.TABLE_EXPENSE_PAYMENTS,
        Supabase.TABLE_PERSONAL_TRANSACTIONS,
        Supabase.TABLE_UNREGISTERED_BANK_SMS,
        Supabase.TABLE_CHILDREN_EXPENSES,
        Supabase.TABLE_FINANCIAL_LEDGER,
        Supabase.TABLE_AUDIT_EVENTS
    )
}
