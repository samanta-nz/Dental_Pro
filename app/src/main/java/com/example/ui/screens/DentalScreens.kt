@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.camera.core.ExperimentalGetImage::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle



import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.setValue

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import kotlin.OptIn
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.*
import com.example.reports.*
import com.example.ui.DentalViewModel
import kotlinx.coroutines.launch
import com.example.ui.theme.*
import java.util.Locale
import coil.compose.AsyncImage
import java.io.File
import android.net.Uri
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard

fun getPatientFormattedName(name: String, gender: String, isPersian: Boolean = true): String {
    val cleanName = name.trim()
    if (cleanName.isBlank()) return ""
    
    val isFemale = gender == "زن" || gender == "خانم" || gender.lowercase() == "female" || cleanName.startsWith("خانم") || cleanName.startsWith("Mrs") || cleanName.startsWith("Ms")
    
    if (cleanName.startsWith("آقای") || cleanName.startsWith("خانم") || cleanName.startsWith("Mr.") || cleanName.startsWith("Mrs.") || cleanName.startsWith("Ms.")) {
        return cleanName
    }
    
    val prefix = if (isPersian) {
        if (isFemale) "خانم " else "آقای "
    } else {
        if (isFemale) "Mrs. " else "Mr. "
    }
    return "$prefix$cleanName"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDentalScreen(viewModel: DentalViewModel, initialBankSms: String? = null, initialOpenAppUpdate: Boolean = false) {
    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    val drugTemplates by viewModel.drugTemplates.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val currentViewingImage by viewModel.currentViewingImage.collectAsStateWithLifecycle()
    val navController = androidx.navigation.compose.rememberNavController()
    
    // لمس نوتیفیکیشن پیامک بانکی → همان پاپ‌آپ کامل ثبت (ماشین‌حساب + تخصیص چندردیفی)
    // که برای آیتم‌های صف «پیامک‌های اسکن‌شده/ثبت‌نشده» هم استفاده می‌شود.
    var pendingBankSmsText by remember(initialBankSms) { mutableStateOf(initialBankSms) }

    if (!pendingBankSmsText.isNullOrBlank()) {
        val smsText = pendingBankSmsText!!
        val parsed = remember(smsText) {
            com.example.util.BankSmsEngine.evaluateSms(smsText, "")
        }
        if (parsed != null && parsed.amountTomans > 0) {
            BankSmsTransactionDialog(
                viewModel = viewModel,
                isPersian = isPersian,
                info = BankSmsTxnInfo(
                    bankName = parsed.bankName.ifBlank { "بانک" },
                    type = parsed.transactionType.ifBlank { "DEPOSIT" },
                    amountTomans = parsed.amountTomans,
                    dateStr = parsed.dateStr,
                    timeStr = parsed.timeStr,
                    accountNumberRaw = parsed.accountNumberRaw,
                    rawText = parsed.rawSms.ifBlank { smsText },
                    score = parsed.score
                ),
                onDismiss = { pendingBankSmsText = null }
            )
        } else {
            // اگر پارس کامل نشد، فرم دستی با متن خام پیامک (fallback)
            BankSmsParserDialog(
                viewModel = viewModel,
                isPersian = isPersian,
                initialSmsText = smsText,
                onDismiss = { pendingBankSmsText = null }
            )
        }
    }

    // سیستم کارتخوان (درخواست کاربر): اگر از روزهای قبل تراکنش کارتخوان تسویه‌نشده باشد،
    // در شروع اپ پاپ‌آپ تسویه‌ی روزانه نمایش داده می‌شود تا حساب مقصد واریز انتخاب شود.
    val hasUnsettledPos by viewModel.hasUnsettledPosFromPreviousDays.collectAsStateWithLifecycle()
    var posDialogDismissed by remember { mutableStateOf(false) }
    if (hasUnsettledPos && !posDialogDismissed) {
        PosSettlementDialog(
            viewModel = viewModel,
            isPersian = isPersian,
            autoOpenedForPreviousDays = true,
            onDismiss = { posDialogDismissed = true }
        )
    }

    // بروزرسانی درون‌برنامه‌ای: اگر سرور نسخه جدیدی اعلام کرده باشد (یا کاربر روی اعلان
    // «بروزرسانی جدید» لمس کرده باشد)، دیالوگ دانلود و نصب نمایش داده می‌شود.
    val availableAppUpdate by viewModel.availableAppUpdate.collectAsStateWithLifecycle()
    var appUpdateDialogDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(initialOpenAppUpdate) {
        if (initialOpenAppUpdate) {
            appUpdateDialogDismissed = false
            viewModel.checkForAppUpdate(notify = false)
        }
    }
    if (availableAppUpdate != null) {
        AppUpdateDialog(
            info = availableAppUpdate!!,
            isPersian = isPersian,
            onDismiss = { viewModel.availableAppUpdate.value = null }
        )
    }

    // بروزرسانی PATCH (رفع باگ): بنر متنی خود-حذف‌شونده در استارت اپ — روزی یک‌بار
    val patchUpdateNotice by viewModel.patchUpdateNotice.collectAsStateWithLifecycle()
    if (patchUpdateNotice != null && availableAppUpdate == null) {
        PatchUpdateAutoBanner(
            info = patchUpdateNotice!!,
            isPersian = isPersian,
            onTap = {
                // لمس بنر → دیالوگ کامل دانلود و نصب
                viewModel.availableAppUpdate.value = patchUpdateNotice
                appUpdateDialogDismissed = false
                viewModel.dismissPatchUpdateNotice()
            },
            onDismiss = { viewModel.dismissPatchUpdateNotice() }
        )
    }
    
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isGoogleDriveConnected by viewModel.isGoogleDriveConnected.collectAsStateWithLifecycle(initialValue = false)
    val needsProfileCompletion = isGoogleDriveConnected && settings.clinicName.isBlank()
    
    if (needsProfileCompletion) {
        ProfileCompletionDialog(viewModel = viewModel, onComplete = { })
    }

    if (currentViewingImage != null) {
        FullScreenImageViewer(imageStr = currentViewingImage!!) {
            viewModel.currentViewingImage.value = null
        }
    }

    var showConnectionStatusDialog by remember { mutableStateOf(false) }
    var showClinicAccountDialog by remember { mutableStateOf(false) }

    if (showConnectionStatusDialog) {
        ConnectionStatusDialog(
            viewModel = viewModel,
            onDismiss = { showConnectionStatusDialog = false }
        )
    }

    if (showClinicAccountDialog) {
        ClinicAccountDialog(
            viewModel = viewModel,
            onNavigateToAccount = { navController.navigate("account") },
            onNavigateToSettings = { viewModel.currentTab.value = "settings" },
            onNavigateToExclusiveSettings = { viewModel.currentTab.value = "exclusive_settings" },
            onDismiss = { showClinicAccountDialog = false }
        )
    }
    val neonPalette = LocalNeonPalette.current
    val snackbarHostState = remember { SnackbarHostState() }
    val layoutDirection = if (isPersian) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, LocalSnackbarHostState provides snackbarHostState) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        val currentSettings by viewModel.settings.collectAsStateWithLifecycle()
                        if (!currentSettings.logoFileId.isNullOrBlank()) {
                            SmartImage(
                                fileId = currentSettings.logoFileId,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .clickable { showClinicAccountDialog = true }
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.img_app_icon),
                                contentDescription = "Logo",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .clickable { showClinicAccountDialog = true },
                                contentScale = ContentScale.Crop
                            )
                        }
                            Text(
                                text = viewModel.label("app_title"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        val isOnline by viewModel.syncEngine.isOnline.collectAsStateWithLifecycle()
                        IconButton(
                            onClick = { showConnectionStatusDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isOnline) androidx.compose.material.icons.Icons.Default.Wifi else androidx.compose.material.icons.Icons.Default.CloudOff,
                                contentDescription = if (isOnline) "Online" else "Offline",
                                tint = if (isOnline) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color.Gray,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        val activeRole by viewModel.currentUserRole.collectAsStateWithLifecycle()
                        var roleMenuExpanded by remember { mutableStateOf(false) }
                        var showDoctorAuthDialog by remember { mutableStateOf(false) }
                        var pendingTargetRole by remember { mutableStateOf<com.example.feature.auth.UserRole?>(null) }

                        if (showDoctorAuthDialog && pendingTargetRole != null) {
                            com.example.ui.components.DoctorRoleAuthDialog(
                                viewModel = viewModel,
                                targetRole = pendingTargetRole!!,
                                onDismiss = {
                                    showDoctorAuthDialog = false
                                    pendingTargetRole = null
                                },
                                onSuccess = {
                                    showDoctorAuthDialog = false
                                    pendingTargetRole = null
                                }
                            )
                        }

                        Box {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .clickable { roleMenuExpanded = true }
                                    .testTag("topbar_role_chip")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        imageVector = if (activeRole == com.example.feature.auth.UserRole.DOCTOR) Icons.Default.Lock else Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = activeRole.titleFa,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = roleMenuExpanded,
                                onDismissRequest = { roleMenuExpanded = false }
                            ) {
                                com.example.feature.auth.UserRole.entries.forEach { role ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (role == com.example.feature.auth.UserRole.DOCTOR) {
                                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                                } else {
                                                    Icon(Icons.Default.Badge, contentDescription = null, modifier = Modifier.size(14.dp))
                                                }
                                                Text(
                                                    text = role.titleFa,
                                                    fontWeight = if (activeRole == role) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        },
                                        onClick = {
                                            roleMenuExpanded = false
                                            if (activeRole != role) {
                                                pendingTargetRole = role
                                                showDoctorAuthDialog = true
                                            }
                                        },
                                        trailingIcon = if (activeRole == role) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null
                                    )
                                }
                            }
                        }
                        // دکمه تنظیمات — کلیک معمولی برای «تنظیمات مطب»
                        // لمس طولانی (۲ ثانیه) برای «تنظیمات اختصاصی نرم‌افزار» (بازگردانی منوی مخفی/اختصاصی)
                        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .combinedClickable(
                                    onClick = { viewModel.currentTab.value = "settings" },
                                    onLongClick = { viewModel.currentTab.value = "exclusive_settings" }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    val dashboardLabel = viewModel.label("dashboard")
                    val patientsLabel = viewModel.label("patients")
                    val appointmentsLabel = viewModel.label("appointments")
                    val moreLabel = if (isPersian) "بخش‌های دیگر" else "More Modules"

                    NavigationBarItem(
                        selected = currentTab == "dashboard",
                        onClick = { viewModel.currentTab.value = "dashboard" },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = dashboardLabel) },
                        label = { Text(dashboardLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                    if (viewModel.hasModuleAccess("patients")) {
                        NavigationBarItem(
                            selected = currentTab == "patients",
                            onClick = {
                                viewModel.selectedPatientId.value = null
                                viewModel.currentTab.value = "patients"
                            },
                            icon = { Icon(Icons.Default.ContactPage, contentDescription = patientsLabel) },
                            label = { Text(patientsLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                    if (viewModel.hasModuleAccess("appointments")) {
                        NavigationBarItem(
                            selected = currentTab == "appointments",
                            onClick = { viewModel.currentTab.value = "appointments" },
                            icon = { Icon(Icons.Default.CalendarToday, contentDescription = appointmentsLabel) },
                            label = { Text(appointmentsLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                    NavigationBarItem(
                        selected = currentTab in listOf("vendors", "labs", "maintenance", "expenses", "personal", "more", "templates", "bank", "settings", "exclusive_settings", "financial", "cheques", "reports"),
                        onClick = { viewModel.currentTab.value = "more" },
                        icon = { Icon(Icons.Default.Menu, contentDescription = moreLabel) },
                        label = { Text(moreLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                com.example.ui.navigation.DentalNavGraph(
                    navController = navController,
                    viewModel = viewModel
                )
            }
        }
    }
}

// ==========================================
// 1. DASHBOARD SCREEN
// ==========================================
@Composable
fun DashboardScreen(viewModel: DentalViewModel) {
    val activeRole by viewModel.currentUserRole.collectAsStateWithLifecycle()
    val patients by viewModel.patients.collectAsStateWithLifecycle()
    val allTreatments by viewModel.allTreatments.collectAsStateWithLifecycle()
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val allPayments by viewModel.allPayments.collectAsStateWithLifecycle()
    val personalTx by viewModel.personalTransactions.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val expenses by viewModel.clinicExpenses.collectAsStateWithLifecycle()

    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    val drugTemplates by viewModel.drugTemplates.collectAsStateWithLifecycle(initialValue = emptyList())
    val currency = viewModel.label("currency")

    // حساب‌های بانکی برای «موجودی کل بانک‌ها» (رفع باگ ۱-۲: قبلاً این کارت جمع تراکنش‌های
    // شخصی را نشان می‌داد، نه موجودی واقعی بانک‌ها)
    val bankAccounts by viewModel.bankAccounts.collectAsStateWithLifecycle()
    val totalBankBalance = remember(bankAccounts) {
        bankAccounts.filter { !it.isDeleted && !it.isCashFund }.sumOf { it.balance }
    }

    // Calculations
    val clinicIncome = allPayments.postedTotal()

    // فقط چکِ «در جریان وصول» — انتقال‌یافته و برگشتی و پاس‌شده در این رقم نمی‌آیند.
    val unclearedCheques = allPayments.filter { it.type == "CHEQUE" && !it.isDeleted && it.chequeStatus == "PENDING" }.sumOf { it.amount }

    // Advanced Calculations for Visual Gradient Charts
    // رفع باگ ۱-۲: «کل مطالبات بیماران» باید بیمار‌به‌بیمار محاسبه شود و «بستانکاری»
    // (پرداخت اضافه/بیعانه بدون طرح درمان) هرگز محاسبه نشود — قبلاً تراز سراسری
    // (جمع هزینه‌ها منهای جمع پرداخت‌ها) گرفته می‌شد که هم بستانکاری را پنهان می‌کرد
    // و هم بدهی یک بیمار را با بستانکاری بیمار دیگر تهاتر می‌کرد.
    val outstandingDebt = remember(patients, allTreatments, allPayments) {
        patients.filter { !it.isDeleted }.sumOf { p ->
            val cost = allTreatments.filter { it.patientId == p.id && !it.isDeleted }
                .sumOf { it.netCost }
            val paid = allPayments.postedTotalFor(p.id)
            (cost - paid).coerceAtLeast(0L) // بستانکاری بیمار هرگز به‌حساب نمی‌آید
        }
    }
    val totalExpenses = expenses.sumOf { it.amount }

    // تعداد بیماران بدهکار — برای جمع‌بندی نمودار مالی.
    val debtorCount = remember(patients, allTreatments, allPayments) {
        patients.filter { !it.isDeleted }.count { p ->
            val cost = allTreatments.filter { it.patientId == p.id && !it.isDeleted }
                .sumOf { it.netCost }
            cost - allPayments.postedTotalFor(p.id) > 0L
        }
    }

    val todayAppointmentsCount = remember(appointments) {
        val now = System.currentTimeMillis()
        appointments.count { com.example.util.PersianCalendarHelper.isSameDay(it.date, now) }
    }

    // Quick Patient Add Form State
    var showQuickPatientDialog by remember { mutableStateOf(false) }

    // لیست پیامک‌های بانکی (ثبت‌نشده/ثبت‌شده/نادیده‌گرفته/سطل آشغال) — جایگزین «ثبت هزینه» (درخواست ۱-۳)
    var showBankSmsManagerDialog by remember { mutableStateOf(false) }

    // چیدمان کارت‌های داشبورد (درخواست ۱۰) — از settings.dashboardCardOrder
    val dashboardOrderKeys = remember(settings.dashboardCardOrder) {
        val custom = settings.dashboardCardOrder.split(",").map { it.trim() }
            .filter { it.isNotBlank() && it != "VIEW_SWITCH" }
        if (custom.isNotEmpty()) custom else listOf("HERO", "SUMMARY", "CHARTS", "QUICK_ACTIONS", "CALENDAR")
    }
    fun dashboardSectionEnabled(key: String): Boolean = when (key) {
        "SUMMARY" -> settings.showSummaryCard
        "CHARTS" -> settings.showCharts
        "QUICK_ACTIONS" -> settings.showQuickActions
        else -> true
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Image Illustration Card (with custom clinic/doctor branding + neon glow)
        item {
            val neonPalette = LocalNeonPalette.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // ارتفاع به محتوا داده می‌شود تا فاصلهٔ اضافی نیفتد؛ wrapContentHeight
                    // با یک کف کوچک، جای بنر را دقیقاً اندازهٔ چهار سطر متن می‌کند.
                    .wrapContentHeight()
                    .heightIn(min = 108.dp)
                    .neonGlow(neonPalette.glow, RoundedCornerShape(16.dp), elevation = 22.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(neonPalette.heroBrush())
                    .border(1.dp, neonPalette.glow.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Soft radial glow accent behind the circular logo on the LEFT side.
                    // ساعت آنالوگ روی همین لوگو کشیده می‌شود: بخش‌هایی از عقربه‌ها که
                    // روی لوگو می‌افتند با شفافیت ۳۰٪ و بخش‌های بیرونِ لوگو با ۱۰۰٪.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(78.dp)
                            .background(neonPalette.glowRadialBrush(), shape = CircleShape)
                    ) {
                        com.example.ui.components.AnalogClockOverlay(
                            size = 78.dp,
                            logoDiameter = 68.dp,
                            insideLogoAlpha = 0.70f,
                            outsideLogoAlpha = 0.70f
                        ) {
                            if (!settings.logoFileId.isNullOrBlank()) {
                                SmartImage(
                                    fileId = settings.logoFileId,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.img_app_icon),
                                    contentDescription = "Clinic Logo",
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, Color.White.copy(alpha = 0.8f), CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (settings.clinicName.isNotBlank()) settings.clinicName else (if (isPersian) "کلینیک دندانپزشکی دیجیتال" else "Digital Dental Clinic"),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isPersian) "نرم‌افزار جامع مدیریت مطب دندانپزشکی" else "Complete Dental Practice Management",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // تاریخ شمسی «شنبه 10 مهر 1401» و ساعت hh:mm:ss با قبل/بعد از ظهر
                        val bannerTick by com.example.ui.components.rememberClockTick()
                        val bannerDate = remember(bannerTick.hour24, isPersian) {
                            com.example.ui.components.formatJalaliBannerDate(isPersian = isPersian)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = bannerDate,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(14.dp)
                            )
                            // هر رقم در یک سلول هم‌عرض رندر می‌شود، پس با عوض‌شدن ثانیه
                            // نه پهنای رقم و نه پهنای کل سطر تغییر نمی‌کند؛ ساعت و
                            // «قبل از ظهر» هرگز تکان نمی‌خورند (مستقل از فونت فارسی/لاتین).
                            com.example.ui.components.StableClockText(
                                tick = bannerTick,
                                isPersian = isPersian,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = com.example.ui.components.formatMeridiem(bannerTick, isPersian),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        // بخش‌های قابل مرتب‌سازی داشبورد — ترتیب از settings.dashboardCardOrder (درخواست ۱۰)
        // (بنر بالا «HERO» همیشه ثابت است)
        dashboardOrderKeys.forEach { key ->
            when (key) {
                "SUMMARY" -> if (dashboardSectionEnabled("SUMMARY")) {
                    item {
                        Text(
                            text = viewModel.label("summary"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StatCard(
                                    title = viewModel.label("total_patients"),
                                    value = "${patients.size}",
                                    icon = Icons.Default.People,
                                    modifier = Modifier.weight(1f),
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                                StatCard(
                                    title = viewModel.label("today_appointments"),
                                    value = "$todayAppointmentsCount",
                                    icon = Icons.Default.Event,
                                    modifier = Modifier.weight(1f),
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            }
                            // «کل دریافتی مطب» و «موجودی کل بانک‌ها» هرکدام کل پهنای سطر را
                            // می‌گیرند (زیر هم)، تا مبلغ و واحد کنارِ هم و کامل در یک خط جا شوند
                            // و دیگر بریده/چندخطی نشوند.
                            StatCard(
                                title = viewModel.label("clinic_income"),
                                value = "${formatAmount(clinicIncome)} $currency",
                                icon = Icons.Default.AttachMoney,
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                valueColor = Color(0xFF388E3C)
                            )
                            // موجودی بانک‌ها: به‌صورت پیش‌فرض ماسک (نقطه‌چین) نمایش داده
                            // می‌شود؛ آیکون چشم برای آشکارسازی فقط برای «پزشک» دیده می‌شود.
                            val isDoctorRole = activeRole == com.example.feature.auth.UserRole.DOCTOR
                            var bankBalanceVisible by remember(isDoctorRole) { mutableStateOf(false) }
                            val showBalance = isDoctorRole && bankBalanceVisible
                            StatCard(
                                title = viewModel.label("personal_balance"),
                                value = if (showBalance) "${formatAmount(totalBankBalance)} $currency" else "••••••••",
                                icon = Icons.Default.AccountBalance,
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.surface,
                                valueColor = MaterialTheme.colorScheme.primary,
                                trailing = if (isDoctorRole) ({
                                    IconButton(
                                        onClick = { bankBalanceVisible = !bankBalanceVisible },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (bankBalanceVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (bankBalanceVisible) "مخفی‌کردن موجودی" else "نمایش موجودی",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }) else null
                            )
                            if (unclearedCheques > 0) {
                                StatCard(
                                    title = viewModel.label("uncleared_cheques"),
                                    value = "${formatAmount(unclearedCheques)} $currency",
                                    icon = Icons.Default.Payments,
                                    modifier = Modifier.fillMaxWidth(),
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    valueColor = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                "CHARTS" -> if (dashboardSectionEnabled("CHARTS")) {
                    item {
                        ClinicFinancialGradientChart(
                            revenue = clinicIncome,
                            outstanding = outstandingDebt,
                            expenses = totalExpenses,
                            isPersian = isPersian,
                            payments = allPayments,
                            clinicExpenses = expenses,
                            unclearedCheques = unclearedCheques,
                            debtorCount = debtorCount
                        )
                    }
                }

                "QUICK_ACTIONS" -> if (dashboardSectionEnabled("QUICK_ACTIONS")) {
                    item {
                        Text(
                            text = viewModel.label("quick_add"),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickActionButton(
                                label = viewModel.label("quick_patient"),
                                icon = Icons.Default.PersonAdd,
                                onClick = { showQuickPatientDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                            QuickActionButton(
                                label = viewModel.label("quick_appointment"),
                                icon = Icons.Default.AddAlarm,
                                onClick = { viewModel.currentTab.value = "appointments" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // درخواست ۱-۳: «ثبت هزینه» حذف شد و این سه جایگزین آن شدند:
                    // پیامک‌ها (لیست پیامک‌های بانکی)، مدیریت چک‌ها، ثبت سفارش پروتز
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QuickActionButton(
                                label = if (isPersian) "پیامک‌ها" else "Bank SMS",
                                icon = Icons.Default.Sms,
                                onClick = { showBankSmsManagerDialog = true },
                                modifier = Modifier.weight(1f)
                            )
                            QuickActionButton(
                                label = if (isPersian) "مدیریت چک‌ها" else "Cheques",
                                icon = Icons.Default.ConfirmationNumber,
                                onClick = { viewModel.currentTab.value = "cheques" },
                                modifier = Modifier.weight(1f)
                            )
                            QuickActionButton(
                                label = if (isPersian) "ثبت سفارش پروتز" else "Lab Order",
                                icon = Icons.Default.Science,
                                onClick = { viewModel.currentTab.value = "labs" },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                "CALENDAR" -> if (dashboardSectionEnabled("CALENDAR")) {
                    item {
                        InteractiveAppointmentsCalendar(viewModel = viewModel, isPersian = isPersian)
                    }
                }
            }
        }

        // Quick Paste banking SMS detector on Dashboard!
    }

    // Quick Add Dialogs
    if (showQuickPatientDialog) {
        AddPatientDialog(
            viewModel = viewModel,
            onDismiss = { showQuickPatientDialog = false }
        )
    }

    if (showBankSmsManagerDialog) {
        UnregisteredBankSmsManagerDialog(
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { showBankSmsManagerDialog = false }
        )
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
            }
            if (trailing != null) trailing()
        }
    }
}

@Composable
fun QuickActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(80.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==========================================
// 2. PATIENTS FILE MANAGER (DENTAL RECORDS)
// ==========================================
@Composable
fun PatientsScreen(viewModel: DentalViewModel) {
    val patients by viewModel.patients.collectAsStateWithLifecycle()
    val allTreatments by viewModel.allTreatments.collectAsStateWithLifecycle()
    val allPayments by viewModel.allPayments.collectAsStateWithLifecycle()
    val selectedPatientId by viewModel.selectedPatientId.collectAsStateWithLifecycle()
    val selectedPatient by viewModel.selectedPatient.collectAsStateWithLifecycle()
    val treatments by viewModel.selectedPatientTreatments.collectAsStateWithLifecycle()
    val payments by viewModel.selectedPatientPayments.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var patientToDelete by remember { mutableStateOf<Patient?>(null) }
    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    val drugTemplates by viewModel.drugTemplates.collectAsStateWithLifecycle(initialValue = emptyList())
    val archivedPatients by viewModel.archivedPatients.collectAsStateWithLifecycle()
    // بایگانی: false = لیست فعال (بایگانی‌نشده)؛ true = لیست آرشیو
    var showArchived by remember { mutableStateOf(false) }
    var patientToArchive by remember { mutableStateOf<Patient?>(null) }
    var patientToUnarchive by remember { mutableStateOf<Patient?>(null) }

    var filterCategory by remember { mutableStateOf("CURRENT") }

    // منبع لیست: اگر در حالت آرشیو هستیم فقط بایگانی‌شده‌ها، وگرنه فقط بایگانی‌نشده‌ها
    val activePatientList = remember(patients) { patients.filter { !it.isDeleted && !it.isArchived } }
    val archivedPatientList = remember(archivedPatients) { archivedPatients.filter { !it.isDeleted } }

    // بدهکاران: بیمارانی که جمع هزینه درمان‌هایشان (منهای تخفیف) بیشتر از پرداخت‌های
    // مؤثرشان است — یعنی مانده‌ی بدهی مثبت دارند.
    val debtorBalances = remember(activePatientList, allTreatments, allPayments) {
        activePatientList.associate { p ->
            val totalCost = allTreatments.filter { it.patientId == p.id && !it.isDeleted }.sumOf { it.netCost }
            val totalPaid = allPayments.filter { it.patientId == p.id }.postedTotal()
            p.id to (totalCost - totalPaid)
        }
    }

    // بیماران جاری: بیمارانی که دست‌کم یک تراپی ناتمام (IN_PROGRESS) یا شروع‌نشده (PLANNED) دارند
    val currentPatientIds = remember(activePatientList, allTreatments) {
        allTreatments
            .filter { !it.isDeleted && (it.status == "IN_PROGRESS" || it.status == "PLANNED") }
            .map { it.patientId }
            .toSet()
    }

    val filteredPatients = remember(activePatientList, archivedPatientList, allTreatments, allPayments, searchQuery, filterCategory, showArchived, debtorBalances, currentPatientIds) {
        val q = searchQuery.trim()
        val patients = if (showArchived) archivedPatientList else activePatientList
        val baseList = if (q.isBlank()) {
            patients
        } else {
            patients.filter { p ->
                p.name.contains(q, ignoreCase = true) ||
                p.phone.contains(q) ||
                p.patientCode.contains(q, ignoreCase = true) ||
                p.id.toString() == q ||
                p.medicalHistory.contains(q, ignoreCase = true) ||
                p.referrer.contains(q, ignoreCase = true) ||
                allTreatments.any { t ->
                    t.patientId == p.id && (
                        t.title.contains(q, ignoreCase = true) ||
                        t.selectedTeeth.contains(q) ||
                        t.notes.contains(q, ignoreCase = true)
                    )
                }
            }
        }

        when (filterCategory) {
            "DEBTORS" -> {
                // لیست بیماران بدهکار — مرتب‌شده بر اساس مبلغ بدهی (بیشترین اول)
                baseList
                    .filter { (debtorBalances[it.id] ?: 0L) > 0L }
                    .sortedByDescending { debtorBalances[it.id] ?: 0L }
            }
            "CURRENT" -> {
                // بیماران جاری (تراپی ناتمام یا شروع‌نشده) — به ترتیب تاریخ ثبت پذیرش (قدیمی‌ترین اول)
                baseList
                    .filter { currentPatientIds.contains(it.id) }
                    .sortedBy { it.createdAt }
            }
            "HIGH_RISK" -> {
                baseList.filter { it.medicalAlerts.isNotBlank() && it.medicalAlerts != "بدون مورد" }
            }
            else -> baseList
        }
    }

    if (selectedPatientId != null && selectedPatient != null) {
        // Patient Detailed Medical & Billing View (Interactive split layout)
        PatientDetailsView(
            patient = selectedPatient!!,
            treatments = treatments,
            payments = payments,
            viewModel = viewModel,
            onBack = { viewModel.selectedPatientId.value = null }
        )
    } else {
        // List of Patients Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = viewModel.label("patients"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    // دکمه «آرشیو» — جابه‌جایی بین لیست فعال و لیست آرشیو
                    OutlinedButton(
                        onClick = { showArchived = !showArchived },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (showArchived) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            if (isPersian) (if (showArchived) "بازگشت به بیماران" else "آرشیو") else (if (showArchived) "Back" else "Archive"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (!showArchived) {
                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(viewModel.label("add_new_patient"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(viewModel.label("search_patient")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Smart Directory Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    // «بیماران جاری» قبل از «همه بیماران» (درخواست کاربر) — معاینه ۶ ماهه حذف شد
                    "CURRENT" to (if (isPersian) "بیماران فعال" else "Active Patients"),
                    "ALL" to (if (isPersian) "همه بیماران" else "All"),
                    "DEBTORS" to (if (isPersian) "بدهکاران" else "Debtors"),
                    "HIGH_RISK" to (if (isPersian) "هشدار پزشکی" else "High Risk")
                ).forEach { (cat, label) ->
                    FilterChip(
                        selected = filterCategory == cat,
                        onClick = { filterCategory = cat },
                        label = { Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                    )
                }
            }

            if (filteredPatients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (showArchived) {
                        com.example.ui.components.EmptyStateView(
                            title = if (isPersian) "آرشیو خالی است" else "Archive is empty",
                            subtitle = if (isPersian) "بیماران بایگانی‌شده در اینجا نمایش داده می‌شوند." else "Archived patients appear here.",
                            actionLabel = if (isPersian) "بازگشت به بیماران" else "Back to patients",
                            onAction = { showArchived = false }
                        )
                    } else {
                        com.example.ui.components.EmptyStateView(
                            title = if (isPersian) "پرونده‌ای یافت نشد" else "No patient records found",
                            subtitle = if (isPersian) "برای ثبت اولین پرونده بیمار، دکمه «افزودن بیمار جدید» را لمس کنید." else "Click 'Add New Patient' to create a patient record.",
                            actionLabel = viewModel.label("add_new_patient"),
                            onAction = { showAddDialog = true }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPatients) { patient ->
                        PatientListItem(
                            patient = patient,
                            isPersian = isPersian,
                            onClick = {
                                viewModel.selectedPatientId.value = patient.id
                            },
                            onDelete = {
                                patientToDelete = patient
                            },
                            isArchivedView = showArchived,
                            onArchive = {
                                patientToArchive = patient
                            },
                            onUnarchive = {
                                patientToUnarchive = patient
                            }
                        )
                    }
                }
            }
        }
    }

    if (patientToDelete != null) {
        val pName = getPatientFormattedName(patientToDelete!!.name, patientToDelete!!.gender, isPersian)
        AlertDialog(
            onDismissRequest = { patientToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = if (isPersian) "تایید حذف پرونده بیمار" else "Confirm Patient Deletion",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Text(
                    text = if (isPersian) "آیا از حذف کامل پرونده «$pName» و تمامی سوابق درمانی، نوبت‌ها و پرداخت‌های مرتبط اطمینان دارید؟ این عملیات غیرقابل بازگشت است."
                    else "Are you sure you want to completely delete patient file '$pName' and all associated treatments, appointments, and payments? This action cannot be undone.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deletePatient(patientToDelete!!)
                        patientToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف کامل پرونده" else "Delete Patient File", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { patientToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    if (patientToArchive != null) {
        val pName = getPatientFormattedName(patientToArchive!!.name, patientToArchive!!.gender, isPersian)
        AlertDialog(
            onDismissRequest = { patientToArchive = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Text(
                        text = if (isPersian) "بایگانی پرونده بیمار" else "Archive Patient",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            },
            text = {
                Text(
                    text = if (isPersian)
                        "آیا می‌خواهید پرونده «$pName» را بایگانی کنید؟ بیمار بایگانی‌شده از لیست‌های فعال (نوبت، پرداخت و...) مخفی و در «لیست آرشیو» قرار می‌گیرد. داده‌ها حذف نمی‌شوند."
                    else "Archive patient file '$pName'? Archived patients are hidden from active lists (appointments, payments, etc.) and moved to the Archive. No data is deleted.",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.archivePatient(patientToArchive!!)
                        patientToArchive = null
                    }
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPersian) "بایگانی" else "Archive", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { patientToArchive = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    if (patientToUnarchive != null) {
        val pName = getPatientFormattedName(patientToUnarchive!!.name, patientToUnarchive!!.gender, isPersian)
        AlertDialog(
            onDismissRequest = { patientToUnarchive = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Unarchive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = if (isPersian) "بازگردانی از آرشیو" else "Unarchive Patient",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            },
            text = {
                Text(
                    text = if (isPersian)
                        "پرونده «$pName» از آرشیو به لیست فعال بیماران بازگردانده شود؟"
                    else "Return patient '$pName' from Archive back to the active patient list?",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.unarchivePatient(patientToUnarchive!!)
                        patientToUnarchive = null
                    }
                ) {
                    Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPersian) "بازگردانی" else "Unarchive", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { patientToUnarchive = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    if (showAddDialog) {
        AddPatientDialog(viewModel = viewModel, onDismiss = { showAddDialog = false })
    }
}

@Composable
fun PatientListItem(
    patient: Patient,
    isPersian: Boolean = true,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    isArchivedView: Boolean = false,
    onArchive: () -> Unit = {},
    onUnarchive: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isArchivedView) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val isFemale = patient.gender == "زن" || patient.gender == "خانم" || patient.gender.lowercase() == "female"
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (!patient.photoFileId.isNullOrBlank()) MaterialTheme.colorScheme.primaryContainer else if (isFemale) Color(0xFFFCE4EC) else Color(0xFFE3F2FD)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!patient.photoFileId.isNullOrBlank()) {
                        SmartImage(
                            fileId = patient.photoFileId,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = if (isFemale) Icons.Default.Woman else Icons.Default.Man,
                            contentDescription = null,
                            tint = if (isFemale) Color(0xFFC2185B) else Color(0xFF1565C0)
                        )
                    }
                }
                Column {
                    Text(text = getPatientFormattedName(patient.name, patient.gender, isPersian), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(text = patient.phone, fontSize = 12.sp, color = Color.Gray)
                    if (isArchivedView) {
                        Text(
                            text = if (isPersian) "در آرشیو" else "Archived",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isArchivedView) {
                    // بازگردانی از آرشیو به لیست فعال
                    IconButton(onClick = onUnarchive) {
                        Icon(Icons.Default.Unarchive, contentDescription = if (isPersian) "بازگردانی" else "Unarchive", tint = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    // بایگانی بیمار
                    IconButton(onClick = onArchive) {
                        Icon(Icons.Default.Archive, contentDescription = if (isPersian) "بایگانی" else "Archive", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.LightGray)
                }
            }
        }
    }
}

@Composable
fun TreatmentStageChecklistCard(
    treatment: Treatment,
    viewModel: DentalViewModel,
    readOnly: Boolean = false
) {
    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    val drugTemplates by viewModel.drugTemplates.collectAsStateWithLifecycle(initialValue = emptyList())
    val allStagesList = remember(treatment.stages) {
        parseStagesList(treatment.stages)
    }
    val completedStagesList = remember(treatment.completedStages) {
        parseStageMarkers(treatment.completedStages)
    }
    val ignoredStagesList = remember(treatment.ignoredStages) {
        parseStageMarkers(treatment.ignoredStages)
    }
    val resolvedStagesList = remember(completedStagesList, ignoredStagesList) {
        (completedStagesList + ignoredStagesList).distinct()
    }
    val allChecked = allStagesList.isNotEmpty() && resolvedStagesList.containsAll(allStagesList)

    // پاپ‌آپ نسخه دارویی پس از تکمیل هر جلسه جاری — قابل ویرایش و نیازمند تایید پزشک
    // پیش از نمایش، نسخه پیشنهادی در برابر سوابق پزشکی/هشدارها/داروهای مصرفی بیمار بررسی و
    // در صورت وجود تداخل یا منع مصرف اصلاح می‌شود؛ موارد لحاظ‌شده در پانوشت نکات نسخه درج می‌گردد.
    val patientsForRx by viewModel.patients.collectAsStateWithLifecycle()
    var pendingRxState by remember { mutableStateOf<PendingRxPopup?>(null) }

    fun onStageMarkedComplete(stageName: String) {
        viewModel.toggleTreatmentStage(treatment, stageName)
        val patient = patientsForRx.find { it.id == treatment.patientId }
        val baseSuggestion = com.example.data.PrescriptionKnowledgeBase.suggest(treatment.title, stageName)
            ?: com.example.data.DrugPrescription()
        val adjusted = com.example.data.PrescriptionKnowledgeBase.adjustForPatient(
            baseSuggestion,
            patient?.medicalHistory ?: "",
            patient?.medicalAlerts ?: ""
        )
        pendingRxState = PendingRxPopup(treatment = treatment, stageName = stageName, prescription = adjusted, patient = patient)
    }

    if (pendingRxState != null) {
        val state = pendingRxState!!
        PrescriptionApprovalDialog(
            viewModel = viewModel,
            treatment = state.treatment,
            patient = state.patient,
            stageName = state.stageName,
            suggested = state.prescription,
            onDismiss = { pendingRxState = null }
        )
    }

    if (allStagesList.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isPersian) "مراحل تراپی و درمان:" else "Therapy Stages:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (!readOnly) {
                        // Group/Batch Check Action
                        TextButton(
                            onClick = { viewModel.toggleAllTreatmentStages(treatment, !allChecked) },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                if (allChecked) Icons.Default.SelectAll else Icons.Default.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (allChecked) (if (isPersian) "خارج کردن همه" else "Uncheck All") else (if (isPersian) "تیک دسته‌ای همه" else "Batch Check All"),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${resolvedStagesList.size}/${allStagesList.size} ${if (isPersian) "مرحله (تکمیل/عدم نیاز)" else "resolved"}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Stage Progress bar incorporating completed + ignored stages
                val progress = if (allStagesList.isEmpty()) 0f else resolvedStagesList.size.toFloat() / allStagesList.size.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )

                // Single stage items
                allStagesList.forEach { stage ->
                    val isCompleted = completedStagesList.contains(stage)
                    val isIgnored = ignoredStagesList.contains(stage)
                    val isRepeated = stage.contains("تکرار جلسه قبل") || stage.contains("[تکراری]")

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (!readOnly) Modifier.clickable {
                                    if (isCompleted) viewModel.toggleTreatmentStage(treatment, stage) else onStageMarkedComplete(stage)
                                }
                                else Modifier
                            )
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isIgnored) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "Ignored",
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "$stage (عدم نیاز به این مرحله)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFD32F2F),
                                style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            )
                        } else if (isRepeated) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Repeated",
                                tint = Color(0xFFFF9800),
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stage,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE65100),
                                style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            )
                        } else if (isCompleted) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Done",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stage,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                            )
                        } else {
                            if (!readOnly) {
                                Checkbox(
                                    checked = false,
                                    onCheckedChange = { onStageMarkedComplete(stage) },
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                    .size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stage,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/** وضعیت پاپ‌آپ نسخه دارویی در انتظار بازبینی/تایید پزشک برای یک جلسه تکمیل‌شده. */
private data class PendingRxPopup(
    val treatment: Treatment,
    val stageName: String,
    val prescription: com.example.data.DrugPrescription,
    val patient: Patient?
)

/**
 * پاپ‌آپ نسخه دارویی پیشنهادی پس از تکمیل جلسه — کاملاً قابل ویرایش توسط پزشک.
 * نسخه پیشنهادی پیشاپیش در برابر سوابق پزشکی/هشدارها/داروهای مصرفی بیمار بررسی شده و
 * پانوشت «موارد احتیاطی لحاظ‌شده» در بخش نکات درج شده است. پس از تایید پزشک، نسخه در
 * پرونده بیمار ثبت می‌شود و امکان ارسال با پیامک یا واتساپ فراهم است.
 */
class RxItemEditState(rawText: String, globalDur: Int) {
    var title by mutableStateOf("")
    var dosage by mutableStateOf("")
    var timing by mutableStateOf("")
    var instructions by mutableStateOf("")
    var durationDays by mutableStateOf(globalDur.toString())

    init {
        if (rawText.isNotBlank()) {
            val clean = rawText.removePrefix("💊").removePrefix("🧴").removePrefix("➕").trim()
            if (clean.contains(" - ") || clean.contains(";;;")) {
                val sep = if (clean.contains(" - ")) " - " else ";;;"
                val p = clean.split(sep)
                title = p.getOrNull(0)?.trim() ?: ""
                dosage = p.getOrNull(1)?.trim() ?: ""
                timing = p.getOrNull(2)?.trim() ?: ""
                instructions = p.getOrNull(3)?.trim() ?: ""
                durationDays = p.getOrNull(4)?.trim().takeIf { !it.isNullOrBlank() } ?: globalDur.toString()
            } else {
                val parsed = parseRxToColumns(clean, globalDur)
                title = parsed.title
                dosage = parsed.dosage
                timing = parsed.timing
                instructions = parsed.instructions
                durationDays = globalDur.toString()
            }
        }
    }

    fun toRawString(): String {
        if (title.isBlank() && dosage.isBlank() && timing.isBlank() && instructions.isBlank()) return ""
        val dDays = durationDays.ifBlank { "5" }
        return "${title.trim()} - ${dosage.trim()} - ${timing.trim()} - ${instructions.trim()} - ${dDays.trim()}"
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxItemEditorRow(
    label: String, 
    state: RxItemEditState, 
    onDelete: (() -> Unit)? = null,
    templates: List<com.example.data.DrugTemplate> = emptyList(),
    isPersian: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row {
                if (templates.isNotEmpty()) {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.height(24.dp), contentPadding = PaddingValues(0.dp)) {
                        Text(if (isPersian) "انتخاب از فایل" else "Templates", fontSize = 10.sp)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        templates.forEach { t ->
                            DropdownMenuItem(
                                text = { Text("${t.title} (${t.dosage})", fontSize = 12.sp) },
                                onClick = {
                                    state.title = t.title
                                    state.dosage = t.dosage
                                    state.timing = t.timing
                                    state.instructions = t.instructions
                                    if (t.defaultDurationDays > 0) state.durationDays = t.defaultDurationDays.toString()
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = state.title, onValueChange = { state.title = it },
                label = { Text("عنوان (نام و دوز)") },
                placeholder = { Text("مثلاً: استامینوفن (AC) - 500mg") },
                modifier = Modifier.weight(2f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
            OutlinedTextField(
                value = state.durationDays, onValueChange = { state.durationDays = it },
                label = { Text("طول درمان (روز)") },
                modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = state.dosage, onValueChange = { state.dosage = it },
                label = { Text("مقادیر مصرف") },
                placeholder = { Text("مثلاً: هر ۶ ساعت ۱/۲ قرص") },
                modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
            OutlinedTextField(
                value = state.timing, onValueChange = { state.timing = it },
                label = { Text("زمان مصرف") },
                placeholder = { Text("مثلاً: بعد از نهار / ناشتا") },
                modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
        }
        OutlinedTextField(
            value = state.instructions, onValueChange = { state.instructions = it },
            label = { Text("دستورات متفرقه") },
            placeholder = { Text("دستورات و نکات تکمیلی مورد نیاز بیمار") },
            modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
        )
    }
}

@Composable
private fun PrescriptionApprovalDialog(
    viewModel: DentalViewModel,
    treatment: Treatment,
    patient: Patient?,
    stageName: String,
    suggested: com.example.data.DrugPrescription,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    val drugTemplates by viewModel.drugTemplates.collectAsStateWithLifecycle(initialValue = emptyList())

    var durationDaysStr by remember { mutableStateOf(suggested.durationDays.takeIf { it > 0 }?.toString() ?: "5") }
    val globalDur = durationDaysStr.toIntOrNull() ?: 5
    
    val analgesicState = remember { RxItemEditState(suggested.analgesic, globalDur) }
    val antibioticState = remember { RxItemEditState(suggested.antibiotic, globalDur) }
    val mouthwashState = remember { RxItemEditState(suggested.mouthwash, globalDur) }
    
    // Parse otherMeds (might contain multiple separated by |||)
    val otherMedsList = remember { mutableStateListOf<RxItemEditState>() }
    LaunchedEffect(Unit) {
        val parts = suggested.otherMeds.split("|||")
        for (p in parts) {
            if (p.isNotBlank()) {
                otherMedsList.add(RxItemEditState(p, globalDur))
            }
        }
        if (otherMedsList.isEmpty()) {
            otherMedsList.add(RxItemEditState("", globalDur))
        }
    }
    
    var notes by remember { mutableStateOf(suggested.notes) }
    var publicNote by remember { mutableStateOf(suggested.publicNote.ifBlank { "لطفاً داروها را سر ساعت و طبق دستور با آب کافی میل کنید. در صورت بروز هرگونه حساسیت پوستی یا گوارشی، با مطب تماس بگیرید." }) } 
    
    var savedId by remember { mutableStateOf<Long?>(null) }
    var isApproved by remember { mutableStateOf(false) }

    val patientName = patient?.name ?: "بیمار"
    val patientIdOrPhone = if (patient != null && patient.patientCode.isNotBlank()) patient.patientCode else patient?.phone ?: ""
    val dateStr = viewModel.formatDate(System.currentTimeMillis())

    // Live parsing for preview
    val parsedRows = remember(
        analgesicState.title, analgesicState.dosage, analgesicState.timing, analgesicState.instructions, analgesicState.durationDays,
        antibioticState.title, antibioticState.dosage, antibioticState.timing, antibioticState.instructions, antibioticState.durationDays,
        mouthwashState.title, mouthwashState.dosage, mouthwashState.timing, mouthwashState.instructions, mouthwashState.durationDays,
        otherMedsList.size, otherMedsList.map { it.title }, otherMedsList.map { it.dosage }, otherMedsList.map { it.timing }, otherMedsList.map { it.instructions }, otherMedsList.map { it.durationDays },
        globalDur
    ) {
        val list = mutableListOf<com.example.reports.RxColumnRowData>()
        val aRaw = analgesicState.toRawString()
        val abRaw = antibioticState.toRawString()
        val mRaw = mouthwashState.toRawString()
        
        if (aRaw.isNotBlank()) list.add(parseRxToColumns(aRaw, globalDur))
        if (abRaw.isNotBlank()) list.add(parseRxToColumns(abRaw, globalDur))
        if (mRaw.isNotBlank()) list.add(parseRxToColumns(mRaw, globalDur))
        
        for (other in otherMedsList) {
            val oRaw = other.toRawString()
            if (oRaw.isNotBlank()) {
                list.add(parseRxToColumns(oRaw, globalDur))
            }
        }
        
        if (list.isEmpty()) {
            list.add(com.example.reports.RxColumnRowData("داروی عمومی", "۱", "طبق دستور", "روزانه", "-"))
        }
        list
    }

    fun buildShareText(): String {
        return buildStandardRxShareText(
            patientName = patientName,
            patientNationalIdOrPhone = patientIdOrPhone,
            dateStr = dateStr,
            durationDays = globalDur,
            rows = parsedRows,
            notes = publicNote,
            clinicName = settings.clinicName,
            doctorName = settings.doctorName,
            gender = patient?.gender ?: "مرد"
        )
    }

    fun persistAndThen(sms: Boolean = false, wa: Boolean = false, after: () -> Unit = {}) {
        val rx = com.example.data.Prescription(
            id = savedId ?: 0L,
            patientId = treatment.patientId,
            treatmentId = treatment.id,
            treatmentTitle = treatment.title,
            stageName = stageName,
            analgesic = analgesicState.toRawString(),
            antibiotic = antibioticState.toRawString(),
            mouthwash = mouthwashState.toRawString(),
            otherMeds = otherMedsList.map { it.toRawString() }.filter { it.isNotBlank() }.joinToString("|||"),
            notes = notes,
            publicNote = publicNote,
            durationDays = globalDur,
            doctorApproved = true,
            sentSms = sms,
            sentWhatsApp = wa
        )
        viewModel.savePrescription(rx) { id ->
            savedId = id
            isApproved = true
            after()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Medication, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("نسخه دارویی و دستورات — $stageName", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("بیمار: $patientName", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        if (patientIdOrPhone.isNotBlank()) {
                            Text("شماره پرونده: $patientIdOrPhone", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Text("۱. ویرایش نسخه:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = durationDaysStr, onValueChange = { durationDaysStr = it },
                    label = { Text("طول درمان پیش‌فرض (روز)") },
                    modifier = Modifier.fillMaxWidth(0.5f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )

                RxItemEditorRow("💊 مسکن", analgesicState)
                RxItemEditorRow("💊 آنتی‌بیوتیک", antibioticState)
                RxItemEditorRow("🧴 دهان‌شویه", mouthwashState)
                
                otherMedsList.forEachIndexed { index, state ->
                    RxItemEditorRow("➕ سایر داروها (${index + 1})", state, onDelete = {
                        if (otherMedsList.size > 1) {
                            otherMedsList.removeAt(index)
                        } else {
                            state.title = ""
                            state.dosage = ""
                            state.timing = ""
                            state.instructions = ""
                        }
                    })
                }
                
                TextButton(onClick = { otherMedsList.add(RxItemEditState("", globalDur)) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("افزودن ردیف داروی جدید")
                }

                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("ملاحظات پزشکی (منع مصرف / داخلی مطب - ارسال نمی‌شود)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                )
                
                OutlinedTextField(
                    value = publicNote, onValueChange = { publicNote = it },
                    label = { Text("یادداشت برای بیمار (در نسخه ارسالی درج می‌شود)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                )

                PrescriptionLiveTablePreview(rows = parsedRows, notes = publicNote)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("۲. تایید نسخه توسط پزشک:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                if (!isApproved) {
                    Button(
                        onClick = { persistAndThen { } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("✓ تایید نهایی و ثبت نسخه توسط پزشک", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                } else {
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("✅ نسخه توسط پزشک تایید و در پرونده بیمار ثبت شد.", fontSize = 12.sp, color = Color(0xFF1B5E20), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Text("۳. ارسال به بیمار (پس از تایید پزشک):", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isApproved) MaterialTheme.colorScheme.primary else Color.Gray)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (patient?.phone.isNullOrBlank()) {
                                android.widget.Toast.makeText(context, "شماره تلفن بیمار ثبت نشده است", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                persistAndThen(sms = true) {
                                    sendSmsPrefilled(context, patient!!.phone, buildShareText())
                                }
                            }
                        },
                        enabled = isApproved,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ارسال پیامک", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (patient?.phone.isNullOrBlank()) {
                                android.widget.Toast.makeText(context, "شماره تلفن بیمار ثبت نشده است", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                persistAndThen(wa = true) {
                                    val cardFile = com.example.reports.generateStandardPrescriptionCardImage(
                                        context = context,
                                        patientName = honorificPatientName(patientName, patient?.gender ?: ""),
                                        patientNationalIdOrPhone = patientIdOrPhone,
                                        dateStr = dateStr,
                                        rows = parsedRows,
                                        notes = publicNote,
                                        clinicName = settings.clinicName,
                                        doctorName = settings.doctorName,
                                        themeSpec = viewModel.currentExportTheme()
                                    )
                                    shareImageToWhatsApp(context, cardFile, patient!!.phone)
                                }
                            }
                        },
                        enabled = isApproved,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("ارسال واتساپ", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (isApproved) {
                Button(onClick = onDismiss) {
                    Text("اتمام و بستن")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isApproved) "انصراف/بستن" else "بستن بدون ثبت") }
        }
    )
}

@Composable
fun PatientDetailsView(
    patient: Patient,
    treatments: List<Treatment>,
    payments: List<Payment>,
    viewModel: DentalViewModel,
    onBack: () -> Unit
) {
    val currency = viewModel.label("currency")
    val totalCost = treatments.sumOf { it.netCost }
    val totalPaid = payments.postedTotal()
    val netBalance = totalCost - totalPaid
    val allStaffMembersForDetails by viewModel.allStaffMembers.collectAsStateWithLifecycle()

    var currentSubTab by remember { mutableStateOf("treatments") } // treatments, payments, installments, documents
    var showTreatmentForm by remember { mutableStateOf(false) }
    var showPaymentForm by remember { mutableStateOf(false) }
    var showAddInstallmentDialog by remember { mutableStateOf(false) }
    var installmentToPay by remember { mutableStateOf<com.example.data.Installment?>(null) }
    var showEditPatientDialog by remember { mutableStateOf(false) }
    var treatmentToDelete by remember { mutableStateOf<Treatment?>(null) }
    var treatmentToEdit by remember { mutableStateOf<Treatment?>(null) }
    var paymentToDelete by remember { mutableStateOf<Payment?>(null) }
    var paymentToEdit by remember { mutableStateOf<Payment?>(null) }
    // انتقال چک با اثر واقعی حسابداری (به هزینه یا حساب شخصی/اشخاص)
    var chequeToTransfer by remember { mutableStateOf<Payment?>(null) }
    var editingRx by remember { mutableStateOf<Prescription?>(null) }

    // پرداخت قسط با انتخاب روش دریافت — تا تراکنش قسط در لیست‌های بانکی/نقدی/پوز دیده شود
    val isPersianForInstallment by viewModel.isPersian.collectAsStateWithLifecycle()
    if (installmentToPay != null) {
        PayInstallmentDialog(
            installment = installmentToPay!!,
            viewModel = viewModel,
            isPersian = isPersianForInstallment,
            currency = currency,
            onDismiss = { installmentToPay = null }
        )
    }

    val allInstallments by viewModel.allInstallments.collectAsStateWithLifecycle()
    val patientInstallments = remember(allInstallments, patient.id) { allInstallments.filter { it.patientId == patient.id } }

    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current

    val avatarEditLauncher = rememberPhotoEditLauncher(PhotoAssetKind.PATIENT_AVATAR) { localPath ->
        viewModel.updatePatientPhoto(patient, localPath)
    }
    val avatarGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { avatarEditLauncher(it) }
    }
    val launchAvatarCamera = rememberEditableCameraLauncher(context) { uri ->
        avatarEditLauncher(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Back Header with Interactive Avatar & Patient Edit Card
        var showAvatarPicker by remember { mutableStateOf(false) }
        val isFemale = patient.gender == "زن" || patient.gender == "خانم" || patient.gender.lowercase() == "female"
        val avatarBg = if (!patient.photoFileId.isNullOrBlank()) MaterialTheme.colorScheme.primaryContainer
                       else if (isFemale) Color(0xFFFCE4EC) else Color(0xFFE3F2FD)
        val avatarBorder = if (!patient.photoFileId.isNullOrBlank()) MaterialTheme.colorScheme.primary
                         else if (isFemale) Color(0xFFE91E63) else Color(0xFF1976D2)
        val avatarTint = if (isFemale) Color(0xFFC2185B) else Color(0xFF1565C0)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }

                // Interactive Patient Photo Avatar
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(avatarBg)
                        .border(2.dp, avatarBorder, CircleShape)
                        .clickable { showAvatarPicker = true },
                    contentAlignment = Alignment.Center
                ) {
                    if (!patient.photoFileId.isNullOrBlank()) {
                        SmartImage(
                            fileId = patient.photoFileId,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = if (isFemale) Icons.Default.Woman else Icons.Default.Man,
                            contentDescription = null,
                            tint = avatarTint,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                // Main Patient Header Column taking full weight(1f)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = getPatientFormattedName(patient.name, patient.gender, isPersian),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (patient.patientCode.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = patient.patientCode,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    // سن بیمار — سطرِ پایین کد بیمار (درخواست کاربر)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (patient.age > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "${patient.age} ${if (isPersian) "ساله" else "yrs"}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "${if (isPersian) "تلفن: " else "Phone: "}${patient.phone.ifBlank { "ثبت نشده" }}",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        if (patient.birthDate.isNotBlank()) {
                            Text(
                                text = "${if (isPersian) "تولد: " else "Born: "}${patient.birthDate}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    com.example.ui.components.AssistantNamesRow(
                        assistantIds = patient.assistantIds,
                        allStaff = allStaffMembersForDetails,
                        isPersian = isPersian
                    )
                }
            }

            // ردیف مستقل آیکون‌های عملیاتی — زیر هدر برگ بیمار (تماس/پیامک/ادیت/رفرش)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (patient.phone.isNotBlank() && patient.phone != "ثبت نشده") {
                    IconButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${patient.phone}"))
                        context.startActivity(intent)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = if (isPersian) "تماس" else "Call",
                            tint = ClearColor
                        )
                    }
                    IconButton(onClick = {
                        sendSmsPrefilled(context, patient.phone, "سلام ${patient.name} عزیز، از مطب تماس می‌گیریم.")
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Message,
                            contentDescription = if (isPersian) "پیامک" else "SMS",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Refresh — force-reload this patient's data from the database
                IconButton(onClick = {
                    viewModel.refreshSelectedPatient()
                    android.widget.Toast.makeText(
                        context,
                        if (isPersian) "به‌روزرسانی شد" else "Refreshed",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = if (isPersian) "به‌روزرسانی" else "Refresh",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }

                // Edit Patient Pencil Button
                IconButton(onClick = { showEditPatientDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Patient",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            }
        }

        if (showEditPatientDialog) {
            EditPatientDialog(
                patient = patient,
                viewModel = viewModel,
                onDismiss = { showEditPatientDialog = false }
            )
        }

            if (showAvatarPicker) {
                AlertDialog(
                    onDismissRequest = { showAvatarPicker = false },
                    title = { Text(if (isPersian) "بارگذاری تصویر بیمار" else "Upload Patient Photo", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (isPersian) "منبع عکس بیمار را انتخاب کنید:" else "Select patient photo source:")
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { launchAvatarCamera(); showAvatarPicker = false },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPersian) "دوربین" else "Camera", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { avatarGalleryLauncher.launch("image/*"); showAvatarPicker = false },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPersian) "گالری" else "Gallery", fontSize = 11.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showAvatarPicker = false }) {
                            Text(if (isPersian) "انصراف" else "Cancel")
                        }
                    }
                )
            }

        // Medical Warnings Badges, Risk Analysis & Self-Declaration Viewer Card
        var showSelfDecDetailsDialog by remember { mutableStateOf(false) }
        var showRiskGuidelinesDialog by remember { mutableStateOf(false) }
        var selectedProtocolTitleForPatientDetail by remember { mutableStateOf<String?>(null) }

        val hasMedicalAlerts = patient.medicalAlerts.isNotBlank() && patient.medicalAlerts != "بدون مورد"
        val hasRiskySelfDeclaration = remember(patient.selfDeclarationJson) {
            try {
                if (patient.selfDeclarationJson.isNotBlank()) {
                    val obj = org.json.JSONObject(patient.selfDeclarationJson)
                    var hasRisk = false
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (obj.getString(key) == "بله") hasRisk = true
                    }
                    hasRisk
                } else false
            } catch(e: Exception) { false }
        }
        val hasSelfDeclaration = patient.selfDeclarationJson.isNotBlank()
        
        val isRedRisk = hasMedicalAlerts || hasRiskySelfDeclaration

        // Only show Prominent Clinical Risks Card if the patient actually has medical alerts or a self-declaration on file
        if (hasMedicalAlerts || hasSelfDeclaration) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRedRisk) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(1.5.dp, if (isRedRisk) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRedRisk) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Text(
                                text = if (isPersian) "🔴 موارد خطر، احتیاط‌ها و ممنوعیت‌های درمانی" else "🔴 Risk Alerts, Precautions & Contraindications",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Button(
                            onClick = { showRiskGuidelinesDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(if (isPersian) "دستورالعمل بالینی" else "Clinical Rules", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (patient.medicalAlerts.isNotBlank() && patient.medicalAlerts != "بدون مورد") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            patient.medicalAlerts.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { alert ->
                                Surface(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = alert,
                                        color = MaterialTheme.colorScheme.onError,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (patient.selfDeclarationJson.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Assignment, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text(
                                text = if (isPersian) "فرم خوداظهاری سلامت بیمار تکمیل شده" else "Health Declaration On File",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(
                            onClick = { showSelfDecDetailsDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(if (isPersian) "مشاهده پاسخ‌ها" else "View Answers", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        } // پایان کارت هشدارهای بالینی — این if فقط همین کارت را می‌پوشاند

        // Detailed Clinical Risk & Contraindications Modal Dialog
        if (showRiskGuidelinesDialog) {
            val alerts = patient.medicalAlerts
            // سایز فونت این دیالوگ به‌صورت خودکار از همان مقدار ذخیره‌شده در صفحات
            // شرح بالینی (دستورالعمل بالینی) گرفته می‌شود — نسبت‌ها حفظ می‌شوند
            val protoBase = settings.clinicalFontSizePt
            val protocolTitleFontSize = (protoBase + 4).sp
            val protocolSectionFontSize = (protoBase + 1).sp
            val protocolBodyFontSize = protoBase.sp
            val protocolSmallFontSize = (protoBase - 1).coerceAtLeast(7).sp
            // این دیالوگ هم مانند صفحه دستورالعمل بالینی از لغزنده سراسری فونت مستثنی است
            val riskDialogDensity = androidx.compose.ui.unit.Density(density = LocalDensity.current.density, fontScale = 1f)

            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides riskDialogDensity) {
            AlertDialog(
                onDismissRequest = { showRiskGuidelinesDialog = false },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                text = if (isPersian) "دستورالعمل بالینی و ممنوعیت‌های درمانی" else "Clinical Guidelines & Contraindications",
                                fontWeight = FontWeight.Bold,
                                fontSize = protocolTitleFontSize,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            text = if (isPersian) "بر اساس آخرین گایدلاین‌های معتبر دانشگاهی (ADA / AHA)" else "Based on ADA / AHA Evidence-Based Clinical Protocols",
                            fontSize = protocolSmallFontSize,
                            color = Color.Gray
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Section 1: Bleeding / Anticoagulants / Bleeding History
                        if (alerts.contains("انعقاد خون") || alerts.contains("خونریزی شدید")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🩸 اختلالات انعقادی / مصرف رقیق‌کننده خون (وارفارین/آسپرین):" else "🩸 Bleeding / Anticoagulant Protocol:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.error)
                                    Text(if (isPersian) "• احتیاط: سنجش آزمایشگاهی INR قبل جراحی یا کشیدن دندان (محدوده مجاز INR ≤ ۲.۵ تا ۳). استفاده از پانسمان بندآورنده خونریزی (ژلفوم، گاز ترانگزامیک اسید، بخیه متابولیک محکم)." else "• Precaution: Check INR (target ≤ 2.5-3). Use hemostatic agents, Tranexamic acid & tight sutures.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: قطع خودسرانه وارفارین بدون دستور پزشک معالج ممنوع است. تجویز داروهای NSAID (ایبوپروفن، نپروکسن، مفنامیک اسید) به علت افزایش خطر خونریزی معده اکیداً ممنوع بوده و فقط استامینوفن مجاز است." else "❌ Contraindication: Do NOT stop Warfarin independently. NSAIDs (Ibuprofen) are strictly contra-indicated; prescribe Acetaminophen.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 2: Penicillin / Anesthetic Allergies
                        if (alerts.contains("حساسیت دارویی")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "⚠️ حساسیت دارویی / پنی‌سیلین یا بی حسی:" else "⚠️ Drug & Anesthetic Allergy Protocol:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.error)
                                    Text(if (isPersian) "• احتیاط (به‌روزرسانی‌شده طبق بیانیه علمی ۲۰۲۱ انجمن قلب آمریکا AHA): کلیندامایسین دیگر به‌دلیل خطر عفونت کلستریدیوم دیفیسیل توصیه نمی‌شود. در حساسیت به پنی‌سیلین، از سفالکسین (در صورت نبود واکنش شدید نوع آنافیلاکسی) یا آزیترومایسین/کلاریترومایسین یا داکسی‌سایکلین استفاده شود. در صورت حساسیت به بی‌حسی‌های آمیدی یا مواد نگهدارنده سولفیت، از مپیواکائین ۳٪ بدون اپی‌نفرین استفاده شود." else "• Precaution (updated per AHA 2021 Scientific Statement): Clindamycin is no longer recommended due to C. difficile risk. For penicillin allergy use Cephalexin (if no history of anaphylaxis) or Azithromycin/Clarithromycin or Doxycycline. Use Mepivacaine 3% without Epinephrine for sulfite/amide allergies.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: تجویز تمام مشتقات پنی‌سیلین (آموکسی‌سیلین، آمی‌سیلین، کوآموکسی‌کلاو) و سفالوسپورین‌ها در بیماران با آنفیلاکسی سابقه اکیداً ممنوع است." else "❌ Contraindication: All Penicillins (Amoxicillin, Augmentin) and Cephalosporins are strictly forbidden.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3: Cardiovascular / Hypertension / Diabetes / Epilepsy (تطبیق دقیق - فقط فشار خون بالا، نه پایین)
                        if (alerts.contains("بیماری قلبی") || alerts.contains("فشار خون بالا") || alerts.contains("دیابت") || alerts.contains("صرع")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🫀 بیماران قلبی-عروقی، فشارخون بالا، دیابت و صرع:" else "🫀 Cardiovascular, Hypertension & Diabetes Protocol:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: کنترل فشار خون قبل کار (فشار بالای ۱۶۰/۱۰۰ نیاز به استراحت و ارجاع). محدودیت اپی‌نفرین به حداکثر ۲ کارپول ۱/۱۰۰۰۰۰. تنظیم وقت صبحگاهی برای بیماران دیابتی پس از مصرف صبحانه و انسولین (پرهیز از افت قندخون حین درمان). در دریچه مصنوعی قلب یا سابقه اندوکاردیت، پروفیلاکسی آنتی‌بیوتیکی طبق AHA 2021 فقط برای پرخطرترین گروه لازم است." else "• Precaution: Check blood pressure (refer if >160/100). Limit Epinephrine to max 2 cartridges 1:100k. Schedule diabetic patients in morning after meal & insulin (avoid hypoglycemia mid-treatment). For prosthetic valves/endocarditis history, AHA 2021 prophylaxis applies only to highest-risk group.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: استفاده از نخ پس‌کشنده لثه (Retraction cord) آغشته به اپی‌نفرین غلیظ اکیداً ممنوع است." else "❌ Contraindication: Retraction cords impregnated with concentrated Epinephrine are forbidden.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3b: Hypotension / Vasovagal Syncope during Injection (NEW)
                        if (alerts.contains("فشار خون پایین")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "💧 فشار خون پایین / سنکوپ وازوواگال حین تزریق:" else "💧 Hypotension / Vasovagal Syncope Protocol:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: بیمار را در وضعیت نیمه‌خوابیده (Semi-supine) و نه کاملاً عمودی قرار دهید؛ پیش از تزریق حتماً آسپیره کنید و بی‌حسی را آهسته تزریق نمایید؛ از ناشتا بودن طولانی بیمار پیش از نوبت پرهیز شود. علائم هشدار: رنگ‌پریدگی، تعریق سرد، سرگیجه، تهوع." else "• Precaution: Position patient semi-supine (not fully upright) before injection; always aspirate before injecting and inject slowly; ensure patient hasn't fasted for long before the appointment. Warning signs: pallor, cold sweat, dizziness, nausea.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "🚑 در صورت بروز غش/سنکوپ حین کار: فوراً درمان را متوقف کنید، بیمار را در وضعیت خوابیده با پاهای بالاتر از سطح قلب (Trendelenburg) قرار دهید، لباس تنگ را شل کنید، علائم حیاتی را پایش و در صورت نیاز اکسیژن تجویز کنید؛ اغلب موارد وازوواگال طی ۱-۲ دقیقه برطرف می‌شود. از بلندشدن ناگهانی بیمار پس از درمان خودداری شود." else "🚑 If syncope occurs: stop treatment immediately, place patient supine with legs elevated (Trendelenburg), loosen tight clothing, monitor vitals, give oxygen if needed; most vasovagal episodes resolve within 1-2 minutes. Avoid sudden postural changes after treatment.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3c: Asthma / Chronic Respiratory Disease (NEW)
                        if (alerts.contains("آسم")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🫁 آسم و بیماری تنفسی مزمن:" else "🫁 Asthma / Chronic Respiratory Disease:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: از بیمار بخواهید اسپری برونکودیلاتور اورژانسی خود را همراه بیاورد؛ از مواد بودار/اسپری‌های قوی که محرک برونکواسپاسم هستند پرهیز شود؛ در آسم شدید، استفاده از رابردم با احتیاط بیشتری انجام شود." else "• Precaution: Have patient bring rescue inhaler to appointment; avoid strong-odor materials/aerosols that trigger bronchospasm; use rubber dam cautiously in severe asthma.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: در بیماران با آسم حساس به آسپرین (Aspirin-Exacerbated Respiratory Disease)، تجویز آسپرین و سایر NSAIDها ممنوع است." else "❌ Contraindication: Avoid Aspirin/NSAIDs in patients with Aspirin-Exacerbated Respiratory Disease.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3d: Kidney Disease (NEW)
                        if (alerts.contains("بیماری کلیوی")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🫘 بیماری کلیوی:" else "🫘 Kidney Disease:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: تنظیم دوز داروهای دفع‌شونده از کلیه؛ در بیماران دیالیزی، درمان روز بعد از دیالیز انجام شود و از بازوی دارای فیستول برای فشارسنجی استفاده نشود." else "• Precaution: Adjust doses of renally-cleared drugs; for dialysis patients, treat the day after dialysis and avoid the fistula arm for BP measurement.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: NSAIDها به‌دلیل سمیت کلیوی و تتراسایکلین‌ها در نارسایی کلیوی ممنوع هستند." else "❌ Contraindication: NSAIDs (nephrotoxic) and Tetracyclines are contraindicated in renal impairment.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3e: Liver Disease / Hepatitis (NEW)
                        if (alerts.contains("بیماری کبدی")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🫄 بیماری کبدی / هپاتیت:" else "🫄 Liver Disease / Hepatitis:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: کبد محل تولید فاکتورهای انعقادی است؛ احتمال خونریزی بیشتر در نظر گرفته شود. رعایت کامل پروتکل کنترل عفونت به‌دلیل احتمال انتقال از راه خون (هپاتیت B/C)." else "• Precaution: Liver produces clotting factors; higher bleeding risk should be considered. Follow full infection-control protocol given bloodborne transmission risk (Hep B/C).", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: در آسیب کبدی قابل‌توجه، دوز استامینوفن باید محدود شود و از داروهایی با متابولیسم عمده کبدی تا حد امکان پرهیز شود." else "❌ Contraindication: Limit Acetaminophen dose in significant liver impairment; avoid drugs with major hepatic metabolism where possible.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3f: Thyroid Disorder (NEW)
                        if (alerts.contains("اختلال تیروئید")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🦋 اختلال تیروئید:" else "🦋 Thyroid Disorder:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: پیش از درمان الکتیو، کنترل بودن وضعیت تیروئید (یوتیروئید) تایید شود." else "• Precaution: Confirm thyroid status is well-controlled (euthyroid) before elective treatment.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: در پرکاری تیروئید کنترل‌نشده، مصرف اپی‌نفرین باید به حداقل برسد (خطر آریتمی/توفان تیروئیدی)." else "❌ Contraindication: Minimize Epinephrine in uncontrolled hyperthyroidism (risk of arrhythmia/thyroid storm).", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3g: Osteoporosis / Bisphosphonate (MRONJ Risk) (NEW)
                        if (alerts.contains("استئوپروز")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🦴 استئوپروز / مصرف بیس‌فسفونات (خطر MRONJ):" else "🦴 Osteoporosis / Bisphosphonate (MRONJ Risk):", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط (طبق راهنمای انجمن دندانپزشکی آمریکا ADA): طبق شواهد فعلی، توقف خودسرانه یا دوره‌ای دارو («تعطیلی دارویی») به‌طور روتین توصیه نمی‌شود و تصمیم باید با هماهنگی پزشک/تیم انکولوژی تجویزکننده گرفته شود. حتی‌الامکان از اقدامات تهاجمی استخوانی غیرضروری پرهیز و بهداشت دهان به‌شدت رعایت شود؛ خطر در بیس‌فسفونات‌های وریدی (مصارف سرطانی) بسیار بیشتر از خوراکی (مصرف استئوپروز) است." else "• Precaution (per ADA guidance): Current evidence does not routinely support a unilateral 'drug holiday'; decisions should be coordinated with the prescribing physician/oncology team. Minimize unnecessary invasive bone procedures and maintain strict oral hygiene; risk is markedly higher with IV bisphosphonates (cancer use) than oral (osteoporosis use).", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: انجام جراحی استخوانی الکتیو بدون هماهنگی پزشکی در بیماران پرخطر (بیس‌فسفونات وریدی/سرطانی) توصیه نمی‌شود." else "❌ Contraindication: Elective invasive bone surgery without medical clearance in high-risk (IV bisphosphonate/cancer) patients should be deferred.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 3h: Cancer / Chemotherapy / Radiotherapy (NEW)
                        if (alerts.contains("سرطان")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🎗️ سرطان / سابقه شیمی‌درمانی یا پرتودرمانی:" else "🎗️ Cancer / Chemotherapy / Radiotherapy:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: پیش از اقدامات تهاجمی حین شیمی‌درمانی فعال، بررسی شمارش خون (خطر نوتروپنی/ترومبوسیتوپنی) و هماهنگی با آنکولوژیست ضروری است. در سابقه پرتودرمانی سر و گردن، خطر استئورادیونکروز وجود دارد؛ از کشیدن دندان غیرضروری در استخوان تحت تابش پرهیز و مشاوره تخصصی گرفته شود." else "• Precaution: Check blood counts (neutropenia/thrombocytopenia risk) before invasive procedures during active chemo, coordinate with oncologist. History of head/neck radiotherapy carries osteoradionecrosis risk; avoid unnecessary extractions in irradiated bone and seek specialist consultation.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: اقدامات تهاجمی الکتیو در دوره نوتروپنی/ترومبوسیتوپنی فعال بدون هماهنگی پزشکی ممنوع است." else "❌ Contraindication: Elective invasive procedures during active neutropenia/thrombocytopenia without medical clearance.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 4: Pregnancy / Lactation
                        if (alerts.contains("بارداری")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🤰 بارداری و شیردهی:" else "🤰 Pregnancy & Lactation Protocol:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.tertiary)
                                    Text(if (isPersian) "• احتیاط: زمان طلایی درمان‌های روتین، سه ماهه دوم بارداری است. رعایت اصل ALARA و استفاده حتمی از پیش‌بند سربی و یقه تیرویید هنگام رادیوگرافی. بی‌حسی انتخابی لیدوکائین ۲٪ با اپی‌نفرین (رده B)." else "• Precaution: Safe window is 2nd trimester. Use lead apron & thyroid collar for X-rays. Lidocaine 2% with 1:100k epi is Class B safe.", fontSize = protocolBodyFontSize)
                                    Text(if (isPersian) "❌ ممنوعیت: تجویز داروهای رده C و D (تتراسایکلین، بنزودیازپین‌ها، NSAIDها در ۳ ماهه سوم) اکیداً ممنوع است." else "❌ Contraindication: Category C/D drugs (Tetracyclines, NSAIDs in 3rd trimester, Benzodiazepines) strictly prohibited.", fontSize = protocolBodyFontSize, color = Color(0xFFB71C1C), fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Section 5: Smoking (NEW)
                        if (alerts.contains("دخانیات")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🚬 مصرف دخانیات:" else "🚬 Smoking:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: خطر بیشتر تاخیر در ترمیم زخم، خشکی سوکت پس از کشیدن دندان، پیشرفت بیماری پریودنتال و شکست ایمپلنت. توصیه به ترک یا کاهش مصرف حداقل ۲۴-۴۸ ساعت پیش و پس از جراحی." else "• Precaution: Higher risk of delayed healing, dry socket after extraction, periodontal progression, and implant failure. Recommend cessation/reduction at least 24-48h before and after surgery.", fontSize = protocolBodyFontSize)
                                }
                            }
                        }

                        // Section 6: Recent Surgery / Hospitalization (NEW)
                        if (alerts.contains("جراحی یا بستری اخیر")) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (isPersian) "🏥 سابقه جراحی یا بستری اخیر:" else "🏥 Recent Surgery / Hospitalization:", fontWeight = FontWeight.Bold, fontSize = protocolSectionFontSize, color = MaterialTheme.colorScheme.primary)
                                    Text(if (isPersian) "• احتیاط: تاییدیه پزشکی جهت انجام درمان‌های الکتیو دندانپزشکی از پزشک معالج اخذ شود؛ توجه به داروهای ضدانعقاد باقی‌مانده، ضعف ایمنی موقت یا خطر عفونت مرتبط با نوع جراحی اخیر." else "• Precaution: Obtain medical clearance from treating physician for elective dental treatment; consider residual anticoagulation, temporary immunosuppression, or infection risk related to the recent surgery.", fontSize = protocolBodyFontSize)
                                }
                            }
                        }

                        // General Clinical Protocol Notice
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (isPersian) "توصیه می‌شود قبل از شروع درمان‌های جراحی، پرینت فرم خوداظهاری توسط بیمار امضا گردد." else "Recommend obtaining patient signed printed declaration prior to surgical procedures.",
                                    fontSize = protocolSmallFontSize,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showRiskGuidelinesDialog = false }) {
                        Text(if (isPersian) "متوجه شدم و بستن" else "Understood & Close", fontWeight = FontWeight.Bold)
                    }
                }
            )
            }
        }

        if (showSelfDecDetailsDialog) {
            val selfDecAnswers = remember(patient.selfDeclarationJson) {
                try {
                    val jsonObj = org.json.JSONObject(patient.selfDeclarationJson)
                    val qLabels = listOf(
                        "bleeding" to "1. سابقه خونریزی شدید پس از کشیدن دندان یا جراحی؟",
                        "allergy" to "2. حساسیت به بی‌حسی دندانپزشکی یا پنی‌سیلین؟",
                        "meds" to "3. مصرف داروهای رقیق‌کننده خون (مانند وارفارین/آسپرین)؟",
                        "chronic" to "4. سابقه بیماری قلبی، فشارخون، دیابت یا صرع؟",
                        "pregnant" to "5. بارداری یا شیردهی (ویژه بانوان)؟",
                        "surgery" to "6. سابقه جراحی یا بستری اخیر در بیمارستان؟"
                    )
                    qLabels.map { (key, qText) ->
                        qText to jsonObj.optString(key, "خیر")
                    }
                } catch(e: Exception) {
                    emptyList()
                }
            }

            AlertDialog(
                onDismissRequest = { showSelfDecDetailsDialog = false },
                title = {
                    Text(
                        text = if (isPersian) "فرم خوداظهاری سلامت بیمار: ${patient.name}" else "Patient Health Declaration: ${patient.name}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (selfDecAnswers.isNotEmpty()) {
                            selfDecAnswers.forEach { (qText, ans) ->
                                val ansColor = if (ans == "بله") MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(text = qText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = "پاسخ: $ans",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ansColor
                                    )
                                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        } else {
                            Text(text = patient.selfDeclarationJson, fontSize = 12.sp)
                        }

                        // کادر ثبت و نمایش امضای لمسی دیجیتال بیمار
                        if (!patient.signatureFileId.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "امضا و اثر انگشت الکترونیکی بیمار جهت تأیید صحت خوداظهاری پزشکی و درمان",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(80.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        SmartImage(
                                            fileId = patient.signatureFileId,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSelfDecDetailsDialog = false }) {
                        Text(if (isPersian) "بستن" else "Close")
                    }
                }
            )
        }

        // Financial Health Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    netBalance > 0 -> MaterialTheme.colorScheme.errorContainer
                    netBalance < 0 -> Color(0xFFE8F5E9)
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = viewModel.label("financial_balance"),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${formatAmount(kotlin.math.abs(netBalance))} $currency",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        color = when {
                            netBalance > 0 -> DebtColor
                            netBalance < 0 -> ClearColor
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    Text(
                        text = when {
                            netBalance > 0 -> viewModel.label("balance_debtor")
                            netBalance < 0 -> viewModel.label("balance_creditor")
                            else -> viewModel.label("balance_settled")
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = when {
                            netBalance > 0 -> DebtColor
                            netBalance < 0 -> ClearColor
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = viewModel.label("total_treatments"), fontSize = 11.sp, color = Color.Gray)
                        Text(text = "${formatAmount(totalCost)} $currency", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = viewModel.label("total_payments"), fontSize = 11.sp, color = Color.Gray)
                        Text(text = "${formatAmount(totalPaid)} $currency", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Send-to-Patient Actions: Statement & Treatment Plan (WhatsApp = image, SMS = short text)
        PatientSendActionsRow(
            patient = patient,
            treatments = treatments,
            payments = payments,
            viewModel = viewModel,
            isPersian = isPersian
        )

        // Segmented Tabs: Treatment / Payments / Installments / Documents
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = { currentSubTab = "treatments" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentSubTab == "treatments") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (currentSubTab == "treatments") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(viewModel.label("treatments_tab"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { currentSubTab = "payments" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentSubTab == "payments") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (currentSubTab == "payments") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(viewModel.label("payments_tab"), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { currentSubTab = "installments" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentSubTab == "installments") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (currentSubTab == "installments") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(if (isPersian) "اقساط و بدهی" else "Installments", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { currentSubTab = "documents" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentSubTab == "documents") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (currentSubTab == "documents") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(if (isPersian) "مدارک و امضا" else "Docs & Sign", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { currentSubTab = "prescriptions" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentSubTab == "prescriptions") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (currentSubTab == "prescriptions") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(if (isPersian) "نسخه پزشکی" else "Prescriptions", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Subtab section title with toggle action
        val titleText = when (currentSubTab) {
            "treatments" -> viewModel.label("treatments_tab")
            "installments" -> if (isPersian) "اقساط و بدهی بیمار" else "Installments & Debts"
            "documents" -> if (isPersian) "رادیوگرافی، مدارک و رضایت‌نامه" else "Docs & Signatures"
            "prescriptions" -> if (isPersian) "نسخه‌های دارویی" else "Prescriptions"
            else -> viewModel.label("payments_tab")
        }
        val isFormOpen = if (currentSubTab == "treatments") showTreatmentForm else (showPaymentForm || paymentToEdit != null)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = titleText, fontWeight = FontWeight.Bold)
            if (currentSubTab == "treatments" || currentSubTab == "payments") {
                IconButton(onClick = {
                    if (currentSubTab == "treatments") {
                        showTreatmentForm = !showTreatmentForm
                    } else {
                        if (showPaymentForm || paymentToEdit != null) {
                            showPaymentForm = false
                            paymentToEdit = null
                        } else {
                            showPaymentForm = true
                        }
                    }
                }) {
                    Icon(
                        imageVector = if (isFormOpen) Icons.Default.Close else Icons.Default.AddCircle,
                        contentDescription = "Toggle Form",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (currentSubTab == "installments") {
                Button(
                    onClick = { showAddInstallmentDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isPersian) "ثبت قسط جدید" else "Add Installment", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Render Active List or Forms
        if (currentSubTab == "treatments") {
            if (showTreatmentForm) {
                AddTreatmentForm(
                    patient = patient,
                    viewModel = viewModel,
                    onSaved = { showTreatmentForm = false }
                )
            } else {
                var treatmentStatusFilter by remember { mutableStateOf("ALL") }
                var treatmentCategoryFilter by remember { mutableStateOf<String?>(null) }
                var treatmentSearchQuery by remember { mutableStateOf("") }
                // فیلتر شیری/دائمی روی لیست درمان‌ها. null یعنی «همه».
                var treatmentDentitionFilter by remember { mutableStateOf<Dentition?>(null) }

                if (treatments.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = if (isPersian) "درمانی ثبت نشده" else "No treatments recorded", color = Color.Gray)
                    }
                } else {
                    // Treatment Plan Filters & Search Row
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Search field
                        OutlinedTextField(
                            value = treatmentSearchQuery,
                            onValueChange = { treatmentSearchQuery = it },
                            placeholder = { Text(if (isPersian) "جستجو در عنوان طرح درمان یا دندان..." else "Search treatment or tooth...", fontSize = 11.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            trailingIcon = {
                                if (treatmentSearchQuery.isNotBlank()) {
                                    IconButton(onClick = { treatmentSearchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Status Filter Chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val statusFilterOptions = listOf(
                                "ALL" to (if (isPersian) "همه (${treatments.size})" else "All (${treatments.size})"),
                                "PLANNED" to (if (isPersian) "برنامه‌ریزی شده (${treatments.count { it.status != "IN_PROGRESS" && it.status != "COMPLETED" && it.status != "تمام شده" && it.status != "تکمیل شده" }})" else "Planned"),
                                "IN_PROGRESS" to (if (isPersian) "در حال انجام (${treatments.count { it.status == "IN_PROGRESS" || it.status == "در حال انجام" }})" else "In Progress"),
                                "COMPLETED" to (if (isPersian) "تکمیل شده (${treatments.count { it.status == "COMPLETED" || it.status == "تمام شده" || it.status == "تکمیل شده" }})" else "Completed")
                            )
                            statusFilterOptions.forEach { (stKey, stLabel) ->
                                FilterChip(
                                    selected = treatmentStatusFilter == stKey,
                                    onClick = { treatmentStatusFilter = stKey },
                                    label = { Text(stLabel, fontSize = 11.sp, fontWeight = if (treatmentStatusFilter == stKey) FontWeight.Bold else FontWeight.Normal) }
                                )
                            }
                        }

                        // Category Filter Chips
                        val txCategories = listOf("عصب‌کشی", "روکش", "کشیدن", "ترمیمی", "جراحی", "ایمپلنت", "سایر")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = treatmentCategoryFilter == null,
                                onClick = { treatmentCategoryFilter = null },
                                label = { Text(if (isPersian) "همه دسته‌ها" else "All Categories", fontSize = 10.5.sp) }
                            )
                            txCategories.forEach { cat ->
                                FilterChip(
                                    selected = treatmentCategoryFilter == cat,
                                    onClick = { treatmentCategoryFilter = if (treatmentCategoryFilter == cat) null else cat },
                                    label = { Text(cat, fontSize = 10.5.sp) }
                                )
                            }
                        }

                        // فیلتر شیری/دائمی — تشخیص از روی شمارهٔ دندانِ هر درمان
                        // (و اگر دندان ثبت نشده باشد، از روی عنوان درمان).
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = treatmentDentitionFilter == null,
                                onClick = { treatmentDentitionFilter = null },
                                label = { Text(if (isPersian) "شیری و دائمی" else "All Dentition", fontSize = 10.5.sp) }
                            )
                            FilterChip(
                                selected = treatmentDentitionFilter == Dentition.PERMANENT,
                                onClick = {
                                    treatmentDentitionFilter =
                                        if (treatmentDentitionFilter == Dentition.PERMANENT) null else Dentition.PERMANENT
                                },
                                label = { Text(if (isPersian) "دائمی" else "Permanent", fontSize = 10.5.sp) }
                            )
                            FilterChip(
                                selected = treatmentDentitionFilter == Dentition.PRIMARY,
                                onClick = {
                                    treatmentDentitionFilter =
                                        if (treatmentDentitionFilter == Dentition.PRIMARY) null else Dentition.PRIMARY
                                },
                                label = { Text(if (isPersian) "شیری" else "Primary", fontSize = 10.5.sp) }
                            )
                        }
                    }

                    val displayedTreatments = treatments.filter { tx ->
                        val matchesStatus = when (treatmentStatusFilter) {
                            "PLANNED" -> tx.status != "IN_PROGRESS" && tx.status != "COMPLETED" && tx.status != "تمام شده" && tx.status != "تکمیل شده"
                            "IN_PROGRESS" -> tx.status == "IN_PROGRESS" || tx.status == "در حال انجام"
                            "COMPLETED" -> tx.status == "COMPLETED" || tx.status == "تمام شده" || tx.status == "تکمیل شده"
                            else -> true
                        }
                        val matchesSearch = treatmentSearchQuery.isBlank() ||
                                tx.title.contains(treatmentSearchQuery, ignoreCase = true) ||
                                tx.selectedTeeth.contains(treatmentSearchQuery, ignoreCase = true) ||
                                tx.notes.contains(treatmentSearchQuery, ignoreCase = true)
                        val matchesCategory = treatmentCategoryFilter == null || when (treatmentCategoryFilter) {
                            "عصب‌کشی" -> tx.title.contains("عصب")
                            "روکش" -> tx.title.contains("روکش")
                            "کشیدن" -> tx.title.contains("کشیدن")
                            "ترمیمی" -> tx.title.contains("پرکردن") || tx.title.contains("ترمیم") || tx.title.contains("کامپوزیت") || tx.title.contains("آمالگام")
                            "جراحی" -> tx.title.contains("جراحی") || tx.title.contains("سینوس") || tx.title.contains("پیوند")
                            "ایمپلنت" -> tx.title.contains("ایمپلنت") || tx.title.contains("کاشت")
                            "سایر" -> !(tx.title.contains("عصب") || tx.title.contains("روکش") || tx.title.contains("کشیدن") || tx.title.contains("پرکردن") || tx.title.contains("ترمیم") || tx.title.contains("کامپوزیت") || tx.title.contains("آمالگام") || tx.title.contains("جراحی") || tx.title.contains("ایمپلنت"))
                            else -> true
                        }
                        // درمانی که روی دندان‌های هر دو دوره ثبت شده (MIXED) در هر دو
                        // فیلتر دیده می‌شود؛ درمانی که هیچ نشانه‌ای ندارد فقط در «همه».
                        val matchesDentition = treatmentDentitionFilter == null || run {
                            val d = treatmentDentition(tx.selectedTeeth, tx.title)
                            d == treatmentDentitionFilter || d == Dentition.MIXED
                        }
                        matchesStatus && matchesSearch && matchesCategory && matchesDentition
                    }

                    if (displayedTreatments.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = if (isPersian) "هیچ طرح درمانی با این فیلتر یافت نشد" else "No treatments match this filter", color = Color.Gray, fontSize = 12.sp)
                        }
                    } else {
                        // طبق درخواست: هر درمان به‌صورت پیش‌فرض جمع (Collapsed) است و فقط
                        // سه سطر اول را نشان می‌دهد — نام درمان، تاریخ، و شماره دندان + وضعیت.
                        // بقیهٔ جزئیات با زدن روی کارت باز می‌شود.
                        var expandedTreatmentIds by remember { mutableStateOf(setOf<Long>()) }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            displayedTreatments.forEach { treatment ->
                            val isTreatmentExpanded = treatment.id in expandedTreatmentIds
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedTreatmentIds = if (isTreatmentExpanded) {
                                            expandedTreatmentIds - treatment.id
                                        } else {
                                            expandedTreatmentIds + treatment.id
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        // ——— سطر ۱: نام درمان (+ فلش باز/بسته شدن) ———
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = treatment.title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .threeSecondLongPress(
                                                        onLongClick = {
                                                            if (TherapyKnowledgeBase.findProtocol(treatment.title) != null) {
                                                                selectedProtocolTitleForPatientDetail = treatment.title
                                                            }
                                                        }
                                                    )
                                            )
                                            Icon(
                                                imageVector = if (isTreatmentExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = if (isTreatmentExpanded) {
                                                    if (isPersian) "بستن جزئیات" else "Collapse"
                                                } else {
                                                    if (isPersian) "نمایش جزئیات" else "Expand"
                                                },
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        // ——— سطر ۲: تاریخ ———
                                        Text(text = viewModel.formatDate(treatment.date), fontSize = 11.sp, color = Color.Gray)
                                        if (isTreatmentExpanded) {
                                            com.example.ui.components.AssistantNamesRow(
                                                assistantIds = treatment.assistantIds,
                                                allStaff = allStaffMembersForDetails,
                                                isPersian = isPersian,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }

                                        // Row containing Tooth Numbers Field NEXT TO the 3-state Status Selector/Chips
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Teeth Field
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (treatment.selectedTeeth.isNotBlank()) {
                                                        if (isPersian) "دندان‌ها: ${treatment.selectedTeeth}" else "Teeth: ${treatment.selectedTeeth}"
                                                    } else {
                                                        if (isPersian) "دندان‌ها: —" else "Teeth: —"
                                                    },
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            // Status Chips ("برنامه‌ریزی شده", "در حال انجام", "تمام شده")
                                            val currentStatus = when (treatment.status) {
                                                "COMPLETED", "تمام شده", "تکمیل شده", "پایان یافته" -> "COMPLETED"
                                                "IN_PROGRESS", "در حال انجام", "در حال درمان" -> "IN_PROGRESS"
                                                else -> "PLANNED"
                                            }

                                            val statusOptions = listOf(
                                                "PLANNED" to (if (isPersian) "برنامه‌ریزی شده" else "Planned"),
                                                "IN_PROGRESS" to (if (isPersian) "در حال انجام" else "In Progress"),
                                                "COMPLETED" to (if (isPersian) "تمام شده" else "Completed")
                                            )

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                statusOptions.forEach { (stKey, stLabel) ->
                                                    val isSel = currentStatus == stKey
                                                    val bg = if (isSel) {
                                                        when (stKey) {
                                                            "COMPLETED" -> Color(0xFFE8F5E9)
                                                            "IN_PROGRESS" -> Color(0xFFFFF3E0)
                                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                                        }
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                                    }
                                                    val textColor = if (isSel) {
                                                        when (stKey) {
                                                            "COMPLETED" -> Color(0xFF2E7D32)
                                                            "IN_PROGRESS" -> Color(0xFFE65100)
                                                            else -> Color.DarkGray
                                                        }
                                                    } else {
                                                        Color.Gray
                                                    }

                                                    Surface(
                                                        color = bg,
                                                        shape = RoundedCornerShape(4.dp),
                                                        border = if (isSel) BorderStroke(1.dp, textColor.copy(alpha = 0.5f)) else null
                                                    ) {
                                                        Text(
                                                            text = stLabel,
                                                            fontSize = 9.sp,
                                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                            color = textColor,
                                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // ——— از اینجا به بعد فقط در حالت باز (Expanded) نمایش داده می‌شود ———
                                        if (isTreatmentExpanded) {
                                        if (treatment.discount > 0.0) {
                                            Surface(
                                                color = Color(0xFFE8F5E9),
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.padding(top = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (isPersian) "تخفیف: -${formatAmount(treatment.discount)} $currency" else "Discount: -${formatAmount(treatment.discount)} $currency",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF2E7D32),
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        val displayNotes = treatment.notes.cleanNotesForDisplay()
                                        if (displayNotes.isNotEmpty()) {
                                            Text(text = displayNotes, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
                                        }

                                        // Therapy Stages Checklist (Read-Only in patient record view)
                                        TreatmentStageChecklistCard(treatment = treatment, viewModel = viewModel, readOnly = true)

                                        // طول کار کانال + شماتیک سه‌بعدی — فقط برای عصب‌کشی/اندو
                                        if (treatment.isEndodontic) {
                                            EndoWorkingLengthCard(treatment = treatment, viewModel = viewModel, readOnly = true)
                                        }

                                        // سطوح درگیر ترمیم — فقط برای درمان‌های ترمیمی
                                        if (treatment.isRestorative) {
                                            ToothSurfaceRestorationCard(treatment = treatment, viewModel = viewModel, readOnly = true)
                                        }

                                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = "${formatAmount(treatment.netCost)} $currency",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                if (treatment.discount > 0.0) {
                                                    Text(
                                                        text = if (isPersian) "تخفیف: -${formatAmount(treatment.discount)} $currency" else "Discount: -${formatAmount(treatment.discount)} $currency",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF2E7D32)
                                                    )
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                OutlinedButton(
                                                    onClick = { treatmentToEdit = treatment },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(if (isPersian) "ویرایش درمان" else "Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                                OutlinedIconButton(
                                                    onClick = { treatmentToDelete = treatment }
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                        } // پایان بخش جزئیات (Expanded)
                                        // در حالت جمع فقط همان سه سطر بالا (نام درمان، تاریخ،
                                        // شماره دندان + وضعیت) دیده می‌شود.
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        } else if (currentSubTab == "installments") {
            if (patientInstallments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = if (isPersian) "هیچ قسط یا تعهد مالی برای این بیمار ثبت نشده است" else "No installments recorded", color = Color.Gray)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    patientInstallments.forEach { inst ->
                        val isOverdue = !inst.isPaid && inst.dueDate < System.currentTimeMillis()
                        val statusColor = when {
                            inst.isPaid -> Color(0xFF2E7D32)
                            isOverdue -> Color(0xFFD32F2F)
                            else -> Color(0xFFE65100)
                        }
                        val statusText = when {
                            inst.isPaid -> if (isPersian) "پرداخت شده" else "Paid"
                            isOverdue -> if (isPersian) "معوقه" else "Overdue"
                            else -> if (isPersian) "در انتظار پرداخت" else "Pending"
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = inst.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(text = "سررسید: ${viewModel.formatDate(inst.dueDate)}", fontSize = 11.sp, color = Color.Gray)
                                        if (inst.note.isNotBlank()) {
                                            Text(text = inst.note, fontSize = 10.sp, color = Color.Gray)
                                        }
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(text = "${formatAmount(inst.amount)} $currency", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = statusColor)
                                        Surface(color = statusColor.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                                            Text(statusText, fontSize = 9.5.sp, color = statusColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!inst.isPaid) {
                                        Button(
                                            onClick = { installmentToPay = inst },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPersian) "ثبت پرداخت قسط" else "Pay Installment", fontSize = 10.5.sp)
                                        }
                                    }
                                    IconButton(onClick = { viewModel.deleteInstallment(inst) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.LightGray)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else if (currentSubTab == "documents") {
            ClinicalDocumentsTab(
                patient = patient,
                viewModel = viewModel,
                isPersian = isPersian
            )
        } else if (currentSubTab == "prescriptions") {
            // "نسخه پزشکی" Tab
            val rxListFlow = remember(patient.id) { viewModel.getPrescriptionsByPatient(patient.id) }
            val rxList by rxListFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (rxList.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isPersian) "هیچ نسخه پزشکی برای این بیمار ثبت نشده است." else "No prescriptions recorded.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                } else {
                    rxList.sortedByDescending { it.date }.forEach { rx ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(rx.treatmentTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                                        Text(viewModel.formatDate(rx.date), fontSize = 11.sp, color = Color.Gray)
                                    }
                                    if (rx.doctorApproved) {
                                        Icon(Icons.Default.VerifiedUser, contentDescription = "Approved", tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                                    }
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                
                                Text(if (isPersian) "ملاحظات دارویی (Notes):" else "Medication Considerations:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                if (rx.notes.isNotBlank()) {
                                    Text(rx.notes, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("-", fontSize = 11.sp, color = Color.Gray)
                                }
                                
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                
                                Text(if (isPersian) "نسخه مربوطه (Prescription):" else "Prescription Details:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                if (rx.analgesic.isNotBlank()) Text("💊 مسکن: ${rx.analgesic}", fontSize = 11.sp)
                                if (rx.antibiotic.isNotBlank()) Text("💊 آنتی‌بیوتیک: ${rx.antibiotic}", fontSize = 11.sp)
                                if (rx.mouthwash.isNotBlank()) Text("🧴 دهان‌شویه: ${rx.mouthwash}", fontSize = 11.sp)
                                if (rx.otherMeds.isNotBlank()) {
                                    val others = rx.otherMeds.split("|||").filter { it.isNotBlank() }
                                    others.forEachIndexed { idx, o -> 
                                        Text("➕ سایر (${idx+1}): ${parseRxToColumns(o, rx.durationDays).title}", fontSize = 11.sp)
                                    }
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(onClick = { editingRx = rx }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isPersian) "ویرایش" else "Edit", fontSize = 11.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val settingsState by viewModel.settings.collectAsStateWithLifecycle()
                                        val pRows = remember(rx) {
                                            val list = mutableListOf<com.example.reports.RxColumnRowData>()
                                            if (rx.analgesic.isNotBlank()) list.add(parseRxToColumns(rx.analgesic, rx.durationDays))
                                            if (rx.antibiotic.isNotBlank()) list.add(parseRxToColumns(rx.antibiotic, rx.durationDays))
                                            if (rx.mouthwash.isNotBlank()) list.add(parseRxToColumns(rx.mouthwash, rx.durationDays))
                                            if (rx.otherMeds.isNotBlank()) {
                                                rx.otherMeds.split("|||").filter { it.isNotBlank() }.forEach { 
                                                    list.add(parseRxToColumns(it, rx.durationDays))
                                                }
                                            }
                                            if (list.isEmpty()) list.add(com.example.reports.RxColumnRowData("نسخه دارویی", "۱", "طبق دستور", "روزانه", "-"))
                                            list
                                        }
                                        val pName = patient.name
                                        val pIdPhone = if (patient.patientCode.isNotBlank()) patient.patientCode else patient.phone
                                        val pDateStr = viewModel.formatDate(rx.date)

                                        OutlinedButton(
                                            onClick = {
                                                val txt = buildStandardRxShareText(
                                                    patientName = pName,
                                                    patientNationalIdOrPhone = pIdPhone,
                                                    dateStr = pDateStr,
                                                    durationDays = rx.durationDays,
                                                    rows = pRows,
                                                    notes = rx.publicNote,
                                                    clinicName = settingsState.clinicName,
                                                    doctorName = settingsState.doctorName,
                                                    gender = patient.gender
                                                )
                                                sendSmsPrefilled(context, patient.phone, txt)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "SMS", modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPersian) "پیامک" else "SMS", fontSize = 10.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                val cardFile = com.example.reports.generateStandardPrescriptionCardImage(
                                                    context = context,
                                                    patientName = honorificPatientName(pName, patient.gender),
                                                    patientNationalIdOrPhone = pIdPhone,
                                                    dateStr = pDateStr,
                                                    rows = pRows,
                                                    notes = rx.publicNote,
                                                    clinicName = settingsState.clinicName,
                                                    doctorName = settingsState.doctorName,
                                                    themeSpec = viewModel.currentExportTheme()
                                                )
                                                shareImageToWhatsApp(context, cardFile, patient.phone)
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF25D366)),
                                            border = BorderStroke(1.dp, Color(0xFF25D366).copy(alpha = 0.5f)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Icon(Icons.Default.Send, contentDescription = "WhatsApp", modifier = Modifier.size(14.dp), tint = Color(0xFF25D366))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPersian) "واتساپ (کارت)" else "WhatsApp", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            if (editingRx != null) {
                val currentRx = editingRx!!
                var eNotes by remember(currentRx) { mutableStateOf(currentRx.notes) }
                var eDurationStr by remember(currentRx) { mutableStateOf(currentRx.durationDays.toString()) }
                val gDur = eDurationStr.toIntOrNull() ?: currentRx.durationDays

                val eAnalgesicState = remember(currentRx) { RxItemEditState(currentRx.analgesic, gDur) }
                val eAntibioticState = remember(currentRx) { RxItemEditState(currentRx.antibiotic, gDur) }
                val eMouthwashState = remember(currentRx) { RxItemEditState(currentRx.mouthwash, gDur) }

                val eOtherMedsList = remember(currentRx) { mutableStateListOf<RxItemEditState>() }
                LaunchedEffect(currentRx) {
                    eOtherMedsList.clear()
                    val parts = currentRx.otherMeds.split("|||")
                    for (p in parts) {
                        if (p.isNotBlank()) {
                            eOtherMedsList.add(RxItemEditState(p, gDur))
                        }
                    }
                    if (eOtherMedsList.isEmpty()) {
                        eOtherMedsList.add(RxItemEditState("", gDur))
                    }
                }

                AlertDialog(
                    onDismissRequest = { editingRx = null },
                    title = { Text(if (isPersian) "ویرایش نسخه و ملاحظات" else "Edit Prescription", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = eDurationStr,
                                onValueChange = { eDurationStr = it },
                                label = { Text(if (isPersian) "طول درمان (روز)" else "Duration (Days)") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = eNotes,
                                onValueChange = { eNotes = it },
                                label = { Text(if (isPersian) "ملاحظات دارویی" else "Notes") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            RxItemEditorRow(
                                label = if (isPersian) "مسکن (Analgesic)" else "Analgesic",
                                state = eAnalgesicState
                            )
                            RxItemEditorRow(
                                label = if (isPersian) "آنتی‌بیوتیک (Antibiotic)" else "Antibiotic",
                                state = eAntibioticState
                            )
                            RxItemEditorRow(
                                label = if (isPersian) "دهان‌شویه (Mouthwash)" else "Mouthwash",
                                state = eMouthwashState
                            )

                            eOtherMedsList.forEachIndexed { idx, state ->
                                RxItemEditorRow(
                                    label = if (isPersian) "سایر داروها (${idx + 1})" else "Other Med (${idx + 1})",
                                    state = state,
                                    onDelete = if (eOtherMedsList.size > 1) { { eOtherMedsList.removeAt(idx) } } else null
                                )
                            }

                            TextButton(onClick = { eOtherMedsList.add(RxItemEditState("", gDur)) }) {
                                Text(if (isPersian) "➕ افزودن داروی جدید" else "➕ Add New Drug", fontSize = 11.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val updated = currentRx.copy(
                                analgesic = eAnalgesicState.toRawString(),
                                antibiotic = eAntibioticState.toRawString(),
                                mouthwash = eMouthwashState.toRawString(),
                                otherMeds = eOtherMedsList.map { it.toRawString() }.filter { it.isNotBlank() }.joinToString("|||"),
                                notes = eNotes,
                                publicNote = eNotes,
                                durationDays = eDurationStr.toIntOrNull() ?: 5,
                                doctorApproved = true
                            )
                            viewModel.savePrescription(updated) { editingRx = null }
                        }) {
                            Text(if (isPersian) "ذخیره" else "Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { editingRx = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
                    }
                )
            }
        } else {
            // Payments Sub-Tab
            if (showPaymentForm || paymentToEdit != null) {
                AddPaymentForm(
                    patientId = patient.id,
                    viewModel = viewModel,
                    existingPayment = paymentToEdit,
                    onSaved = {
                        showPaymentForm = false
                        paymentToEdit = null
                    }
                )
            } else {
                if (payments.isNotEmpty()) {
                    val context = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val items = payments.map { p ->
                                com.example.reports.SummaryLineItem(
                                    title = viewModel.label(p.type.lowercase()),
                                    subtitle = viewModel.formatDate(p.date),
                                    amountText = "${formatAmount(p.amount)} $currency"
                                )
                            }
                            val cardFile = com.example.reports.buildSummaryImage(
                                context = context,
                                cardTitle = if (isPersian) "صورتحساب پرداختی‌های مراجع" else "Patient Payment Statement",
                                clinicName = settings.clinicName.ifBlank { "کلینیک دندانپزشکی" },
                                doctorName = settings.doctorName.ifBlank { "دکتر" },
                                patientName = patient.name,
                                items = items,
                                footerLines = listOf(
                                    Pair(if (isPersian) "مجموع دریافتی" else "Total Paid", "${formatAmount(payments.postedTotal())} $currency")
                                ),
                                themeSpec = viewModel.currentExportTheme(),
                                fileNamePrefix = "payments_statement_${patient.id}",
                                gender = patient.gender
                            )
                            com.example.reports.shareImageNativeChooser(context, cardFile)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isPersian) "📤 ارسال / اشتراک‌گذاری صورت‌حساب پرداختی‌ها" else "📤 Share Payments Statement", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (payments.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = if (isPersian) "دریافتی ثبت نشده" else "No payments recorded", color = Color.Gray)
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        payments.forEach { payment ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "${viewModel.label("payment_type")}: ${viewModel.label(payment.type.lowercase())}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Text(text = viewModel.formatDate(payment.date), fontSize = 11.sp, color = Color.Gray)
                                            com.example.ui.components.AssistantNamesRow(
                                                assistantIds = payment.assistantIds,
                                                allStaff = allStaffMembersForDetails,
                                                isPersian = isPersian,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                            if (payment.type == "CHEQUE") {
                                                Text(
                                                    text = "${viewModel.label("cheque_number")}: ${payment.chequeNumber ?: ""}",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Gray
                                                )
                                                if (!payment.sayadiId.isNullOrBlank()) {
                                                    Text(
                                                        text = "شناسه صیادی: ${payment.sayadiId}",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                payment.chequeDueDate?.let {
                                                    Text(
                                                        text = "${viewModel.label("cheque_due_date")}: ${viewModel.formatDate(it)}",
                                                        fontSize = 11.sp,
                                                        color = DebtColor
                                                    )
                                                }
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(text = "${formatAmount(payment.amount)} $currency", fontWeight = FontWeight.Bold, color = ClearColor)
                                            // انتقال چک — فقط برای چک‌های صیادی که هنوز برگشتی/انتقال‌یافته نشده‌اند
                                            if (viewModel.canTransferCheque(payment)) {
                                                IconButton(onClick = { chequeToTransfer = payment }) {
                                                    Icon(
                                                        Icons.Default.SwapHoriz,
                                                        contentDescription = if (isPersian) "انتقال چک" else "Transfer cheque",
                                                        tint = MaterialTheme.colorScheme.tertiary
                                                    )
                                                }
                                            }
                                            IconButton(onClick = { paymentToEdit = payment }) {
                                                Icon(Icons.Default.Edit, contentDescription = if (isPersian) "ویرایش پرداخت" else "Edit payment", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick = { paymentToDelete = payment }) {
                                                Icon(Icons.Default.Delete, contentDescription = if (isPersian) "حذف پرداخت" else "Delete payment", tint = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                    if (payment.type == "CHEQUE" && !payment.chequePhotoFileId.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        SmartImage(
                                            fileId = payment.chequePhotoFileId,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(110.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    if (treatmentToDelete != null) {
        AlertDialog(
            onDismissRequest = { treatmentToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = if (isPersian) "تایید حذف درمان" else "Confirm Treatment Deletion",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Text(
                    text = if (isPersian) "آیا از حذف درمان «${treatmentToDelete!!.title}» برای بیمار اطمینان دارید؟"
                    else "Are you sure you want to delete treatment '${treatmentToDelete!!.title}'?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val deleted = treatmentToDelete!!
                        viewModel.deleteTreatment(deleted)
                        treatmentToDelete = null
                        showUndoSnackbar(
                            scope = coroutineScope,
                            hostState = snackbarHostState,
                            message = if (isPersian) "درمان «${deleted.title}» حذف شد" else "'${deleted.title}' deleted",
                            actionLabel = if (isPersian) "بازگردانی" else "Undo",
                            onUndo = { viewModel.undoDeleteTreatment(deleted) }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف درمان" else "Delete Treatment", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { treatmentToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    if (paymentToDelete != null) {
        AlertDialog(
            onDismissRequest = { paymentToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = if (isPersian) "تایید حذف تراکنش پرداخت" else "Confirm Payment Deletion",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Text(
                    text = if (isPersian) "آیا از حذف تراکنش پرداخت به مبلغ «${formatAmount(paymentToDelete!!.amount)} $currency» اطمینان دارید؟"
                    else "Are you sure you want to delete this payment of ${formatAmount(paymentToDelete!!.amount)} $currency?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val deleted = paymentToDelete!!
                        viewModel.deletePayment(deleted)
                        paymentToDelete = null
                        showUndoSnackbar(
                            scope = coroutineScope,
                            hostState = snackbarHostState,
                            message = if (isPersian) "پرداخت «${formatAmount(deleted.amount)} $currency» حذف شد" else "Payment of ${formatAmount(deleted.amount)} $currency deleted",
                            actionLabel = if (isPersian) "بازگردانی" else "Undo",
                            onUndo = { viewModel.undoDeletePayment(deleted) }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف پرداخت" else "Delete Payment", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { paymentToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    // انتقال چک — با اثر واقعی و تأثیرگذار بر حسابداری (هزینه مطب یا حساب شخصی/اشخاص)
    if (chequeToTransfer != null) {
        ChequeTransferDialog(
            viewModel = viewModel,
            isPersian = isPersian,
            cheque = chequeToTransfer!!,
            onDismiss = { chequeToTransfer = null }
        )
    }

    if (treatmentToEdit != null) {
        EditTreatmentDialog(
            treatment = treatmentToEdit!!,
            viewModel = viewModel,
            onDelete = {
                treatmentToDelete = treatmentToEdit
                treatmentToEdit = null
            },
            onDismiss = { treatmentToEdit = null }
        )
    }

    if (selectedProtocolTitleForPatientDetail != null) {
        ClinicalProtocolDialog(
            therapyTitle = selectedProtocolTitleForPatientDetail!!,
            patientMedicalHistory = patient.medicalHistory,
            patientMedicalAlerts = patient.medicalAlerts,
            isPersian = isPersian,
            initialFontSizePt = settings.clinicalFontSizePt,
            onDismiss = { selectedProtocolTitleForPatientDetail = null }
        )
    }

    if (showAddInstallmentDialog) {
        AddInstallmentDialog(
            patientId = patient.id,
            viewModel = viewModel,
            onDismiss = { showAddInstallmentDialog = false }
        )
    }
}
}

// ==========================================
// ADD INSTALLMENT DIALOG
// ==========================================
@Composable
fun AddInstallmentDialog(
    patientId: Long,
    viewModel: DentalViewModel,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("قسط درمان دندانپزشکی") }
    var amountStr by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var daysOffset by remember { mutableStateOf("30") }
    var installmentCountStr by remember { mutableStateOf("1") }
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPersian) "➕ ثبت اقساط جدید برای بیمار" else "➕ Add Patient Installment(s)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isPersian) "عنوان قسط" else "Installment Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text(if (isPersian) "مبلغ هر قسط (تومان)" else "Amount per Installment") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = installmentCountStr,
                        onValueChange = { installmentCountStr = it },
                        label = { Text(if (isPersian) "تعداد اقساط (N)" else "Count (N)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = daysOffset,
                        onValueChange = { daysOffset = it },
                        label = { Text(if (isPersian) "فاصله اقساط (روز)" else "Interval (Days)") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(if (isPersian) "توضیحات یا شماره چک/ضمانت" else "Note / Cheque Info") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountStr.toEnglishLong()
                    val offset = daysOffset.toEnglishLong().coerceAtLeast(1)
                    val count = installmentCountStr.toEnglishLong().coerceAtLeast(1).toInt()
                    if (amount > 0) {
                        for (i in 1..count) {
                            val dueDate = System.currentTimeMillis() + (i * offset * 24 * 3600 * 1000L)
                            val itemTitle = if (count > 1) "$title (قسط $i از $count)" else title
                            viewModel.addInstallment(
                                patientId = patientId,
                                treatmentId = null,
                                title = itemTitle,
                                amount = amount,
                                dueDate = dueDate,
                                note = note
                            )
                        }
                        onDismiss()
                    }
                },
                enabled = amountStr.toEnglishLong() > 0
            ) {
                Text(if (isPersian) "ثبت اقساط" else "Save Installments")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (isPersian) "انصراف" else "Cancel")
            }
        }
    )
}

@Composable
fun EditTreatmentDialog(
    treatment: Treatment,
    viewModel: DentalViewModel,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    var title by remember { mutableStateOf(treatment.title) }
    var costStr by remember { mutableStateOf(treatment.cost.toString()) }
    var discountStr by remember { mutableStateOf(treatment.discount.toString()) }
    var selectedTeeth by remember { mutableStateOf(treatment.selectedTeeth) }
    var notes by remember { mutableStateOf(treatment.notes) }
    var status by remember { mutableStateOf(treatment.status) }
    var stages by remember { mutableStateOf(treatment.stages) }
    val allStaffMembersForEdit by viewModel.allStaffMembers.collectAsStateWithLifecycle()
    var selectedAssistantSyncIds by remember { mutableStateOf(com.example.ui.components.parseAssistantSyncIds(treatment.assistantIds)) }
    // پیش‌فرض: نام دستیار فعال به‌صورت خودکار وارد می‌شود — فقط اگر هنوز دستیاری انتخاب نشده باشد (درخواست ۶)
    val defaultAssistantSyncIds by viewModel.activeAssistantSyncIdsFlow.collectAsStateWithLifecycle()
    LaunchedEffect(defaultAssistantSyncIds) {
        if (selectedAssistantSyncIds.isEmpty() && defaultAssistantSyncIds.isNotEmpty()) {
            selectedAssistantSyncIds = defaultAssistantSyncIds
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPersian) "ویرایش طرح درمان" else "Edit Treatment Plan", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(if (isPersian) "عنوان درمان" else "Treatment Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = costStr,
                        onValueChange = { costStr = it },
                        label = { Text(if (isPersian) "هزینه (تومان)" else "Cost") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = discountStr,
                        onValueChange = { discountStr = it },
                        label = { Text(if (isPersian) "تخفیف (تومان)" else "Discount") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }
                OutlinedTextField(
                    value = selectedTeeth,
                    onValueChange = { selectedTeeth = it },
                    label = { Text(if (isPersian) "دندان‌های مرتبط (مثلاً 11, 12)" else "Teeth") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = stages,
                    onValueChange = { stages = it },
                    label = { Text(if (isPersian) "مراحل درمان (با | جدا کنید)" else "Treatment Stages (separated by |)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (isPersian) "وضعیت طرح درمان:" else "Treatment Status:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val statusOptions = listOf(
                            "PLANNED" to (if (isPersian) "برنامه‌ریزی شده" else "Planned"),
                            "IN_PROGRESS" to (if (isPersian) "در حال انجام" else "In Progress"),
                            "COMPLETED" to (if (isPersian) "تمام شده" else "Completed")
                        )
                        statusOptions.forEach { (stKey, stLabel) ->
                            FilterChip(
                                selected = status == stKey,
                                onClick = { status = stKey },
                                label = { Text(stLabel, fontSize = 11.sp, fontWeight = if (status == stKey) FontWeight.Bold else FontWeight.Normal) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                com.example.ui.components.AssistantMultiSelectField(
                    selectedSyncIds = selectedAssistantSyncIds,
                    onSelectionChange = { selectedAssistantSyncIds = it },
                    allStaff = allStaffMembersForEdit,
                    isPersian = isPersian
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(if (isPersian) "توضیحات و ملاحظات بالینی" else "Clinical Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cost = costStr.toEnglishLong()
                    val discount = discountStr.toEnglishLong()
                    if (title.isNotBlank()) {
                        viewModel.updateTreatment(
                            treatment.copy(
                                title = title.trim(),
                                cost = cost,
                                discount = discount,
                                selectedTeeth = selectedTeeth.trim(),
                                notes = notes.trim(),
                                status = status,
                                stages = stages.trim(),
                                assistantIds = com.example.ui.components.joinAssistantSyncIds(selectedAssistantSyncIds)
                            )
                        )
                        onDismiss()
                    }
                }
            ) {
                Text(if (isPersian) "ذخیره تغییرات" else "Save Changes")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onDelete()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPersian) "حذف طرح درمان" else "Delete", fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onDismiss) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        }
    )
}

// ----------------------------------------------------
// ULTRA FAST INPUTS: PRE-FILLED TREATMENT TEMPLATES
// ----------------------------------------------------
@Composable
fun AddTreatmentForm(
    patient: com.example.data.Patient,
    viewModel: DentalViewModel,
    onSaved: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var costStr by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var discountStr by remember { mutableStateOf("") }
    var selectedTeeth by remember { mutableStateOf("") }
    var attemptedSubmit by remember { mutableStateOf(false) }
    val titleError = if (title.isBlank()) "شرح درمان الزامی است" else null
    val costError = if (!com.example.util.isValidAmount(costStr)) "هزینه باید یک عدد معتبر و غیرمنفی باشد" else null

    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val customTemplates by viewModel.templates.collectAsStateWithLifecycle()
    val chartSystem by viewModel.dentalChartSystem.collectAsStateWithLifecycle()

    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val categories = listOf("عصب‌کشی", "روکش", "کشیدن", "ترمیمی", "سایر موارد")

    val allStaffMembersForTreatment by viewModel.allStaffMembers.collectAsStateWithLifecycle()
    var selectedAssistantSyncIds by remember { mutableStateOf(listOf<String>()) }
    // پیش‌فرض: نام دستیار فعال به‌صورت خودکار در تمام فعالیت‌ها وارد می‌شود (درخواست ۶)
    val defaultAssistantSyncIds by viewModel.activeAssistantSyncIdsFlow.collectAsStateWithLifecycle()
    LaunchedEffect(defaultAssistantSyncIds) {
        if (selectedAssistantSyncIds.isEmpty() && defaultAssistantSyncIds.isNotEmpty()) {
            selectedAssistantSyncIds = defaultAssistantSyncIds
        }
    }

    val filteredTemplates = remember(customTemplates, selectedCategory) {
        val filtered = if (selectedCategory == null) customTemplates else {
            customTemplates.filter { temp ->
                val faTitle = temp.titleFa.ifBlank { temp.title }
                when (selectedCategory) {
                    "عصب‌کشی" -> faTitle.contains("عصب")
                    "روکش" -> faTitle.contains("روکش")
                    "کشیدن" -> faTitle.contains("کشیدن")
                    "ترمیمی" -> faTitle.contains("پرکردن") || faTitle.contains("ترمیم") || faTitle.contains("کامپوزیت") || faTitle.contains("آمالگام")
                    "سایر موارد" -> !(faTitle.contains("عصب") || faTitle.contains("روکش") || faTitle.contains("کشیدن") || faTitle.contains("پرکردن") || faTitle.contains("ترمیم") || faTitle.contains("کامپوزیت") || faTitle.contains("آمالگام"))
                    else -> true
                }
            }
        }
        filtered
    }

    val starredTemplatesList = filteredTemplates.filter { it.isStarred }.sortedBy { it.sortOrder }
    val generalTemplatesList = filteredTemplates.filter { !it.isStarred }.sortedBy { it.sortOrder }
    val allCustomTemplates = (starredTemplatesList + generalTemplatesList)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (customTemplates.isNotEmpty()) {
                var templateDropdownExpanded by remember { mutableStateOf(false) }

                if (isPersian) {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("همه الگوها", fontSize = 11.sp) }
                            )
                        }
                        items(categories.size) { idx ->
                            val cat = categories[idx]
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = cat },
                                label = { Text(cat, fontSize = 11.sp) }
                            )
                        }
                    }
                }
                
                OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                androidx.compose.material3.ExposedDropdownMenuBox(
                    expanded = templateDropdownExpanded,
                    onExpandedChange = { templateDropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val currentTemplate = allCustomTemplates.find { it.title == title }
                    OutlinedTextField(
                        value = if (isPersian) (currentTemplate?.titleFa?.ifBlank { currentTemplate.title } ?: "انتخاب الگوی درمان...") else (currentTemplate?.title ?: "Select template..."),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = templateDropdownExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable, true),
                        colors = androidx.compose.material3.ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        label = { Text(if (isPersian) "الگوهای آماده برای ورود سریع:" else "Quick Entry Templates:") }
                    )
                    ExposedDropdownMenu(
                        expanded = templateDropdownExpanded,
                        onDismissRequest = { templateDropdownExpanded = false }
                    ) {
                        allCustomTemplates.forEach { temp ->
                            val isStarred = temp.isStarred
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (isStarred) {
                                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                                        }
                                        Text(
                                            if (isPersian) temp.titleFa.ifBlank { temp.title } else temp.title,
                                            fontSize = 13.sp,
                                            fontWeight = if (isStarred) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                },
                                onClick = {
                                    title = temp.title
                                    costStr = temp.defaultCost.toString()
                                    note = temp.defaultNotes
                                    templateDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(viewModel.label("treatment_title")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = attemptedSubmit && titleError != null,
                supportingText = {
                    if (attemptedSubmit && titleError != null) {
                        Text(titleError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = costStr,
                        onValueChange = { costStr = it },
                        label = { Text(if (viewModel.isPersian.collectAsStateWithLifecycle().value) "هزینه واحد (تومان)" else "Unit Cost (Toman)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = attemptedSubmit && costError != null,
                        supportingText = {
                            if (attemptedSubmit && costError != null) {
                                Text(costError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            }
                        }
                    )
                    RealTimeAmountHelper(costStr)
                }
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = discountStr,
                        onValueChange = { discountStr = it },
                        label = { Text(if (isPersian) "تخفیف (تومان)" else "Discount (Toman)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    RealTimeAmountHelper(discountStr)
                }
            }

            // چارت دندانی — دورهٔ دندانی از روی سن بیمار تعیین می‌شود
            // (تاریخ تولد مقدم بر عدد ذخیره‌شدهٔ سن است). در دورهٔ مختلط،
            // چارت برای هر جایگاه دندانِ محتمل در آن سن را نشان می‌دهد و
            // کاربر می‌تواند با کلیدهای بالای چارت دستی سوئیچ کند.
            val patientAgeForChart = remember(patient.birthDate, patient.age) {
                effectivePatientAge(patient)
            }
            // حالت پیش‌فرض چارت از روی سن: زیر ۶ سال شیری، ۶ تا ۱۲ سال چارت
            // ترکیبی (هر جایگاه بر اساس جدول رویش AAPD)، بالای ۱۲ سال دائمی.
            var chartMode by remember(patient.id) {
                mutableStateOf(dentitionForAge(patientAgeForChart))
            }
            InteractiveToothChart(
                selectedTeeth = selectedTeeth,
                onTeethChanged = { selectedTeeth = it },
                system = chartSystem,
                isDeciduous = chartMode == Dentition.PRIMARY,
                dentitionLabel = dentitionSummaryLabel(patientAgeForChart),
                allowDentitionToggle = true,
                patientAge = patientAgeForChart,
                chartMode = chartMode,
                onChartModeChange = { chartMode = it }
            )

            com.example.ui.components.AssistantMultiSelectField(
                selectedSyncIds = selectedAssistantSyncIds,
                onSelectionChange = { selectedAssistantSyncIds = it },
                allStaff = allStaffMembersForTreatment,
                isPersian = isPersian
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(viewModel.label("notes")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = {
                    attemptedSubmit = true
                    val cost = costStr.toEnglishLong()
                    val discount = discountStr.toEnglishLong()
                    if (titleError == null && costError == null) {
                        var finalNote = note
                        val lowerTitle = title.lowercase()
                        if ((lowerTitle.contains("عصب") || lowerTitle.contains("rct") || lowerTitle.contains("endo") || lowerTitle.contains("ریشه") || lowerTitle.contains("کانال"))
                            && !finalNote.contains("[ENDO_WORKING_LENGTH:")) {
                            val defaultCanals = getDefaultCanalsForTeeth(selectedTeeth)
                            finalNote = encodeEndoCanalData(finalNote, defaultCanals)
                        }

                        viewModel.addTreatmentWithDetails(
                            patientId = patient.id,
                            title = title,
                            cost = cost,
                            discount = discount,
                            selectedTeeth = selectedTeeth,
                            notes = finalNote,
                            assistantIds = com.example.ui.components.joinAssistantSyncIds(selectedAssistantSyncIds)
                        )
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(viewModel.label("save"), fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
fun AddPaymentForm(
    patientId: Long,
    viewModel: DentalViewModel,
    onSaved: () -> Unit,
    initialPaymentType: String = "CASH",
    existingPayment: Payment? = null
) {
    val editKey = existingPayment?.id
    var amountStr by remember(editKey) { mutableStateOf(existingPayment?.amount?.takeIf { it > 0 }?.toString() ?: "") }
    var attemptedSubmit by remember { mutableStateOf(false) }
    val amountError = if (!com.example.util.isValidPositiveAmount(amountStr)) "مبلغ باید یک عدد معتبر و بزرگ‌تر از صفر باشد" else null
    var paymentType by remember(editKey) { mutableStateOf(existingPayment?.type ?: initialPaymentType) } // CASH, CARD, CHEQUE
    var chequeNum by remember(editKey) { mutableStateOf(existingPayment?.chequeNumber.orEmpty()) }
    var chequeDueDays by remember { mutableStateOf("30") } // e.g. due in 30 days
    var sayadiId by remember(editKey) { mutableStateOf(existingPayment?.sayadiId.orEmpty()) }
    var chequeBank by remember(editKey) { mutableStateOf(existingPayment?.chequeBank.orEmpty()) }
    var chequeDrawer by remember(editKey) { mutableStateOf(existingPayment?.chequeDrawer.orEmpty()) }
    var chequePhotoFileId by remember(editKey) { mutableStateOf(existingPayment?.chequePhotoFileId) }
    var selectedAccountId by remember(editKey) { mutableStateOf(existingPayment?.bankAccountId) }
    var selectedDepositSmsId by remember { mutableStateOf<String?>(null) }
    var paymentDate by remember(editKey) { mutableLongStateOf(existingPayment?.date ?: System.currentTimeMillis()) }
    val bankAccounts by viewModel.bankAccounts.collectAsStateWithLifecycle()
    val allPaymentsForPos by viewModel.allPayments.collectAsStateWithLifecycle()
    var showScannerSim by remember { mutableStateOf(false) }
    val allStaffMembersForPayment by viewModel.allStaffMembers.collectAsStateWithLifecycle()
    var selectedAssistantSyncIds by remember(editKey) {
        mutableStateOf(
            existingPayment?.assistantIds
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: listOf()
        )
    }
    // پیش‌فرض: نام دستیار فعال به‌صورت خودکار در تمام فعالیت‌ها وارد می‌شود (درخواست ۶)
    val defaultAssistantSyncIds by viewModel.activeAssistantSyncIdsFlow.collectAsStateWithLifecycle()
    LaunchedEffect(defaultAssistantSyncIds, editKey) {
        if (existingPayment == null && selectedAssistantSyncIds.isEmpty() && defaultAssistantSyncIds.isNotEmpty()) {
            selectedAssistantSyncIds = defaultAssistantSyncIds
        }
    }

    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val context = androidx.compose.ui.platform.LocalContext.current
    val currency = viewModel.label("currency")


    val contentResolver = context.contentResolver

    // تقویم شمسی برای تاریخ تراکنش (جایگزین DatePickerDialog میلادی)
    var showPaymentJalaliPicker by remember { mutableStateOf(false) }
    if (showPaymentJalaliPicker) {
        JalaliDatePickerDialog(
            initialTimestamp = paymentDate,
            isPersian = isPersian,
            onDateSelected = { jy, jm, jd ->
                val keepCal = java.util.Calendar.getInstance().apply { timeInMillis = paymentDate }
                paymentDate = com.example.util.PersianCalendarHelper.jalaliToTimestamp(
                    jy, jm, jd,
                    keepCal.get(java.util.Calendar.HOUR_OF_DAY),
                    keepCal.get(java.util.Calendar.MINUTE)
                )
                showPaymentJalaliPicker = false
            },
            onDismiss = { showPaymentJalaliPicker = false }
        )
    }

    // Camera launcher
    val chequePhotoEditLauncher = rememberPhotoEditLauncher(PhotoAssetKind.SAYAD_CHEQUE) { localPath ->
        chequePhotoFileId = localPath
    }
    val launchCamera = rememberEditableCameraLauncher(context) { uri ->
        chequePhotoEditLauncher(uri)
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            chequePhotoEditLauncher(uri)
        }
    }

    // درخواست ۳-۱: دکمه «استخراج و ثبت هوشمند تراکنش از پیامک بانکی» از بخش پرداختی
    // بیماران حذف شد — پیامک‌ها از مسیر «پیامک‌ها» در داشبورد و بخش بانک‌ها مدیریت می‌شوند.

    if (showScannerSim) {
        RealSayadiBarcodeScannerDialog(
            onDismiss = { showScannerSim = false },
            onScanned = { scanned16DigitId ->
                sayadiId = scanned16DigitId
                showScannerSim = false
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (existingPayment != null) {
                Text(
                    text = if (isPersian) "ویرایش پرداخت ثبت‌شده" else "Edit recorded payment",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // 1. Payment Method Selector Row AT THE TOP
            Text(text = if (isPersian) "روش دریافت / پرداخت:" else "Payment Method:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "CASH" to (if (isPersian) "نقد (صندوق)" else "Cash"),
                    "CARD_TRANSFER" to (if (isPersian) "انتقال وجه" else "Fund Transfer"),
                    "CHEQUE" to (if (isPersian) "چک صیادی" else "Cheque"),
                    "POS" to (if (isPersian) "کارتخوان" else "POS Card")
                ).forEach { (type, labelText) ->
                    ElevatedFilterChip(
                        selected = paymentType == type,
                        onClick = { paymentType = type },
                        label = { Text(labelText, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 2. Amount and Date Inputs directly below Payment Method
            // (برای چک صیادی این فیلدهای «تراکنش» حذف می‌شوند — مبلغ/تاریخ از فرم خودِ چک می‌آید؛ درخواست ۸)
            if (paymentType != "CHEQUE" || existingPayment != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Amount Input
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text(viewModel.label("amount")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = attemptedSubmit && amountError != null,
                        supportingText = {
                            if (attemptedSubmit && amountError != null) {
                                Text(amountError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                            }
                        }
                    )
                    RealTimeAmountHelper(amountStr)
                }

                // Date Input
                OutlinedTextField(
                    value = viewModel.formatDate(paymentDate),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (isPersian) "تاریخ تراکنش" else "Date") },
                    trailingIcon = {
                        IconButton(onClick = { showPaymentJalaliPicker = true }) {
                            Icon(Icons.Default.CalendarToday, contentDescription = "Date", tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.weight(1f).clickable { showPaymentJalaliPicker = true },
                    textStyle = TextStyle(fontSize = 12.sp)
                )
            }
            }

            // If Cheque, show comprehensive sayadi cheque fields

            if (paymentType == "POS") {
                // سیستم کارتخوان (درخواست کاربر): تراکنش کارتخوان هنگام ثبت «حساب» نمی‌گیرد؛
                // همه‌ی تراکنش‌های همان روز در یک ردیف خلاصه نمایش داده می‌شوند و روز بعد در
                // پاپ‌آپ تسویه، جمع روز به حساب انتخابی واریز و بایگانی می‌شود.
                val todayKey = com.example.util.PersianCalendarHelper.jalaliKeyOf(System.currentTimeMillis())
                val todayPosRows = allPaymentsForPos.filter {
                    !it.isDeleted && it.type == "POS" && it.bankAccountId == null &&
                        com.example.util.PersianCalendarHelper.jalaliKeyOf(it.date) == todayKey
                }
                val todaySum = todayPosRows.sumOf { it.amount }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isPersian)
                                "🧾 تراکنش‌های امروز کارتخوان: ${todayPosRows.size} مورد — جمع: ${formatAmount(todaySum)} $currency"
                            else "Today's POS transactions: ${todayPosRows.size} — total ${formatAmount(todaySum)} $currency",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isPersian)
                                "این تراکنش به جمع امروز اضافه می‌شود؛ روز بعد در شروع اپ، پاپ‌آپ تسویه حساب مقصد را می‌پرسد و پس از تایید، تراکنش‌ها با حفظ طرف‌حساب هر کدام، بایگانی می‌شوند."
                            else "This transaction joins today's POS batch; tomorrow a settlement popup asks for the destination account.",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                }
            }
            if (paymentType == "CARD_TRANSFER") {
                // فقط حساب‌ها/کارت‌های واقعاً بانکی — صندوق نقدی و تنخواه گردان کارت نیستند
                val pureBankAccounts = bankAccounts.filter {
                    !(it.bankName.contains("صندوق") || it.bankName.contains("تنخواه") || it.accountNumber.startsWith("CASH") || it.accountNumber.startsWith("PETTY"))
                }
                if (pureBankAccounts.isEmpty()) {
                    Text(text = if (isPersian) "ابتدا در بخش مدیریت مالی، حساب/کارت بانکی تعریف کنید" else "Please define a bank account in financial management first", fontSize = 11.sp, color = Color.Gray)
                } else {
                    Text(text = if (isPersian) "انتخاب حساب/کارت مقصد:" else "Select Destination Account/Card:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pureBankAccounts.forEach { acc ->
                            InputChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = acc.id },
                                label = { Text("${acc.bankName} - ${acc.cardHolder}", fontSize = 11.sp) }
                            )
                        }
                    }
                }
                val pendingDeposits by viewModel.unregisteredBankSmsList.collectAsStateWithLifecycle()
                val depositSms = pendingDeposits.filter { !it.isDeleted && it.type == "DEPOSIT" }
                if (depositSms.isNotEmpty()) {
                    Text(
                        text = if (isPersian) "پیامک‌های واریز ثبت‌نشده:" else "Unregistered deposit SMS:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        depositSms.take(8).forEach { sms ->
                            val selected = selectedDepositSmsId == sms.smsNativeId
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                                onClick = {
                                    selectedDepositSmsId = if (selected) null else sms.smsNativeId
                                    if (!selected) {
                                        amountStr = sms.amountTomans.toString()
                                    }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${sms.bankNameGuess} — ${sms.dateStr} ${sms.timeStr}",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${formatAmount(sms.amountTomans)} $currency",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (paymentType == "CASH") {
                // دریافتی نقدی: ثبت اختیاری در «تنخواه گردان مطب» یا «کیف پول»
                val cashDestinationAccounts = bankAccounts.filter {
                    it.bankName.contains("صندوق") || it.bankName.contains("تنخواه") || it.accountNumber.startsWith("CASH") || it.accountNumber.startsWith("PETTY")
                }
                if (cashDestinationAccounts.isNotEmpty()) {
                    Text(text = if (isPersian) "ثبت در صندوق (اختیاری):" else "Cash destination (optional):", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        cashDestinationAccounts.forEach { acc ->
                            InputChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = if (selectedAccountId == acc.id) null else acc.id },
                                label = { Text(acc.bankName, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
            if (paymentType == "CHEQUE") {
                var showBatchChequeUpload by remember { mutableStateOf(false) }
                var showManualChequeRegister by remember { mutableStateOf(false) }
                var showRealScanner by remember { mutableStateOf(false) }

                if (showRealScanner) {
                    RealSayadiBarcodeScannerDialog(
                        onDismiss = { showRealScanner = false },
                        onScanned = { scannedId ->
                            sayadiId = scannedId
                            showRealScanner = false
                        }
                    )
                }

                if (showBatchChequeUpload) {
                    BatchChequeUploadDialog(
                        patientId = patientId,
                        viewModel = viewModel,
                        isPersian = true,
                        onDismiss = { showBatchChequeUpload = false; onSaved() }
                    )
                }

                if (showManualChequeRegister) {
                    ManualChequeRegisterDialog(
                        patientId = patientId,
                        viewModel = viewModel,
                        isPersian = true,
                        onDismiss = { showManualChequeRegister = false },
                        onSaved = onSaved
                    )
                }

                OutlinedButton(
                    onClick = { showBatchChequeUpload = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPersian) "ثبت هوشمند دسته‌ای چک‌ها" else "AI Smart Batch Cheque Registration", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // ثبت دستی-بارکد چک‌ها — همان فرم تایید استخراج هوشمند (درخواست ۹)
                OutlinedButton(
                    onClick = { showManualChequeRegister = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPersian) "ثبت دستی-بارکد چک‌ها" else "Manual Cheque Registration", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = sayadiId,
                        onValueChange = { sayadiId = it },
                        label = { Text(if (isPersian) "شناسه ۱۶ رقمی صیادی" else "16-digit Sayadi ID") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    IconButton(onClick = { showRealScanner = true }) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan Barcode",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // درخواست ۳-۳: چهار فیلد «شماره چک / تاریخ سررسید / بانک صادرکننده / نام
                // صادرکننده» به‌همراه «تصویر چک صیادی + دکمه‌های دوربین و گالری» حذف شدند —
                // این مشخصات فقط در فرم «ثبت هوشمند دسته‌ای» یا «ثبت دستی-بارکد چک‌ها» وارد
                // می‌شوند که فرم کامل خودشان را دارند.
            }

            com.example.ui.components.AssistantMultiSelectField(
                selectedSyncIds = selectedAssistantSyncIds,
                onSelectionChange = { selectedAssistantSyncIds = it },
                allStaff = allStaffMembersForPayment,
                isPersian = isPersian
            )

            if (paymentType == "CHEQUE" && existingPayment == null) {
                // برای چک صیادی، ثبت فقط از طریق «ثبت هوشمند دسته‌ای» یا «ثبت دستی-بارکد چک‌ها» انجام می‌شود (درخواست ۸)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = if (isPersian)
                            "ℹ️ مبلغ و تاریخ چک از فرم بالا ثبت می‌شود — دکمه ذخیره برای سایر روش‌های پرداخت است."
                        else
                            "ℹ️ Cheque amount/date are registered via the buttons above — the save button applies to other payment methods.",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            } else {
            Button(
                onClick = {
                    attemptedSubmit = true
                    val amount = amountStr.toEnglishLong()
                    if (amountError == null) {
                        val dueTimestamp = if (paymentType == "CHEQUE") {
                            existingPayment?.chequeDueDate ?: run {
                                val days = chequeDueDays.toEnglishLong()
                                System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L)
                            }
                        } else existingPayment?.chequeDueDate

                        val assistantJoined = com.example.ui.components.joinAssistantSyncIds(selectedAssistantSyncIds)
                        if (existingPayment != null) {
                            viewModel.updatePayment(
                                existingPayment.copy(
                                    amount = amount,
                                    type = paymentType,
                                    bankAccountId = selectedAccountId,
                                    chequeNumber = if (paymentType == "CHEQUE") (chequeNum.takeIf { it.isNotBlank() } ?: existingPayment.chequeNumber) else existingPayment.chequeNumber,
                                    chequeDueDate = dueTimestamp,
                                    date = paymentDate,
                                    isCleared = if (paymentType == "CHEQUE") existingPayment.isCleared else true,
                                    sayadiId = if (paymentType == "CHEQUE") (sayadiId.takeIf { it.isNotBlank() } ?: existingPayment.sayadiId) else existingPayment.sayadiId,
                                    chequeBank = if (paymentType == "CHEQUE") (chequeBank.takeIf { it.isNotBlank() } ?: existingPayment.chequeBank) else existingPayment.chequeBank,
                                    chequeDrawer = if (paymentType == "CHEQUE") (chequeDrawer.takeIf { it.isNotBlank() } ?: existingPayment.chequeDrawer) else existingPayment.chequeDrawer,
                                    chequePhotoFileId = if (paymentType == "CHEQUE") chequePhotoFileId else existingPayment.chequePhotoFileId,
                                    assistantIds = assistantJoined
                                )
                            )
                        } else {
                            viewModel.addPaymentWithAccount(
                                bankAccountId = selectedAccountId,
                                patientId = patientId,
                                amount = amount,
                                type = paymentType,
                                chequeNum = if (paymentType == "CHEQUE") chequeNum else null,
                                chequeDue = dueTimestamp,
                                sayadiId = if (paymentType == "CHEQUE") sayadiId else null,
                                chequeBank = if (paymentType == "CHEQUE") chequeBank else null,
                                chequeDrawer = if (paymentType == "CHEQUE") chequeDrawer else null,
                                chequePhotoFileId = if (paymentType == "CHEQUE") chequePhotoFileId else null,
                                date = paymentDate,
                                assistantIds = assistantJoined
                            )
                        }
                        onSaved()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (existingPayment != null) (if (isPersian) "بروزرسانی پرداخت" else "Update payment")
                    else viewModel.label("save"),
                    fontWeight = FontWeight.Bold
                )
            }
            }
        }
    }
    
}

// ===== 3. APPOINTMENTS SCHEDULER =====

/**
 * تقویم پاپ‌آپ شمسی (جلالی) — جایگزینِ DatePickerDialog میلادی اندروید.
 * هفته از شنبه شروع می‌شود، جمعه‌ها قرمز، «امروز» و روزِ انتخاب‌شده هایلایت می‌شوند.
 */
@Composable
fun JalaliDatePickerDialog(
    initialTimestamp: Long = System.currentTimeMillis(),
    isPersian: Boolean = true,
    onDateSelected: (jy: Int, jm: Int, jd: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val helper = com.example.util.PersianCalendarHelper
    val todayJ = remember { helper.timestampToJalali(System.currentTimeMillis()) }
    val initialJ = remember { helper.timestampToJalali(initialTimestamp) }

    var viewYear by remember { mutableStateOf(initialJ.year) }
    var viewMonth by remember { mutableStateOf(initialJ.month) }
    var selectedDay by remember { mutableStateOf(initialJ.day) }

    val daysInMonth = helper.getDaysInMonth(viewYear, viewMonth)
    val leadingBlanks = helper.getDayOfWeekIndex(viewYear, viewMonth, 1)
    val weekRows = remember(daysInMonth, leadingBlanks) {
        ((0 until leadingBlanks).map { null } + (1..daysInMonth).toList()).chunked(7)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isPersian) "تقویم شمسی" else "Jalali Calendar",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // هدر ماه/سال + ناوبری ماه قبل/بعد (در چیدمان راست‌به‌چپ: راست = ماه قبل)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (viewMonth == 1) { viewMonth = 12; viewYear -= 1 } else viewMonth -= 1
                    }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = if (isPersian) "ماه قبل" else "Previous month")
                    }
                    Text(
                        text = "${helper.MONTH_NAMES[viewMonth - 1]} $viewYear",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    IconButton(onClick = {
                        if (viewMonth == 12) { viewMonth = 1; viewYear += 1 } else viewMonth += 1
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = if (isPersian) "ماه بعد" else "Next month")
                    }
                }

                // سرستون روزهای هفته (شنبه تا جمعه)
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf("ش", "ی", "د", "س", "چ", "پ", "ج").forEach { wd ->
                        Text(
                            text = wd,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // شبکه روزهای ماه
                weekRows.forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        week.forEach { day ->
                            if (day == null) {
                                Box(modifier = Modifier.weight(1f).height(32.dp))
                            } else {
                                val isToday = viewYear == todayJ.year && viewMonth == todayJ.month && day == todayJ.day
                                val isSelected = day == selectedDay
                                val isFriday = helper.getDayOfWeekIndex(viewYear, viewMonth, day) == 6
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                isSelected -> MaterialTheme.colorScheme.primary
                                                isToday -> MaterialTheme.colorScheme.primaryContainer
                                                else -> Color.Transparent
                                            }
                                        )
                                        .clickable { selectedDay = day },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$day",
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isSelected -> Color.White
                                            isFriday -> Color(0xFFC2410C)
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // میان‌بر انتخاب امروز
                TextButton(
                    onClick = {
                        viewYear = todayJ.year
                        viewMonth = todayJ.month
                        selectedDay = todayJ.day
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Today, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPersian) "انتخاب امروز (${todayJ})" else "Select Today (${todayJ})", fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDateSelected(viewYear, viewMonth, selectedDay) },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isPersian) "تایید تاریخ" else "Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isPersian) "انصراف" else "Cancel") }
        }
    )
}

@Composable
fun AppointmentsScreen(viewModel: DentalViewModel) {
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val patients by viewModel.patients.collectAsStateWithLifecycle()
    val allTreatments by viewModel.allTreatments.collectAsStateWithLifecycle()

    var showForm by remember { mutableStateOf(false) }
    var apptToComplete by remember { mutableStateOf<Appointment?>(null) }
    var sessionAppointment by remember { mutableStateOf<Appointment?>(null) }
    var apptToDelete by remember { mutableStateOf<Appointment?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var archiveFilter by remember { mutableStateOf("TODAY") } // TODAY, UPCOMING, INCOMPLETE_SESSIONS, CANCELED, ARCHIVED_COMPLETED
    var selectedUnitFilter by remember { mutableStateOf("ALL") } // ALL, UNIT_1, UNIT_2, HYGIENE
    var dischargeApprovalAppt by remember { mutableStateOf<Pair<Appointment, String>?>(null) }
    var pendingRxForAppt by remember { mutableStateOf<PendingRxPopup?>(null) }
    var showHolidayConfirmQuestionDialog by remember { mutableStateOf<Pair<String, Long>?>(null) }

    // Form States
    var selectedPatient by remember { mutableStateOf<Patient?>(null) }
    var treatmentType by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var dueDaysStr by remember { mutableStateOf("1") }
    var selectedTreatmentIds by remember { mutableStateOf(setOf<Long>()) }
    var selectedAssistantSyncIds by remember { mutableStateOf(listOf<String>()) }
    var selectedUnit by remember { mutableStateOf("UNIT_1") }
    var selectedDurationMinutes by remember { mutableStateOf(30) }
    // پیش‌فرض: نام دستیار فعال به‌صورت خودکار در تمام فعالیت‌ها وارد می‌شود (درخواست ۶)
    val defaultAssistantSyncIds by viewModel.activeAssistantSyncIdsFlow.collectAsStateWithLifecycle()
    LaunchedEffect(defaultAssistantSyncIds) {
        if (selectedAssistantSyncIds.isEmpty() && defaultAssistantSyncIds.isNotEmpty()) {
            selectedAssistantSyncIds = defaultAssistantSyncIds
        }
    }
    val allStaffMembersForAppointments by viewModel.allStaffMembers.collectAsStateWithLifecycle()

    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = LocalSnackbarHostState.current
    val filteredAppointments = appointments.filter { appt ->
        val matchesQuery = appt.patientName.contains(searchQuery, ignoreCase = true) ||
                appt.treatmentType.contains(searchQuery, ignoreCase = true) ||
                appt.note.contains(searchQuery, ignoreCase = true) ||
                viewModel.formatDate(appt.date).contains(searchQuery)

        val now = System.currentTimeMillis()
        val matchesFilter = when (archiveFilter) {
            "TODAY" -> {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val todayStart = cal.timeInMillis
                val todayEnd = todayStart + 24 * 60 * 60 * 1000L - 1
                appt.date in todayStart..todayEnd && appt.status != "CANCELED"
            }
            "UPCOMING" -> appt.date > now && !appt.isArchived && appt.status != "CANCELED"
            "CANCELED" -> appt.status == "CANCELED"
            "ARCHIVED_COMPLETED" -> appt.isArchived && appt.isCompleted
            else -> !appt.isArchived && appt.status != "CANCELED"
        }

        // فیلتر یونیت/صندلی — نوبت‌های بدون یونیت (رکوردهای قدیمی) به‌عنوان یونیت ۱ در نظر گرفته می‌شوند
        val matchesUnit = selectedUnitFilter == "ALL" || (appt.unit ?: "UNIT_1") == selectedUnitFilter
        matchesQuery && matchesFilter && matchesUnit
    }.sortedBy { it.date } // نوبت‌ها به ترتیب ساعت و روز

    // گروه‌بندی نوبت‌ها بر اساس روز — هر روز در یک کادر مجزا نمایش داده می‌شود
    val appointmentsByDay = filteredAppointments.groupBy { appt ->
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = appt.date }
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }.toSortedMap()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = viewModel.label("appointments"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Button(
                onClick = { showForm = !showForm },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(if (showForm) Icons.Default.Close else Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (showForm) viewModel.label("cancel") else viewModel.label("quick_appointment"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        // Search & Filter Header
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(if (isPersian) "جستجوی نوبت بر اساس نام، نوع درمان یا تاریخ..." else "Search appointments...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        // Archive & Category Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "TODAY" to if (isPersian) "نوبت‌های امروز" else "Today",
                "UPCOMING" to if (isPersian) "نوبت‌های آینده" else "Upcoming",
                "INCOMPLETE_SESSIONS" to if (isPersian) "جلسات ناتمام" else "Incomplete Sessions",
                "CANCELED" to if (isPersian) "کنسل‌شده" else "Canceled",
                "ARCHIVED_COMPLETED" to if (isPersian) "تکمیل و آرشیو شده" else "Completed & Archived"
            ).forEach { (filterKey, filterLabel) ->
                ElevatedFilterChip(
                    selected = archiveFilter == filterKey,
                    onClick = { archiveFilter = filterKey },
                    label = { Text(filterLabel, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }
        }

        // Multi-Chair / Dental Unit Timeline Filter Chips (یونیت‌های مطب)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "ALL" to (if (isPersian) "همه یونیت‌ها" else "All Chairs"),
                "UNIT_1" to (if (isPersian) "یونیت شماره ۱" else "Chair #1"),
                "UNIT_2" to (if (isPersian) "یونیت شماره ۲ (جراحی)" else "Chair #2 (Surg)"),
                "HYGIENE" to (if (isPersian) "یونیت بخش بهداشت" else "Hygiene Chair")
            ).forEach { (uKey, uLabel) ->
                InputChip(
                    selected = selectedUnitFilter == uKey,
                    onClick = { selectedUnitFilter = uKey },
                    label = { Text(uLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Default.EventSeat, contentDescription = null, modifier = Modifier.size(12.dp)) }
                )
            }
        }

        if (showForm) {
            // Interactive Fast Booking Form
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (isPersian) "ثبت سریع نوبت پزشک" else "Fast Appointment Scheduler",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Select Patient — لیست کشویی فقط شامل بیماران بایگانی‌نشده (درخواست ۵)
                    Text(text = viewModel.label("patient_name"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    // فقط بیماران فعال (حذف‌نشده و بایگانی‌نشده)
                    val activePatientsForAppt = remember(patients) {
                        patients.filter { !it.isDeleted && !it.isArchived }
                    }
                    var patientDropdownExpanded by remember { mutableStateOf(false) }
                    androidx.compose.material3.ExposedDropdownMenuBox(
                        expanded = patientDropdownExpanded,
                        onExpandedChange = { patientDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedPatient?.let { getPatientFormattedName(it.name, it.gender, isPersian) } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            placeholder = { Text(if (isPersian) "انتخاب بیمار از لیست بیماران فعال..." else "Select an active patient...") },
                            trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = patientDropdownExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable, true),
                            label = { Text(if (isPersian) "انتخاب بیمار" else "Select Patient") }
                        )
                        ExposedDropdownMenu(
                            expanded = patientDropdownExpanded,
                            onDismissRequest = { patientDropdownExpanded = false }
                        ) {
                            if (activePatientsForAppt.isEmpty()) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (isPersian) "بیمار فعالی وجود ندارد" else "No active patients") },
                                    onClick = { patientDropdownExpanded = false }
                                )
                            } else {
                                // بیماران فعال: دارای تراپی ناتمام (IN_PROGRESS) یا شروع‌نشده (PLANNED) — درخواست کاربر
                                val activeTherapyIds = allTreatments
                                    .filter { !it.isDeleted && (it.status == "IN_PROGRESS" || it.status == "PLANNED") }
                                    .map { it.patientId }
                                    .toSet()
                                val activeTherapyPatients = activePatientsForAppt.filter { activeTherapyIds.contains(it.id) }
                                val otherPatients = activePatientsForAppt.filter { !activeTherapyIds.contains(it.id) }

                                val patientMenuItem: @Composable (Patient) -> Unit = { p ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(getPatientFormattedName(p.name, p.gender, isPersian), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                if (p.phone.isNotBlank()) {
                                                    Text(p.phone, fontSize = 10.sp, color = Color.Gray)
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedPatient = p
                                            selectedTreatmentIds = emptySet()
                                            patientDropdownExpanded = false
                                        }
                                    )
                                }

                                if (activeTherapyPatients.isNotEmpty()) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (isPersian) "⭐ بیماران فعال (تراپی ناتمام / شروع‌نشده)" else "⭐ Active Patients (unfinished / not started)",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = { }
                                    )
                                    activeTherapyPatients.forEach { p -> patientMenuItem(p) }
                                }
                                if (otherPatients.isNotEmpty()) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (isPersian) "سایر بیماران" else "Other patients",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        onClick = { }
                                    )
                                    otherPatients.forEach { p -> patientMenuItem(p) }
                                }
                            }
                        }
                    }

                    val patientTreatments = allTreatments.filter { it.patientId == selectedPatient?.id && it.status != "COMPLETED" }
                    if (patientTreatments.isNotEmpty()) {
                        var txDropdownExpanded by remember { mutableStateOf(false) }
                        
                        OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                        androidx.compose.material3.ExposedDropdownMenuBox(
                            expanded = txDropdownExpanded,
                            onExpandedChange = { txDropdownExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = if (selectedTreatmentIds.isEmpty()) (if (isPersian) "انتخاب تراپی ناتمام..." else "Select unfinished therapy...") else (if (isPersian) "${selectedTreatmentIds.size} تراپی انتخاب شده" else "${selectedTreatmentIds.size} selected"),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = txDropdownExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable, true),
                                colors = androidx.compose.material3.ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                label = { Text(if (isPersian) "انتخاب از طرح درمان:" else "Select from Treatment Plan:") }
                            )
                            ExposedDropdownMenu(
                                expanded = txDropdownExpanded,
                                onDismissRequest = { txDropdownExpanded = false }
                            ) {
                                patientTreatments.forEach { tr ->
                                    val isSelected = selectedTreatmentIds.contains(tr.id)
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Checkbox(checked = isSelected, onCheckedChange = null)
                                                Text(tr.title, fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            selectedTreatmentIds = if (isSelected) {
                                                selectedTreatmentIds - tr.id
                                            } else {
                                                selectedTreatmentIds + tr.id
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    val context = LocalContext.current
                    var selectedDateMills by remember { mutableStateOf<Long?>(null) }
                    var selectedHour by remember { mutableStateOf(16) }
                    var selectedMinute by remember { mutableStateOf(0) }

                    val timePickerDialog = remember {
                        android.app.TimePickerDialog(
                            context,
                            { _, hourOfDay, minute ->
                                selectedHour = hourOfDay
                                selectedMinute = minute
                            },
                            selectedHour,
                            selectedMinute,
                            true
                        )
                    }
                    // تقویم پاپ‌آپ شمسی (جلالی) — به‌جای DatePickerDialog میلادی اندروید
                    var showJalaliDatePicker by remember { mutableStateOf(false) }
                    // فیلد نمایش رقمی تاریخ (روز/ماه/سال با جداساز «/»؛ سال به‌صورت خودکار 14xx)
                    var dateInputStr by remember { mutableStateOf("") }
                    fun applyJalaliDate(jy: Int, jm: Int, jd: Int) {
                        selectedDateMills = com.example.util.PersianCalendarHelper.jalaliToTimestamp(jy, jm, jd)
                        dateInputStr = "$jd".padStart(2, '0') + "/" + "$jm".padStart(2, '0') + "/" + "$jy"
                        val diffMs = (selectedDateMills ?: 0L) - System.currentTimeMillis()
                        val diffDays = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diffMs) + 1
                        dueDaysStr = if (diffDays > 0) diffDays.toString() else "0"
                    }
                    LaunchedEffect(Unit) {
                        // مقدار اولیه: تاریخ روانِ فردا (پیش‌فرض قبلی فرم: +۱ روز)
                        val initCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
                        val j = com.example.util.PersianCalendarHelper.timestampToJalali(initCal.timeInMillis)
                        applyJalaliDate(j.year, j.month, j.day)
                    }
                    if (showJalaliDatePicker) {
                        JalaliDatePickerDialog(
                            initialTimestamp = selectedDateMills ?: System.currentTimeMillis(),
                            isPersian = isPersian,
                            onDateSelected = { jy, jm, jd ->
                                applyJalaliDate(jy, jm, jd)
                                showJalaliDatePicker = false
                                timePickerDialog.show()
                            },
                            onDismiss = { showJalaliDatePicker = false }
                        )
                    }

                    // Calculation of exact appointment date & time
                    val targetCalculatedMills = remember(dueDaysStr, selectedHour, selectedMinute, selectedDateMills) {
                        val days = dueDaysStr.toEnglishLong()
                        val cal = java.util.Calendar.getInstance()
                        if (selectedDateMills != null) {
                            cal.timeInMillis = selectedDateMills!!
                        } else {
                            cal.add(java.util.Calendar.DAY_OF_YEAR, days.toInt())
                        }
                        cal.set(java.util.Calendar.HOUR_OF_DAY, selectedHour)
                        cal.set(java.util.Calendar.MINUTE, selectedMinute)
                        cal.set(java.util.Calendar.SECOND, 0)
                        cal.set(java.util.Calendar.MILLISECOND, 0)
                        cal.timeInMillis
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = dueDaysStr,
                            onValueChange = { input -> 
                                dueDaysStr = input.filter { c -> c.isDigit() }.take(4)
                                selectedDateMills = null
                                // همگام‌سازی فیلد رقمی تاریخ با «چند روز بعد؟»
                                val d = dueDaysStr.toEnglishLong().toInt()
                                if (d in 0..3650) {
                                    val syncCal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, d) }
                                    val j = com.example.util.PersianCalendarHelper.timestampToJalali(syncCal.timeInMillis)
                                    dateInputStr = j.day.toString().padStart(2, '0') + "/" + j.month.toString().padStart(2, '0') + "/" + j.year
                                }
                            },
                            label = { Text(if (isPersian) "چند روز بعد؟" else "In how many days?") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        val timeFormattedStr = String.format(java.util.Locale.US, "%02d:%02d", selectedHour, selectedMinute)
                        OutlinedTextField(
                            value = timeFormattedStr,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(if (isPersian) "ساعت" else "Time") },
                            modifier = Modifier.weight(0.8f).clickable { timePickerDialog.show() },
                            trailingIcon = {
                                IconButton(onClick = { timePickerDialog.show() }) {
                                    Icon(Icons.Default.AccessTime, contentDescription = "Pick Time")
                                }
                            }
                        )
                    }

                    // فیلد نمایش رقمی تاریخ (روز/ماه/سال با «/») + دکمه تقویم شمسی
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = dateInputStr,
                            onValueChange = { input ->
                                // فقط ارقام؛ جداساز «/» خودکار درج و در بخش سال پیشوند «14» وارد می‌شود
                                dateInputStr = formatJalaliDayFirstInput(input)
                                val parsed = parseJalaliDayFirstInput(dateInputStr)
                                if (parsed != null) {
                                    val (d, m, y) = parsed
                                    applyJalaliDate(y, m, d)
                                }
                            },
                            label = { Text(if (isPersian) "تاریخ (روز/ماه/سال)" else "Date (DD/MM/YYYY)") },
                            placeholder = { Text("04/06/1404") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            trailingIcon = {
                                IconButton(onClick = { showJalaliDatePicker = true }) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = if (isPersian) "تقویم شمسی" else "Jalali Calendar", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }

                    // انتخاب یونیت/صندلی درمانی — پیش‌فرض یونیت ۱
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "UNIT_1" to (if (isPersian) "یونیت ۱" else "Chair 1"),
                            "UNIT_2" to (if (isPersian) "یونیت ۲ (جراحی)" else "Chair 2 (Surg)"),
                            "HYGIENE" to (if (isPersian) "یونیت بهداشت" else "Hygiene")
                        ).forEach { (uKey, uLabel) ->
                            FilterChip(
                                selected = selectedUnit == uKey,
                                onClick = { selectedUnit = uKey },
                                label = { Text(uLabel, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) },
                                leadingIcon = { Icon(Icons.Default.EventSeat, contentDescription = null, modifier = Modifier.size(13.dp)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Text(
                        text = if (isPersian) "مدت جلسه" else "Duration",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(15, 30, 45, 60, 90).forEach { mins ->
                            FilterChip(
                                selected = selectedDurationMinutes == mins,
                                onClick = { selectedDurationMinutes = mins },
                                label = {
                                    Text(
                                        if (isPersian) "$mins دقیقه" else "${mins}m",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    com.example.ui.components.AssistantMultiSelectField(
                        selectedSyncIds = selectedAssistantSyncIds,
                        onSelectionChange = { selectedAssistantSyncIds = it },
                        allStaff = allStaffMembersForAppointments,
                        isPersian = isPersian
                    )

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(viewModel.label("notes")) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Red calculated date/time preview text
                    val previewText = viewModel.formatDateFullVerbalWithTime(targetCalculatedMills)
                    Surface(
                        color = Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = previewText,
                            color = Color.Red,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val pat = selectedPatient
                            if (pat != null) {
                                val reason = viewModel.getHolidayOrNonWorkingReason(targetCalculatedMills)
                                if (reason != null) {
                                    showHolidayConfirmQuestionDialog = reason to targetCalculatedMills
                                } else {
                                    val derivedTxType = if (selectedTreatmentIds.isNotEmpty()) (if (isPersian) "طرح های انتخابی" else "Selected Treatments") else (if (isPersian) "جلسه درمانی" else "Therapy Session")
                                    val conflictResult = com.example.util.AppointmentConflictDetector.checkConflict(
                                        targetDate = targetCalculatedMills,
                                        durationMinutes = selectedDurationMinutes,
                                        existingAppointments = appointments,
                                        unit = selectedUnit
                                    )
                                    if (conflictResult.hasConflict) {
                                        showHolidayConfirmQuestionDialog = conflictResult.messageFa to targetCalculatedMills
                                    } else {
                                    viewModel.addAppointment(
                                        Appointment(
                                            patientId = pat.id,
                                            patientName = pat.name,
                                            date = targetCalculatedMills,
                                            treatmentType = derivedTxType,
                                            note = note,
                                            unit = selectedUnit,
                                            durationMinutes = selectedDurationMinutes,
                                            plannedTreatmentIds = selectedTreatmentIds.joinToString(","),
                                            assistantIds = com.example.ui.components.joinAssistantSyncIds(selectedAssistantSyncIds)
                                        )
                                    )
                                    showForm = false
                                    selectedPatient = null
                                    note = ""
                                    selectedTreatmentIds = emptySet()
                                    selectedAssistantSyncIds = emptyList()
                                    selectedDateMills = null
                                    selectedUnit = "UNIT_1"
                                    selectedDurationMinutes = 30
                                }
                            }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isPersian) "تایید و ذخیره اطلاعات" else "Confirm & Save Information", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (showHolidayConfirmQuestionDialog != null) {
                val (reason, dateMills) = showHolidayConfirmQuestionDialog!!
                val dateVerbal = viewModel.formatDateFullVerbal(dateMills)
                AlertDialog(
                    onDismissRequest = { showHolidayConfirmQuestionDialog = null },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F))
                            Text(
                                text = if (isPersian) "ثبت نوبت در روز تعطیل / غیرکاری" else "Book Appointment on Off Day",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color(0xFFD32F2F)
                            )
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (isPersian) "تاریخ انتخاب شده ($dateVerbal) مصادف با «$reason» می‌باشد."
                                else "Selected date ($dateVerbal) is: '$reason'.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isPersian) "آیا از ثبت نوبت در این روز تعطیل یا غیرکاری مطب اطمینان دارید؟"
                                else "Are you sure you want to register an appointment on this off day?",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val pat = selectedPatient
                                if (pat != null) {
                                    val derivedTxType = if (selectedTreatmentIds.isNotEmpty()) (if (isPersian) "طرح های انتخابی" else "Selected Treatments") else (if (isPersian) "جلسه درمانی" else "Therapy Session")
                                    val conflictResult = com.example.util.AppointmentConflictDetector.checkConflict(
                                        targetDate = dateMills,
                                        durationMinutes = selectedDurationMinutes,
                                        existingAppointments = appointments,
                                        unit = selectedUnit
                                    )
                                    if (conflictResult.hasConflict) {
                                        showHolidayConfirmQuestionDialog = conflictResult.messageFa to dateMills
                                    } else {
                                    viewModel.addAppointment(
                                        Appointment(
                                            patientId = pat.id,
                                            patientName = pat.name,
                                            date = dateMills,
                                            treatmentType = derivedTxType,
                                            note = note,
                                            unit = selectedUnit,
                                            durationMinutes = selectedDurationMinutes,
                                            plannedTreatmentIds = selectedTreatmentIds.joinToString(","),
                                            assistantIds = com.example.ui.components.joinAssistantSyncIds(selectedAssistantSyncIds)
                                        )
                                    )
                                    showForm = false
                                    selectedPatient = null
                                    note = ""
                                    selectedTreatmentIds = emptySet()
                                    selectedAssistantSyncIds = emptyList()
                                    selectedUnit = "UNIT_1"
                                    selectedDurationMinutes = 30
                                }
                            }
                                showHolidayConfirmQuestionDialog = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            Text(if (isPersian) "بله، ثبت نوبت در روز تعطیل" else "Yes, Book on Off Day", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showHolidayConfirmQuestionDialog = null }) {
                            Text(if (isPersian) "خیر / انصراف" else "Cancel")
                        }
                    }
                )
            }
        } else if (archiveFilter == "INCOMPLETE_SESSIONS") {
            // Specialized View for Incomplete Treatments and Planned Sessions
            val incompleteTreatments = allTreatments.filter { tr ->
                val patient = patients.find { it.id == tr.patientId }
                val patientName = patient?.name ?: ""
                val matchesQuery = patientName.contains(searchQuery, ignoreCase = true) ||
                        tr.title.contains(searchQuery, ignoreCase = true) ||
                        tr.selectedTeeth.contains(searchQuery)
                tr.status != "COMPLETED" && matchesQuery
            }

            val startedTreatments = incompleteTreatments.filter { it.status == "IN_PROGRESS" || it.completedStages.isNotBlank() }
            val plannedTreatments = incompleteTreatments.filter { it.status == "PLANNED" && it.completedStages.isBlank() }

            if (incompleteTreatments.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isPersian) "هیچ جلسه یا طرح درمان ناتمامی ثبت نشده است" else "No incomplete treatment sessions found",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (startedTreatments.isNotEmpty()) {
                        item {
                            Text(
                                text = if (isPersian) "۱. درمان‌های آغاز شده اما ناتمام (${startedTreatments.size} مورد)" else "1. In-Progress Treatments (${startedTreatments.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        }
                        items(startedTreatments.size) { idx ->
                            val tr = startedTreatments[idx]
                            val pat = patients.find { it.id == tr.patientId }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFFFA726).copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pat?.name ?: (if (isPersian) "بیمار ناشناس" else "Unknown"),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = tr.title + if (tr.selectedTeeth.isNotBlank()) " — دندان: ${tr.selectedTeeth}" else "",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    if (tr.stages.isNotBlank()) {
                                        val totalStages = parseStagesList(tr.stages)
                                        val completedList = parseStageMarkers(tr.completedStages)
                                        Text(
                                            text = if (isPersian) "پیشرفت مراحل: ${completedList.size} از ${totalStages.size} مرحله تکمیل شده" else "Progress: ${completedList.size}/${totalStages.size} completed",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(if (isPersian) "در حال انجام" else "In Progress", fontSize = 10.sp, color = Color(0xFFE65100)) },
                                            colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFFFF3E0))
                                        )
                                        Button(
                                            onClick = {
                                                selectedPatient = pat
                                                selectedTreatmentIds = setOf(tr.id)
                                                showForm = true
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPersian) "ثبت نوبت بعدی" else "Book Next Session", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (plannedTreatments.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isPersian) "۲. درمان‌های ثبت شده اما شروع نشده (${plannedTreatments.size} مورد)" else "2. Planned Treatments (${plannedTreatments.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(plannedTreatments.size) { idx ->
                            val tr = plannedTreatments[idx]
                            val pat = patients.find { it.id == tr.patientId }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = pat?.name ?: (if (isPersian) "بیمار ناشناس" else "Unknown"),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = tr.title + if (tr.selectedTeeth.isNotBlank()) " — دندان: ${tr.selectedTeeth}" else "",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(if (isPersian) "شروع نشده" else "Planned", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary) },
                                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        )
                                        Button(
                                            onClick = {
                                                selectedPatient = pat
                                                selectedTreatmentIds = setOf(tr.id)
                                                showForm = true
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPersian) "ثبت نوبت اول" else "Book First Session", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Appointments List
            if (filteredAppointments.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    com.example.ui.components.EmptyStateView(
                        title = if (searchQuery.isNotBlank() || archiveFilter != "ALL") (if (isPersian) "هیچ نوبتی با این مشخصات یافت نشد" else "No appointments found") else viewModel.label("no_appointments"),
                        subtitle = if (isPersian) "برای ثبت سریع نوبت، دکمه «نوبت‌دهی سریع» را لمس کنید." else "Click Quick Appointment to book a new slot.",
                        actionLabel = viewModel.label("quick_appointment"),
                        onAction = { showForm = true }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    appointmentsByDay.forEach { (dayMillis, dayAppts) ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // هدر روز — هر روز در کادر مجزای خودش
                                Text(
                                    text = viewModel.formatDateFullVerbal(dayMillis),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                dayAppts.forEach { appt ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(text = appt.patientName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(appt.date))
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "🕒 $timeStr",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                            // نمایش یونیت/صندلی اختصاص‌یافته به این نوبت
                                            val apptUnitLabel = when (appt.unit ?: "UNIT_1") {
                                                "UNIT_2" -> if (isPersian) "یونیت ۲" else "Chair 2"
                                                "HYGIENE" -> if (isPersian) "بهداشت" else "Hygiene"
                                                else -> if (isPersian) "یونیت ۱" else "Chair 1"
                                            }
                                            Surface(
                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = "🦷 $apptUnitLabel",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(text = appt.treatmentType, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                        Text(text = viewModel.formatDateFullVerbal(appt.date), fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                        com.example.ui.components.AssistantNamesRow(
                                            assistantIds = appt.assistantIds,
                                            allStaff = allStaffMembersForAppointments,
                                            isPersian = isPersian,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                        if (appt.note.isNotEmpty()) {
                                            Text(text = appt.note, fontSize = 11.sp, color = Color.Gray)
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        var statusDropdownExpanded by remember { mutableStateOf(false) }
                                        val statusMap = mapOf(
                                            "PENDING" to if (isPersian) "در انتظار" else "Pending",
                                            "IN_OFFICE" to if (isPersian) "داخل مطب" else "In Office",
                                            "COMPLETED" to if (isPersian) "تکمیل‌شده" else "Completed",
                                            "CANCELED" to if (isPersian) "کنسل‌شده" else "Canceled"
                                        )
                                        val statusColor = when (appt.status) {
                                            "PENDING" -> Color(0xFFFFA000)
                                            "IN_OFFICE" -> Color(0xFF0288D1)
                                            "COMPLETED" -> Color(0xFF388E3C)
                                            "CANCELED" -> Color(0xFFD32F2F)
                                            else -> Color.Gray
                                        }

                                        Box {
                                            Surface(
                                                color = statusColor.copy(alpha = 0.1f),
                                                shape = RoundedCornerShape(6.dp),
                                                modifier = Modifier.clickable { statusDropdownExpanded = true }
                                            ) {
                                                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(statusColor))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(text = statusMap[appt.status] ?: appt.status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = statusColor)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                            DropdownMenu(
                                                expanded = statusDropdownExpanded,
                                                onDismissRequest = { statusDropdownExpanded = false }
                                            ) {
                                                statusMap.forEach { (key, value) ->
                                                    DropdownMenuItem(
                                                        text = { Text(value, fontSize = 12.sp) },
                                                        onClick = {
                                                            viewModel.updateAppointment(appt.copy(status = key, isCompleted = (key == "COMPLETED")))
                                                            statusDropdownExpanded = false
                                                            if (key == "COMPLETED") {
                                                                apptToComplete = appt // For navigating to treatment plan
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                val isArchivedOrCompleted = appt.isArchived || appt.isCompleted || archiveFilter == "PAST"

                                if (isArchivedOrCompleted) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = if (appt.dischargeSent) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    if (appt.dischargeSent) Icons.Default.CheckCircle else Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = if (appt.dischargeSent) Color(0xFF2E7D32) else Color.Gray,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = if (appt.dischargeSent) (if (isPersian) "توضیحات ترخیص ارسال شد (${appt.dischargeMethod})" else "Discharge Sent (${appt.dischargeMethod})")
                                                    else (if (isPersian) "توضیحات ترخیص ارسال نشده" else "Discharge Not Sent"),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (appt.dischargeSent) Color(0xFF2E7D32) else Color.Gray
                                                )
                                            }
                                        }
                                    }
                                }

                                // Action buttons in a single row under the appointment field
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { sessionAppointment = appt },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(if (isArchivedOrCompleted) Icons.Default.Visibility else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            if (isArchivedOrCompleted) (if (isPersian) "مشاهده جلسه" else "View Session")
                                            else (if (isPersian) "شروع جلسه" else "Start Session"),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (isArchivedOrCompleted) {
                                        OutlinedIconButton(
                                            onClick = { dischargeApprovalAppt = Pair(appt, "پیامک") },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Message,
                                                contentDescription = if (isPersian) "ارسال توضیحات ترخیص پیامکی" else "Discharge SMS",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = { dischargeApprovalAppt = Pair(appt, "واتساپ") },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Chat,
                                                contentDescription = if (isPersian) "ارسال توضیحات ترخیص واتساپ" else "Discharge WhatsApp",
                                                tint = Color(0xFF25D366),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else {
                                        OutlinedIconButton(
                                            onClick = {
                                                val pObj = patients.find { it.name == appt.patientName }
                                                val msg = buildAppointmentReminderText(isPersian, appt.patientName, appt.date, viewModel, pObj?.gender ?: "مرد")
                                                sendSmsPrefilled(context, pObj?.phone ?: "", msg)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Message,
                                                contentDescription = if (isPersian) "اطلاع‌رسانی پیامکی" else "Notify via SMS",
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = {
                                                val pObj = patients.find { it.name == appt.patientName }
                                                val msg = buildAppointmentReminderText(isPersian, appt.patientName, appt.date, viewModel, pObj?.gender ?: "مرد")
                                                val file = generateSimpleInfoCardImage(
                                                    context,
                                                    title = if (isPersian) "یادآوری نوبت مطب" else "Appointment Reminder",
                                                    lines = msg.split("۔", "\n").filter { it.isNotBlank() },
                                                    themeSpec = viewModel.currentExportTheme()
                                                )
                                                val phone = pObj?.phone
                                                shareImageToWhatsApp(context, file, phone)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Chat,
                                                contentDescription = if (isPersian) "اطلاع‌رسانی از طریق واتساپ" else "Notify via WhatsApp",
                                                tint = Color(0xFF25D366),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = { apptToComplete = appt },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Archive, contentDescription = if (isPersian) "آرشیو نوبت" else "Archive", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                        OutlinedIconButton(
                                            onClick = { apptToDelete = appt },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                                } // end dayAppts.forEach
                            }
                        }
                    } // end item
                    } // end appointmentsByDay.forEach
                }
            }
        }
    }

    // Deletion Confirmation Dialog
    if (apptToDelete != null) {
        AlertDialog(
            onDismissRequest = { apptToDelete = null },
            title = { Text(if (isPersian) "حذف نوبت دندانپزشکی" else "Delete Appointment", fontWeight = FontWeight.Bold) },
            text = { Text(if (isPersian) "آیا از حذف نوبت بیمار «${apptToDelete?.patientName}» اطمینان دارید؟" else "Are you sure you want to delete this appointment?") },
            confirmButton = {
                Button(
                    onClick = {
                        val deleted = apptToDelete!!
                        viewModel.deleteAppointment(deleted)
                        apptToDelete = null
                        showUndoSnackbar(
                            scope = coroutineScope,
                            hostState = snackbarHostState,
                            message = if (isPersian) "نوبت «${deleted.patientName}» حذف شد" else "Appointment for '${deleted.patientName}' deleted",
                            actionLabel = if (isPersian) "بازگردانی" else "Undo",
                            onUndo = { viewModel.undoDeleteAppointment(deleted) }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (isPersian) "حذف نهایی" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { apptToDelete = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    if (apptToComplete != null) {
        AlertDialog(
            onDismissRequest = { apptToComplete = null },
            title = { Text(if (isPersian) "تکمیل و آرشیو نوبت دندانپزشکی" else "Complete & Archive Appointment", fontWeight = FontWeight.Bold) },
            text = { Text(if (isPersian) "نوبت بیمار «${apptToComplete?.patientName}» به وضعیت تکمیل‌شده تغییر یافت. مایلید به پرونده بیمار و بخش صدور فاکتور هدایت شوید؟ (طرح درمان‌های مرتبط نیز به وضعیت تکمیل شده تغییر خواهند کرد)" else "Appointment marked as completed. Go to Treatment Plan / Payment?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val currentAppt = apptToComplete!!
                            viewModel.archiveAppointment(currentAppt)
                            
                            // Navigation
                            viewModel.selectedPatientId.value = currentAppt.patientId
                            viewModel.currentTab.value = "patients"
                            
                            apptToComplete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) { Text(if (isPersian) "انتقال به بخش مالی" else "Go to Financial") }
                    
                    OutlinedButton(
                        onClick = {
                            val currentAppt = apptToComplete!!
                            viewModel.archiveAppointment(currentAppt)
                            
                            // Generate combined prescription
                            val txIds = currentAppt.plannedTreatmentIds.split(",").mapNotNull { it.trim().toLongOrNull() }
                            val sessionTreatments = allTreatments.filter { txIds.contains(it.id) }
                            val rxPatient = patients.find { it.id == currentAppt.patientId }
                            
                            var cAnalgesic = mutableSetOf<String>()
                            var cAntibiotic = mutableSetOf<String>()
                            var cMouthwash = mutableSetOf<String>()
                            var cOther = mutableSetOf<String>()
                            var cNotes = mutableSetOf<String>()
                            var combinedTitle = mutableSetOf<String>()
                            
                            val titlesToSearch = mutableListOf<String>()
                            sessionTreatments.forEach { tx -> titlesToSearch.add(tx.title) }
                            if (titlesToSearch.isEmpty() && currentAppt.treatmentType.isNotBlank()) {
                                titlesToSearch.add(currentAppt.treatmentType)
                            }
                            
                            titlesToSearch.forEach { title ->
                                combinedTitle.add(title)
                                val baseSuggestion = com.example.data.PrescriptionKnowledgeBase.suggest(title, "تکمیل جلسه")
                                if (baseSuggestion != null && !baseSuggestion.isBlank()) {
                                    if (baseSuggestion.analgesic.isNotBlank()) cAnalgesic.add(baseSuggestion.analgesic)
                                    if (baseSuggestion.antibiotic.isNotBlank()) cAntibiotic.add(baseSuggestion.antibiotic)
                                    if (baseSuggestion.mouthwash.isNotBlank()) cMouthwash.add(baseSuggestion.mouthwash)
                                    if (baseSuggestion.otherMeds.isNotBlank()) cOther.add(baseSuggestion.otherMeds)
                                    if (baseSuggestion.notes.isNotBlank()) cNotes.add(baseSuggestion.notes)
                                }
                            }
                            
                            val combinedPrescription = com.example.data.DrugPrescription(
                                analgesic = cAnalgesic.joinToString(" + "),
                                antibiotic = cAntibiotic.joinToString(" + "),
                                mouthwash = cMouthwash.joinToString(" + "),
                                otherMeds = cOther.joinToString(" + "),
                                notes = (cNotes + listOf(currentAppt.note)).filter { it.isNotBlank() }.joinToString("\n")
                            )
                            
                            val adjusted = com.example.data.PrescriptionKnowledgeBase.adjustForPatient(
                                combinedPrescription,
                                rxPatient?.medicalHistory ?: "",
                                rxPatient?.medicalAlerts ?: ""
                            )
                            val displayTitle = if (combinedTitle.isNotEmpty()) combinedTitle.joinToString(" / ") else currentAppt.treatmentType.ifBlank { "تکمیل جلسه درمان" }
                            val dummyTx = sessionTreatments.firstOrNull()?.copy(title = displayTitle) 
                                ?: com.example.data.Treatment(id = 0L, patientId = currentAppt.patientId, title = displayTitle, cost = 0)
                            
                            pendingRxForAppt = PendingRxPopup(treatment = dummyTx, stageName = "تکمیل و آرشیو جلسه", prescription = adjusted, patient = rxPatient)
                            
                            apptToComplete = null
                        }
                    ) { Text(if (isPersian) "فقط ثبت و نسخه" else "Only Archive & Rx") }
                }
            },
            dismissButton = {
                TextButton(onClick = { apptToComplete = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    // Doctor Approval Dialog for Discharge & Post-Care Instructions
    if (pendingRxForAppt != null) {
        val rxState = pendingRxForAppt!!
        PrescriptionApprovalDialog(
            viewModel = viewModel,
            treatment = rxState.treatment,
            patient = rxState.patient,
            stageName = rxState.stageName,
            suggested = rxState.prescription,
            onDismiss = { pendingRxForAppt = null }
        )
    }

    if (dischargeApprovalAppt != null) {
        val (targetAppt, method) = dischargeApprovalAppt!!
        val settings by viewModel.settings.collectAsStateWithLifecycle()
        // سوابق پزشکی و داروهای بیمار از فرم پذیرش، تا تذکرات ترخیص
        // مخصوص همین بیمار ساخته شود.
        val allPatients by viewModel.patients.collectAsStateWithLifecycle()
        val dischargePatient = remember(allPatients, targetAppt.patientId) {
            allPatients.firstOrNull { it.id == targetAppt.patientId }
        }
        val defaultText = remember(targetAppt.id, dischargePatient?.id) {
            TherapyKnowledgeBase.generateDischargeInstructions(
                treatmentTitle = targetAppt.treatmentType,
                patientName = targetAppt.patientName,
                clinicName = settings.clinicName,
                isPersian = isPersian,
                stageText = targetAppt.note,
                medicalHistory = dischargePatient?.medicalHistory.orEmpty(),
                medicalAlerts = dischargePatient?.medicalAlerts.orEmpty()
            )
        }
        var approvedText by remember(targetAppt.id) { mutableStateOf(defaultText) }

        AlertDialog(
            onDismissRequest = { dischargeApprovalAppt = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = if (isPersian) "تایید پزشک جهت ارسال توضیحات ترخیص" else "Doctor Approval - Post-Care Instructions",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isPersian) "این توضیحات به‌صورت خودکار مطابق تراپی‌های این جلسه تهیه شده است. قبل از ارسال، بررسی و تایید پزشک الزامی است."
                            else "Auto-generated per session therapies. Requires doctor approval before dispatch.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    OutlinedTextField(
                        value = approvedText,
                        onValueChange = { approvedText = it },
                        label = { Text(if (isPersian) "متن توضیحات ترخیص و مراقبتی (قابل ویرایش)" else "Post-Care Instructions (Editable)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 280.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val patPhone = patients.find { it.name == targetAppt.patientName }?.phone ?: ""
                        if (method == "پیامک" || method == "SMS") {
                            sendSmsPrefilled(context, patPhone, approvedText)
                            viewModel.updateAppointment(targetAppt.copy(dischargeSent = true, dischargeMethod = "پیامک"))
                        } else {
                            val patObj = patients.find { it.name == targetAppt.patientName }
                            val cardFile = com.example.reports.generatePostOpCareInstructionsCardImage(
                                context = context,
                                clinicName = viewModel.getSettings().clinicName,
                                doctorName = viewModel.getSettings().doctorName,
                                patientName = targetAppt.patientName,
                                patientFatherName = patObj?.fatherName ?: "",
                                patientNationalId = patObj?.nationalId ?: "",
                                treatmentTitle = targetAppt.treatmentType,
                                careInstructions = approvedText.split("\n").filter { it.isNotBlank() },
                                emergencyPhone = viewModel.getSettings().clinicPhone,
                                themeSpec = viewModel.currentExportTheme()
                            )
                            val phone = patObj?.phone
                            shareImageToWhatsApp(context, cardFile, phone)
                            viewModel.updateAppointment(targetAppt.copy(dischargeSent = true, dischargeMethod = "واتساپ"))
                        }
                        dischargeApprovalAppt = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(if (method == "پیامک" || method == "SMS") Icons.AutoMirrored.Filled.Message else Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPersian) "تایید پزشک و ارسال ($method)" else "Approve & Send ($method)", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { dischargeApprovalAppt = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    // Interactive Session Details & Stage Management Dialog
    if (sessionAppointment != null) {
        val appt = sessionAppointment!!
        val patientTreatments = allTreatments.filter { it.patientId == appt.patientId }
        val allTemplatesList by viewModel.templates.collectAsStateWithLifecycle()
        val settings by viewModel.settings.collectAsStateWithLifecycle()

        val plannedIds = remember(appt.plannedTreatmentIds) { appt.plannedTreatmentIds.split(",").mapNotNull { it.toLongOrNull() }.toSet() }
        val sessionTreatments = remember(patientTreatments, plannedIds) { patientTreatments.filter { plannedIds.contains(it.id) } }
        val combinedSessionSummary = remember(sessionTreatments) {
            if (sessionTreatments.isEmpty()) {
                if (isPersian) "جلسه درمانی" else "Therapy Session"
            } else {
                sessionTreatments.joinToString(" + ") { tx ->
                    val cleanTitle = tx.title.lineSequence().firstOrNull()?.trim().orEmpty()
                    val teethPart = if (tx.selectedTeeth.isNotBlank()) " (دندان ${tx.selectedTeeth})" else ""
                    "$cleanTitle$teethPart"
                }
            }
        }

        var showManageTherapiesDialog by remember { mutableStateOf(false) }
        var editNotes by remember { mutableStateOf(appt.note) }
        var editTxType by remember(appt.id, appt.treatmentType, combinedSessionSummary) {
            mutableStateOf(
                if (appt.treatmentType.isBlank() || appt.treatmentType == "Selected Treatments" || appt.treatmentType == "طرح های انتخابی") combinedSessionSummary
                else appt.treatmentType
            )
        }
        var selectedTemplateForTx by remember { mutableStateOf<Pair<Long, TreatmentTemplate>?>(null) }
        var sessionFinishConfirmed by remember(appt.id) { mutableStateOf(appt.isCompleted) }

        // ——— حذف جلسه: تأیید دو مرحله‌ای + تایپ کلمهٔ «حذف» ———
        // فقط برای جلسات تکمیل‌شده یا آرشیوشده در دسترس است.
        var deleteStep by remember(appt.id) { mutableIntStateOf(0) }
        var deleteConfirmText by remember(appt.id) { mutableStateOf("") }
        val canDeleteSession = appt.isArchived || appt.isCompleted

        if (showManageTherapiesDialog) {
            AppointmentSessionDialog(
                appointment = appt,
                viewModel = viewModel,
                onDismiss = { showManageTherapiesDialog = false }
            )
        }

        AlertDialog(
            onDismissRequest = { sessionAppointment = null },
            title = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MedicalServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (isPersian) "مدیریت جلسات و تراپی درمان" else "Session & Therapy Stage Management",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        // حذف جلسه — فقط جلسات تکمیل‌شده/آرشیوشده
                        if (canDeleteSession) {
                            IconButton(onClick = { deleteStep = 1 }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    contentDescription = if (isPersian) "حذف جلسه" else "Delete session",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${if (isPersian) "بیمار: " else "Patient: "}${appt.patientName} | ${viewModel.formatDate(appt.date)}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = editTxType,
                        onValueChange = { editTxType = it },
                        label = { Text(if (isPersian) "عنوان درمان / شرح خلاصه تمام تراپی‌های جلسه" else "Session Treatment Title") },
                        trailingIcon = {
                            IconButton(onClick = { editTxType = combinedSessionSummary }) {
                                Icon(
                                    imageVector = Icons.Default.Autorenew,
                                    contentDescription = "Autofill session summary",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = editNotes,
                        onValueChange = { editNotes = it },
                        label = { Text(viewModel.label("notes")) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                    // Stages fetched directly from Database for this patient
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPersian) "تراپی‌های این جلسه:" else "Session Therapies:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedButton(
                            onClick = { showManageTherapiesDialog = true },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(if (isPersian) "ویرایش تراپی‌های جلسه" else "Manage Session Therapies", fontSize = 10.sp)
                        }
                    }
                    val plannedIds = remember(appt.plannedTreatmentIds) { appt.plannedTreatmentIds.split(",").mapNotNull { it.toLongOrNull() }.toSet() }
                    val sessionTreatments = patientTreatments.filter { plannedIds.contains(it.id) }

                    if (sessionTreatments.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isPersian) "هیچ طرح درمانی برای این بیمار در پرونده ثبت نشده است." else "No treatment plans recorded for this patient.",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    } else {
                        sessionTreatments.forEach { tx ->
                            val patientForProtocol = remember(appt.patientId) { patients.find { it.id == appt.patientId } }
                            // Auto-fetch stages from ready templates if tx.stages is empty
                            var txStagesStr = tx.stages
                            if (txStagesStr.isBlank() && allTemplatesList.isNotEmpty()) {
                                val matchedTemp = allTemplatesList.find { temp ->
                                    tx.title.contains(temp.title) || temp.title.contains(tx.title)
                                }
                                if (matchedTemp != null && matchedTemp.stages.isNotBlank()) {
                                    txStagesStr = matchedTemp.stages
                                    viewModel.updateTreatment(tx.copy(stages = joinStagesList(parseStagesList(matchedTemp.stages))))
                                }
                            }

                            val allStagesList = remember(txStagesStr) {
                                parseStagesList(txStagesStr)
                            }
                            val completedStagesList = remember(tx.completedStages) {
                                parseStageMarkers(tx.completedStages)
                            }
                            val ignoredStagesList = remember(tx.ignoredStages) {
                                parseStageMarkers(tx.ignoredStages)
                            }
                            val remainingStagesList = remember(allStagesList, completedStagesList, ignoredStagesList) {
                                allStagesList.filter { !completedStagesList.contains(it) && !ignoredStagesList.contains(it) }
                            }
                            // دیالوگ ادیت مرحله جاری در همان نشست (بدون خروج از روند جلسه)
                            var showEditStageDialog by remember { mutableStateOf(false) }
                            var stageEditNoteDraft by remember(tx.id) { mutableStateOf("") }
                            // دیالوگ شرح مفصل پروتکل بالینی تراپی (با لمس طولانی روی عنوان تراپی باز می‌شود)
                            var showTherapyProtocolDialog by remember { mutableStateOf(false) }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (remainingStagesList.isEmpty() && allStagesList.isNotEmpty()) 
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) 
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // Treatment Title & Status Header
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = run {
                                                        // شمارهٔ جلسه = مراحل حل‌شده (انجام‌شده + ایگنورشده) + ۱،
                                                        // محدودشده به تعداد کل مراحل تا از «جلسه ۶ از ۵» جلوگیری شود.
                                                        val resolvedCount =
                                                            (completedStagesList + ignoredStagesList).distinct().size
                                                        val totalCount = allStagesList.size
                                                        val sessionNumber =
                                                            if (totalCount > 0) (resolvedCount + 1).coerceAtMost(totalCount)
                                                            else resolvedCount + 1
                                                        val shortTitle = tx.title.lineSequence().firstOrNull()?.trim().orEmpty()
                                                        val sessionLabel = if (totalCount > 0) {
                                                            if (isPersian) "جلسه $sessionNumber از $totalCount"
                                                            else "Session $sessionNumber of $totalCount"
                                                        } else {
                                                            if (isPersian) "جلسه $sessionNumber" else "Session $sessionNumber"
                                                        }
                                                        "$shortTitle — $sessionLabel"
                                                    },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    modifier = Modifier.threeSecondLongPress(
                                                        onLongClick = {
                                                            if (TherapyKnowledgeBase.findProtocol(tx.title) != null) {
                                                                showTherapyProtocolDialog = true
                                                            }
                                                        }
                                                    )
                                                )
                                            }
                                            if (tx.selectedTeeth.isNotBlank()) {
                                                Text(text = "${if (isPersian) "دندان:" else "Tooth:"} ${tx.selectedTeeth}", fontSize = 10.sp, color = Color.Gray)
                                            }
                                        }
                                        Surface(
                                            color = when (tx.status) {
                                                "COMPLETED" -> Color(0xFFE8F5E9)
                                                "IN_PROGRESS" -> MaterialTheme.colorScheme.primaryContainer
                                                else -> MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = when (tx.status) {
                                                    "COMPLETED" -> if (isPersian) "✓ تکمیل‌شده" else "Completed"
                                                    "CANCELLED" -> if (isPersian) "✕ لغوشده" else "Cancelled"
                                                    else -> {
                                                        val allStages = parseStagesList(tx.stages)
                                                        val completedStages = parseStageMarkers(tx.completedStages)
                                                        val ignoredStages = parseStageMarkers(tx.ignoredStages)
                                                        val resolvedStages = (completedStages + ignoredStages).distinct()
                                                        val completedCount = resolvedStages.size
                                                        val totalSessions = allStages.size
                                                        val hasIncompleted = allStages.isNotEmpty() && !resolvedStages.containsAll(allStages)
                                                        
                                                        if (hasIncompleted) {
                                                            if (isPersian) "◐ در حال انجام (${completedCount}/${totalSessions} جلسه)" else "In Progress (${completedCount}/${totalSessions})"
                                                        } else {
                                                            if (isPersian) "○ برنامه‌ریزی‌شده" else "Planned"
                                                        }
                                                    }
                                                },
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (tx.status) {
                                                    "COMPLETED" -> Color(0xFF2E7D32)
                                                    "IN_PROGRESS" -> MaterialTheme.colorScheme.primary
                                                    else -> Color.Gray
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    // Option to import/sync stages from ready templates if empty
                                    if (allStagesList.isEmpty()) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                    Text(
                                                        text = if (isPersian) "فراخوانی مراحل درمان از الگوهای آماده:" else "Import stages from ready templates:",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    allTemplatesList.forEach { template ->
                                                        AssistChip(
                                                            onClick = {
                                                                viewModel.updateTreatment(tx.copy(stages = joinStagesList(parseStagesList(template.stages))))
                                                            },
                                                            label = { Text(if (isPersian) template.titleFa.ifBlank { template.title } else template.title, fontSize = 10.sp) },
                                                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(12.dp)) }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Progress Bar
                                        val totalDone = completedStagesList.size + ignoredStagesList.size
                                        val progress = if (allStagesList.isEmpty()) 0f else totalDone.toFloat() / allStagesList.size.toFloat()
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(
                                                    text = "${if (isPersian) "پیشرفت مراحل تراپی:" else "Stage Progress:"} $totalDone از ${allStagesList.size}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = "${(progress * 100).toInt()}%",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            LinearProgressIndicator(
                                                progress = { progress },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(5.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            )
                                        }

                                        // Section 1: Completed stages in previous sessions
                                        if (completedStagesList.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = if (isPersian) "✓ مراحل انجام‌شده در جلسات قبلی:" else "✓ Completed in previous sessions:",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF2E7D32)
                                                )
                                                completedStagesList.forEach { doneStage ->
                                                    val isRepeated = doneStage.startsWith("[REPEAT] ")
                                                    val displayStage = if (isRepeated) doneStage.removePrefix("[REPEAT] ") else doneStage
                                                    val tickColor = Color(0xFF2E7D32)
                                                    val surfaceColor = Color(0xFFE8F5E9)
                                                    val textColor = Color(0xFF1B5E20)
                                                    
                                                    Surface(
                                                        color = surfaceColor,
                                                        shape = RoundedCornerShape(6.dp),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = tickColor, modifier = Modifier.size(14.dp))
                                                                Text(
                                                                    text = displayStage,
                                                                    fontSize = 11.sp,
                                                                    color = textColor,
                                                                    style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                                                                )
                                                            }
                                                            IconButton(
                                                                onClick = { viewModel.toggleTreatmentStage(tx, doneStage) },
                                                                modifier = Modifier.size(18.dp)
                                                            ) {
                                                                Icon(Icons.Default.Undo, contentDescription = "Undo", tint = Color.Gray, modifier = Modifier.size(12.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                            }
                                        }

                                        // Section 2: Active stage for THIS session & Future queued stages
                                        val activeCurrentStage = remainingStagesList.firstOrNull()
                                        val futureQueuedStages = remainingStagesList.drop(1)

                                        if (activeCurrentStage != null) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    text = if (isPersian) "📍 مرحله اختصاصی جهت اجرا در این جلسه:" else "📍 Active Stage for THIS Session:",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )

                                                // Primary Active Stage Card for Current Session
                                                Card(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                    ),
                                                    shape = RoundedCornerShape(10.dp),
                                                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                Icon(Icons.Default.PlayCircleFilled, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                                                Text(
                                                                    text = activeCurrentStage,
                                                                    fontSize = 13.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = MaterialTheme.colorScheme.onSurface
                                                                )
                                                            }
                                                        }

                                                        Button(
                                                            onClick = {
                                                                // Confirm and execute stage in this session
                                                                // شناسهٔ جلسه ثبت می‌شود تا با حذف این جلسه، همین مرحله برگردد.
                                                                viewModel.toggleTreatmentStage(tx, activeCurrentStage, appt.id)
                                                                val updatedType = if (sessionTreatments.isNotEmpty()) {
                                                                    sessionTreatments.joinToString(" + ") { sessionTx ->
                                                                        val cleanT = sessionTx.title.lineSequence().firstOrNull()?.trim().orEmpty()
                                                                        if (sessionTx.id == tx.id) {
                                                                            "$cleanT ($activeCurrentStage)"
                                                                        } else {
                                                                            cleanT
                                                                        }
                                                                    }
                                                                } else {
                                                                    "${tx.title} ($activeCurrentStage)"
                                                                }
                                                                editTxType = updatedType
                                                                viewModel.updateAppointment(appt.copy(treatmentType = updatedType, note = editNotes))
                                                            },
                                                            modifier = Modifier.fillMaxWidth(),
                                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(vertical = 6.dp)
                                                        ) {
                                                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = if (isPersian) "✓ تایید و ثبت انجام این مرحله در این جلسه" else "✓ Confirm & Complete Stage in this Session",
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }

                                                        // ردیف اکشن‌های تکمیلی: ایگنور کردن / ادیت همین جلسه / تکرار جلسه قبلی
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            OutlinedButton(
                                                                onClick = {
                                                                    // بی‌نیازی از این مرحله: ایگنور کردن بدون علامت‌گذاری به‌عنوان انجام‌شده
                                                                    viewModel.ignoreTreatmentStage(tx, activeCurrentStage, appt.id)
                                                                },
                                                                modifier = Modifier.weight(1f),
                                                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                                                                shape = RoundedCornerShape(8.dp),
                                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFB71C1C))
                                                            ) {
                                                                Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(13.dp))
                                                                Spacer(modifier = Modifier.width(3.dp))
                                                                Text(
                                                                    text = if (isPersian) "عدم نیاز به این مرحله" else "Skip Stage",
                                                                    fontSize = 9.sp
                                                                )
                                                            }
                                                            OutlinedButton(
                                                                onClick = {
                                                                    stageEditNoteDraft = activeCurrentStage ?: ""
                                                                    showEditStageDialog = true
                                                                },
                                                                modifier = Modifier.weight(1f),
                                                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                                                                shape = RoundedCornerShape(8.dp)
                                                            ) {
                                                                Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(13.dp))
                                                                Spacer(modifier = Modifier.width(3.dp))
                                                                Text(
                                                                    text = if (isPersian) "ادیت همین جلسه" else "Edit Session",
                                                                    fontSize = 9.sp
                                                                )
                                                            }
                                                            
                                                            var showNewStageDialog by remember { mutableStateOf(false) }
                                                            var newStageNameDraft by remember { mutableStateOf("") }

                                                            OutlinedButton(
                                                                onClick = {
                                                                    newStageNameDraft = ""
                                                                    showNewStageDialog = true
                                                                },
                                                                modifier = Modifier.weight(1f),
                                                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                                                                shape = RoundedCornerShape(8.dp)
                                                            ) {
                                                                Icon(Icons.Default.PostAdd, contentDescription = null, modifier = Modifier.size(13.dp))
                                                                Spacer(modifier = Modifier.width(3.dp))
                                                                Text(
                                                                    text = if (isPersian) "مرحله جدید" else "New Stage",
                                                                    fontSize = 9.sp
                                                                )
                                                            }

                                                            if (showNewStageDialog) {
                                                                AlertDialog(
                                                                    onDismissRequest = { showNewStageDialog = false },
                                                                    title = {
                                                                        Text(
                                                                            if (isPersian) "افزودن مرحله جدید" else "Add New Stage",
                                                                            fontSize = 14.sp,
                                                                            fontWeight = FontWeight.Bold
                                                                        )
                                                                    },
                                                                    text = {
                                                                        OutlinedTextField(
                                                                            value = newStageNameDraft,
                                                                            onValueChange = { newStageNameDraft = it },
                                                                            placeholder = { Text(if (isPersian) "نام مرحله جدید را وارد کنید..." else "Enter new stage name...") },
                                                                            modifier = Modifier.fillMaxWidth(),
                                                                            textStyle = TextStyle(fontSize = 12.sp)
                                                                        )
                                                                    },
                                                                    confirmButton = {
                                                                        Button(
                                                                            onClick = {
                                                                                // نام تکراری مجاز نیست: مراحل با نامشان شناسایی
                                                                                // می‌شوند، پس دو مرحلهٔ هم‌نام باعث می‌شد علامت‌زدن
                                                                                // یکی، دیگری را هم انجام‌شده نشان دهد.
                                                                                if (newStageNameDraft.isNotBlank() &&
                                                                                    allStagesList.none { it == newStageNameDraft.trim() }
                                                                                ) {
                                                                                    val newStageName = newStageNameDraft.trim()
                                                                                    val newStagesList = allStagesList.toMutableList()
                                                                                    val index = if (remainingStagesList.isNotEmpty()) {
                                                                                        newStagesList.indexOf(remainingStagesList.first())
                                                                                    } else {
                                                                                        newStagesList.size
                                                                                    }
                                                                                    if (index >= 0) {
                                                                                        newStagesList.add(index, newStageName)
                                                                                    } else {
                                                                                        newStagesList.add(newStageName)
                                                                                    }
                                                                                    val newStagesStr = joinStagesList(newStagesList)
                                                                                    viewModel.updateTreatment(tx.copy(stages = newStagesStr))
                                                                                }
                                                                                showNewStageDialog = false
                                                                            }
                                                                        ) {
                                                                            Text(if (isPersian) "تایید و ثبت" else "Confirm", fontSize = 12.sp)
                                                                        }
                                                                    },
                                                                    dismissButton = {
                                                                        TextButton(onClick = { showNewStageDialog = false }) {
                                                                            Text(if (isPersian) "انصراف" else "Cancel", fontSize = 12.sp)
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }

                                                if (showEditStageDialog) {
                                                    AlertDialog(
                                                        onDismissRequest = { showEditStageDialog = false },
                                                        title = {
                                                            Text(if (isPersian) "ویرایش شرح این جلسه" else "Edit This Session")
                                                        },
                                                        text = {
                                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                                Text(
                                                                    text = if (isPersian)
                                                                        "متن این مرحله را ویرایش کنید. تغییر فقط روی پروندهٔ همین بیمار اثر دارد و الگوی آماده دست‌نخورده می‌ماند."
                                                                    else
                                                                        "Edit this stage's text. The change affects only this patient's record; the shared template is untouched.",
                                                                    fontSize = 11.sp,
                                                                    color = Color.Gray
                                                                )
                                                                OutlinedTextField(
                                                                    value = stageEditNoteDraft,
                                                                    onValueChange = { stageEditNoteDraft = it },
                                                                    label = { Text(if (isPersian) "شرح مرحله" else "Stage description") },
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    minLines = 3
                                                                )
                                                            }
                                                        },
                                                        confirmButton = {
                                                            TextButton(
                                                                enabled = stageEditNoteDraft.isNotBlank(),
                                                                onClick = {
                                                                    // رفع باگ: قبلاً replaceTreatmentStage صدا زده می‌شد که
                                                                    // فقط رشتهٔ stages را عوض می‌کرد؛ اگر همین مرحله در
                                                                    // completedStages/ignoredStages هم بود، نام قدیمی آنجا
                                                                    // می‌ماند و مرحله دوباره «باقی‌مانده» به‌نظر می‌رسید.
                                                                    // replaceCurrentStageText هر سه فهرست را هم‌زمان
                                                                    // به‌روز می‌کند.
                                                                    if (activeCurrentStage != null && stageEditNoteDraft.isNotBlank()) {
                                                                        viewModel.replaceCurrentStageText(tx, activeCurrentStage, stageEditNoteDraft.trim())
                                                                    }
                                                                    showEditStageDialog = false
                                                                }
                                                            ) {
                                                                Text(if (isPersian) "ذخیره" else "Save")
                                                            }
                                                        },
                                                        dismissButton = {
                                                            TextButton(onClick = { showEditStageDialog = false }) {
                                                                Text(if (isPersian) "انصراف" else "Cancel")
                                                            }
                                                        }
                                                    )
                                                }

                                                if (showTherapyProtocolDialog) {
                                                    ClinicalProtocolDialog(
                                                        therapyTitle = tx.title,
                                                        patientMedicalHistory = patientForProtocol?.medicalHistory ?: "",
                                                        patientMedicalAlerts = patientForProtocol?.medicalAlerts ?: "",
                                                        isPersian = isPersian,
                                                        initialFontSizePt = settings.clinicalFontSizePt,
                                                        onDismiss = { showTherapyProtocolDialog = false }
                                                    )
                                                }

                                                // Section 3: Future queued stages for upcoming sessions
                                                if (futureQueuedStages.isNotEmpty()) {
                                                    Text(
                                                        text = if (isPersian) "⏳ مراحل و جلسات بعدی تراپی (در نوبت درمان):" else "⏳ Upcoming Future Therapy Sessions:",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.Gray,
                                                        modifier = Modifier.padding(top = 4.dp)
                                                    )

                                                    futureQueuedStages.forEachIndexed { fIdx, futureStage ->
                                                        Card(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                                            shape = RoundedCornerShape(8.dp),
                                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                        ) {
                                                            Row(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                                                    Text(
                                                                        text = futureStage,
                                                                        fontSize = 11.sp,
                                                                        color = Color.DarkGray
                                                                    )
                                                                }
                                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                    Surface(
                                                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                                                        shape = RoundedCornerShape(4.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = if (isPersian) "جلسه ${completedStagesList.size + fIdx + 2}" else "Session ${completedStagesList.size + fIdx + 2}",
                                                                            fontSize = 9.sp,
                                                                            color = Color.Gray,
                                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            // All stages finished
                                            Surface(
                                                color = Color(0xFFE8F5E9),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                                    Text(
                                                        text = if (isPersian) "تمامی مراحل تراپی این درمان با موفقیت به پایان رسید." else "All therapy stages for this treatment have been completed.",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF1B5E20)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // طول کار کانال + شماتیک سه‌بعدی — فقط برای عصب‌کشی/اندو
                                    if (tx.isEndodontic) {
                                        EndoWorkingLengthCard(treatment = tx, viewModel = viewModel)
                                    }

                                    // سطوح درگیر ترمیم — فقط برای درمان‌های ترمیمی
                                    if (tx.isRestorative) {
                                        ToothSurfaceRestorationCard(treatment = tx, viewModel = viewModel)
                                    }
                                }
                            }
                        }
                    }

                    // پایان جلسه و آرشیو نوبت — در انتهای صفحه مدیریت جلسات و تراپی درمان
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = sessionFinishConfirmed, onCheckedChange = { sessionFinishConfirmed = it })
                        Text(
                            text = if (isPersian) "این جلسه به پایان رسید (اتمام کردن)" else "This session has finished",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (sessionFinishConfirmed && !appt.isArchived) {
                            viewModel.archiveAppointment(appt)
                            val rxPatient = patients.find { it.id == appt.patientId }
                            val baseSuggestion = com.example.data.PrescriptionKnowledgeBase.suggest(editTxType, "تکمیل جلسه") ?: com.example.data.DrugPrescription()
                            val baseWithNotes = baseSuggestion.copy(notes = if (editNotes.isNotBlank()) "${baseSuggestion.notes}\n$editNotes".trim() else baseSuggestion.notes)
                            val adjusted = com.example.data.PrescriptionKnowledgeBase.adjustForPatient(
                                baseWithNotes,
                                rxPatient?.medicalHistory ?: "",
                                rxPatient?.medicalAlerts ?: ""
                            )
                            val dummyTx = com.example.data.Treatment(id = 0L, patientId = appt.patientId, title = editTxType.ifBlank { "تکمیل جلسه درمان" }, cost = 0)
                            pendingRxForAppt = PendingRxPopup(treatment = dummyTx, stageName = "تکمیل و آرشیو جلسه", prescription = adjusted, patient = rxPatient)
                        } else {
                            viewModel.updateAppointment(appt.copy(treatmentType = editTxType, note = editNotes, isCompleted = sessionFinishConfirmed))
                        }
                        sessionAppointment = null
                    },
                    shape = RoundedCornerShape(8.dp)
                ) { Text(viewModel.label("save"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { sessionAppointment = null }) { Text(viewModel.label("cancel")) }
            }
        )

        // ========================================================
        // حذف جلسه — مرحلهٔ ۱: هشدار
        // ========================================================
        if (deleteStep == 1) {
            AlertDialog(
                onDismissRequest = { deleteStep = 0 },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = {
                    Text(
                        if (isPersian) "حذف جلسه؟" else "Delete session?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (isPersian)
                                "جلسهٔ «${appt.patientName}» در تاریخ ${viewModel.formatDate(appt.date)} حذف می‌شود."
                            else
                                "The session for ${appt.patientName} on ${viewModel.formatDate(appt.date)} will be deleted.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            if (isPersian)
                                "این کار برگشت‌پذیر نیست و سابقهٔ جلسه از آرشیو هم پاک می‌شود. طرح‌های درمانی بیمار حذف نمی‌شوند."
                            else
                                "This cannot be undone; the session is removed from the archive as well. The patient's treatment plans are not deleted.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { deleteConfirmText = ""; deleteStep = 2 },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text(if (isPersian) "ادامه" else "Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteStep = 0 }) { Text(if (isPersian) "انصراف" else "Cancel") }
                }
            )
        }

        // ========================================================
        // حذف جلسه — مرحلهٔ ۲: تایپ کلمهٔ «حذف»
        // ========================================================
        if (deleteStep == 2) {
            val requiredWord = if (isPersian) "حذف" else "DELETE"
            val typedOk = deleteConfirmText.trim() == requiredWord
            AlertDialog(
                onDismissRequest = { deleteStep = 0 },
                icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                title = {
                    Text(
                        if (isPersian) "تأیید نهایی حذف" else "Final confirmation",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (isPersian)
                                "برای حذف قطعی، کلمهٔ «$requiredWord» را در کادر زیر تایپ کنید."
                            else
                                "To permanently delete, type the word \"$requiredWord\" below.",
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = deleteConfirmText,
                            onValueChange = { deleteConfirmText = it },
                            singleLine = true,
                            isError = deleteConfirmText.isNotBlank() && !typedOk,
                            placeholder = { Text(requiredWord) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (deleteConfirmText.isNotBlank() && !typedOk) {
                            Text(
                                if (isPersian) "کلمهٔ واردشده مطابقت ندارد." else "The typed word does not match.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (typedOk) {
                                viewModel.deleteAppointment(appt)
                                deleteStep = 0
                                deleteConfirmText = ""
                                sessionAppointment = null
                            }
                        },
                        enabled = typedOk,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text(if (isPersian) "حذف قطعی" else "Delete permanently") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteStep = 0; deleteConfirmText = "" }) {
                        Text(if (isPersian) "انصراف" else "Cancel")
                    }
                }
            )
        }
    }

}

// ==========================================
// MORE MODULES MENU SELECTION GRID
// ==========================================
@Composable
fun MoreModulesGridScreen(viewModel: DentalViewModel) {
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = if (isPersian) "پنل‌های تخصصی و حسابداری مطب" else "Specialized Modules & General Ledgers",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoreSectionTitle(
                    if (isPersian) "مالی و حسابداری" else "Finance & Accounting"
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleMenuCard(
                        title = viewModel.label("expenses"),
                        desc = if (isPersian) "قبوض، اجاره مطب، حقوق پرسنل" else "Clinic bills, salaries & utilities",
                        icon = Icons.Default.ReceiptLong,
                        onClick = { viewModel.currentTab.value = "expenses" },
                        modifier = Modifier.weight(1f)
                    )
                    ModuleMenuCard(
                        title = viewModel.label("personal"),
                        desc = if (isPersian) "حساب‌ها و برداشت‌های شخصی پزشک" else "Private bookkeeping & drawers ledger",
                        icon = Icons.Default.AccountBalanceWallet,
                        onClick = { viewModel.currentTab.value = "personal" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleMenuCard(
                        title = if (isPersian) "مدیریت چک‌های صیادی" else "Sayad Cheques Ledger",
                        desc = if (isPersian) "اسکن صیاد، پاس، برگشتی، انتقال چک" else "Scan, clear, bounce & endorse cheques",
                        icon = Icons.Default.ConfirmationNumber,
                        onClick = { viewModel.currentTab.value = "cheques" },
                        modifier = Modifier.weight(1f)
                    )
                    ModuleMenuCard(
                        title = if (isPersian) "حساب‌ها و کارت‌ها" else "Bank Accounts",
                        desc = if (isPersian) "مدیریت موجودی حساب‌های بانکی" else "Manage bank balances",
                        icon = Icons.Default.CreditCard,
                        onClick = { viewModel.currentTab.value = "bank" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleMenuCard(
                        title = if (isPersian) "گزارش‌ها و اسناد" else "Reports & Documents",
                        desc = if (isPersian) "چاپ فاکتور، صورت‌حساب و گزارش‌های مالی PDF" else "Print invoices, statements & financial PDFs",
                        icon = Icons.Default.Description,
                        onClick = { viewModel.currentTab.value = "reports" },
                        modifier = Modifier.weight(1f)
                    )
                }

                MoreSectionTitle(
                    if (isPersian) "هزینه‌های مطب" else "Clinic Expenses"
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleMenuCard(
                        title = viewModel.label("vendors"),
                        desc = if (isPersian) "تدارکات و خرید اقلام مصرفی" else "Suppliers & supplies orders",
                        // آیکون فروشندگان لوازم دندانپزشکی — نه سبد خرید عمومی
                        icon = Icons.Default.MedicalServices,
                        onClick = { viewModel.currentTab.value = "vendors" },
                        modifier = Modifier.weight(1f)
                    )
                    ModuleMenuCard(
                        title = viewModel.label("labs"),
                        desc = if (isPersian) "ساخت روکش و پروتزهای دندانی" else "Prosthetics, crowns & dentures",
                        // آیکون لابراتوار پروتز — آچار (Build) بی‌ربط بود
                        icon = Icons.Default.Science,
                        onClick = { viewModel.currentTab.value = "labs" },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleMenuCard(
                        title = viewModel.label("maintenance"),
                        desc = if (isPersian) "تعمیر اتوکلاو، توربین و یونیت" else "Repairs & dental chair maintenance",
                        icon = Icons.Default.Handyman,
                        onClick = { viewModel.currentTab.value = "maintenance" },
                        modifier = Modifier.weight(1f)
                    )
                }

                MoreSectionTitle(
                    if (isPersian) "تنظیمات" else "Settings"
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleMenuCard(
                        title = if (isPersian) "شخصی‌سازی مطب" else "Clinic Branding",
                        desc = if (isPersian) "نام کلینیک، پزشک و چیدمان داشبورد" else "Branding, staff list & dashboard layout",
                        icon = Icons.Default.AdminPanelSettings,
                        onClick = { viewModel.currentTab.value = "settings" },
                        modifier = Modifier.weight(1f)
                    )
                    ModuleMenuCard(
                        title = if (isPersian) "پرسنل مطب" else "Staff Management",
                        desc = if (isPersian) "افزودن، ویرایش و حذف پزشک و دستیاران" else "Add, edit & remove doctors and assistants",
                        icon = Icons.Default.Badge,
                        onClick = { viewModel.currentTab.value = "staff_management" },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp, bottom = 2.dp)
    )
}

@Composable
fun ModuleMenuCard(
    title: String,
    desc: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(130.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
            }
            Column {
                Text(text = title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = desc, fontSize = 11.sp, color = Color.Gray, maxLines = 2)
            }
        }
    }
    
}

// ==========================================
// 4. VENDORS MANAGEMENT
// ==========================================
@Composable
fun VendorsScreen(viewModel: DentalViewModel) {
    val vendors by viewModel.vendors.collectAsStateWithLifecycle()
    val clinicExpenses by viewModel.clinicExpenses.collectAsStateWithLifecycle()
    val expensePayments by viewModel.allExpensePayments.collectAsStateWithLifecycle()
    var showForm by remember { mutableStateOf(false) }
    var vendorToDelete by remember { mutableStateOf<Vendor?>(null) }
    var apptToComplete by remember { mutableStateOf<Appointment?>(null) }
    var sessionAppointment by remember { mutableStateOf<Appointment?>(null) }

    // Form inputs
    var name by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var orders by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }

    val currency = viewModel.label("currency")
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val scope = rememberCoroutineScope()

    var invoicePhotoFileId by remember { mutableStateOf<String?>(null) }
    var isScanningInvoice by remember { mutableStateOf(false) }

    // بازطراحی (درخواست ۱۵): ثبت پرداخت به فروشنده + مشاهده جزئیات/مانده
    var vendorToPay by remember { mutableStateOf<com.example.data.Vendor?>(null) }
    var vendorPayAmountStr by remember { mutableStateOf("") }
    var vendorPayNote by remember { mutableStateOf("") }
    var vendorToDetail by remember { mutableStateOf<com.example.data.Vendor?>(null) }

    val context = LocalContext.current
    val contentResolver = context.contentResolver

    // Camera launcher
    val launchCamera = rememberSafeCameraLauncher(context) { path ->
        invoicePhotoFileId = path
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                invoicePhotoFileId = saveUriToLocalFile(context, uri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = viewModel.label("vendors"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = { showForm = !showForm }) {
                Icon(if (showForm) Icons.Default.Close else Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (showForm) viewModel.label("cancel") else viewModel.label("add_vendor"))
            }
        }
        Text(
            text = if (isPersian)
                "فاکتورها و پرداخت‌های این فروشنده در بخش «${com.example.data.PartySection.STORE.faTitle}» به‌صورت طرف‌حسابِ حسابِ مستقل ثبت و محاسبه می‌شود."
            else
                "This supplier's invoices & payments are tracked as an independent party account in the \"Dental Supply Store\" section.",
            fontSize = 10.5.sp,
            color = Color.Gray
        )

        if (showForm) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Document & Invoice Camera/Gallery picker
                    Text(
                        text = if (isPersian) "تصویر فاکتور خرید" else "Purchase Invoice Photo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { launchCamera() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = "Camera", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPersian) "عکس دوربین" else "Camera Image", fontSize = 11.sp)
                        }

                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Photo, contentDescription = "Gallery", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPersian) "گالری" else "Gallery", fontSize = 11.sp)
                        }
                    }

                    // Photo preview and AI trigger
                    invoicePhotoFileId?.let { base64 ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                SmartImage(
                                    fileId = base64,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = { invoicePhotoFileId = null },
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        .size(24.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(viewModel.label("vendor_name")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = company, onValueChange = { company = it }, label = { Text(viewModel.label("company_name")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text(viewModel.label("phone")) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text(viewModel.label("product_type")) }, modifier = Modifier.fillMaxWidth())
                    
                    Column {
                        OutlinedTextField(value = orders, onValueChange = { orders = it }, label = { Text(viewModel.label("total_purchases")) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        RealTimeAmountHelper(orders)
                    }

                    Column {
                        OutlinedTextField(value = paid, onValueChange = { paid = it }, label = { Text(viewModel.label("paid_amount")) }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        RealTimeAmountHelper(paid)
                    }

                    Button(
                        onClick = {
                            val ord = orders.toEnglishLong()
                            val pd = paid.toEnglishLong()
                            if (name.isNotEmpty()) {
                                viewModel.addVendor(name, company, phone, type, ord, pd, invoicePhotoFileId)
                                showForm = false
                                // reset
                                name = ""
                                company = ""
                                phone = ""
                                type = ""
                                orders = ""
                                paid = ""
                                invoicePhotoFileId = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(viewModel.label("save"))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(vendors) { vendor ->
                    val ledger = vendorLedger(vendor, clinicExpenses, expensePayments)
                    val balance = ledger.remaining
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "${vendor.name} (${vendor.company})", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(text = "${viewModel.label("product_type")}: ${vendor.itemType}", fontSize = 12.sp, color = Color.Gray)
                                Text(text = "${viewModel.label("phone")}: ${vendor.phone}", fontSize = 11.sp, color = Color.Gray)
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                                    Text(text = "${viewModel.label("total_purchases")}: ${formatAmount(ledger.orders)} $currency", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(text = "${viewModel.label("paid_amount")}: ${formatAmount(ledger.paid)} $currency", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(100.dp)) {
                                Text(
                                    text = if (ledger.isPartyDebtor) {
                                        if (isPersian) "بدهکار به ما" else "Owes us"
                                    } else viewModel.label("vendor_debt"),
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                                Text(
                                    text = "${formatAmount(kotlin.math.abs(balance))} $currency",
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        ledger.isPartyDebtor -> Color(0xFF6A1B9A)
                                        balance > 0 -> DebtColor
                                        else -> ClearColor
                                    }
                                )
                                
                                // Render thumbnail if invoice photo exists
                                vendor.invoicePhotoFileId?.let { base64 ->
                                    var showZoom by remember { mutableStateOf(false) }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Card(
                                        onClick = { showZoom = true },
                                        modifier = Modifier.size(44.dp),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, Color.LightGray)
                                    ) {
                                        SmartImage(
                                            fileId = base64,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }

                                    if (showZoom) {
                                        AlertDialog(
                                            onDismissRequest = { showZoom = false },
                                            title = { Text(if (isPersian) "تصویر فاکتور خرید" else "Invoice Photo Preview") },
                                            text = {
                                                SmartImage(
                                                    fileId = base64,
                                                    modifier = Modifier.fillMaxWidth().height(300.dp),
                                                    contentScale = ContentScale.Fit
                                                )
                                            },
                                            confirmButton = {
                                                TextButton(onClick = { showZoom = false }) {
                                                    Text(if (isPersian) "بستن" else "Close")
                                                }
                                            }
                                        )
                                    }
                                }

                                IconButton(onClick = { vendorToDelete = vendor }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.LightGray)
                                }
                            }
                        }

                        // بازطراحی (درخواست ۱۵): جزئیات + مانده + ثبت پرداخت
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isPersian)
                                    "مانده حساب: ${formatAmount(balance)} $currency ${if (balance > 0) "(بدهی)" else "(تسویه)"}"
                                else
                                    "Balance: ${formatAmount(balance)} $currency",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (balance > 0) DebtColor else ClearColor
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { vendorToDetail = vendor }) {
                                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(if (isPersian) "جزئیات" else "Details", fontSize = 10.sp)
                                }
                                TextButton(onClick = {
                                    vendorToPay = vendor
                                    vendorPayAmountStr = ""
                                    vendorPayNote = ""
                                }) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(if (isPersian) "ثبت پرداخت" else "Record Payment", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // دیالوگ ثبت پرداخت به فروشنده — بدهی فروشنده را کاهش می‌دهد و به‌صورت هزینه ثبت می‌شود (درخواست ۱۵)
    vendorToPay?.let { vendor ->
        AlertDialog(
            onDismissRequest = { vendorToPay = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Payment, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (isPersian) "ثبت پرداخت به ${vendor.name}" else "Record payment to ${vendor.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val vendorPayLedger = vendorLedger(vendor, clinicExpenses, expensePayments)
                    val vendorBalance = vendorPayLedger.remaining
                    Text(
                        text = when {
                            vendorPayLedger.isPartyDebtor ->
                                if (isPersian) "بدهکار به ما: ${formatAmount(vendorPayLedger.partyDebtAmount)} $currency"
                                else "Owes us: ${formatAmount(vendorPayLedger.partyDebtAmount)} $currency"
                            else ->
                                if (isPersian) "مانده فعلی: ${formatAmount(vendorBalance)} $currency"
                                else "Current balance: ${formatAmount(vendorBalance)} $currency"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            vendorPayLedger.isPartyDebtor -> Color(0xFF6A1B9A)
                            vendorBalance > 0 -> DebtColor
                            else -> ClearColor
                        }
                    )
                    OutlinedTextField(
                        value = vendorPayAmountStr,
                        onValueChange = { vendorPayAmountStr = it.filter(Char::isDigit) },
                        label = { Text(if (isPersian) "مبلغ پرداختی" else "Payment amount") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = vendorPayNote,
                        onValueChange = { vendorPayNote = it },
                        label = { Text(if (isPersian) "توضیح پرداخت (اختیاری)" else "Payment note (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = vendorPayAmountStr.toEnglishLongOrNull() ?: 0L
                        if (amt > 0L) {
                            viewModel.payVendor(vendor, amt, vendorPayNote)
                            vendorToPay = null
                        }
                    },
                    enabled = vendorPayAmountStr.toEnglishLongOrNull()?.let { it > 0L } == true
                ) {
                    Text(if (isPersian) "ثبت پرداخت" else "Save Payment", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vendorToPay = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    // دیالوگ جزئیات فروشنده — مشخصات کامل + تصویر فاکتور (درخواست ۱۵)
    vendorToDetail?.let { vendor ->
        val detailLedger = vendorLedger(vendor, clinicExpenses, expensePayments)
        val vendorBalance = detailLedger.remaining
        AlertDialog(
            onDismissRequest = { vendorToDetail = null },
            title = { Text(if (isPersian) "جزئیات ${vendor.name}" else "Details — ${vendor.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vendor.company.isNotBlank()) Text(if (isPersian) "شرکت: ${vendor.company}" else "Company: ${vendor.company}", fontSize = 12.sp)
                    if (vendor.phone.isNotBlank()) Text(if (isPersian) "تلفن: ${vendor.phone}" else "Phone: ${vendor.phone}", fontSize = 12.sp)
                    if (vendor.itemType.isNotBlank()) Text(if (isPersian) "نوع کالا/خدمات: ${vendor.itemType}" else "Type: ${vendor.itemType}", fontSize = 12.sp)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    Text(if (isPersian) "خرید کل: ${formatAmount(detailLedger.orders)} $currency" else "Total purchases: ${formatAmount(detailLedger.orders)} $currency", fontSize = 12.sp)
                    Text(if (isPersian) "پرداختی: ${formatAmount(detailLedger.paid)} $currency" else "Paid: ${formatAmount(detailLedger.paid)} $currency", fontSize = 12.sp)
                    Text(
                        text = when {
                            detailLedger.isPartyDebtor ->
                                if (isPersian) "بدهکار به ما: ${formatAmount(detailLedger.partyDebtAmount)} $currency"
                                else "Owes us: ${formatAmount(detailLedger.partyDebtAmount)} $currency"
                            else ->
                                if (isPersian) "مانده: ${formatAmount(vendorBalance)} $currency"
                                else "Balance: ${formatAmount(vendorBalance)} $currency"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            detailLedger.isPartyDebtor -> Color(0xFF6A1B9A)
                            vendorBalance > 0 -> DebtColor
                            else -> ClearColor
                        }
                    )
                    vendor.invoicePhotoFileId?.let { photo ->
                        Text(if (isPersian) "تصویر فاکتور خرید:" else "Purchase invoice photo:", fontSize = 11.sp, color = Color.Gray)
                        SmartImage(
                            fileId = photo,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vendorToDetail = null }) { Text(if (isPersian) "بستن" else "Close") }
            }
        )
    }

    if (vendorToDelete != null) {
        AlertDialog(
            onDismissRequest = { vendorToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(text = if (isPersian) "تایید حذف تامین‌کننده/لابراتوار" else "Confirm Vendor Deletion", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = if (isPersian) "آیا از حذف کامل اطلاعات «${vendorToDelete!!.name} (${vendorToDelete!!.company})» اطمینان دارید؟ این عمل غیرقابل بازگشت است."
                    else "Are you sure you want to delete vendor '${vendorToDelete!!.name}'?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteVendor(vendorToDelete!!)
                        vendorToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف اطلاعات" else "Delete Vendor", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vendorToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }
}

// ==========================================
// 5. DENTAL LABS
// ==========================================
@Composable
fun LabsScreen(viewModel: DentalViewModel) {
    val orders by viewModel.labOrders.collectAsStateWithLifecycle()
    val services by viewModel.labServices.collectAsStateWithLifecycle()
    val patients by viewModel.patients.collectAsStateWithLifecycle()
    val allTreatments by viewModel.allTreatments.collectAsStateWithLifecycle()
    val vendors by viewModel.vendors.collectAsStateWithLifecycle()
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val currency = viewModel.label("currency")

    var selectedTab by remember { mutableStateOf(0) } // 0: Orders, 1: Services Catalog
    var showForm by remember { mutableStateOf(false) }
    var labOrderToDelete by remember { mutableStateOf<LabOrder?>(null) }
    var labOrderToEdit by remember { mutableStateOf<LabOrder?>(null) }
    var serviceToDelete by remember { mutableStateOf<LabService?>(null) }
    var serviceToEdit by remember { mutableStateOf<LabService?>(null) }

    // Lab Order Form inputs
    var selectedLabName by remember { mutableStateOf("") }
    var selectedPatientObj by remember { mutableStateOf<Patient?>(null) }
    var toothNum by remember { mutableStateOf("") }
    var prosthesis by remember { mutableStateOf("") }
    var shadeBrand by remember { mutableStateOf("") }
    var shadeColor by remember { mutableStateOf("") }
    var unitPriceStr by remember { mutableStateOf("") }
    var costStr by remember { mutableStateOf("") }
    var selectedDueDate by remember { mutableStateOf<Long?>(null) }
    var formattedDueDateText by remember { mutableStateOf("") }
    var deliveryType by remember { mutableStateOf("FINAL") } // FRAME or FINAL
    // روزهای قابل ویرایش تحویل (درخواست ۱۲) — پیش‌فرض از تعرفه خدمات
    var frameDaysOverride by remember { mutableStateOf("3") }
    var finalDaysOverride by remember { mutableStateOf("7") }
    var selectedTxIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // قیمت واحد = آخرین قیمت واردشده (درخواست ۱۳) — در حافظهٔ جلسه نگه داشته می‌شود تا
    // برای سفارش بعدی همان قیمت پیش‌فرض شود (قابل ویرایش)
    var lastUnitPrice by remember { mutableStateOf<String?>(null) }
    var itemPriceOverrides by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var patientQuery by remember { mutableStateOf("") }

    // Service Form inputs
    var sLabName by remember { mutableStateOf("") }
    var sServiceName by remember { mutableStateOf("") }
    var sPriceStr by remember { mutableStateOf("") }
    var sNotes by remember { mutableStateOf("") }
    var sFrameDays by remember { mutableStateOf("3") }
    var sFinalDays by remember { mutableStateOf("7") }

    val context = LocalContext.current

    // تقویم شمسی برای تاریخ تحویل سفارش لابراتوار (جایگزین DatePickerDialog میلادی)
    var showLabDueJalaliPicker by remember { mutableStateOf(false) }
    if (showLabDueJalaliPicker) {
        JalaliDatePickerDialog(
            initialTimestamp = selectedDueDate ?: System.currentTimeMillis(),
            isPersian = isPersian,
            onDateSelected = { jy, jm, jd ->
                val picked = com.example.util.PersianCalendarHelper.jalaliToTimestamp(jy, jm, jd)
                selectedDueDate = picked
                formattedDueDateText = viewModel.formatDateFullVerbal(picked)
                showLabDueJalaliPicker = false
            },
            onDismiss = { showLabDueJalaliPicker = false }
        )
    }

    // منبع واحد لابراتوارها در کل اپ: تعرفه خدمات + تأمین‌کننده‌ها + طرف‌حساب‌های بخش هزینه‌ها (LAB)
    val labParties by viewModel.partyAccounts.collectAsStateWithLifecycle()
    val availableLabNames = (
        services.map { it.labName } +
            vendors.map { it.name } +
            labParties.filter { it.section.trim() == com.example.data.PartySection.LAB.key }.map { it.name }
        ).map { it.trim() }.distinct().filter { it.isNotBlank() }

    // به‌درخواست کاربر: نام خدمت لابراتوار فقط انتخابی است — فهرست استاندارد
    // خدمات لابراتواری + عناوین تراپی‌ها (هماهنگ با بخش تراپی‌ها در کل اپ).
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val labServiceTitleOptions = remember(templates, services) {
        com.example.data.LabServiceCatalog.options(
            therapyTitles = templates.filter { !it.isDeleted }.map { it.titleFa.ifBlank { it.title } },
            existingServiceNames = services.map { it.serviceName }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = viewModel.label("labs"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = { showForm = !showForm }) {
                Icon(if (showForm) Icons.Default.Close else Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    if (showForm) viewModel.label("cancel")
                    else if (selectedTab == 0) viewModel.label("add_lab_order")
                    else if (isPersian) "لابراتوار جدید" else "New Lab"
                )
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0; showForm = false },
                text = { Text(if (isPersian) "ثبت و لیست سفارشات کاری" else "Lab Orders", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1; showForm = false },
                text = { Text(if (isPersian) "تعرفه و لیست قیمت خدمات" else "Lab Prices Catalog", fontWeight = FontWeight.Bold) }
            )
        }

        if (selectedTab == 0) {
            // Orders Tab
            if (showForm) {
                // قیمت واحد = آخرین قیمت واردشده (درخواست ۱۳) — پیش‌فرض سفارش جدید
                LaunchedEffect(showForm) {
                    if (unitPriceStr.isBlank() && !lastUnitPrice.isNullOrBlank()) {
                        unitPriceStr = lastUnitPrice!!
                        val count = parseTeethCount(toothNum)
                        if (count > 0) costStr = ((lastUnitPrice!!.toEnglishLongOrNull() ?: 0L) * count).toString()
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(if (isPersian) "ثبت سفارش پروتز به لابراتوار" else "New Prosthesis Order", fontWeight = FontWeight.Bold, fontSize = 14.sp)

                        // 1. Lab Selector (Dropdown)
                        Text(if (isPersian) "انتخاب لابراتوار:" else "Select Lab:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        var labDropdownExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = labDropdownExpanded,
                            onExpandedChange = { labDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedLabName,
                                onValueChange = { selectedLabName = it; labDropdownExpanded = true },
                                label = { Text(if (isPersian) "نام لابراتوار (انتخاب یا تایپ جدید)" else "Lab Name (select or type new)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = labDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            val filteredLabNames = availableLabNames.filter { selectedLabName.isBlank() || it.contains(selectedLabName, ignoreCase = true) }
                            if (filteredLabNames.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = labDropdownExpanded,
                                    onDismissRequest = { labDropdownExpanded = false }
                                ) {
                                    filteredLabNames.forEach { lName ->
                                        DropdownMenuItem(
                                            text = { Text(lName) },
                                            onClick = {
                                                selectedLabName = lName
                                                labDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Patient Selector (Dropdown) & Auto-Fill from Record
                        Text(if (isPersian) "انتخاب بیمار از پرونده:" else "Select Patient:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        var patientDropdownExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = patientDropdownExpanded,
                            onExpandedChange = { patientDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedPatientObj?.name ?: patientQuery,
                                onValueChange = {
                                    patientQuery = it
                                    selectedPatientObj = null
                                    selectedTxIds = emptySet()
                                    itemPriceOverrides = emptyMap()
                                    patientDropdownExpanded = true
                                },
                                label = { Text(viewModel.label("patient_name")) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = patientDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            val filteredPatients = patients.filter { !it.isArchived && (patientQuery.isBlank() || it.name.contains(patientQuery, ignoreCase = true)) }
                            if (filteredPatients.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = patientDropdownExpanded,
                                    onDismissRequest = { patientDropdownExpanded = false }
                                ) {
                                    filteredPatients.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p.name) },
                                            onClick = {
                                                selectedPatientObj = p
                                                patientQuery = ""
                                                patientDropdownExpanded = false
                                                // (درخواست ۱۱) رفتار قبلی که درست کار می‌کرد بازسازی شد:
                                                // انتخاب بیمار => همه درمان‌های بازِ مرتبط با لابراتوار همان بیمار به‌صورت
                                                // پیش‌فرض تیک می‌خورند و شماره دندان/نوع پروتز/قیمت واحد خودکار پر می‌شود؛
                                                // کاربر می‌تواند قبل از ثبت، موارد را تغییر دهد.
                                                val pTreatments = allTreatments.filter { it.patientId == p.id && it.status != "COMPLETED" && isLabRelatedTreatment(it, services) }
                                                selectedTxIds = pTreatments.map { it.id }.toSet()
                                                itemPriceOverrides = pTreatments.associate { tx ->
                                                    val matchedService = services.find { s ->
                                                        (selectedLabName.isBlank() || s.labName.equals(selectedLabName, ignoreCase = true)) &&
                                                        (s.serviceName.contains(tx.title, ignoreCase = true) || tx.title.contains(s.serviceName, ignoreCase = true))
                                                    } ?: services.find { s -> s.serviceName.contains(tx.title, ignoreCase = true) || tx.title.contains(s.serviceName, ignoreCase = true) }
                                                    tx.id to (matchedService?.price ?: 0L).toString()
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 3. Itemized treatment checklist — matches each treatment to the lab's price list,
                        // groups by type, and combines everything into a single invoice.
                        if (selectedPatientObj != null) {
                            val patientTreatments = allTreatments.filter { it.patientId == selectedPatientObj!!.id && it.status != "COMPLETED" && isLabRelatedTreatment(it, services) }
                            if (patientTreatments.isNotEmpty()) {
                                Text(
                                    if (isPersian) "خدمات مربوط به این بیمار (تیک بزنید تا در فاکتور واحد لحاظ شود):" else "Patient's services (check to include in the combined invoice):",
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    patientTreatments.forEach { tx ->
                                        val isChecked = selectedTxIds.contains(tx.id)
                                        val priceStr = itemPriceOverrides[tx.id] ?: tx.cost.toString()
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    selectedTxIds = if (checked) selectedTxIds + tx.id else selectedTxIds - tx.id
                                                    if (checked && !itemPriceOverrides.containsKey(tx.id)) {
                                                        val matchedService = services.find { s ->
                                                            (selectedLabName.isBlank() || s.labName.equals(selectedLabName, ignoreCase = true)) &&
                                                            (s.serviceName.contains(tx.title, ignoreCase = true) || tx.title.contains(s.serviceName, ignoreCase = true))
                                                        }
                                                        itemPriceOverrides = itemPriceOverrides + (tx.id to (matchedService?.price ?: tx.cost).toString())
                                                    }
                                                }
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                // درخواست کاربر: فقط نام پروتز/خدمت ذکر شود، بدون شماره‌های دندان
                                                Text(tx.title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            OutlinedTextField(
                                                value = priceStr,
                                                onValueChange = { itemPriceOverrides = itemPriceOverrides + (tx.id to it) },
                                                modifier = Modifier.width(110.dp),
                                                singleLine = true,
                                                enabled = isChecked,
                                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                                            )
                                        }
                                    }
                                }

                                // Recompute the aggregated fields whenever the selection changes
                                LaunchedEffect(selectedTxIds, itemPriceOverrides, selectedPatientObj) {
                                    val chosen = patientTreatments.filter { selectedTxIds.contains(it.id) }
                                    if (chosen.isNotEmpty()) {
                                        toothNum = chosen.map { it.selectedTeeth }.filter { it.isNotBlank() }.distinct().joinToString(", ")
                                        val grouped = chosen.groupBy { it.title }
                                        // پروتز درخواستی فقط «نام» درخواست است — شماره‌های دندان در فیلد جداگانه‌ی
                                        // «شماره دندان‌ها» باقی می‌مانند و وارد این فیلد نمی‌شوند.
                                        prosthesis = grouped.entries.joinToString(" + ") { (title, list) -> if (list.size > 1) "$title x${list.size}" else title }
                                        val totalCost = chosen.sumOf { (itemPriceOverrides[it.id] ?: it.cost.toString()).toEnglishLongOrNull() ?: it.cost }
                                        costStr = totalCost.toString()
                                        val count = parseTeethCount(toothNum)
                                        if (count > 0) {
                                            unitPriceStr = (totalCost / count).toString()
                                        }
                                    }
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (isPersian) "تعداد خدمات انتخاب‌شده: ${selectedTxIds.size}" else "Selected services: ${selectedTxIds.size}",
                                            fontSize = 11.sp, fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            "${formatAmount(costStr.toEnglishLongOrNull() ?: 0L)} $currency",
                                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }

                        // 4. Quick Service selector from Catalog for selected lab
                        val availableServices = services.filter { s -> selectedLabName.isBlank() || s.labName.contains(selectedLabName, ignoreCase = true) }
                        if (availableServices.isNotEmpty()) {
                            Text(if (isPersian) "انتخاب خدمت از تعرفه و محاسبه هوشمند زمان تحویل:" else "Select Service from Price Catalog:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                availableServices.forEach { s ->
                                    FilterChip(
                                        selected = prosthesis == s.serviceName,
                                        onClick = {
                                            if (selectedLabName.isBlank()) selectedLabName = s.labName
                                            // این یک سفارش تک‌خدمتی سریع است؛ برای جلوگیری از ناهماهنگی قیمت،
                                            // انتخاب چک‌لیست درمان‌های تعدادی (که مبلغ را خودکار بازنویسی می‌کند) پاک می‌شود
                                            // تا قیمت دقیقاً همان تعرفه ثبت‌شده لابراتوار باقی بماند.
                                            selectedTxIds = emptySet()
                                            itemPriceOverrides = emptyMap()
                                            prosthesis = s.serviceName
                                            unitPriceStr = s.price.toString()
                                            val count = parseTeethCount(toothNum)
                                            costStr = (s.price * count).toString()
                                            val days = if (deliveryType == "FRAME") s.frameDeliveryDays else s.finalDeliveryDays
                                            val targetTime = System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L)
                                            selectedDueDate = targetTime
                                            formattedDueDateText = viewModel.formatDateFullVerbal(targetTime)
                                        },
                                        label = { Text("${s.serviceName} (${formatAmount(s.price)})", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = toothNum,
                            onValueChange = { input ->
                                toothNum = input
                                val count = parseTeethCount(input)
                                val uPrice = unitPriceStr.toEnglishLongOrNull()
                                if (uPrice != null && uPrice > 0L) {
                                    costStr = (uPrice * count).toString()
                                } else {
                                    val total = costStr.toEnglishLongOrNull()
                                    if (total != null && count > 0) {
                                        unitPriceStr = (total / count).toString()
                                    }
                                }
                            },
                            label = { Text(if (isPersian) "شماره دندان‌ها (مثال: 13/14/15/16/17)" else "Tooth Numbers (e.g. 13/14/15/16/17)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(value = prosthesis, onValueChange = { prosthesis = it }, label = { Text(viewModel.label("prosthesis_type")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                        // فیلد انتخابی و سرچ شونده رنگ و شید رنگ
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = if (isPersian) "🎨 انتخاب شرکت سازنده / برند و رنگ شید دندان" else "Shade Brand & Color",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                // Brand selector & input
                                var brandSearch by remember { mutableStateOf("") }
                                val shadeBrands = listOf("نوریتاکه (Noritake)", "ویتا (Vita)", "ایوکلار (Ivoclar)", "زرکونزاهن (Zirkonzahn)", "شوفو (Shofu)", "جی‌سی (GC)", "دنتسپلی (Dentsply)")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    shadeBrands.forEach { b ->
                                        FilterChip(
                                            selected = shadeBrand == b,
                                            onClick = { shadeBrand = if (shadeBrand == b) "" else b },
                                            label = { Text(b, fontSize = 10.sp) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = shadeBrand,
                                    onValueChange = { shadeBrand = it },
                                    label = { Text(if (isPersian) "نام شرکت / برند (مثال: نوریتاکه)" else "Shade Brand (e.g. Noritake)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                // Shade color chips & input
                                val commonShades = listOf("A1", "A2", "A3", "A3.5", "A4", "B1", "B2", "B3", "B4", "C1", "C2", "C3", "C4", "D2", "D3", "D4", "BL1", "BL2", "BL3", "BL4")
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    commonShades.forEach { sh ->
                                        FilterChip(
                                            selected = shadeColor.equals(sh, ignoreCase = true),
                                            onClick = { shadeColor = if (shadeColor == sh) "" else sh },
                                            label = { Text(sh, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = shadeColor,
                                    onValueChange = { shadeColor = it },
                                    label = { Text(if (isPersian) "رنگ / شید (مثال: A3 یا BL2)" else "Shade / Color (e.g. A3 or BL2)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }

                        // خلاصه محاسبات ضرب تعداد واحد دندان در قیمت واحد
                        val teethCount = parseTeethCount(toothNum)
                        val currentUnitPrice = unitPriceStr.toEnglishLongOrNull() ?: (if (teethCount > 0) (costStr.toEnglishLongOrNull() ?: 0L) / teethCount else 0L)
                        val currentTotalCost = costStr.toEnglishLongOrNull() ?: (currentUnitPrice * teethCount)

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(if (isPersian) "تعداد سفارش بر حسب واحد دندان:" else "Teeth Unit Count:", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text("$teethCount واحد دندان", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(if (isPersian) "قیمت هر واحد خدمات در لابراتوار:" else "Lab Unit Price:", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text("${formatAmount(currentUnitPrice)} $currency", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isPersian) "جمع کل فاکتور ($teethCount واحد × ${formatAmount(currentUnitPrice)} تومان):" else "Calculated Invoice Total:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Text("${formatAmount(currentTotalCost)} $currency", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        Text(if (isPersian) "نوع تحویل سفارش:" else "Delivery Type:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = deliveryType == "FRAME",
                                onClick = {
                                    deliveryType = "FRAME"
                                    val matchedServ = services.find { it.serviceName.contains(prosthesis, ignoreCase = true) }
                                    val days = (matchedServ?.frameDeliveryDays?.toLong()) ?: (frameDaysOverride.toEnglishLongOrNull() ?: 3L)
                                    val targetTime = System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L)
                                    selectedDueDate = targetTime
                                    formattedDueDateText = viewModel.formatDateFullVerbal(targetTime)
                                },
                                label = { Text(if (isPersian) "امتحان فریم (${frameDaysOverride} روزه)" else "Frame Test (${frameDaysOverride}d)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = deliveryType == "FINAL",
                                onClick = {
                                    deliveryType = "FINAL"
                                    val matchedServ = services.find { it.serviceName.contains(prosthesis, ignoreCase = true) }
                                    val days = (matchedServ?.finalDeliveryDays?.toLong()) ?: (finalDaysOverride.toEnglishLongOrNull() ?: 7L)
                                    val targetTime = System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L)
                                    selectedDueDate = targetTime
                                    formattedDueDateText = viewModel.formatDateFullVerbal(targetTime)
                                },
                                label = { Text(if (isPersian) "تحویل نهایی (${finalDaysOverride} روزه)" else "Final Delivery (${finalDaysOverride}d)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // روزهای قابل ویرایش امتحان فریم / تحویل نهایی (درخواست ۱۲)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = frameDaysOverride,
                                onValueChange = { input -> frameDaysOverride = input.filter(Char::isDigit) },
                                label = { Text(if (isPersian) "روزهای امتحان فریم" else "Frame Days") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = finalDaysOverride,
                                onValueChange = { input -> finalDaysOverride = input.filter(Char::isDigit) },
                                label = { Text(if (isPersian) "روزهای تحویل نهایی" else "Final Days") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = unitPriceStr,
                                onValueChange = { input ->
                                    unitPriceStr = input
                                    if (input.toEnglishLongOrNull() ?: 0L > 0L) lastUnitPrice = input
                                    val uPrice = input.toEnglishLongOrNull() ?: 0L
                                    val count = parseTeethCount(toothNum)
                                    costStr = (uPrice * count).toString()
                                },
                                label = { Text(if (isPersian) "قیمت واحد (تومان)" else "Unit Price (Toman)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = costStr,
                                onValueChange = { input ->
                                    costStr = input
                                    val total = input.toEnglishLongOrNull() ?: 0L
                                    val count = parseTeethCount(toothNum)
                                    if (count > 0 && total > 0L) {
                                        unitPriceStr = (total / count).toString()
                                    }
                                },
                                label = { Text(if (isPersian) "جمع کل فاکتور (تومان)" else "Total Cost (Toman)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }

                        // درخواست کاربر: عنوان این فیلد «تاریخ ارسال به لابراتوار» باشد (به‌جای تحویل سررسید)
                        OutlinedTextField(
                            value = if (formattedDueDateText.isNotEmpty()) formattedDueDateText else (if (isPersian) "انتخاب تاریخ ارسال" else "Select Send Date"),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(if (isPersian) "تاریخ ارسال به لابراتوار" else "Sent to Lab Date") },
                            trailingIcon = {
                                IconButton(onClick = { showLabDueJalaliPicker = true }) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = "Send to Lab Date", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { showLabDueJalaliPicker = true }
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = if (isPersian) "ℹ️ با ثبت سفارش، مبلغ فاکتور بلافاصله به صورت بدهی به لابراتوار در هزینه های جاری ثبت می گردد."
                                else "ℹ️ Total invoice cost will be automatically posted to Clinic Expenses as lab debt.",
                                fontSize = 11.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Button(
                            onClick = {
                                val cost = costStr.toEnglishLong()
                                val fallbackDays = if (deliveryType == "FRAME") frameDaysOverride.toEnglishLongOrNull() ?: 3L else finalDaysOverride.toEnglishLongOrNull() ?: 7L
                                val dueTime = selectedDueDate ?: (System.currentTimeMillis() + fallbackDays * 24 * 60 * 60 * 1000L)
                                val pName = selectedPatientObj?.name ?: ""
                                if (selectedLabName.isNotEmpty() && pName.isNotEmpty()) {
                                    viewModel.addProsthesisOrderAndExpense(
                                        labName = selectedLabName,
                                        patientName = pName,
                                        toothNum = toothNum,
                                        prosthesis = prosthesis,
                                        cost = cost,
                                        dueDate = dueTime,
                                        shadeBrand = shadeBrand,
                                        shadeColor = shadeColor
                                    )
                                    showForm = false
                                    selectedLabName = ""
                                    selectedPatientObj = null
                                    selectedTxIds = emptySet()
                                    itemPriceOverrides = emptyMap()
                                    patientQuery = ""
                                    toothNum = ""
                                    prosthesis = ""
                                    shadeBrand = ""
                                    shadeColor = ""
                                    unitPriceStr = ""
                                    costStr = ""
                                    formattedDueDateText = ""
                                    selectedDueDate = null
                                    frameDaysOverride = "3"
                                    finalDaysOverride = "7"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isPersian) "ثبت سفارش و صدور فاکتور بدهی" else "Save Order & Issue Invoice")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(orders) { order ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val count = parseTeethCount(order.toothNumber)
                                        val unitPrice = if (count > 0) order.cost / count else order.cost
                                        Text(text = "لابراتوار ${order.labName} ⬅️ بیمار: ${order.patientName}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(text = "${viewModel.label("prosthesis_type")}: ${order.prosthesisType} (${viewModel.label("tooth_num")}: ${if (order.toothNumber.isNotBlank()) order.toothNumber else "-"})", fontSize = 12.sp, color = Color.Gray)
                                        if (!order.shadeBrand.isNullOrBlank() || !order.shadeColor.isNullOrBlank()) {
                                            val sInfo = listOfNotNull(order.shadeBrand?.takeIf { it.isNotBlank() }, order.shadeColor?.takeIf { it.isNotBlank() }).joinToString(" | ")
                                            Text(text = "🎨 رنگ / شید: $sInfo", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                                        }
                                        Text(text = if (isPersian) "تعداد: $count واحد دندان | قیمت: ${formatAmount(unitPrice)} $currency" else "Units: $count | Unit Price: ${formatAmount(unitPrice)} $currency", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                        Text(text = "سررسید تحویل: ${viewModel.formatDateFullVerbal(order.dueDate)}", fontSize = 11.sp, color = DebtColor, fontWeight = FontWeight.Bold)
                                        Text(text = if (isPersian) "جمع کل فاکتور: ${formatAmount(order.cost)} $currency" else "Total Invoice Cost: ${formatAmount(order.cost)} $currency", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Row {
                                        IconButton(onClick = { labOrderToEdit = order }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                                        }
                                        IconButton(onClick = { labOrderToDelete = order }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("ORDERED", "RECEIVED", "FITTED").forEach { status ->
                                        val isSelected = order.status == status
                                        ElevatedButton(
                                            onClick = { viewModel.updateLabOrderStatus(order, status) },
                                            colors = ButtonDefaults.elevatedButtonColors(
                                                containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                            ),
                                            shape = RoundedCornerShape(6.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(text = viewModel.label("status_${status.lowercase()}"), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    var showOfficialInvoiceDialog by remember { mutableStateOf(false) }
                                    OutlinedButton(
                                        onClick = { showOfficialInvoiceDialog = true },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(if (isPersian) "فاکتور رسمی" else "Invoice", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }

                                    if (showOfficialInvoiceDialog) {
                                        OfficialLabInvoiceDialog(
                                            order = order,
                                            viewModel = viewModel,
                                            isPersian = isPersian,
                                            onDismiss = { showOfficialInvoiceDialog = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Services Catalog Tab
            if (showForm) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(if (isPersian) "لابراتوار جدید و تعرفهٔ خدمات آن" else "New Lab & Its Price List", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = if (isPersian)
                                "لابراتوار این‌جا یک‌بار تعریف می‌شود و در کل اپ (سفارشات و صورتحساب هزینه‌ها) همان منبع واحد است؛ خدمات به‌صورت زیرمجموعهٔ همان لابراتوار ثبت می‌شوند."
                            else
                                "The lab is defined once here and shared app-wide; services are stored as its sub-items.",
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                        // نام لابراتوار: انتخاب از لابراتوارهای موجود یا تایپ نام جدید
                        var sLabDropdownExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = sLabDropdownExpanded,
                            onExpandedChange = { sLabDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = sLabName,
                                onValueChange = { sLabName = it; sLabDropdownExpanded = true },
                                label = { Text(if (isPersian) "نام لابراتوار (انتخاب یا نام جدید)" else "Lab Name (select or new)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sLabDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            val filteredLabs = availableLabNames.filter { sLabName.isBlank() || it.contains(sLabName, ignoreCase = true) }
                            if (filteredLabs.isNotEmpty()) {
                                ExposedDropdownMenu(
                                    expanded = sLabDropdownExpanded,
                                    onDismissRequest = { sLabDropdownExpanded = false }
                                ) {
                                    filteredLabs.forEach { lName ->
                                        DropdownMenuItem(text = { Text(lName) }, onClick = { sLabName = lName; sLabDropdownExpanded = false })
                                    }
                                }
                            }
                        }
                        // نام خدمت: به‌درخواست کاربر فقط انتخابی (غیرقابل تایپ) —
                        // فهرست استاندارد خدمات لابراتوار + عناوین تراپی‌ها برای یکپارچگی کل اپ.
                        var sServiceDropdownExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = sServiceDropdownExpanded,
                            onExpandedChange = { sServiceDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = sServiceName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(if (isPersian) "نام خدمت (فقط انتخاب از لیست)" else "Service Name (select only)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sServiceDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = sServiceDropdownExpanded,
                                onDismissRequest = { sServiceDropdownExpanded = false }
                            ) {
                                labServiceTitleOptions.forEach { opt ->
                                    DropdownMenuItem(text = { Text(opt) }, onClick = { sServiceName = opt; sServiceDropdownExpanded = false })
                                }
                            }
                        }
                        OutlinedTextField(
                            value = sPriceStr,
                            onValueChange = { sPriceStr = it },
                            label = { Text(if (isPersian) "قیمت خدمت (تومان)" else "Price (Toman)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = sFrameDays,
                                onValueChange = { sFrameDays = it },
                                label = { Text(if (isPersian) "روزهای تحویل امتحان فریم" else "Frame Days") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = sFinalDays,
                                onValueChange = { sFinalDays = it },
                                label = { Text(if (isPersian) "روزهای تحویل نهایی" else "Final Days") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = sNotes,
                            onValueChange = { sNotes = it },
                            label = { Text(if (isPersian) "توضیحات و مشخصات" else "Notes") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2
                        )
                        Button(
                            onClick = {
                                val p = sPriceStr.toEnglishLong()
                                val fDays = sFrameDays.toEnglishLong().toInt().coerceAtLeast(1)
                                val fnDays = sFinalDays.toEnglishLong().toInt().coerceAtLeast(1)
                                if (sLabName.isNotBlank() && sServiceName.isNotBlank()) {
                                    viewModel.addLabService(sLabName, sServiceName, p, sNotes, fDays, fnDays)
                                    showForm = false
                                    sLabName = ""
                                    sServiceName = ""
                                    sPriceStr = ""
                                    sNotes = ""
                                    sFrameDays = "3"
                                    sFinalDays = "7"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isPersian) "ثبت در تعرفه خدمات" else "Save Service")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // به‌درخواست کاربر: خدمات به‌صورت زیرمجموعهٔ هر لابراتوار نمایش داده می‌شوند.
                    val groupedServices = services.groupBy { it.labName.trim() }
                    groupedServices.keys.sortedBy { it }.forEach { gLabName ->
                        item(key = "labheader-$gLabName") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Icon(Icons.Default.Science, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = gLabName.ifBlank { if (isPersian) "بدون نام لابراتوار" else "Unnamed lab" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (isPersian) "«${groupedServices[gLabName]!!.size} خدمت»" else "(${groupedServices[gLabName]!!.size} services)",
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                        items(groupedServices[gLabName]!!.sortedBy { it.serviceName }, key = { it.id }) { serv ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = serv.serviceName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        text = "⏱️ تحویل امتحان فریم: ${serv.frameDeliveryDays} روز | تحویل نهایی: ${serv.finalDeliveryDays} روز",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    if (serv.notes.isNotBlank()) {
                                        Text(text = serv.notes, fontSize = 11.sp, color = Color.Gray)
                                    }
                                    Text(text = "${formatAmount(serv.price)} $currency", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { serviceToEdit = serv }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Service", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { serviceToDelete = serv }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Service", tint = Color.LightGray)
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    if (serviceToEdit != null) {
        var eLab by remember { mutableStateOf(serviceToEdit!!.labName) }
        var eName by remember { mutableStateOf(serviceToEdit!!.serviceName) }
        var ePrice by remember { mutableStateOf(serviceToEdit!!.price.toString()) }
        var eNotes by remember { mutableStateOf(serviceToEdit!!.notes) }
        var eFrame by remember { mutableStateOf(serviceToEdit!!.frameDeliveryDays.toString()) }
        var eFinal by remember { mutableStateOf(serviceToEdit!!.finalDeliveryDays.toString()) }

        AlertDialog(
            onDismissRequest = { serviceToEdit = null },
            title = { Text(if (isPersian) "ویرایش تعرفه خدمت لابراتوار" else "Edit Lab Service", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = eLab, onValueChange = { eLab = it }, label = { Text(if (isPersian) "نام لابراتوار" else "Lab Name") }, modifier = Modifier.fillMaxWidth())
                    // نام خدمت فقط انتخابی — هماهنگ با فهرست استاندارد و تراپی‌ها
                    var eNameExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = eNameExpanded,
                        onExpandedChange = { eNameExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = eName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(if (isPersian) "نام خدمت (فقط انتخاب از لیست)" else "Service Name (select only)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = eNameExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = eNameExpanded,
                            onDismissRequest = { eNameExpanded = false }
                        ) {
                            labServiceTitleOptions.forEach { opt ->
                                DropdownMenuItem(text = { Text(opt) }, onClick = { eName = opt; eNameExpanded = false })
                            }
                        }
                    }
                    OutlinedTextField(value = ePrice, onValueChange = { ePrice = it }, label = { Text(if (isPersian) "قیمت (تومان)" else "Price") }, modifier = Modifier.fillMaxWidth())
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = eFrame, onValueChange = { eFrame = it }, label = { Text(if (isPersian) "روزهای فریم" else "Frame Days") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = eFinal, onValueChange = { eFinal = it }, label = { Text(if (isPersian) "روزهای نهایی" else "Final Days") }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = eNotes, onValueChange = { eNotes = it }, label = { Text(if (isPersian) "توضیحات" else "Notes") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val price = ePrice.toEnglishLong()
                        val fDays = eFrame.toEnglishLong().toInt().coerceAtLeast(1)
                        val fnDays = eFinal.toEnglishLong().toInt().coerceAtLeast(1)
                        if (eLab.isNotBlank() && eName.isNotBlank()) {
                            viewModel.updateLabService(
                                serviceToEdit!!.copy(
                                    labName = eLab,
                                    serviceName = eName,
                                    price = price,
                                    notes = eNotes,
                                    frameDeliveryDays = fDays,
                                    finalDeliveryDays = fnDays
                                )
                            )
                            serviceToEdit = null
                        }
                    }
                ) {
                    Text(if (isPersian) "ذخیره" else "Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { serviceToEdit = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    if (serviceToDelete != null) {
        AlertDialog(
            onDismissRequest = { serviceToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(text = if (isPersian) "تایید حذف خدمت" else "Confirm Service Deletion", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = if (isPersian) "آیا از حذف خدمت «${serviceToDelete!!.serviceName}» متعلق به لابراتوار «${serviceToDelete!!.labName}» اطمینان دارید؟"
                    else "Are you sure you want to delete service ${serviceToDelete!!.serviceName}?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteLabService(serviceToDelete!!)
                        serviceToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف خدمت" else "Delete Service", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { serviceToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    if (labOrderToEdit != null) {
        LabOrderEditDialog(
            order = labOrderToEdit!!,
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { labOrderToEdit = null }
        )
    }

    if (labOrderToDelete != null) {
        AlertDialog(
            onDismissRequest = { labOrderToDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(text = if (isPersian) "تایید حذف سفارش" else "Confirm Order Deletion", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = if (isPersian) "آیا از حذف سفارش «${labOrderToDelete!!.prosthesisType}» برای بیمار «${labOrderToDelete!!.patientName}» اطمینان دارید؟\n\nهزینهٔ ثبت‌شده در «هزینه‌های مطب» بابت این سفارش و پرداخت‌های آن هم حذف می‌شوند."
                    else "Delete the lab order for ${labOrderToDelete!!.patientName}?\n\nThe linked clinic expense and its payments will be removed too.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteLabOrder(labOrderToDelete!!)
                        labOrderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف سفارش" else "Delete Order", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { labOrderToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }
}

// ==========================================
// SAYADI BARCODE SCANNER DIALOG (CAMERA & ML KIT)
// ==========================================
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
fun RealSayadiBarcodeScannerDialog(
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnScanned by rememberUpdatedState(onScanned)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_PDF417,
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX,
                    Barcode.FORMAT_CODE_128
                )
                .build()
        )
    }
    val isDisposed = remember { AtomicBoolean(false) }
    val hasDeliveredResult = remember { AtomicBoolean(false) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            isDisposed.set(true)
            cameraProvider?.unbindAll()
            analysisExecutor.shutdownNow()
            barcodeScanner.close()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    var manualInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "اسکنر بارکد صیادی (دوربین)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                if (hasCameraPermission) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val future = ProcessCameraProvider.getInstance(ctx)
                                future.addListener({
                                    val provider = future.get()
                                    cameraProvider = provider
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null && !isDisposed.get()) {
                                            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                            barcodeScanner.process(image)
                                                .addOnSuccessListener { barcodes ->
                                                    for (barcode in barcodes) {
                                                        val rawValue = barcode.rawValue ?: continue
                                                        val digits = rawValue.replace(Regex("[^0-9]"), "")
                                                        if (digits.length == 16 && hasDeliveredResult.compareAndSet(false, true)) {
                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            currentOnScanned(digits)
                                                            currentOnDismiss()
                                                            break
                                                        }
                                                    }
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }

                                    try {
                                        provider.unbindAll()
                                        provider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageAnalysis
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Target overlay frame
                        Box(
                            modifier = Modifier
                                .size(width = 220.dp, height = 110.dp)
                                .border(2.dp, Color(0xFF00E676), RoundedCornerShape(8.dp))
                        )
                    }
                } else {
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("اجازه دسترسی به دوربین")
                    }
                }

                OutlinedTextField(
                    value = manualInput,
                    onValueChange = {
                        manualInput = it.filter(Char::isDigit).take(16)
                        errorMessage = null
                    },
                    label = { Text("یا وارد کردن دستی شناسه ۱۶ رقمی صیادی") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("انصراف")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (manualInput.length == 16) {
                                currentOnScanned(manualInput)
                                currentOnDismiss()
                            } else {
                                errorMessage = "شناسه صیادی باید دقیقاً ۱۶ رقم باشد"
                            }
                        },
                        enabled = manualInput.length == 16
                    ) {
                        Text("تایید")
                    }
                }
            }
        }
    }
}

// ==========================================
