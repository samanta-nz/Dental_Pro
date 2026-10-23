package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.feature.auth.UserRole
import com.example.ui.DentalViewModel
import com.example.util.Supabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Google OAuth with PKCE is the only sign-in method. Device PIN still protects local records. */
@Composable
fun AuthScreen(viewModel: DentalViewModel, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(true) }
    var awaitingGoogle by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var needsLock by remember { mutableStateOf(false) }
    val authMutex = remember { Mutex() }
    fun t(fa: String, en: String) = if (isPersian) fa else en

    suspend fun completeSignIn() = authMutex.withLock {
        busy = true
        error = null
        try {
            val session = checkNotNull(Supabase.currentSession) { "Google sign-in is required" }
            val user = try { Supabase.currentUser() } catch (e: Exception) {
                // Offline access requires a previously verified account on this exact device.
                if (session.userId == viewModel.getCachedAccountId() && viewModel.isProfileSetupComplete()) null else throw e
            }
            if (user != null) {
                check(user.emailVerified) { t("حساب گوگل تأیید نشده است.", "Google account is not verified.") }
                status = t("در حال دریافت اطلاعات مطب…", "Syncing your clinic…")
                viewModel.bootstrapAfterLogin { status = t("همگام‌سازی ${ (it * 100).toInt() }٪", "Syncing ${ (it * 100).toInt() }%") }
                val profile = Supabase.fetchRowByColumn(Supabase.TABLE_USERS, "id", user.id)
                fun field(key: String) = profile?.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (profile == null) Supabase.upsertRow(Supabase.TABLE_USERS, user.id, mapOf(
                    "id" to user.id, "name" to user.name, "email" to user.email,
                    "phone" to user.phone, "role" to "DOCTOR", "updatedAt" to System.currentTimeMillis()
                ), conflictColumn = "id")
                viewModel.setCachedUserProfile(
                    name = field("name").ifBlank { user.name }, phone = field("phone").ifBlank { user.phone },
                    email = user.email, doctorName = field("doctor_name").ifBlank { user.name }, accountId = user.id
                )
                viewModel.markProfileSetupComplete()
            }
            awaitingGoogle = false
            if (viewModel.hasDoctorPinConfigured() || viewModel.hasPinConfigured(UserRole.ASSISTANT)) {
                needsLock = true
            } else {
                viewModel.setUserRole(UserRole.DOCTOR)
                onUnlocked()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            error = if (e is com.example.sync.AccountMismatchException) e.message else t("ورود کامل نشد. اتصال اینترنت را بررسی و دوباره تلاش کنید.", "Sign-in could not finish. Check your connection and try again.")
        } finally { busy = false }
    }

    LaunchedEffect(Unit) {
        if (Supabase.currentSession != null) completeSignIn() else busy = false
    }
    LaunchedEffect(Unit) {
        viewModel.pendingOAuthCode.collect { code ->
            if (code.isNullOrBlank()) return@collect
            viewModel.pendingOAuthCode.value = null
            busy = true
            try {
                Supabase.exchangeOAuthCode(code)
                completeSignIn()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Supabase.clearPkceVerifier()
                error = t("ورود گوگل کامل نشد؛ دوباره تلاش کنید.", "Google sign-in failed. Please try again.")
                busy = false
                awaitingGoogle = false
            }
        }
    }
    if (needsLock) {
        LockScreen(viewModel, onUnlocked)
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("DentalPro", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(t("مدیریت مطب، با حساب گوگل شما", "Your clinic, with your Google account"), textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            if (busy) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(status.ifBlank { t("در حال بررسی ورود…", "Checking your session…") }, textAlign = TextAlign.Center)
            } else {
                Button(onClick = {
                    error = null
                    try {
                        val url = Supabase.buildGoogleAuthorizeUrl(Supabase.generatePkceVerifier())
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        awaitingGoogle = true
                    } catch (e: Exception) { error = t("مرورگر باز نشد. یک مرورگر نصب و دوباره تلاش کنید.", "Could not open a browser. Install one and retry.") }
                }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(t("ادامه با گوگل", "Continue with Google"))
                }
                if (awaitingGoogle) Text(t("ورود را در مرورگر کامل کنید. برای شروع دوباره، دکمه بالا را بزنید.", "Finish signing in in your browser, or tap above to start again."), modifier = Modifier.padding(top = 16.dp), textAlign = TextAlign.Center)
                if (Supabase.currentSession != null && error != null) TextButton(onClick = { scope.launch { completeSignIn() } }) { Text(t("تلاش دوباره برای دریافت اطلاعات", "Retry syncing your data")) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 20.dp), textAlign = TextAlign.Center) }
            Spacer(Modifier.height(24.dp))
            Text(t("برای بازیابی اطلاعات، همیشه با همان حساب گوگل وارد شوید.", "Use the same Google account to restore your data."), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}
