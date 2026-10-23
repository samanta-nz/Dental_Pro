package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.ui.DentalViewModel
import com.example.util.Supabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    viewModel: DentalViewModel,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    val isOnline by viewModel.syncEngine.isOnline.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val lastSyncError by viewModel.lastSyncError.collectAsStateWithLifecycle()
    val activeRole by viewModel.currentUserRole.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pendingOpsCount by viewModel.pendingSyncOpsCount.collectAsStateWithLifecycle()

    val logoutError by viewModel.logoutError.collectAsStateWithLifecycle()
    if (logoutError != null) {
        AlertDialog(onDismissRequest = { viewModel.logoutError.value = null }, title = { Text("خروج انجام نشد") },
            text = { Text(logoutError.orEmpty()) }, confirmButton = { TextButton(onClick = { viewModel.logoutError.value = null }) { Text("باشه") } })
    }
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var userName by remember { mutableStateOf("") }
    var userEmail by remember { mutableStateOf("") }
    var userPhone by remember { mutableStateOf("") }
    var isFetchingUser by remember { mutableStateOf(true) }

    var isManualSyncing by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showDeleteAccountStep1 by remember { mutableStateOf(false) }
    var showDeleteAccountStep2 by remember { mutableStateOf(false) }
    var deleteConfirmText by remember { mutableStateOf("") }
    var isDeletingAccount by remember { mutableStateOf(false) }

    // Clinic Info Card - edit mode state
    var isEditingClinicInfo by remember { mutableStateOf(false) }
    var isSavingClinicInfo by remember { mutableStateOf(false) }
    var editClinicName by remember { mutableStateOf("") }
    var editDoctorName by remember { mutableStateOf("") }
    var editDoctorLicenseNo by remember { mutableStateOf("") }
    var editClinicPhone by remember { mutableStateOf("") }

    fun t(fa: String, en: String) = if (isPersian) fa else en

    // Fetch user details from Supabase Auth
    LaunchedEffect(Unit) {
        try {
            val acc = Supabase.currentUser()
            userName = acc.name
            userEmail = acc.email
            userPhone = acc.phone
            // به‌درخواست کاربر: اگر هنوز آواتاری ثبت نشده، اولین‌بار از عکس
            // گوگل‌اکانت لود، در سرور ذخیره و سینک می‌شود؛ با لمس طولانی قابل جایگزینی است.
            viewModel.seedAvatarFromGoogleIfEmpty(acc.avatarUrl)
        } catch (e: Exception) {
            // Offline or fallback to clinic info
            userName = settings.doctorName.ifBlank { "کاربر دنتال‌پرو" }
        } finally {
            isFetchingUser = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = t("حساب کاربری و وضعیت ابری", "User Account & Cloud Sync"),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // User Profile Card (شامل لمس طولانی برای ویرایش آواتار + لیست پرسنل زیر سطر پزشک)
            val allStaffMembers by viewModel.allStaffMembers.collectAsStateWithLifecycle()
            var showEditProfileDialog by remember { mutableStateOf(false) }
            val isMediaUploading by viewModel.isMediaUploading.collectAsStateWithLifecycle()

            // انتخاب آواتار مجزا از لوگو — گالری / دوربین (درخواست: آواتار مجزا + وضعیت ابری)
            val avatarEditLauncher = rememberPhotoEditLauncher(PhotoAssetKind.STAFF_AVATAR) { localPath ->
                viewModel.updateClinicAvatar(localPath)
            }
            val avatarGalleryLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) avatarEditLauncher(uri)
            }
            val avatarCameraLauncher = rememberEditableCameraLauncher(LocalContext.current) { uri ->
                avatarEditLauncher(uri)
            }

            AppUpdatesSection(viewModel, isPersian)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(cs.primaryContainer)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = {
                                            showEditProfileDialog = true
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            // آواتار حساب کاربری — مجزا از لوگوی مطب (درخواست: آواتار مجزا از لوگو)
                            if (!settings.avatarFileId.isNullOrBlank()) {
                                SmartImage(fileId = settings.avatarFileId, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = "Long press to edit avatar",
                                    tint = cs.onPrimaryContainer,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (userName.isNotBlank()) userName else (if (settings.doctorName.isNotBlank()) "دکتر ${settings.doctorName}" else t("پزشک معالج", "Doctor")),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (userEmail.isNotBlank()) {
                                Text(
                                    text = userEmail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = cs.primary.copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = t("نقش: ${activeRole.titleFa}", "Role: ${activeRole.titleEn}"),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = cs.primary
                                    )
                                }
                                Text(
                                    text = t("(لمس طولانی برای ویرایش آواتار)", "(Long press to edit avatar)"),
                                    fontSize = 9.5.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }

                    // زیر سطر پزشک — لیست پرسنل و دستیاران مطب
                    if (allStaffMembers.isNotEmpty()) {
                        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                        Text(
                            text = t("👥 پرسنل و دستیاران پذیرش شده مطب:", "Clinic Staff & Assistants:"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            allStaffMembers.forEach { staff ->
                                val isActiveDoctor = settings.activeDoctorSyncId.isNotBlank() && staff.syncId == settings.activeDoctorSyncId
                                val isActiveAssistant = settings.activeAssistantSyncId.isNotBlank() && staff.syncId == settings.activeAssistantSyncId
                                val isActive = isActiveDoctor || isActiveAssistant
                                // درخواست کاربر: پرسنل «فعال» پررنگ‌تر از بقیه نمایش داده شوند —
                                // پس‌زمینه رنگی، متن ضخیم‌تر و آیکون درشت‌تر برای فعال؛ کم‌رنگ برای غیرفعال.
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isActive) cs.primary.copy(alpha = 0.10f) else Color.Transparent)
                                        .padding(horizontal = 6.dp, vertical = if (isActive) 4.dp else 0.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        // آیکون متفاوت برای پزشک و دستیار + نشان «فعال»
                                        Icon(
                                            imageVector = if (staff.role == "DOCTOR") Icons.Default.MedicalServices else Icons.Default.Person,
                                            contentDescription = null,
                                            tint = if (isActive) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.55f),
                                            modifier = Modifier.size(if (isActive) 18.dp else 15.dp)
                                        )
                                        Text(
                                            staff.fullName,
                                            fontSize = if (isActive) 13.sp else 12.sp,
                                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                                            color = if (isActive) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.75f)
                                        )
                                        if (staff.phone.isNotBlank()) {
                                            Text("(${staff.phone})", fontSize = 11.sp, color = if (isActive) Color.Gray else Color.Gray.copy(alpha = 0.6f))
                                        }
                                        if (isActive) {
                                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isActive) cs.primary.copy(alpha = 0.15f) else cs.secondaryContainer.copy(alpha = 0.5f)
                                    ) {
                                        Text(
                                            text = when {
                                                isActiveDoctor -> t("پزشک فعال", "Active Doctor")
                                                isActiveAssistant -> t("دستیار فعال", "Active Assistant")
                                                staff.role == "DOCTOR" -> "پزشک"
                                                else -> "دستیار"
                                            },
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = if (isActive) cs.primary else cs.onSecondaryContainer,
                                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

                // ویرایش آواتار حساب کاربری — با لمس طولانی آواتار (آواتار مجزا از لوگو + وضعیت ابری)
                if (showEditProfileDialog) {
                    AlertDialog(
                        onDismissRequest = { showEditProfileDialog = false },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = cs.primary)
                                Text(t("آواتار حساب کاربری", "User Avatar"), fontWeight = FontWeight.Bold)
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(96.dp)
                                        .clip(CircleShape)
                                        .background(cs.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!settings.avatarFileId.isNullOrBlank()) {
                                        SmartImage(fileId = settings.avatarFileId, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(48.dp))
                                    }
                                }
                                if (isMediaUploading) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Text(t("در حال آپلود به فضای ابری...", "Uploading to cloud..."), fontSize = 11.sp)
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                                        Text(
                                            if (settings.avatarFileId.isNullOrBlank())
                                                t("هنوز آواتاری انتخاب نشده — آواتار به‌صورت ابری ذخیره می‌شود", "No avatar yet — stored in cloud")
                                            else
                                                t("آواتار به‌صورت ابری ذخیره شده است", "Avatar stored in cloud"),
                                            fontSize = 10.5.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { avatarGalleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(t("گالری", "Gallery"), fontSize = 11.sp)
                                    }
                                    OutlinedButton(onClick = { avatarCameraLauncher() }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(t("دوربین", "Camera"), fontSize = 11.sp)
                                    }
                                }
                                if (!settings.avatarFileId.isNullOrBlank()) {
                                    TextButton(onClick = {
                                        viewModel.updateClinicSettings(settings.copy(avatarFileId = null))
                                        showEditProfileDialog = false
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(t("حذف آواتار", "Remove Avatar"), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showEditProfileDialog = false }) { Text(t("بستن", "Close")) }
                        }
                    )
                }

            // Clinic & Practice Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Business, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = t("اطلاعات مطب ثبت شده", "Registered Clinic Details"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (!isEditingClinicInfo) {
                            if (activeRole == com.example.feature.auth.UserRole.DOCTOR) {
                                IconButton(
                                    onClick = {
                                        editClinicName = settings.clinicName
                                        editDoctorName = settings.doctorName
                                        editDoctorLicenseNo = settings.doctorLicenseNo
                                        editClinicPhone = settings.clinicPhone
                                        isEditingClinicInfo = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = t("ویرایش اطلاعات مطب", "Edit clinic info"),
                                        tint = cs.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    Divider(color = cs.outlineVariant.copy(alpha = 0.5f))

                    if (isEditingClinicInfo) {
                        OutlinedTextField(
                            value = editClinicName,
                            onValueChange = { editClinicName = it },
                            label = { Text(t("نام مطب", "Clinic Name")) },
                            singleLine = true,
                            enabled = !isSavingClinicInfo,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editDoctorName,
                            onValueChange = { editDoctorName = it },
                            label = { Text(t("پزشک مسئول", "Doctor Name")) },
                            singleLine = true,
                            enabled = !isSavingClinicInfo,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editDoctorLicenseNo,
                            onValueChange = { editDoctorLicenseNo = it },
                            label = { Text(t("شماره نظام پزشکی", "Medical License No.")) },
                            singleLine = true,
                            enabled = !isSavingClinicInfo,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = editClinicPhone,
                            onValueChange = { editClinicPhone = it },
                            label = { Text(t("تلفن مطب", "Clinic Phone")) },
                            singleLine = true,
                            enabled = !isSavingClinicInfo,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { isEditingClinicInfo = false },
                                enabled = !isSavingClinicInfo,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(t("انصراف", "Cancel"))
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        isSavingClinicInfo = true
                                        try {
                                            // NOTE: updateClinicSettings(...) is assumed to exist on
                                            // DentalViewModel following the same persist pattern as
                                            // completeOnboarding/configureRolePin (repository.insertSettings
                                            // + enqueueSyncOp("clinic_settings", ...)). Verify/adjust the
                                            // exact function name & signature against DentalViewModel.kt.
                                            viewModel.updateClinicSettings(
                                                settings.copy(
                                                    clinicName = editClinicName.trim(),
                                                    doctorName = editDoctorName.trim(),
                                                    doctorLicenseNo = editDoctorLicenseNo.trim(),
                                                    clinicPhone = editClinicPhone.trim()
                                                )
                                            )
                                            isEditingClinicInfo = false
                                        } catch (e: Exception) {
                                            syncFeedbackMessage = t(
                                                "ذخیره اطلاعات مطب ناموفق بود: ${e.message}",
                                                "Failed to save clinic info: ${e.message}"
                                            )
                                        } finally {
                                            isSavingClinicInfo = false
                                        }
                                    }
                                },
                                enabled = !isSavingClinicInfo && editClinicName.isNotBlank() && editDoctorName.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isSavingClinicInfo) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = cs.onPrimary
                                    )
                                } else {
                                    Text(t("ذخیره", "Save"))
                                }
                            }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t("نام مطب:", "Clinic Name:"), color = cs.onSurfaceVariant, fontSize = 13.sp)
                            Text(settings.clinicName.ifBlank { "-" }, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(t("پزشک مسئول:", "Doctor Name:"), color = cs.onSurfaceVariant, fontSize = 13.sp)
                            Text(settings.doctorName.ifBlank { "-" }, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }
                        if (settings.doctorLicenseNo.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(t("شماره نظام پزشکی:", "Medical License No:"), color = cs.onSurfaceVariant, fontSize = 13.sp)
                                Text(settings.doctorLicenseNo, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            }
                        }
                        if (settings.clinicPhone.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(t("تلفن مطب:", "Clinic Phone:"), color = cs.onSurfaceVariant, fontSize = 13.sp)
                                Text(settings.clinicPhone, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Sync Status & Cloud Health Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (isOnline) Color(0xFF4CAF50) else Color(0xFFFF5252),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = t("وضعیت همگام‌سازی ابری", "Cloud Sync Status"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isOnline) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFFF5252).copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isOnline) t("آنلاین (متصل به سرور)", "Online") else t("آفلاین (ذخیره محلی)", "Offline"),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isOnline) Color(0xFF4CAF50) else Color(0xFFFF5252)
                            )
                        }
                    }

                    Divider(color = cs.outlineVariant.copy(alpha = 0.5f))

                    // Pending Operations Count
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("عملیات در صف ارسال:", "Pending Sync Queue:"), color = cs.onSurfaceVariant, fontSize = 13.sp)
                        Text(
                            text = if (pendingOpsCount > 0) t("$pendingOpsCount مورد در انتظار", "$pendingOpsCount pending") else t("تمام تغییرات ارسال شده", "Up to date"),
                            fontWeight = FontWeight.SemiBold,
                            color = if (pendingOpsCount > 0) cs.primary else Color(0xFF4CAF50),
                            fontSize = 13.sp
                        )
                    }

                    // Last Synced Timestamp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(t("آخرین همگام‌سازی موفق:", "Last Successful Sync:"), color = cs.onSurfaceVariant, fontSize = 13.sp)
                        val lastSyncStr = if (lastSyncedAt != null && lastSyncedAt!! > 0) {
                            val sdf = SimpleDateFormat("yyyy/MM/dd - HH:mm:ss", Locale.getDefault())
                            sdf.format(Date(lastSyncedAt!!))
                        } else {
                            t("هنوز انجام نشده", "Not synced yet")
                        }
                        Text(lastSyncStr, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }

                    // Last Sync Error (if any)
                    AnimatedVisibility(visible = lastSyncError != null) {
                        lastSyncError?.let { err ->
                            Surface(
                                color = cs.errorContainer.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = cs.error, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = t("خطای اخیر: $err", "Last Error: $err"),
                                        fontSize = 11.sp,
                                        color = cs.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    // Feedback banner
                    AnimatedVisibility(visible = syncFeedbackMessage != null) {
                        syncFeedbackMessage?.let { msg ->
                            Surface(
                                color = cs.primaryContainer.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = msg,
                                    fontSize = 12.sp,
                                    color = cs.onPrimaryContainer,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }

                    // Separate Queue Execution Buttons: Push Queue & Pull Queue
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Push Queue Button (صف ارسال)
                        Button(
                            onClick = {
                                scope.launch {
                                    isManualSyncing = true
                                    syncFeedbackMessage = null
                                    try {
                                        viewModel.syncEngine.pushPending()
                                        syncFeedbackMessage = t("صف ارسال با موفقیت پردازش شد", "Push queue synced successfully")
                                    } catch (e: Exception) {
                                        syncFeedbackMessage = t("خطا در ارسال: ${e.message}", "Push failed: ${e.message}")
                                    } finally {
                                        isManualSyncing = false
                                    }
                                }
                            },
                            enabled = !isManualSyncing,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("صف ارسال", "Push Queue"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Pull Queue Button (صف دریافت)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isManualSyncing = true
                                    syncFeedbackMessage = null
                                    try {
                                        viewModel.syncEngine.pullAll()
                                        // نکته مهم: pullAll دیگر برای شکست یک کالکشن خاص exception پرتاب
                                        // نمی‌کند (هر کالکشن جدا retry و ایزوله می‌شود)، پس موفقیت باید از
                                        // روی failedCollections/lastError واقعی سنجیده شود، نه صرفاً از
                                        // اینکه catch اجرا نشده.
                                        val failed = viewModel.syncEngine.failedCollections.value
                                        val details = viewModel.syncEngine.lastError.value
                                        // درخواست کاربر: نتیجه «خیلی خلاصه و بدون جزییات» اعلام شود
                                        val inventory = try {
                                            viewModel.syncEngine.localInventoryCompact()
                                        } catch (_: Exception) { "—" }
                                        syncFeedbackMessage = if (failed.isEmpty() && details.isNullOrBlank()) {
                                            t(
                                                "✅ صف دریافت با موفقیت انجام شد — $inventory",
                                                "✅ Pull completed — $inventory"
                                            )
                                        } else {
                                            t(
                                                "⚠ همگام‌سازی ناقص بود — $inventory" +
                                                    (if (failed.isNotEmpty()) "\nبخش‌های ناموفق: ${failed.joinToString()}" else ""),
                                                "⚠ Sync incomplete — $inventory" +
                                                    (if (failed.isNotEmpty()) "\nFailed: ${failed.joinToString()}" else "")
                                            )
                                        }
                                    } catch (e: Exception) {
                                        syncFeedbackMessage = t("خطا در دریافت: ${e.message}", "Pull failed: ${e.message}")
                                    } finally {
                                        isManualSyncing = false
                                    }
                                }
                            },
                            enabled = !isManualSyncing,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("صف دریافت", "Pull Queue"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Diagnostic Tool Card
            var isRunningDiagnostic by remember { mutableStateOf(false) }
            var diagnosticReport by remember { mutableStateOf<com.example.sync.DiagnosticReport?>(null) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HealthAndSafety, contentDescription = null, tint = cs.secondary, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = t("تست سلامت اتصال و سرور", "Diagnostic Health Check"),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    isRunningDiagnostic = true
                                    try {
                                        diagnosticReport = viewModel.syncEngine.runDiagnostic()
                                    } finally {
                                        isRunningDiagnostic = false
                                    }
                                }
                            },
                            enabled = !isRunningDiagnostic,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isRunningDiagnostic) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(t("اجرای تست", "Run Test"), fontSize = 12.sp)
                            }
                        }
                    }

                    if (diagnosticReport != null) {
                        Divider(color = cs.outlineVariant.copy(alpha = 0.5f))
                        diagnosticReport?.items?.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = if (item.isSuccess) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = if (item.isSuccess) Color(0xFF4CAF50) else Color(0xFFFF5252),
                                    modifier = Modifier.size(18.dp).padding(top = 2.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(item.detail, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = t(
                                "برای بررسی وضعیت سرور، صحت سشن کاربر و دسترسی کالکشن‌ها دکمه «اجرای تست» را بزنید.",
                                "Tap 'Run Test' to verify server reachability, user session, and collection permissions."
                            ),
                            fontSize = 12.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // تلاش مجدد آپلود فایل‌های ناقص (عکس/امضا/چک) — اگر قبلاً به‌خاطر خطای سرور آپلود نشده باشند
            val requeueMsg by viewModel.requeueResultMessage.collectAsStateWithLifecycle()
            OutlinedButton(
                onClick = { viewModel.retryStuckUploads() },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("تلاش مجدد آپلود فایل‌های ناقص", "Retry Uploading Incomplete Files"), fontSize = 13.sp)
            }
            if (requeueMsg != null) {
                Text(
                    requeueMsg ?: "",
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            // Logout Button
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = cs.error
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cs.error.copy(alpha = 0.5f))
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("خروج از حساب کاربری", "Logout from Account"), fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(10.dp))

            // Delete Account Button — زیر خروج از حساب، طبق درخواست
            OutlinedButton(
                onClick = { showDeleteAccountStep1 = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(cs.error)
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("حذف حساب کاربری", "Delete Account"), fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // مرحله‌ی ۱: هشدار اولیه و توضیح برگشت‌ناپذیر بودن حذف
    if (showDeleteAccountStep1) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountStep1 = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = cs.error) },
            title = { Text(t("حذف حساب کاربری", "Delete Account")) },
            text = {
                Text(t(
                    "با حذف حساب، تمام بیماران، درمان‌ها، پرداخت‌ها، نوبت‌ها، پرسنل، عکس‌ها، امضاها و هر اطلاعات دیگری که در این حساب ثبت شده، برای همیشه از سرور و از این گوشی پاک می‌شود. این عملیات هیچ راه بازگشتی ندارد.",
                    "Deleting your account permanently erases every patient, treatment, payment, appointment, staff member, photo, signature, and any other data in this account — from both the server and this phone. This cannot be undone."
                ))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteAccountStep1 = false
                        deleteConfirmText = ""
                        showDeleteAccountStep2 = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error)
                ) {
                    Text(t("متوجه‌ام، ادامه", "I understand, continue"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountStep1 = false }) {
                    Text(t("انصراف", "Cancel"))
                }
            }
        )
    }

    // مرحله‌ی ۲: تایید نهایی با تایپ عبارت تاییدیه (جلوگیری از حذف تصادفی)
    var deleteAccountError by remember { mutableStateOf<String?>(null) }
    if (showDeleteAccountStep2) {
        val confirmWord = t("حذف", "DELETE")
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteAccountStep2 = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = cs.error) },
            title = { Text(t("تایید نهایی", "Final Confirmation")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t(
                        "برای تایید نهایی، عبارت «$confirmWord» را دقیقاً همینطور تایپ کنید:",
                        "To confirm, type the word \"$confirmWord\" exactly as shown:"
                    ))
                    OutlinedTextField(
                        value = deleteConfirmText,
                        onValueChange = { deleteConfirmText = it; deleteAccountError = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isDeletingAccount,
                        singleLine = true
                    )
                    val progressMessage by viewModel.deleteAccountState.collectAsStateWithLifecycle()
                    if (isDeletingAccount) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            if (progressMessage != null) {
                                Text(progressMessage ?: "", fontSize = 10.sp, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    // نکته‌ی رفع باگ: قبلاً وقتی حذف سمت سرور واقعاً fail می‌شد، دیالوگ فقط بی‌صدا
                    // بسته می‌شد — کاربر فکر می‌کرد حذف انجام شده درحالی‌که هیچ‌چیز پاک نشده بود.
                    // حالا خطا واضح نشان داده می‌شود و دیالوگ باز می‌ماند تا کاربر بتواند دوباره تلاش کند.
                    if (deleteAccountError != null) {
                        Text(
                            deleteAccountError ?: "",
                            fontSize = 12.sp,
                            color = cs.error,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isDeletingAccount = true
                        deleteAccountError = null
                        viewModel.deleteAccount { success ->
                            isDeletingAccount = false
                            if (success) {
                                showDeleteAccountStep2 = false
                                onLogout()
                            } else {
                                deleteAccountError = t(
                                    "حذف کامل نشد — به‌خاطر مشکل ارتباط با سرور، برخی اطلاعات هنوز پاک نشده‌اند. اتصال اینترنت را بررسی کن و دوباره تلاش کن؛ حساب محلی حذف نشد.",
                                    "Deletion did not complete — some data on the server could not be removed due to a connection issue. Check your internet and try again; your local account was not deleted."
                                )
                            }
                        }
                    },
                    enabled = deleteConfirmText.trim() == confirmWord && !isDeletingAccount,
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error)
                ) {
                    Text(t("حذف همیشگی حساب", "Permanently Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isDeletingAccount) showDeleteAccountStep2 = false }) {
                    Text(t("انصراف", "Cancel"))
                }
            }
        )
    }

    // Confirmation Logout Dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.Default.Logout, contentDescription = null, tint = cs.error) },
            title = { Text(t("تایید خروج", "Confirm Logout")) },
            text = {
                Text(t(
                    "آیا مطمئن هستید که می‌خواهید از حساب کاربری خود خارج شوید؟ تمام داده‌های محلی این دستگاه (دیتابیس، تنظیمات و کش تصاویر) پاک خواهد شد.",
                    "Are you sure you want to log out? All local data on this device (database, settings, and image cache) will be permanently erased."
                ))
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout(onDone = onLogout)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error)
                ) {
                    Text(t("خروج", "Logout"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(t("انصراف", "Cancel"))
                }
            }
        )
    }
}