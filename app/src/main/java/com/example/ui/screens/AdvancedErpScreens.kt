package com.example.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.example.util.PersianCalendarHelper
import com.example.util.JalaliDate
import com.example.util.CalendarOccasion

import kotlinx.coroutines.launch
import androidx.compose.animation.*

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.*
import com.example.reports.*
import com.example.ui.DentalViewModel
import com.example.ui.BackupSelectionConfig
import com.example.ui.CloudBackupRecord
import com.example.ui.theme.*
import com.example.feature.auth.UserRole
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.compose.AsyncImage
import java.io.File
import java.io.ByteArrayOutputStream
import android.net.Uri
import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.gestures.detectDragGestures

// ==========================================
/** ابتدای همان روز (۰۰:۰۰:۰۰٫۰۰۰) — برای کرانهٔ «از» در بازهٔ اسکن پیامک. */
internal fun startOfDayMillis(timestamp: Long): Long =
    java.util.Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

/** انتهای همان روز (۲۳:۵۹:۵۹٫۹۹۹) — برای کرانهٔ «تا» در بازهٔ اسکن پیامک. */
internal fun endOfDayMillis(timestamp: Long): Long =
    java.util.Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(java.util.Calendar.HOUR_OF_DAY, 23)
        set(java.util.Calendar.MINUTE, 59)
        set(java.util.Calendar.SECOND, 59)
        set(java.util.Calendar.MILLISECOND, 999)
    }.timeInMillis

fun formatToothLabel(fdiNumber: String, system: String): String {
    return when (system) {
        "UNIVERSAL" -> {
            when (fdiNumber) {
                "18" -> "1"; "17" -> "2"; "16" -> "3"; "15" -> "4"; "14" -> "5"; "13" -> "6"; "12" -> "7"; "11" -> "8"
                "21" -> "9"; "22" -> "10"; "23" -> "11"; "24" -> "12"; "25" -> "13"; "26" -> "14"; "27" -> "15"; "28" -> "16"
                "31" -> "17"; "32" -> "18"; "33" -> "19"; "34" -> "20"; "35" -> "21"; "36" -> "22"; "37" -> "23"; "38" -> "24"
                "48" -> "32"; "47" -> "31"; "46" -> "30"; "45" -> "29"; "44" -> "28"; "43" -> "27"; "42" -> "26"; "41" -> "25"
                else -> fdiNumber
            }
        }
        "PALMER" -> {
            when (fdiNumber) {
                "18" -> "8┘"; "17" -> "7┘"; "16" -> "6┘"; "15" -> "5┘"; "14" -> "4┘"; "13" -> "3┘"; "12" -> "2┘"; "11" -> "1┘"
                "21" -> "└1"; "22" -> "└2"; "23" -> "└3"; "24" -> "└4"; "25" -> "└5"; "26" -> "└6"; "27" -> "└7"; "28" -> "└8"
                "31" -> "┌1"; "32" -> "┌2"; "33" -> "┌3"; "34" -> "┌4"; "35" -> "┌5"; "36" -> "┌6"; "37" -> "┌7"; "38" -> "┌8"
                "48" -> "8┐"; "47" -> "7┐"; "46" -> "6┐"; "45" -> "5┐"; "44" -> "4┐"; "43" -> "3┐"; "42" -> "2┐"; "41" -> "1┐"
                else -> fdiNumber
            }
        }
        else -> fdiNumber
    }
}

// 1. INTERACTIVE DENTAL CHART (32 TEETH)
// ==========================================
@Composable
fun InteractiveToothChart(
    selectedTeeth: String,
    onTeethChanged: (String) -> Unit,
    system: String = "FDI",
    toothStatusMap: Map<String, String> = emptyMap(),
    isDeciduous: Boolean = false,
    /** توضیح دورهٔ دندانی بیمار که از روی سن او تشخیص داده شده. */
    dentitionLabel: String? = null,
    /** اجازهٔ جابه‌جایی دستی بین چارت شیری / مختلط / دائمی. */
    allowDentitionToggle: Boolean = false,
    onDentitionToggle: (Boolean) -> Unit = {},
    /**
     * سن بیمار. اگر داده شود و [chartMode] برابر MIXED باشد، چارت به‌جای
     * «همه شیری یا همه دائمی»، برای هر جایگاه دندانِ محتمل در آن سن را
     * نشان می‌دهد (بر اساس جدول رویش AAPD).
     */
    patientAge: Int? = null,
    chartMode: Dentition = if (isDeciduous) Dentition.PRIMARY else Dentition.PERMANENT,
    onChartModeChange: (Dentition) -> Unit = {}
) {
    val selectedList = remember(selectedTeeth) {
        if (selectedTeeth.isBlank()) emptyList() else selectedTeeth.split(",").map { it.trim() }
    }

    // Standard FDI 32 tooth numbering:
    val upperRight = listOf("18", "17", "16", "15", "14", "13", "12", "11")
    val upperLeft = listOf("21", "22", "23", "24", "25", "26", "27", "28")
    val lowerLeft = listOf("31", "32", "33", "34", "35", "36", "37", "38")
    val lowerRight = listOf("48", "47", "46", "45", "44", "43", "42", "41")

    fun toggleTooth(number: String, pontic: Boolean = false) {
        val existing = selectedList.find { it.startsWith(number) }
        val newList = if (existing != null) {
            if (pontic) {
                if (existing.endsWith("(P)")) {
                    selectedList - existing + number
                } else {
                    selectedList - existing + "$number(P)"
                }
            } else {
                selectedList - existing
            }
        } else {
            if (pontic) {
                selectedList + "$number(P)"
            } else {
                selectedList + number
            }
        }
        onTeethChanged(newList.joinToString(","))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "چارت دندانی تعاملی ۳ بعدی و تفکیک وضعیت",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = "استاندارد: $system",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // نوار دورهٔ دندانی: نتیجهٔ تشخیص از روی سن + امکان تغییر دستی
            if (dentitionLabel != null || allowDentitionToggle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dentitionLabel != null) {
                        Text(
                            text = dentitionLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    if (allowDentitionToggle) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = chartMode == Dentition.PERMANENT,
                                onClick = {
                                    onChartModeChange(Dentition.PERMANENT); onDentitionToggle(false)
                                },
                                label = { Text("دائمی", fontSize = 10.sp) }
                            )
                            FilterChip(
                                selected = chartMode == Dentition.MIXED,
                                onClick = {
                                    onChartModeChange(Dentition.MIXED); onDentitionToggle(false)
                                },
                                label = { Text("مختلط", fontSize = 10.sp) }
                            )
                            FilterChip(
                                selected = chartMode == Dentition.PRIMARY,
                                onClick = {
                                    onChartModeChange(Dentition.PRIMARY); onDentitionToggle(true)
                                },
                                label = { Text("شیری", fontSize = 10.sp) }
                            )
                        }
                    }
                }
            }

            // Legend Bar for Tooth Color Coding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToothLegendItem(color = Color(0xFFE53935), label = "عصب‌کشی (🔴)")
                ToothLegendItem(color = Color(0xFF1E88E5), label = "پرکردن (🔵)")
                ToothLegendItem(color = Color(0xFFFFB300), label = "روکش (🟡)")
                ToothLegendItem(color = Color(0xFF546E7A), label = "ایمپلنت (🩶)")
                ToothLegendItem(color = Color(0xFF43A047), label = "کشیدن/جرم‌گیری (🟢)")
            }

            // در دورهٔ مختلط، چارت ترکیبی است: هر جایگاه بر اساس جدول رویش
            // (AAPD) با شمارهٔ شیری یا دائمی نشان داده می‌شود و جایگاه‌های
            // رویش‌نکرده اصلاً نمایش داده نمی‌شوند.
            if (chartMode == Dentition.MIXED) {
                Text(
                    text = "چارت مختلط: شماره‌های ۵۱ تا ۸۵ دندان شیری و ۱۱ تا ۴۸ دندان دائمی هستند. " +
                        "دندان‌های رویش‌نکرده در این سن نمایش داده نمی‌شوند.",
                    fontSize = 9.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

                        // Upper Arch (Maxillary)
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "فک بالا (Maxillary)",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val upperArch = archForMode(chartMode, patientAge, upper = true)
                    upperArch.forEachIndexed { index, toothNum ->
                        val center = (upperArch.size - 1) / 2.0f
                        val d = Math.abs(index - center)
                        val yOff = (d * 4.5f - 24.0f).dp
                        val existing = selectedList.find { it.startsWith(toothNum) }
                        Box(modifier = Modifier.offset(y = yOff)) {
                            ToothButton(
                                number = toothNum,
                                displayLabel = formatToothLabel(toothNum, system) + if (existing?.endsWith("(P)") == true) " (P)" else "",
                                isSelected = existing != null,
                                isPontic = existing?.endsWith("(P)") == true,
                                statusType = toothStatusMap[toothNum],
                                onClick = { toggleTooth(toothNum, false) },
                                onLongClick = { toggleTooth(toothNum, true) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                        // Lower Arch (Mandibular)
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "فک پایین (Mandibular)",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val lowerArch = archForMode(chartMode, patientAge, upper = false)
                    lowerArch.forEachIndexed { index, toothNum ->
                        val center = (lowerArch.size - 1) / 2.0f
                        val d = Math.abs(index - center)
                        val yOff = (24.0f - d * 4.5f).dp
                        val existing = selectedList.find { it.startsWith(toothNum) }
                        Box(modifier = Modifier.offset(y = yOff)) {
                            ToothButton(
                                number = toothNum,
                                displayLabel = formatToothLabel(toothNum, system) + if (existing?.endsWith("(P)") == true) " (P)" else "",
                                isSelected = existing != null,
                                isPontic = existing?.endsWith("(P)") == true,
                                statusType = toothStatusMap[toothNum],
                                onClick = { toggleTooth(toothNum, false) },
                                onLongClick = { toggleTooth(toothNum, true) }
                            )
                        }
                    }
                }
            }

            Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    val formattedSelected = selectedList.sorted().map { formatToothLabel(it, system) }.joinToString(", ")
                    Text(
                        text = "دندان‌های انتخاب شده ($system): $formattedSelected",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

@Composable
fun ToothLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(text = label, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ToothButton(
    number: String,
    displayLabel: String = number,
    isSelected: Boolean,
    isPontic: Boolean = false,
    statusType: String? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val statusColor = remember(statusType) {
        when {
            statusType == null -> null
            statusType.contains("عصب") || statusType.contains("root") || statusType.contains("rct", ignoreCase = true) -> Color(0xFFE53935)
            statusType.contains("پر") || statusType.contains("ترمیم") || statusType.contains("fill", ignoreCase = true) -> Color(0xFF1E88E5)
            statusType.contains("روکش") || statusType.contains("پروتز") || statusType.contains("crown", ignoreCase = true) -> Color(0xFFFFB300)
            statusType.contains("ایمپلنت") || statusType.contains("implant", ignoreCase = true) -> Color(0xFF546E7A)
            statusType.contains("کشیدن") || statusType.contains("جرم") || statusType.contains("clean", ignoreCase = true) -> Color(0xFF43A047)
            else -> Color(0xFF8E24AA)
        }
    }

    val backgroundColor = when {
        isSelected -> if (isPontic) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
        statusColor != null -> statusColor
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when {
        isSelected -> if (isPontic) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onPrimary
        statusColor != null -> Color.White
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .size(33.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(
                width = if (isSelected && statusColor != null) 2.dp else 0.dp,
                color = if (isSelected && statusColor != null) Color.White else Color.Transparent,
                shape = CircleShape
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isPontic) "$displayLabel(P)" else displayLabel,
            fontSize = if (isPontic) 8.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

// ==========================================
// 2. CLINIC FINANCIAL GRADIENT CHART
// ==========================================
@Composable
fun ClinicFinancialGradientChart(
    revenue: Long,
    outstanding: Long,
    expenses: Long,
    isPersian: Boolean,
    /** پرداخت‌های وصول‌شده برای نمودار روند ماهانه. */
    payments: List<com.example.data.Payment> = emptyList(),
    /** هزینه‌های مطب برای روند ماهانه و تفکیک دسته‌ها. */
    clinicExpenses: List<com.example.data.ClinicExpense> = emptyList(),
    /** چک‌های وصول‌نشده. */
    unclearedCheques: Long = 0L,
    /** تعداد بیماران بدهکار. */
    debtorCount: Int = 0
) {
    val maxVal = maxOf(revenue, outstanding, expenses, 1_000_000L)
    val net = revenue - expenses
    // حاشیهٔ سود: چه سهمی از درآمد وصول‌شده پس از هزینه‌ها باقی می‌ماند.
    val margin = if (revenue > 0) (net.toDouble() / revenue.toDouble() * 100).toInt() else 0
    // نرخ وصول: چه سهمی از کل مبلغ کارکرد واقعاً وصول شده است.
    val collectionRate = if (revenue + outstanding > 0)
        (revenue.toDouble() / (revenue + outstanding).toDouble() * 100).toInt() else 0

    // ——— تفکیک هزینه‌ها بر اساس دسته ———
    val expenseByCategory = remember(clinicExpenses) {
        clinicExpenses.filter { !it.isDeleted }
            .groupBy { it.category.ifBlank { if (isPersian) "سایر" else "Other" } }
            .map { (k, v) -> k to v.sumOf { e -> e.amount } }
            .sortedByDescending { it.second }
            .take(5)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPersian) "آنالیز و نمودار پویای مالی مطب" else "Dynamic Financial Dashboard Analysis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            // ——— ۱) شاخص‌های کلیدی ———
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FinancialKpiTile(
                    label = if (isPersian) "سود خالص" else "Net profit",
                    value = formatAmount(net),
                    tint = if (net >= 0) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.weight(1f)
                )
                FinancialKpiTile(
                    label = if (isPersian) "حاشیهٔ سود" else "Margin",
                    value = "٪$margin",
                    tint = when {
                        margin >= 40 -> Color(0xFF2E7D32)
                        margin >= 15 -> Color(0xFFF57C00)
                        else -> Color(0xFFC62828)
                    },
                    modifier = Modifier.weight(1f)
                )
                FinancialKpiTile(
                    label = if (isPersian) "نرخ وصول" else "Collection",
                    value = "٪$collectionRate",
                    tint = when {
                        collectionRate >= 80 -> Color(0xFF2E7D32)
                        collectionRate >= 55 -> Color(0xFFF57C00)
                        else -> Color(0xFFC62828)
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

            // ——— ۲) میله‌های اصلی ———
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GradientChartBar(
                    label = if (isPersian) "کل دریافتی مطب (وصول شده)" else "Total Income (Received)",
                    value = revenue,
                    maxVal = maxVal,
                    colors = listOf(Color(0xFF4CAF50), Color(0xFF388E3C))
                )
                GradientChartBar(
                    label = if (isPersian) "کل مطالبات بیماران (بدهکاری‌ها)" else "Outstanding Patient Balances",
                    value = outstanding,
                    maxVal = maxVal,
                    colors = listOf(Color(0xFFE57373), Color(0xFFD32F2F))
                )
                GradientChartBar(
                    label = if (isPersian) "هزینه‌های جاری مطب (تدارکات و قبوض)" else "Current Expenses (Materials & Bills)",
                    value = expenses,
                    maxVal = maxVal,
                    colors = listOf(Color(0xFFFFB74D), Color(0xFFF57C00))
                )
                if (unclearedCheques > 0) {
                    GradientChartBar(
                        label = if (isPersian) "چک‌های در جریان وصول" else "Uncleared cheques",
                        value = unclearedCheques,
                        maxVal = maxVal,
                        colors = listOf(Color(0xFF9575CD), Color(0xFF5E35B1))
                    )
                }
            }

            // «روند ۶ ماه اخیر (درآمد در برابر هزینه)» طبق درخواست کاربر حذف شد.

            // ——— ۴) بیشترین دسته‌های هزینه ———
            if (expenseByCategory.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(
                    text = if (isPersian) "بیشترین دسته‌های هزینه" else "Top expense categories",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                val catMax = expenseByCategory.maxOf { it.second }.coerceAtLeast(1L)
                expenseByCategory.forEach { (name, amount) ->
                    val share = if (expenses > 0) (amount.toDouble() / expenses * 100).toInt() else 0
                    GradientChartBar(
                        label = "$name  (٪$share)",
                        value = amount,
                        maxVal = catMax,
                        colors = listOf(Color(0xFF90A4AE), Color(0xFF546E7A))
                    )
                }
            }

            // ——— ۵) جمع‌بندی ———
            if (debtorCount > 0 || outstanding > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text(
                    text = if (isPersian)
                        "$debtorCount بیمار در مجموع ${formatAmount(outstanding)} بدهکارند. نرخ وصول ٪$collectionRate است."
                    else
                        "$debtorCount patients owe ${formatAmount(outstanding)} in total. Collection rate is $collectionRate%.",
                    fontSize = 10.5.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

/** یک ماه از روند مالی. */
data class MonthlyFinancialPoint(
    val label: String,
    val income: Long,
    val expense: Long
)

/**
 * ساخت روند [count] ماه اخیر بر اساس تقویم جلالی.
 * فقط پرداخت‌های واقعاً وصول‌شده و هزینه‌های حذف‌نشده شمرده می‌شوند.
 */
fun buildMonthlyTrend(
    payments: List<com.example.data.Payment>,
    expenses: List<com.example.data.ClinicExpense>,
    count: Int
): List<MonthlyFinancialPoint> {
    if (payments.isEmpty() && expenses.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val today = com.example.util.PersianCalendarHelper.timestampToJalali(now)

    // کلید ماه: year*12 + (month-1) تا محاسبهٔ «ماه قبل» ساده و بدون خطا باشد.
    fun keyOf(ts: Long): Int {
        val j = com.example.util.PersianCalendarHelper.timestampToJalali(ts)
        return j.year * 12 + (j.month - 1)
    }
    val currentKey = today.year * 12 + (today.month - 1)
    val firstKey = currentKey - (count - 1)

    val incomeByKey = HashMap<Int, Long>()
    payments.forEach { p ->
        if (!p.isEffectivelyPaid) return@forEach
        val k = keyOf(p.date)
        if (k in firstKey..currentKey) incomeByKey[k] = (incomeByKey[k] ?: 0L) + p.amount
    }
    val expenseByKey = HashMap<Int, Long>()
    expenses.forEach { e ->
        if (e.isDeleted) return@forEach
        val k = keyOf(e.date)
        if (k in firstKey..currentKey) expenseByKey[k] = (expenseByKey[k] ?: 0L) + e.amount
    }

    return (firstKey..currentKey).map { k ->
        val monthIndex = k % 12
        MonthlyFinancialPoint(
            label = com.example.util.PersianCalendarHelper.MONTH_NAMES.getOrElse(monthIndex) { "" },
            income = incomeByKey[k] ?: 0L,
            expense = expenseByKey[k] ?: 0L
        )
    }
}

/** نمودار ستونی دوتایی روند ماهانه. */
@Composable
fun MonthlyTrendChart(months: List<MonthlyFinancialPoint>, isPersian: Boolean) {
    val peak = remember(months) {
        months.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1L) ?: 1L
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        months.forEach { m ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom
                ) {
                    TrendBar(
                        fraction = (m.income.toDouble() / peak).toFloat(),
                        colors = listOf(Color(0xFF66BB6A), Color(0xFF388E3C))
                    )
                    Spacer(Modifier.width(3.dp))
                    TrendBar(
                        fraction = (m.expense.toDouble() / peak).toFloat(),
                        colors = listOf(Color(0xFFFFB74D), Color(0xFFF57C00))
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = m.label,
                    fontSize = 8.5.sp,
                    color = Color.Gray,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TrendBar(fraction: Float, colors: List<Color>) {
    // حتی مقدار صفر یک خط نازک نشان می‌دهد تا ستون‌ها قابل مقایسه بمانند.
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .width(11.dp)
            .fillMaxHeight(if (f <= 0f) 0.012f else f.coerceAtLeast(0.03f))
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .background(brush = androidx.compose.ui.graphics.Brush.verticalGradient(colors))
    )
}

/** کاشی شاخص کلیدی. */
@Composable
fun FinancialKpiTile(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.10f))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
        Text(text = label, fontSize = 9.sp, color = Color.Gray, maxLines = 1)
    }
}

@Composable
fun GradientChartBar(
    label: String,
    value: Long,
    maxVal: Long,
    colors: List<Color>
) {
    val fraction = (value.toDouble() / maxVal.toDouble()).coerceIn(0.01, 1.0).toFloat()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.Gray)
            Text(text = "${String.format(java.util.Locale.US, "%,d", value)} تومان", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
                    )
            )
        }
    }
}

// ==========================================
// 4. TEMPLATES MANAGER SCREEN
// ==========================================
@Composable
fun TemplatesManagerScreen(
    viewModel: DentalViewModel,
    isPersian: Boolean,
    onBack: () -> Unit
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val currencyUnit by viewModel.currencyUnit.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<com.example.data.TreatmentTemplate?>(null) }
    var protocolTitleToShow by remember { mutableStateOf<String?>(null) }

    var title by remember { mutableStateOf("") }
    var titleFa by remember { mutableStateOf("") }
    var costStr by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var stageList by remember { mutableStateOf(mutableStateListOf<String>()) }
    var newStageInput by remember { mutableStateOf("") }
    var isStarredDraft by remember { mutableStateOf(true) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = if (isPersian) "مدیریت الگوهای آماده درمان" else "Manage Treatment Templates",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = { 
                    title = ""
                    titleFa = ""
                    costStr = ""
                    notes = ""
                    stageList.clear()
                    newStageInput = ""
                    isStarredDraft = true
                    showAddDialog = true 
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (isPersian) "الگوی جدید" else "New Template", fontWeight = FontWeight.Bold)
            }
        }

        if (templates.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(text = if (isPersian) "هیچ الگوی آماده‌ای ثبت نشده است" else "No treatment templates declared yet", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(templates) { temp ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (temp.isStarred) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = temp.titleFa.ifBlank { temp.title },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.threeSecondLongPress(
                                                onLongClick = {
                                                    protocolTitleToShow = temp.title
                                                }
                                            )
                                        )
                                    }
                                    if (temp.titleFa.isNotBlank() && temp.title.isNotBlank()) {
                                        Text(text = temp.title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (temp.defaultNotes.isNotBlank()) {
                                        Text(text = temp.defaultNotes, fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        text = "${formatAmount(temp.defaultCost)} $currencyUnit",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontSize = 13.sp
                                    )
                                    IconButton(
                                        onClick = { viewModel.moveTemplateUp(temp) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = { viewModel.moveTemplateDown(temp) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = { viewModel.toggleTemplateStar(temp) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (temp.isStarred) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = "Star",
                                            tint = if (temp.isStarred) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingTemplate = temp
                                            title = temp.title
                                            titleFa = temp.titleFa
                                            costStr = temp.defaultCost.toString()
                                            notes = temp.defaultNotes
                                            isStarredDraft = temp.isStarred
                                            stageList.clear()
                                            stageList.addAll(parseStagesList(temp.stages))
                                            newStageInput = ""
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Template", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            // Dynamic Stage Steps Timeline
                            val stageItems = parseStagesList(temp.stages)
                            if (stageItems.isNotEmpty()) {
                                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Text(
                                    text = if (isPersian) "مراحل و گام‌های درمان (تراپی):" else "Therapy Steps & Workflow:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    stageItems.forEachIndexed { idx, stageText ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape)
                                                    .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${idx + 1}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(
                                                text = stageText,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
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

    // Protocol knowledge dialog on long-press
    protocolTitleToShow?.let { pTitle ->
        ClinicalProtocolDialog(
            therapyTitle = pTitle,
            isPersian = isPersian,
            onDismiss = { protocolTitleToShow = null }
        )
    }

    // Edit Existing Template Dialog
    editingTemplate?.let { activeTemp ->
        AlertDialog(
            onDismissRequest = { editingTemplate = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isPersian) "ویرایش الگوی درمان" else "Edit Treatment Template", fontWeight = FontWeight.Bold)
                    IconButton(onClick = {
                        viewModel.deleteTemplate(activeTemp)
                        editingTemplate = null
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Template", tint = MaterialTheme.colorScheme.error)
                    }
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (isPersian) "تراپی مهم (نمایش در منوهای سریع):" else "Starred / Important Therapy:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Switch(
                                checked = isStarredDraft,
                                onCheckedChange = { isStarredDraft = it }
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = titleFa,
                            onValueChange = { titleFa = it },
                            label = { Text(if (isPersian) "عنوان (نام فارسی تراپی)" else "Title (Persian therapy name)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(if (isPersian) "title (نام انگلیسی تراپی)" else "title (English therapy name)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = costStr,
                            onValueChange = { costStr = it },
                            label = { Text(if (isPersian) "هزینه پیش‌فرض ($currencyUnit)" else "Default Cost ($currencyUnit)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text(if (isPersian) "توضیحات و جزئیات خدمت" else "Details / Notes") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = if (isPersian) "مراحل و گام‌های درمان:" else "Define Therapy Steps:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    itemsIndexed(stageList) { idx, step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "جلسه ${idx + 1}: $step",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { stageList.removeAt(idx) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newStageInput,
                                onValueChange = { newStageInput = it },
                                label = { Text(if (isPersian) "عنوان مرحله جدید" else "New Step Title") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    if (newStageInput.isNotBlank()) {
                                        stageList.add(newStageInput.trim())
                                        newStageInput = ""
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (isPersian) "افزودن" else "Add")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cost = costStr.toEnglishLong()
                        if (title.isNotEmpty() && cost >= 0) {
                            val compiledStages = joinStagesList(stageList)
                            val updated = activeTemp.copy(
                                title = title,
                                titleFa = titleFa,
                                defaultCost = cost,
                                defaultNotes = notes,
                                stages = compiledStages,
                                isStarred = isStarredDraft
                            )
                            viewModel.updateTemplate(updated)
                            editingTemplate = null
                        }
                    }
                ) {
                    Text(if (isPersian) "بروزرسانی تغییرات" else "Update Template")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTemplate = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(if (isPersian) "تعریف طرح و مراحل درمان" else "Add Treatment Template & Steps", fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = titleFa,
                            onValueChange = { titleFa = it },
                            label = { Text(if (isPersian) "عنوان (نام فارسی تراپی، مثلاً روکش زیرکونیا)" else "Title (Persian therapy name)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(if (isPersian) "title (نام انگلیسی تراپی، مثلاً Zirconia Crown)" else "title (English therapy name)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = costStr,
                            onValueChange = { costStr = it },
                            label = { Text(if (isPersian) "هزینه پیش‌فرض ($currencyUnit)" else "Default Cost ($currencyUnit)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text(if (isPersian) "توضیحات و جزئیات خدمت" else "Details / Notes") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = if (isPersian) "تعریف دستی مراحل تراپی و درمان:" else "Define Therapy Steps Manually:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Dynamic list of added steps
                    itemsIndexed(stageList) { idx, step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "جلسه ${idx + 1}: $step",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { stageList.removeAt(idx) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newStageInput,
                                onValueChange = { newStageInput = it },
                                label = { Text(if (isPersian) "عنوان مرحله جدید (مثلا: قالب‌گیری)" else "New Step Title") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    if (newStageInput.isNotBlank()) {
                                        stageList.add(newStageInput.trim())
                                        newStageInput = ""
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (isPersian) "افزودن" else "Add")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cost = costStr.toEnglishLong()
                        if (title.isNotEmpty() && cost >= 0) {
                            val compiledStages = joinStagesList(stageList)
                            viewModel.addTemplate(title, cost, notes, compiledStages, titleFa = titleFa)
                            showAddDialog = false
                            title = ""
                            titleFa = ""
                            costStr = ""
                            notes = ""
                            stageList.clear()
                        }
                    }
                ) {
                    Text(if (isPersian) "ذخیره الگوی کامل" else "Save Template")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }
}


// ==========================================
public data class DisplayDoc(
    val title: String,
    val createdAt: Long,
    val notes: String,
    val fileId: String?,
    val docId: Long?
)

@Composable
fun ClinicalDocumentsTab(
    patient: Patient,
    viewModel: DentalViewModel,
    isPersian: Boolean
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showBeforeAfterDialog by remember { mutableStateOf(false) }

    var selectedScannerTemplate by remember { mutableStateOf("OPG Panoramic Scan") }
    var noteInput by remember { mutableStateOf("") }
    var selectedImageFileId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val clinicSettings by viewModel.settings.collectAsStateWithLifecycle()

    val patientDocs by viewModel.getDocumentsForPatient(patient.id).collectAsStateWithLifecycle(initialValue = emptyList())
    val docList = remember(patient.documentUris, patientDocs) {
        val legacy = if (patient.documentUris.isBlank()) emptyList() else
            patient.documentUris.split(",").filter { it.isNotBlank() }.map { legacyPath ->
                // legacy documents usually just contain a local path. We put that in fileId so SmartImage can try to load it.
                DisplayDoc(title = "سند قدیمی", createdAt = 0L, notes = "", fileId = legacyPath, docId = null)
            }
        val modern = patientDocs.map {
            DisplayDoc(title = it.title, createdAt = it.createdAt, notes = it.notes, fileId = it.fileId ?: it.uri, docId = it.id)
        }
        legacy + modern
    }

    val templates = listOf(
        "OPG Panoramic Scan",
        "Intraoral Camera Shot",
        "Bitewing Left Molar X-Ray",
        "Bitewing Right Canine Scan",
        "Cone Beam Computed Tomography (CBCT)"
    )

    val scanEditLauncher = rememberPhotoEditLauncher(PhotoAssetKind.DOCUMENT_SCAN) { localPath ->
        selectedImageFileId = localPath
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { scanEditLauncher(it) }
    }

    val launchCamera = rememberEditableCameraLauncher(context) { uri ->
        scanEditLauncher(uri)
    }

    fun handleAddScan() {
        val imgStr = selectedImageFileId ?: ""
        viewModel.addPatientDocument(
            patientId = patient.id,
            localFilePath = imgStr,
            docType = "RADIOGRAPH",
            title = selectedScannerTemplate,
            notes = noteInput
        )
        showAddDialog = false
        noteInput = ""
        selectedImageFileId = null
    }

    // ۴) عکس امضا — عکس امضای واقعی/فیزیکی (از دوربین یا گالری)، با پس‌زمینه‌ی شفاف —
    // جدا از پد امضای دیجیتالِ روی صفحه که در showConsentDialog است.
    var pendingSignaturePhotoPath by remember { mutableStateOf<String?>(null) }
    val signaturePhotoEditLauncher = rememberPhotoEditLauncher(PhotoAssetKind.SIGNATURE) { localPath ->
        pendingSignaturePhotoPath = localPath
    }
    val signaturePhotoGalleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { signaturePhotoEditLauncher(it) }
    }
    val signaturePhotoCameraLauncher = rememberEditableCameraLauncher(context) { uri ->
        signaturePhotoEditLauncher(uri)
    }
    fun handleAddSignaturePhoto() {
        viewModel.addPatientDocument(
            patientId = patient.id,
            localFilePath = pendingSignaturePhotoPath ?: "",
            docType = "CONSENT",
            title = "عکس امضای اسکن‌شده",
            notes = ""
        )
        pendingSignaturePhotoPath = null
    }

    var selectedDocTab by remember { mutableIntStateOf(0) } // 0: Scans & Live Imaging, 1: Before & After Photos, 2: Consents & Signatures

    // Categorize documents into 3 distinct sections
    val scansList = remember(docList) {
        docList.filter { doc ->
            !doc.title.contains("قبل") && !doc.title.contains("بعد") && !doc.title.contains("امضا") && !doc.title.contains("رضایت")
        }
    }

    val beforeAfterList = remember(docList) {
        docList.filter { doc ->
            doc.title.contains("قبل") || doc.title.contains("بعد") || doc.title.contains("درمان") || doc.title.contains("بالینی")
        }
    }

    val consentsList = remember(docList) {
        docList.filter { doc ->
            doc.title.contains("رضایت") || doc.title.contains("امضا")
        }
    }

    val latestSignatureDoc = remember(consentsList) {
        consentsList.lastOrNull { doc ->
            !doc.fileId.isNullOrBlank()
        }
    }

    // The consent/signature PatientDocument backing latestSignatureDoc, resolved by DOC_ID
    // rather than by re-reading its pipe-string's index 3 (which is PatientDocument.uri, a
    // local-cache-only path — never valid base64; see fetchSignatureBase64 below).
    val latestSignatureFileDoc = remember(latestSignatureDoc, patientDocs) {
        val docId = latestSignatureDoc?.docId
        patientDocs.firstOrNull { it.id == docId }
    }

    val coroutineScope = rememberCoroutineScope()

    // Resolves a shareable signature image to Base64 (supporting PatientDocument,
    // local file path, and patient's admission signature signatureFileId).
    suspend fun fetchSignatureBase64(doc: PatientDocument?): String {
        // 1. Check attached PatientDocument if present
        if (doc != null) {
            val fileId = doc.fileId
            if (!fileId.isNullOrBlank()) {
                val cached = doc.localCachePath?.let { java.io.File(it) }
                val file = if (cached != null && cached.exists() && cached.length() > 0) {
                    cached
                } else if (fileId.startsWith("/") && java.io.File(fileId).exists()) {
                    java.io.File(fileId)
                } else {
                    com.example.sync.SyncEngine.downloadFile(context, fileId, com.example.util.Supabase.BUCKET_PATIENT_MEDIA)
                }
                if (file != null && file.exists()) {
                    return try {
                        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                    } catch (e: Exception) { "" }
                }
            }
        }
        // 2. Fallback: check patient's own registered admission signature (signatureFileId)
        val patSig = patient.signatureFileId
        if (!patSig.isNullOrBlank()) {
            val file = if (patSig.startsWith("/") && java.io.File(patSig).exists()) {
                java.io.File(patSig)
            } else {
                com.example.sync.SyncEngine.downloadFile(context, patSig, com.example.util.Supabase.BUCKET_PATIENT_MEDIA)
            }
            if (file != null && file.exists()) {
                return try {
                    android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                } catch (e: Exception) { "" }
            }
        }
        return ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (isPersian) "مدارک، اسکن‌ها و رضایت‌نامه‌های پرونده بیمار" else "Patient Clinical Records & Consents",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // 3 Distinct Tabs as requested
            TabRow(
                selectedTabIndex = selectedDocTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
            ) {
                Tab(
                    selected = selectedDocTab == 0,
                    onClick = { selectedDocTab = 0 },
                    text = { Text(if (isPersian) "۱. اسکن و تصویربرداری" else "1. Radiography & Scans", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedDocTab == 1,
                    onClick = { selectedDocTab = 1 },
                    text = { Text(if (isPersian) "۲. قبل و بعد درمان" else "2. Before & After", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedDocTab == 2,
                    onClick = { selectedDocTab = 2 },
                    text = { Text(if (isPersian) "۳. رضایت‌نامه و امضا" else "3. Consent & Signature", fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                )
            }

            // Tab Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (selectedDocTab) {
                    0 -> {
                        Button(
                            onClick = { showAddDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPersian) "ثبت اسکن رادیوگرافی یا تصویربرداری زنده" else "Add Radiography Scan / Live Image", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    1 -> {
                        Button(
                            onClick = { showBeforeAfterDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPersian) "مقایسه تخصصی و ثبت تصاویر قبل و بعد درمان" else "Compare & Manage Before/After Photos", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    2 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { showConsentDialog = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                            ) {
                                Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (consentsList.isEmpty()) (if (isPersian) "دریافت یکبار امضای دیجیتال و فرم رضایتنامه استاندارد" else "Capture One-Time Signature & Standard Consent")
                                    else (if (isPersian) "تجدید امضا / صدور رضایتنامه جدید" else "Re-sign / Issue New Consent"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { signaturePhotoCameraLauncher() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPersian) "عکس امضا از دوربین" else "Photo of Signature", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { signaturePhotoGalleryLauncher.launch("image/*") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPersian) "عکس امضا از گالری" else "Signature from Gallery", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val positiveItems = mutableListOf<String>()
                                        if (patient.medicalHistory.isNotBlank()) positiveItems.add(patient.medicalHistory)
                                        if (patient.medicalAlerts.isNotBlank()) positiveItems.add("هشدارهای پزشکی: ${patient.medicalAlerts}")
                                        if (patient.note.isNotBlank()) positiveItems.add("ملاحظات: ${patient.note}")

                                        coroutineScope.launch {
                                            val sigBase64 = fetchSignatureBase64(latestSignatureFileDoc)

                                            val imgFile = com.example.reports.generateAdmissionFormCardImage(
                                                context = context,
                                                patientName = patient.name,
                                                patientPhone = patient.phone,
                                                patientAge = if (patient.age > 0) "${patient.age} ساله" else "ثبت نشده",
                                                patientNationalId = patient.patientCode,
                                                admissionDateStr = viewModel.formatDate(System.currentTimeMillis()),
                                                positiveMedicalHistory = positiveItems,
                                                chiefComplaint = if (patient.note.isNotBlank()) patient.note else "چکاپ و درمان دندانپزشکی",
                                                signatureBase64 = sigBase64,
                                                clinicName = if (clinicSettings.clinicName.isNotBlank()) clinicSettings.clinicName else "کلینیک دندانپزشکی",
                                                themeSpec = viewModel.currentExportTheme()
                                            )
                                            com.example.reports.shareImageToWhatsApp(context, imgFile, patient.phone)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPersian) "ارسال تصویر فرم پذیرش با امضا" else "Share Admission Form Image", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        val consentTitles = consentsList.map { it.title }.filter { it.isNotBlank() }
                                        val consentTitleText = if (consentTitles.isNotEmpty()) consentTitles.joinToString("، ") else "رضایت‌نامه آگاهانه و برائت‌نامه خدمات دندان‌پزشکی"

                                        coroutineScope.launch {
                                            val sigBase64 = fetchSignatureBase64(latestSignatureFileDoc)

                                            val template = com.example.data.LegalConsentKnowledgeBase.getTemplates().firstOrNull { consentTitleText.contains(it.title) } ?: com.example.data.LegalConsentKnowledgeBase.getTemplates().last()
                                            val finalLegalText = com.example.data.LegalConsentKnowledgeBase.buildFinalLegalText(
                                                template = template,
                                                patientName = patient.name,
                                                patientNationalId = patient.patientCode,
                                                patientFatherName = "",
                                                signerRole = "خود بیمار",
                                                doctorName = clinicSettings.doctorName,
                                                medicalNo = "",
                                                clinicName = clinicSettings.clinicName,
                                                treatmentTitle = consentTitleText
                                            )

                                            val imgFile = com.example.reports.generateLegalConsentFormDocument(
                                                context = context,
                                                patientName = patient.name,
                                                patientNationalId = patient.patientCode,
                                                patientPhone = patient.phone,
                                                patientAge = if (patient.age > 0) "${patient.age} ساله" else "ثبت نشده",
                                                signerRole = "خود بیمار",
                                                doctorName = if (clinicSettings.doctorName.isNotBlank()) clinicSettings.doctorName else "پزشک معالج",
                                                doctorMedicalNo = "",
                                                clinicName = if (clinicSettings.clinicName.isNotBlank()) clinicSettings.clinicName else "کلینیک دندان‌پزشکی",
                                                consentTitle = consentTitleText,
                                                legalText = finalLegalText,
                                                signatureBase64 = sigBase64,
                                                themeSpec = viewModel.currentExportTheme()
                                            )

                                            val uri = com.example.reports.fileProviderUri(context, imgFile)
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "image/png"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            try {
                                                context.startActivity(Intent.createChooser(shareIntent, "چاپ و بایگانی سند رسمی رضایت‌نامه (ارائه به مراجع قانونی)"))
                                            } catch (e: Exception) {}
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPersian) "چاپ / ارائه به مراجع قانونی" else "Print / Archivable Document", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val consentTitles = consentsList.map { it.title }.filter { it.isNotBlank() }
                                        val consentTitleText = if (consentTitles.isNotEmpty()) consentTitles.joinToString("، ") else "رضایت‌نامه آگاهانه و برائت‌نامه خدمات دندان‌پزشکی"

                                        coroutineScope.launch {
                                            val sigBase64 = fetchSignatureBase64(latestSignatureFileDoc)

                                            val template = com.example.data.LegalConsentKnowledgeBase.getTemplates().firstOrNull { consentTitleText.contains(it.title) } ?: com.example.data.LegalConsentKnowledgeBase.getTemplates().last()
                                            val finalLegalText = com.example.data.LegalConsentKnowledgeBase.buildFinalLegalText(
                                                template = template,
                                                patientName = patient.name,
                                                patientNationalId = patient.patientCode,
                                                patientFatherName = "",
                                                signerRole = "خود بیمار",
                                                doctorName = clinicSettings.doctorName,
                                                medicalNo = "",
                                                clinicName = clinicSettings.clinicName,
                                                treatmentTitle = consentTitleText
                                            )

                                            val imgFile = com.example.reports.generateLegalConsentFormDocument(
                                                context = context,
                                                patientName = patient.name,
                                                patientNationalId = patient.patientCode,
                                                patientPhone = patient.phone,
                                                patientAge = if (patient.age > 0) "${patient.age} ساله" else "ثبت نشده",
                                                signerRole = "خود بیمار",
                                                doctorName = if (clinicSettings.doctorName.isNotBlank()) clinicSettings.doctorName else "پزشک معالج",
                                                doctorMedicalNo = "",
                                                clinicName = if (clinicSettings.clinicName.isNotBlank()) clinicSettings.clinicName else "کلینیک دندان‌پزشکی",
                                                consentTitle = consentTitleText,
                                                legalText = finalLegalText,
                                                signatureBase64 = sigBase64,
                                                themeSpec = viewModel.currentExportTheme()
                                            )
                                            com.example.reports.shareImageToWhatsApp(context, imgFile, patient.phone)
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPersian) "ارسال واتساپ" else "Send via WhatsApp", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        val currentTabDocs = when (selectedDocTab) {
            0 -> scansList
            1 -> beforeAfterList.filterNot { doc -> doc.title.contains("امضا") || doc.title.contains("رضایت") }
            else -> consentsList
        }

        if (currentTabDocs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .clickable {
                        when (selectedDocTab) {
                            0 -> showAddDialog = true
                            1 -> showBeforeAfterDialog = true
                            2 -> showConsentDialog = true
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = when (selectedDocTab) {
                            0 -> Icons.Outlined.PhotoCamera
                            1 -> Icons.Outlined.Compare
                            else -> Icons.Outlined.Draw
                        },
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color.Gray
                    )
                    Text(
                        text = when (selectedDocTab) {
                            0 -> if (isPersian) "هیچ اسکن یا رادیوگرافی ثبت نشده است (جهت افزودن لمس کنید)" else "No scans added"
                            1 -> if (isPersian) "هیچ تصویر قبل و بعد درمانی ثبت نشده است (جهت افزودن لمس کنید)" else "No before/after photos"
                            else -> if (isPersian) "هیچ فرم رضایتنامه یا امضایی برای این بیمار ثبت نشده است (جهت امضا لمس کنید)" else "No consent or signature captured"
                        },
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                currentTabDocs.forEach { doc ->
                    val isSignatureDoc = doc.title.contains("امضا") || doc.title.contains("رضایت")

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (isSignatureDoc) 120.dp else 180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSignatureDoc) Color(0xFFF0F4F8) else Color(0xFF07141C))
                                    .clickable { if (!doc.fileId.isNullOrBlank()) viewModel.currentViewingImage.value = doc.fileId },
                                contentAlignment = Alignment.Center
                            ) {
                                if (!doc.fileId.isNullOrBlank()) {
                                    com.example.ui.screens.SmartImage(
                                        fileId = doc.fileId,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = if (isSignatureDoc) ContentScale.Fit else ContentScale.Crop
                                    )
                                } else {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val w = size.width
                                        val h = size.height
                                        val path = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(0f, h * 0.7f)
                                            quadraticTo(w * 0.25f, h * 0.65f, w * 0.5f, h * 0.75f)
                                            quadraticTo(w * 0.75f, h * 0.8f, w, h * 0.7f)
                                            lineTo(w, h)
                                            lineTo(0f, h)
                                            close()
                                        }
                                        drawPath(path, Color.White.copy(alpha = 0.15f))
                                        for (i in 2..8) {
                                            val cx = w * (i * 0.11f)
                                            val cy = h * 0.5f
                                            drawCircle(Color.White.copy(alpha = 0.2f), radius = 18f, center = androidx.compose.ui.geometry.Offset(cx, cy))
                                            drawLine(Color.White.copy(alpha = 0.35f), start = androidx.compose.ui.geometry.Offset(cx, cy), end = androidx.compose.ui.geometry.Offset(cx, cy + 28f), strokeWidth = 3f)
                                        }
                                    }
                                    Text(
                                        text = "SCAN_SAMPLE_SECURED_XRAY",
                                        fontSize = 9.sp,
                                        color = Color.Green.copy(alpha = 0.4f),
                                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                                    )
                                    Icon(
                                        Icons.Default.HealthAndSafety,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.2f),
                                        modifier = Modifier.size(50.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = doc.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(text = viewModel.formatDate(if (doc.createdAt > 0L) doc.createdAt else System.currentTimeMillis()), fontSize = 10.sp, color = Color.Gray)
                                    if (doc.notes.isNotEmpty()) {
                                        Text(text = if (isPersian) "یادداشت: ${doc.notes}" else "Notes: ${doc.notes}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (doc.docId != null) {
                                            val toDelete = patientDocs.find { it.id == doc.docId }
                                            if (toDelete != null) viewModel.deletePatientDocument(toDelete)
                                        } else {
                                            // Handle legacy string deletion
                                            val currentUris = patient.documentUris.split(",").toMutableList()
                                            if (currentUris.isNotEmpty()) {
                                                currentUris.removeAt(currentUris.lastIndex)
                                                viewModel.updatePatient(patient.copy(documentUris = currentUris.joinToString(",")))
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.LightGray)
                                }
                            }


                        }
                    }
                }
            }
        }
    }
    if (showConsentDialog) {
        DigitalConsentSignatureDialog(
            patient = patient,
            isPersian = isPersian,
            onDismiss = { showConsentDialog = false },
            onSaveSignature = { templateTitle, localPath ->
                viewModel.addPatientDocument(
                    patientId = patient.id,
                    localFilePath = localPath,
                    docType = "CONSENT",
                    title = "امضای رضایتنامه: $templateTitle",
                    notes = "امضا شده توسط بیمار"
                )
                showConsentDialog = false
            }
        )
    }
    if (pendingSignaturePhotoPath != null) {
        val isUploadingSignature by viewModel.isMediaUploading.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { pendingSignaturePhotoPath = null },
            title = { Text(if (isPersian) "ثبت عکس امضا" else "Save Signature Photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val previewBitmap = remember(pendingSignaturePhotoPath) { decodeLocalThumbnail(pendingSignaturePhotoPath) }
                    if (previewBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFEFEFEF))
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = previewBitmap.asImageBitmap(),
                                contentDescription = "Preview",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                    Text(
                        if (isPersian) "پس‌زمینه‌ی روشن به‌صورت خودکار شفاف شد." else "Light background was made transparent automatically.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DocumentUploadProgressBar(isUploading = isUploadingSignature, isPersian = isPersian)
                }
            },
            confirmButton = {
                Button(onClick = { handleAddSignaturePhoto() }) {
                    Text(if (isPersian) "ثبت در پرونده" else "Save to File")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSignaturePhotoPath = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    if (showBeforeAfterDialog) {
        BeforeAfterComparisonDialog(
            docList = docList,
            isPersian = isPersian,
            patient = patient,
            viewModel = viewModel,
            onDismiss = { showBeforeAfterDialog = false }
        )
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(if (isPersian) "اسکن رادیوگرافی و تصویربرداری پرونده" else "Capture Clinical Radiography Scan", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = if (isPersian) "نوع تصویر یا اسکن دندانپزشکی را انتخاب کنید:" else "Choose Scan/Capture Modality Template:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        templates.forEach { temp ->
                            InputChip(
                                selected = selectedScannerTemplate == temp,
                                onClick = { selectedScannerTemplate = temp },
                                label = { Text(temp) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text(if (isPersian) "یادداشت تشخیصی پزشک" else "Diagnostic notes / report") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(text = if (isPersian) "انتخاب منبع تصویر مدرک:" else "Choose Document Photo Source:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { launchCamera() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPersian) "دوربین عکاسی" else "Camera", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { galleryLauncher.launch("image/*") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPersian) "انتخاب از گالری" else "Gallery", fontSize = 11.sp)
                        }
                    }

                    val isUploadingScan by viewModel.isMediaUploading.collectAsStateWithLifecycle()
                    DocumentUploadProgressBar(isUploading = isUploadingScan, isPersian = isPersian)

                    selectedImageFileId?.let { localPath ->
                        val previewBitmap = remember(localPath) { decodeLocalThumbnail(localPath) }
                        if (previewBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
                                    contentDescription = "Preview",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Text(
                                    text = if (isPersian) "تصویر انتخاب شد" else "Image selected",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(4.dp)
                                        .align(Alignment.BottomCenter)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { handleAddScan() }) {
                    Text(if (isPersian) "ثبت در پرونده" else "Record & Store File")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }
}

// ===== 7. ERP CLINIC SETTINGS PANEL (DEDICATED HUB) =====
/**
 * کارت تنظیمات کرکره‌ای (Collapsible): هدر قابل لمس + بدنه قابل جمع‌شدن —
 * برای همه کارت‌های صفحه تنظیمات (درخواست: قابلیت کرکره‌ای برای همه کارت‌ها).
 */
@Composable
private fun SettingsCollapsibleCard(
    icon: ImageVector,
    title: String,
    isPersian: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    cardColors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    borderColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onToggle() }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (isExpanded) (if (isPersian) "جمع کردن" else "Collapse") else (if (isPersian) "باز کردن" else "Expand"),
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = cardColors,
                border = BorderStroke(1.dp, borderColor)
            ) {
                content()
            }
        }
    }
}

/** نگه داشتن دقیقاً ۳ ثانیه — برای منوی مخفی تنظیمات (درخواست ۱۸). */
private fun Modifier.tapOrHoldTwoSeconds(onTap: () -> Unit, onHold: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(2000L) { waitForUpOrCancellation() }
        if (up == null) {
            onHold()
            waitForUpOrCancellation()
        } else {
            onTap()
        }
    }
}

@Composable
private fun MapShareIcon(
    icon: ImageVector,
    label: String,
    tint: Color,
    onTap: () -> Unit,
    onHold: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .tapOrHoldTwoSeconds(onTap, onHold)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(36.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * همان [MapShareIcon] اما برای آیکون‌های چندرنگِ برداری (نشان و بلد) که رنگ
 * خودشان را دارند و نباید با tint یکدست شوند.
 */
@Composable
private fun MapSharePainterIcon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    label: String,
    onTap: () -> Unit,
    onHold: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .tapOrHoldTwoSeconds(onTap, onHold)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painter,
            contentDescription = label,
            modifier = Modifier.size(36.dp)
        )
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun Modifier.holdThreeSeconds(onTrigger: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(3000L) {
            waitForUpOrCancellation()
        }
        if (up == null) {
            onTrigger()
            waitForUpOrCancellation()
        }
    }
}

@Composable
fun ClinicSettingsScreen(
    viewModel: DentalViewModel,
    isPersian: Boolean,
    onNavigateToAccount: () -> Unit = {},
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val advancedSettingsRevealed by viewModel.advancedSettingsRevealed.collectAsStateWithLifecycle()
    var showApiKeysScreen by remember { mutableStateOf(false) }
    var showSecurityPinScreen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ————————————————————————————————————————————————
    // کرکره‌ای‌شدن کارت‌ها + منوی مخفی تنظیمات (با ذخیره دائمی وضعیت باز/بسته در تنظیمات)
    // ————————————————————————————————————————————————
    val allCardKeys = listOf(
        "identity", "appearance", "language", "ai_security", "working_days",
        "chart_notation", "calendar", "app_settings", "rbac",
        "dashboard_layout", "clear_data"
    )
    var expandedSettingsKeys by remember {
        val fromServer = settings.settingsExpandedCards.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        mutableStateOf(if (fromServer.isNotEmpty()) fromServer else allCardKeys.toSet())
    }
    LaunchedEffect(settings.settingsExpandedCards) {
        val fromServer = settings.settingsExpandedCards.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (fromServer.isNotEmpty() && fromServer != expandedSettingsKeys) {
            expandedSettingsKeys = fromServer
        }
    }
    var showHiddenCards by remember { mutableStateOf(false) }
    var showCardVisibilityMenu by remember { mutableStateOf(false) }
    fun toggleSettingsKey(key: String) {
        val newSet = if (expandedSettingsKeys.contains(key)) expandedSettingsKeys - key else expandedSettingsKeys + key
        expandedSettingsKeys = newSet
        viewModel.updateSettingsExpandedCards(newSet.joinToString(","))
    }
    val settingsCardVisibilityMap = remember(settings.settingsCardVisibility) {
        val map = mutableMapOf<String, String>()
        try {
            val obj = org.json.JSONObject(settings.settingsCardVisibility)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = obj.optString(k, "NORMAL")
            }
        } catch (e: Exception) { /* خالی = همه عادی */ }
        map
    }
    fun isCardVisible(key: String): Boolean = settingsCardVisibilityMap[key] != "HIDDEN" || showHiddenCards
    val settingsCardDefs = listOf(
        Triple("identity", Icons.Default.Business, if (isPersian) "هویت مطب" else "Clinic Identity"),
        Triple("appearance", Icons.Default.Palette, if (isPersian) "ظاهر و تم برنامه" else "App Appearance"),
        Triple("language", Icons.Default.Language, if (isPersian) "زبان و واحد پولی" else "Language & Currency"),
        Triple("ai_security", Icons.Default.Security, if (isPersian) "هوش مصنوعی و امنیت ورود" else "AI & Security"),
        Triple("working_days", Icons.Default.DateRange, if (isPersian) "روزهای کاری هفته" else "Working Days"),
        Triple("chart_notation", Icons.Default.GridOn, if (isPersian) "استاندارد چارت دندان" else "Chart Notation"),
        Triple("calendar", Icons.Default.Event, if (isPersian) "مناسبت‌ها و تقویم" else "Calendar & Occasions"),
        Triple("app_settings", Icons.Default.AdminPanelSettings, if (isPersian) "تنظیمات اختصاصی نرم‌افزار" else "Advanced App Settings"),
        Triple("rbac", Icons.Default.Badge, if (isPersian) "نقش کاربری" else "User Role"),
        Triple("dashboard_layout", Icons.Default.Dashboard, if (isPersian) "چیدمان داشبورد" else "Dashboard Layout"),
        Triple("clear_data", Icons.Default.DeleteForever, if (isPersian) "پاکسازی اطلاعات" else "Clear Data")
    )

    if (showApiKeysScreen) {
        ApiKeysSettingsScreen(isPersian = isPersian, onBack = { showApiKeysScreen = false })
        return
    }
    if (showSecurityPinScreen) {
        SecurityPinSettingsScreen(viewModel = viewModel, isPersian = isPersian, onBack = { showSecurityPinScreen = false })
        return
    }

    val showOccasions by viewModel.showOccasions.collectAsStateWithLifecycle()

    val logoEditLauncher = rememberPhotoEditLauncher(PhotoAssetKind.LOGO) { localPath ->
        viewModel.updateClinicLogo(localPath)
        android.widget.Toast.makeText(context, if (isPersian) "لوگوی جدید با موفقیت اعمال شد" else "New logo applied", android.widget.Toast.LENGTH_SHORT).show()
    }
    val logoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) logoEditLauncher(uri)
    }

    // Export/Import — الگوهای آماده درمان و سرفصل‌های شخصی (CSV، قابل ویرایش در اکسل/ویندوز/تبلت)
    var showImportTemplatesPicker by remember { mutableStateOf(false) }
    var showImportSubAccountsPicker by remember { mutableStateOf(false) }
    var pendingImportedTemplates by remember { mutableStateOf<List<TreatmentTemplate>?>(null) }
    var pendingImportedSubAccounts by remember { mutableStateOf<List<PersonalSubAccount>?>(null) }

    val importTemplatesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val parsed = readTemplatesFromCsvUri(context, uri)
            if (parsed != null) pendingImportedTemplates = parsed
            else android.widget.Toast.makeText(context, if (isPersian) "فایل CSV نامعتبر است" else "Invalid CSV file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val importSubAccountsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val parsed = readSubAccountsFromCsvUri(context, uri)
            if (parsed != null) pendingImportedSubAccounts = parsed
            else android.widget.Toast.makeText(context, if (isPersian) "فایل CSV نامعتبر است" else "Invalid CSV file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && pendingExportJson != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingExportJson!!.toByteArray())
                }
                android.widget.Toast.makeText(context, if (isPersian) "فایل پشتیبان با موفقیت ذخیره شد" else "Backup saved successfully", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, if (isPersian) "خطا در ذخیره فایل" else "Error saving file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        pendingExportJson = null
    }

    var importCsvReplaceMode by remember { mutableStateOf(false) }
    var showCsvImportChoiceDialog by remember { mutableStateOf(false) }
    var personalOccasionTitle by remember { mutableStateOf("") }
    var personalOccasionDayStr by remember { mutableStateOf("") }
    var personalOccasionMonthStr by remember { mutableStateOf("") }
    var personalOccasionYearStr by remember { mutableStateOf("") }
    var personalOccasionType by remember { mutableStateOf("تولد") }
    var personalOccasionIsHoliday by remember { mutableStateOf(false) }
    var personalOccasionDesc by remember { mutableStateOf("") }
    var occasionTypeExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val importCalendarCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val bytes = stream.readBytes()
                    val csvContent = String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    val success = viewModel.importCalendarEventsFromCsv(csvContent, replace = importCsvReplaceMode)
                    if (success) {
                        android.widget.Toast.makeText(context, if (importCsvReplaceMode) "مناسبت‌ها با موفقیت جایگزین شدند" else "مناسبت‌ها با موفقیت اضافه شدند", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(context, "خطا در قالب فایل CSV ارسالی", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "خطا در خواندن فایل: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    var backupConfig by remember { mutableStateOf(BackupSelectionConfig()) }
    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val jsonStr = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (jsonStr != null) {
                    coroutineScope.launch {
                        val success = viewModel.restoreFromBackupJson(jsonStr, backupConfig)
                        if (success) {
                            android.widget.Toast.makeText(context, if (isPersian) "پشتیبان با موفقیت بازیابی شد" else "Backup restored successfully", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, if (isPersian) "فایل پشتیبان نامعتبر است" else "Invalid backup file", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, if (isPersian) "خطا در خواندن فایل" else "Error reading file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(showImportTemplatesPicker) {
        if (showImportTemplatesPicker) { importTemplatesLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "*/*")); showImportTemplatesPicker = false }
    }
    LaunchedEffect(showImportSubAccountsPicker) {
        if (showImportSubAccountsPicker) { importSubAccountsLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "*/*")); showImportSubAccountsPicker = false }
    }
    val currencyUnit by viewModel.currencyUnit.collectAsStateWithLifecycle()
    val autoBackupInterval by viewModel.autoBackupInterval.collectAsStateWithLifecycle()
    val lastBackupTimestamp by viewModel.lastBackupTimestamp.collectAsStateWithLifecycle()
    val isCloudSyncEnabled by viewModel.isCloudSyncEnabled.collectAsStateWithLifecycle()
    val cloudBackups by viewModel.cloudBackups.collectAsStateWithLifecycle()
    val lastCloudSyncTimestamp by viewModel.lastCloudSyncTimestamp.collectAsStateWithLifecycle()
    val dentalChartSystem by viewModel.dentalChartSystem.collectAsStateWithLifecycle()
    val calendarType by viewModel.calendarType.collectAsStateWithLifecycle()

    var clinicName by remember { mutableStateOf("") }
    var doctorName by remember { mutableStateOf("") }
    var doctorLicenseNo by remember { mutableStateOf("") }
    var clinicPhone by remember { mutableStateOf("") }
    var clinicAddress by remember { mutableStateOf("") }
    var clinicMapLink by remember { mutableStateOf("") }
    var googleMapsUrl by remember { mutableStateOf("") }
    var wazeMapsUrl by remember { mutableStateOf("") }
    // لینک سرویس‌های نقشه ایرانی — نشان (neshan.org) و بلد (balad.ir)
    var neshanMapsUrl by remember { mutableStateOf("") }
    var baladMapsUrl by remember { mutableStateOf("") }
    var mapLinkEditorKind by remember { mutableStateOf<String?>(null) }
    var mapLinkDraft by remember { mutableStateOf("") }
    var showSummary by remember { mutableStateOf(true) }
    var showQuick by remember { mutableStateOf(true) }
    var showCharts by remember { mutableStateOf(true) }
    var fontScaleIdx by remember { mutableIntStateOf(9) }
    var clinicalFontIdx by remember { mutableIntStateOf(3) }
    var apptTemplate by remember { mutableStateOf("") }
    var invoiceTemplate by remember { mutableStateOf("") }
    var treatmentPlanTemplate by remember { mutableStateOf("") }
    var installmentTemplate by remember { mutableStateOf("") }
    var overdueInstallmentTemplate by remember { mutableStateOf("") }
    var postOpTemplate by remember { mutableStateOf("") }

    var cloudRestoreConfirmRecord by remember { mutableStateOf<CloudBackupRecord?>(null) }
    var backupJsonPreview by remember { mutableStateOf<String?>(null) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreJsonInput by remember { mutableStateOf("") }
    var restoreStatusMsg by remember { mutableStateOf<String?>(null) }
    var showClearDataConfirm by remember { mutableStateOf(false) }
    
    val exportDbLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            viewModel.exportDatabaseToFile(context, uri)
        }
    }
    
    val importDbLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.importDatabaseFromFile(context, uri)
        }
    }

    LaunchedEffect(settings) {
        clinicName = settings.clinicName
        doctorName = settings.doctorName
        doctorLicenseNo = settings.doctorLicenseNo
        clinicPhone = settings.clinicPhone
        clinicAddress = settings.clinicAddress
        clinicMapLink = settings.clinicMapLink
        val links = parseClinicMapLinks(settings.clinicMapLink)
        googleMapsUrl = links.google
        wazeMapsUrl = links.waze
        neshanMapsUrl = links.neshan
        baladMapsUrl = links.balad
        showSummary = settings.showSummaryCard
        showQuick = settings.showQuickActions
        showCharts = settings.showCharts
        fontScaleIdx = settings.globalFontScaleIndex
        clinicalFontIdx = (settings.clinicalFontSizePt - 8).coerceIn(0, 19)
        apptTemplate = settings.appointmentTemplate
        invoiceTemplate = settings.invoiceTemplate
        treatmentPlanTemplate = settings.treatmentPlanTemplate
        installmentTemplate = settings.installmentTemplate
        overdueInstallmentTemplate = settings.overdueInstallmentTemplate
        postOpTemplate = settings.postOpTemplate
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = if (isPersian) "تنظیمات اختصاصی نرم‌افزار مطب" else "Dedicated Clinic App Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // منوی مخفی تنظیمات: چرخ‌دنده را ۳ ثانیه نگه دارید (درخواست ۱۸)
            IconButton(
                onClick = { showCardVisibilityMenu = true },
                modifier = Modifier.holdThreeSeconds { showCardVisibilityMenu = true }
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = if (isPersian) "منوی مخفی تنظیمات (۳ ثانیه نگه دارید)" else "Hidden settings menu (hold 3s)",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ————————————————————————————————————————————————
        // منوی مخفی تنظیمات (لمس ۳ ثانیه‌ای چرخ‌دنده) — نمایش عادی/مخفی هر کارت (درخواست ۱۸)
        // ————————————————————————————————————————————————
        if (showCardVisibilityMenu) {
            AlertDialog(
                onDismissRequest = { showCardVisibilityMenu = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (isPersian) "منوی مخفی تنظیمات" else "Hidden Settings Menu", fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (isPersian)
                                "برای هر کارت مشخص کنید: نمایش عادی در صفحه تنظیمات، یا فقط از طریق همین منوی مخفی."
                            else
                                "Choose for each card: show normally in settings, or only via this hidden menu.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        settingsCardDefs.forEach { (key, icon, label) ->
                            val current = settingsCardVisibilityMap[key] ?: "NORMAL"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        val newVal = if (current == "NORMAL") "HIDDEN" else "NORMAL"
                                        val next = settingsCardVisibilityMap.toMutableMap().apply { put(key, newVal) }
                                        val json = org.json.JSONObject().apply {
                                            next.forEach { (k, v) -> put(k, v) }
                                        }.toString()
                                        viewModel.updateSettingsCardVisibility(json)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Surface(
                                    color = if (current == "NORMAL") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (current == "NORMAL")
                                            (if (isPersian) "نمایش عادی" else "Normal")
                                        else
                                            (if (isPersian) "فقط منوی مخفی" else "Hidden only"),
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        color = if (current == "NORMAL") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (isPersian) "نمایش موقت کارت‌های مخفی" else "Temporarily show hidden cards", fontSize = 11.sp)
                                Text(if (isPersian) "فقط برای پیش‌نمایش در همین صفحه" else "Preview only", fontSize = 9.sp, color = Color.Gray)
                            }
                            Switch(checked = showHiddenCards, onCheckedChange = { showHiddenCards = it })
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showCardVisibilityMenu = false }) {
                        Text(if (isPersian) "بستن" else "Close")
                    }
                }
            )
        }



        if (showCsvImportChoiceDialog) {
            AlertDialog(
                onDismissRequest = { showCsvImportChoiceDialog = false },
                title = { Text(if (isPersian) "ورود فایل CSV مناسبت‌های تقویم" else "Import Calendar CSV", fontWeight = FontWeight.Bold) },
                text = { Text(if (isPersian) "آیا مناسبت‌های موجود در فایل به لیست فعلی اضافه شوند یا جایگزین مناسبت‌های قبلی گردند؟" else "Should new events be added to existing events or replace them entirely?") },
                confirmButton = {
                    Button(onClick = {
                        importCsvReplaceMode = false
                        showCsvImportChoiceDialog = false
                        importCalendarCsvLauncher.launch("*/*")
                    }) { Text(if (isPersian) "اضافه کردن (افزودن)" else "Add") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            importCsvReplaceMode = true
                            showCsvImportChoiceDialog = false
                            importCalendarCsvLauncher.launch("*/*")
                        }) { Text(if (isPersian) "جایگزین کردن" else "Replace", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { showCsvImportChoiceDialog = false }) { Text(if (isPersian) "انصراف" else "Cancel") }
                    }
                }
            )
        }

        if (pendingImportedTemplates != null) {
            val importedList = pendingImportedTemplates!!
            AlertDialog(
                onDismissRequest = { pendingImportedTemplates = null },
                title = { Text(if (isPersian) "ورودی الگوهای درمان" else "Import Templates", fontWeight = FontWeight.Bold) },
                text = { Text(if (isPersian) "${importedList.size} الگو در فایل پیدا شد. آیا به لیست فعلی اضافه شود یا کل لیست فعلی جایگزین شود؟" else "${importedList.size} templates found. Add to the current list, or replace it entirely?") },
                confirmButton = {
                    Button(onClick = {
                        importedList.forEach { viewModel.addTemplate(it.title, it.defaultCost, it.defaultNotes, it.stages, it.isStarred, titleFa = it.titleFa) }
                        pendingImportedTemplates = null
                    }) { Text(if (isPersian) "افزودن" else "Add") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            viewModel.templates.value.forEach { viewModel.deleteTemplate(it) }
                            importedList.forEach { viewModel.addTemplate(it.title, it.defaultCost, it.defaultNotes, it.stages, it.isStarred, titleFa = it.titleFa) }
                            pendingImportedTemplates = null
                        }) { Text(if (isPersian) "جایگزینی کامل" else "Replace All", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { pendingImportedTemplates = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
                    }
                }
            )
        }

        if (pendingImportedSubAccounts != null) {
            val importedList = pendingImportedSubAccounts!!
            AlertDialog(
                onDismissRequest = { pendingImportedSubAccounts = null },
                title = { Text(if (isPersian) "ورودی سرفصل‌های شخصی" else "Import Sub-Accounts", fontWeight = FontWeight.Bold) },
                text = { Text(if (isPersian) "${importedList.size} سرفصل در فایل پیدا شد. آیا به لیست فعلی اضافه شود یا کل لیست فعلی جایگزین شود؟" else "${importedList.size} sub-accounts found. Add to the current list, or replace it entirely?") },
                confirmButton = {
                    Button(onClick = {
                        importedList.forEach { viewModel.addPersonalSubAccount(it.name, it.category, it.ledgerSide) }
                        pendingImportedSubAccounts = null
                    }) { Text(if (isPersian) "افزودن" else "Add") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            viewModel.personalSubAccounts.value.forEach { viewModel.deletePersonalSubAccount(it) }
                            importedList.forEach { viewModel.addPersonalSubAccount(it.name, it.category, it.ledgerSide) }
                            pendingImportedSubAccounts = null
                        }) { Text(if (isPersian) "جایگزینی کامل" else "Replace All", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { pendingImportedSubAccounts = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
                    }
                }
            )
        }

        val currentUserRole by viewModel.currentUserRole.collectAsStateWithLifecycle()
        var showDoctorAuthDialog by remember { mutableStateOf(false) }
        var pendingTargetRole by remember { mutableStateOf<UserRole?>(null) }

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

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ----------------------------------------------------
            // CARD 1: هویت و مشخصات مطب و پزشک معالج (CLINIC & DOCTOR IDENTITY)
            // ----------------------------------------------------
            if (isCardVisible("identity")) {
            item {
                SettingsCollapsibleCard(
                    icon = Icons.Default.Business,
                    title = if (isPersian) "هویت مطب" else "Clinic Identity",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("identity"),
                    onToggle = { toggleSettingsKey("identity") }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // پزشک فعال — نام فقط از پرسنل (منبع واحد)؛ نام و شماره نظام از کارت هویت حذف شده‌اند
                        val activeDoctor by viewModel.activeDoctor.collectAsStateWithLifecycle()
                        if (activeDoctor != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.MedicalServices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = if (isPersian) "پزشک فعال (از پرسنل مطب):" else "Active Doctor (from staff):",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(activeDoctor!!.fullName, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (settings.doctorLicenseNo.isNotBlank()) {
                                        Text(
                                            text = if (isPersian) "نظام: ${settings.doctorLicenseNo}" else "License: ${settings.doctorLicenseNo}",
                                            fontSize = 10.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = clinicName,
                            onValueChange = { clinicName = it },
                            label = { Text(if (isPersian) "نام مطب / کلینیک" else "Clinic Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = clinicPhone,
                            onValueChange = { clinicPhone = it },
                            label = { Text(if (isPersian) "شماره تلفن مطب" else "Clinic Phone") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                            )
                        )

                        // آدرس مطب + لینک لوکیشن (بلد/گوگل‌مپ) — درخواست ۱۹
                        OutlinedTextField(
                            value = clinicAddress,
                            onValueChange = { clinicAddress = it },
                            label = { Text(if (isPersian) "آدرس مطب" else "Clinic Address") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2
                        )
                        Text(
                            text = if (isPersian) "لوکیشن مطب — لمس کوتاه: اشتراک لینک | نگه داشتن ۲ ثانیه: ثبت لینک" else "Maps — tap to share, hold 2s to set URL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MapShareIcon(
                                icon = Icons.Default.Map,
                                label = if (isPersian) "گوگل‌مپ" else "Google Maps",
                                tint = Color(0xFF1A73E8),
                                onTap = {
                                    val url = googleMapsUrl.trim()
                                    if (url.isBlank()) {
                                        Toast.makeText(context, if (isPersian) "لینک گوگل‌مپ ثبت نشده — ۲ ثانیه نگه دارید" else "No Google Maps URL — hold 2s", Toast.LENGTH_SHORT).show()
                                    } else {
                                        com.example.reports.shareTextNativeChooser(context, url, if (isPersian) "اشتراک لوکیشن گوگل‌مپ" else "Share Google Maps")
                                    }
                                },
                                onHold = {
                                    mapLinkDraft = googleMapsUrl
                                    mapLinkEditorKind = "google"
                                }
                            )
                            MapShareIcon(
                                icon = Icons.Default.Navigation,
                                label = "Waze",
                                tint = Color(0xFF33CCFF),
                                onTap = {
                                    val url = wazeMapsUrl.trim()
                                    if (url.isBlank()) {
                                        Toast.makeText(context, if (isPersian) "لینک ویز ثبت نشده — ۲ ثانیه نگه دارید" else "No Waze URL — hold 2s", Toast.LENGTH_SHORT).show()
                                    } else {
                                        com.example.reports.shareTextNativeChooser(context, url, if (isPersian) "اشتراک لوکیشن ویز" else "Share Waze")
                                    }
                                },
                                onHold = {
                                    mapLinkDraft = wazeMapsUrl
                                    mapLinkEditorKind = "waze"
                                }
                            )
                            // نشان — سرویس نقشه ایرانی (neshan.org)
                            MapSharePainterIcon(
                                painter = painterResource(id = R.drawable.ic_map_neshan),
                                label = if (isPersian) "نشان" else "Neshan",
                                onTap = {
                                    val url = neshanMapsUrl.trim()
                                    if (url.isBlank()) {
                                        Toast.makeText(context, if (isPersian) "لینک نشان ثبت نشده — ۲ ثانیه نگه دارید" else "No Neshan URL — hold 2s", Toast.LENGTH_SHORT).show()
                                    } else {
                                        com.example.reports.shareTextNativeChooser(context, url, if (isPersian) "اشتراک لوکیشن نشان" else "Share Neshan")
                                    }
                                },
                                onHold = {
                                    mapLinkDraft = neshanMapsUrl
                                    mapLinkEditorKind = "neshan"
                                }
                            )
                            // بلد — سرویس نقشه ایرانی (balad.ir)
                            MapSharePainterIcon(
                                painter = painterResource(id = R.drawable.ic_map_balad),
                                label = if (isPersian) "بلد" else "Balad",
                                onTap = {
                                    val url = baladMapsUrl.trim()
                                    if (url.isBlank()) {
                                        Toast.makeText(context, if (isPersian) "لینک بلد ثبت نشده — ۲ ثانیه نگه دارید" else "No Balad URL — hold 2s", Toast.LENGTH_SHORT).show()
                                    } else {
                                        com.example.reports.shareTextNativeChooser(context, url, if (isPersian) "اشتراک لوکیشن بلد" else "Share Balad")
                                    }
                                },
                                onHold = {
                                    mapLinkDraft = baladMapsUrl
                                    mapLinkEditorKind = "balad"
                                }
                            )
                        }
                        if (mapLinkEditorKind != null) {
                            AlertDialog(
                                onDismissRequest = { mapLinkEditorKind = null },
                                title = {
                                    Text(
                                        when (mapLinkEditorKind) {
                                            "waze" -> if (isPersian) "لینک ویز" else "Waze URL"
                                            "neshan" -> if (isPersian) "لینک نشان" else "Neshan URL"
                                            "balad" -> if (isPersian) "لینک بلد" else "Balad URL"
                                            else -> if (isPersian) "لینک گوگل‌مپ" else "Google Maps URL"
                                        }
                                    )
                                },
                                text = {
                                    OutlinedTextField(
                                        value = mapLinkDraft,
                                        onValueChange = { mapLinkDraft = it },
                                        label = { Text(if (isPersian) "لینک را بچسبانید" else "Paste URL") },
                                        placeholder = {
                                            Text(
                                                when (mapLinkEditorKind) {
                                                    "neshan" -> "https://nshn.ir/..."
                                                    "balad" -> "https://balad.ir/p/..."
                                                    "waze" -> "https://waze.com/ul?ll=..."
                                                    else -> "https://maps.app.goo.gl/..."
                                                },
                                                fontSize = 11.sp
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                },
                                confirmButton = {
                                    Button(onClick = {
                                        val trimmed = mapLinkDraft.trim()
                                        when (mapLinkEditorKind) {
                                            "waze" -> wazeMapsUrl = trimmed
                                            "neshan" -> neshanMapsUrl = trimmed
                                            "balad" -> baladMapsUrl = trimmed
                                            else -> googleMapsUrl = trimmed
                                        }
                                        clinicMapLink = encodeClinicMapLinks(googleMapsUrl, wazeMapsUrl, neshanMapsUrl, baladMapsUrl)
                                        viewModel.updateClinicSettings(settings.copy(clinicMapLink = clinicMapLink))
                                        mapLinkEditorKind = null
                                        Toast.makeText(context, if (isPersian) "لینک ذخیره شد" else "Saved", Toast.LENGTH_SHORT).show()
                                    }) { Text(if (isPersian) "ذخیره" else "Save") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { mapLinkEditorKind = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Logo Customization Card (Circular Crop)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (!settings.logoFileId.isNullOrBlank()) {
                                    SmartImage(
                                        fileId = settings.logoFileId,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    )
                                } else {
                                    Image(
                                        painter = painterResource(id = R.drawable.img_app_icon),
                                        contentDescription = "Default Logo",
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(CircleShape)
                                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (isPersian) "لوگوی اختصاصی مطب (کادر دایره‌ای)" else "Clinic Logo (Circular Frame)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(if (isPersian) "امکان تغییر از گالری و نمایش در بنر داشبورد" else "Select from gallery & display on dashboard banner", fontSize = 10.sp, color = Color.Gray)
                                }
                                OutlinedButton(
                                    onClick = { logoPickerLauncher.launch("image/*") },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(if (isPersian) "تغییر لوگو" else "Change Logo", fontSize = 11.sp)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                viewModel.updateClinicSettings(
                                    settings.copy(
                                        clinicName = clinicName.trim(),
                                        clinicPhone = clinicPhone.trim(),
                                        clinicAddress = clinicAddress.trim(),
                                        clinicMapLink = encodeClinicMapLinks(googleMapsUrl, wazeMapsUrl, neshanMapsUrl, baladMapsUrl)
                                    )
                                )
                                android.widget.Toast.makeText(context, if (isPersian) "اطلاعات مطب ذخیره شد" else "Clinic info saved", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isPersian) "ذخیره سریع اطلاعات مطب" else "Save Clinic Details", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            }

// ----------------------------------------------------
            // SECTION 1.5: APPEARANCE — THEME PICKER
            // ----------------------------------------------------
            if (isCardVisible("appearance")) {
            item {
                val activeThemeId = settings.themeName
                val activePalette = neonPaletteById(activeThemeId)
                SettingsCollapsibleCard(
                    icon = Icons.Default.Palette,
                    title = if (isPersian) "ظاهر و تم برنامه" else "App Appearance & Theme",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("appearance"),
                    onToggle = { toggleSettingsKey("appearance") },
                    borderColor = activePalette.primary.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "تم رنگی" else "Color Theme",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = if (isPersian) "یک تم را برای رنگ‌بندی برنامه انتخاب کنید. تغییر بلافاصله اعمال می‌شود."
                                   else "Pick a theme to recolor the app. Changes apply instantly.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AllNeonPalettes.forEach { palette ->
                                val isSelected = palette.id == activeThemeId
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(76.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, palette.primary, RoundedCornerShape(14.dp))
                                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                                        )
                                        .background(palette.background)
                                        .clickable { viewModel.updateTheme(palette.id) }
                                        .padding(vertical = 10.dp, horizontal = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(
                                                brush = Brush.sweepGradient(
                                                    palette.swatchDots + palette.swatchDots.first()
                                                )
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .background(palette.background),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = palette.glow,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (isPersian) palette.labelFa else palette.labelEn,
                                        fontSize = 9.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) palette.glow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center,
                                        maxLines = 2
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = activePalette.primary.copy(alpha = 0.15f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isPersian) "حالت تیره" else "Dark Mode",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isPersian) "مستقل از تم رنگی — می‌توانید حالت روشن را نیز انتخاب کنید" else "Independent of the color theme",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                            Switch(
                                checked = settings.isDarkMode,
                                onCheckedChange = { viewModel.setDarkMode(it) }
                            )
                        }
                    }
                }
            }
            }

// ----------------------------------------------------
            // SECTION 3: LANGUAGE & CURRENCY
            // ----------------------------------------------------
            if (isCardVisible("language")) {
            item {
                SettingsCollapsibleCard(
                    icon = Icons.Default.Language,
                    title = if (isPersian) "زبان و واحد پولی" else "Language & Currency",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("language"),
                    onToggle = { toggleSettingsKey("language") },
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "زبان و واحد پولی" else "Language & Currency",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Multi-Language Selector
                        val selectedLanguage by viewModel.selectedLanguage.collectAsStateWithLifecycle()
                        val context = LocalContext.current
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isPersian) "زبان رابط کاربری نرم‌افزار:" else "App UI Language:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    "fa" to "🇮🇷 فارسی",
                                    "en" to "🇺🇸 English",
                                    "ar" to "🇸🇦 العربية",
                                    "tr" to "🇹🇷 Türkçe",
                                    "az" to "🇦🇿 Azərbaycanca",
                                    "zh" to "🇨🇳 中文",
                                    "de" to "🇩🇪 Deutsch"
                                ).forEach { (langCode, langLabel) ->
                                    val isSupported = langCode == "fa" || langCode == "en"
                                    FilterChip(
                                        selected = selectedLanguage == langCode,
                                        onClick = { 
                                            if (isSupported) {
                                                viewModel.setLanguage(langCode) 
                                            } else {
                                                android.widget.Toast.makeText(context, if (isPersian) "بزودی" else "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        label = { Text(langLabel, fontSize = 11.sp, fontWeight = if (selectedLanguage == langCode) FontWeight.Bold else FontWeight.Normal) }
                                    )
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Currency Selector
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isPersian) "واحد پولی نمایش مبالغ و محاسبات:" else "Display Currency Unit:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    "تومان",
                                    "ریال",
                                    "SAR",
                                    "₺ TRY",
                                    "₼ AZN",
                                    "¥ CNY",
                                    "€ EUR",
                                    "$ USD"
                                ).forEach { unit ->
                                    val isSupported = unit == "تومان" || unit == "ریال" || unit == "$ USD"
                                    FilterChip(
                                        selected = currencyUnit == unit,
                                        onClick = { 
                                            if (isSupported) {
                                                viewModel.setCurrencyUnit(unit) 
                                            } else {
                                                android.widget.Toast.makeText(context, if (isPersian) "بزودی" else "Coming soon", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        label = { Text(unit, fontSize = 11.sp, fontWeight = if (currencyUnit == unit) FontWeight.Bold else FontWeight.Normal) }
                                    )
                                }
                            }
                        }

                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        // Calendar Type Selector (Shamsi, Qamari, Miladi)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (isPersian) "نظام تقویم و نمایش تاریخ‌ها در سیستم" else "Calendar System & Date Display Standard",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(
                                    "SHAMSI" to (if (isPersian) "هجری شمسی (جلالی)" else "Solar Hijri"),
                                    "QAMARI" to (if (isPersian) "هجری قمری (اسلامی)" else "Lunar Hijri"),
                                    "MILADI" to (if (isPersian) "میلادی (گریگوری)" else "Gregorian")
                                ).forEach { (calKey, calLabel) ->
                                    ElevatedFilterChip(
                                        selected = calendarType == calKey,
                                        onClick = { viewModel.setCalendarType(calKey) },
                                        label = { Text(calLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }

            // ----------------------------------------------------
            // CARD 4: ابزارهای هوش مصنوعی و امنیت (AI & SECURITY PIN)
            // ----------------------------------------------------
            if (isCardVisible("ai_security")) {
            item {
                SettingsCollapsibleCard(
                    icon = Icons.Default.Security,
                    title = if (isPersian) "هوش مصنوعی و امنیت ورود" else "AI & Security",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("ai_security"),
                    onToggle = { toggleSettingsKey("ai_security") },
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "ابزارهای هوش مصنوعی و امنیت ورود" else "AI Assistant & Security Settings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = if (isPersian) "مدیریت کلیدهای API هوش مصنوعی (Gemini / Groq) و تنظیمات رمز ورود و پین‌کد امنیتی:"
                            else "Manage AI API keys and application PIN / security lock:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showApiKeysScreen = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isPersian) "تنظیمات API هوش مصنوعی" else "AI API Settings", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { showSecurityPinScreen = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isPersian) "تنظیمات امنیتی و پین" else "Security & PIN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            }

// ----------------------------------------------------
            // SECTION: WEEKLY WORKING DAYS CONFIGURATION
            // ----------------------------------------------------
            if (isCardVisible("working_days")) {
            item {
                val currentWorkingDays = settings.workingDays
                val activeDaysList = remember(currentWorkingDays) {
                    currentWorkingDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                }

                SettingsCollapsibleCard(
                    icon = Icons.Default.DateRange,
                    title = if (isPersian) "روزهای کاری هفته" else "Weekly Working Days",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("working_days"),
                    onToggle = { toggleSettingsKey("working_days") },
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "تنظیمات روزهای کاری هفتگی مطب" else "Weekly Working Days",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = if (isPersian) "روزهای مجاز کاری کلینیک را برای ثبت نوبت مشخص کنید:"
                            else "Select allowed working days for appointment booking:",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )

                        val dayNames = listOf(
                            0 to (if (isPersian) "شنبه" else "Sat"),
                            1 to (if (isPersian) "یکشنبه" else "Sun"),
                            2 to (if (isPersian) "دوشنبه" else "Mon"),
                            3 to (if (isPersian) "سه‌شنبه" else "Tue"),
                            4 to (if (isPersian) "چهارشنبه" else "Wed"),
                            5 to (if (isPersian) "پنج‌شنبه" else "Thu"),
                            6 to (if (isPersian) "جمعه" else "Fri")
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            dayNames.forEach { (dayIdx, dayLabel) ->
                                val isSelected = dayIdx in activeDaysList
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        val newSet = if (isSelected) {
                                            activeDaysList - dayIdx
                                        } else {
                                            activeDaysList + dayIdx
                                        }
                                        val newStr = newSet.sorted().joinToString(",")
                                        viewModel.updateWorkingDays(newStr)
                                    },
                                    label = {
                                        Text(
                                            text = dayLabel,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
            }

// ----------------------------------------------------
            // SECTION 5: DENTAL CHART NOTATION STANDARD (ILLUSTRATED)
            // ----------------------------------------------------
            if (isCardVisible("chart_notation")) {
            item {
                SettingsCollapsibleCard(
                    icon = Icons.Default.GridOn,
                    title = if (isPersian) "استاندارد شماره‌گذاری چارت دندان" else "Dental Chart Notation",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("chart_notation"),
                    onToggle = { toggleSettingsKey("chart_notation") },
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.GridOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "استاندارد شماره‌گذاری چارت دندان" else "Dental Chart Notation",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Text(
                            text = if (isPersian) "سیستم شماره‌گذاری دندان‌ها را برای نمایش در پرونده بیمار و چارت تعاملی انتخاب کنید:" else "Select dental chart numbering system:",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )

                        // Selector Chips for FDI, Universal, Palmer
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("FDI" to "بین‌المللی (FDI)", "UNIVERSAL" to "یونیورسال (Universal)", "PALMER" to "پالمر (Palmer)").forEach { (sysKey, labelStr) ->
                                ElevatedFilterChip(
                                    selected = dentalChartSystem == sysKey,
                                    onClick = { viewModel.setDentalChartSystem(sysKey) },
                                    label = { Text(if (isPersian) labelStr else sysKey, fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Visual Illustrated Preview Diagrams for all 3 Systems
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (isPersian) "نمای مصور سیستم انتخاب شده ($dentalChartSystem):" else "Illustrated Preview ($dentalChartSystem):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                when (dentalChartSystem) {
                                    "UNIVERSAL" -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("فک بالا (۱ تا ۱۶): 1 .. 8 | 9 .. 16", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                                                listOf("1", "2", "3", "4", "5", "6", "7", "8", "|", "9", "10", "11", "12", "13", "14", "15", "16").forEachIndexed { index, num ->
                                                    val d = Math.abs(index - 8)
                                                    val yOff = (d * 2.2f - 17.6f).dp
                                                    Surface(
                                                        modifier = Modifier.offset(y = yOff),
                                                        color = if (num == "|") Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(num, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Divider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                            Text("فک پایین (۱۷ تا ۳۲): 32 .. 25 | 24 .. 17", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                                                listOf("32", "31", "30", "29", "28", "27", "26", "25", "|", "24", "23", "22", "21", "20", "19", "18", "17").forEachIndexed { index, num ->
                                                    val d = Math.abs(index - 8)
                                                    val yOff = (17.6f - d * 2.2f).dp
                                                    Surface(
                                                        modifier = Modifier.offset(y = yOff),
                                                        color = if (num == "|") Color.Transparent else MaterialTheme.colorScheme.secondaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(num, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "PALMER" -> {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("فک بالا ( Palmer Grid ┘ └ ): 8┘ .. 1┘ | └1 .. └8", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                                                listOf("8┘", "7┘", "6┘", "5┘", "4┘", "3┘", "2┘", "1┘", "|", "└1", "└2", "└3", "└4", "└5", "└6", "└7", "└8").forEachIndexed { index, num ->
                                                    val d = Math.abs(index - 8)
                                                    val yOff = (d * 2.2f - 17.6f).dp
                                                    Surface(
                                                        modifier = Modifier.offset(y = yOff),
                                                        color = if (num == "|") Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(num, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Divider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                            Text("فک پایین ( Palmer Grid ┐ ┌ ): 8┐ .. 1┐ | ┌1 .. ┌8", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                                                listOf("8┐", "7┐", "6┐", "5┐", "4┐", "3┐", "2┐", "1┐", "|", "┌1", "┌2", "┌3", "┌4", "┌5", "┌6", "┌7", "┌8").forEachIndexed { index, num ->
                                                    val d = Math.abs(index - 8)
                                                    val yOff = (17.6f - d * 2.2f).dp
                                                    Surface(
                                                        modifier = Modifier.offset(y = yOff),
                                                        color = if (num == "|") Color.Transparent else MaterialTheme.colorScheme.secondaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(num, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else -> { // FDI
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("فک بالا (رباعی ۱ و ۲): 18 .. 11 | 21 .. 28", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                                                listOf("18", "17", "16", "15", "14", "13", "12", "11", "|", "21", "22", "23", "24", "25", "26", "27", "28").forEachIndexed { index, num ->
                                                    val d = Math.abs(index - 8)
                                                    val yOff = (d * 2.2f - 17.6f).dp
                                                    Surface(
                                                        modifier = Modifier.offset(y = yOff),
                                                        color = if (num == "|") Color.Transparent else MaterialTheme.colorScheme.primaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(num, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Divider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                            Text("فک پایین (رباعی ۴ و ۳): 48 .. 41 | 31 .. 38", fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(vertical = 14.dp)) {
                                                listOf("48", "47", "46", "45", "44", "43", "42", "41", "|", "31", "32", "33", "34", "35", "36", "37", "38").forEachIndexed { index, num ->
                                                    val d = Math.abs(index - 8)
                                                    val yOff = (17.6f - d * 2.2f).dp
                                                    Surface(
                                                        modifier = Modifier.offset(y = yOff),
                                                        color = if (num == "|") Color.Transparent else MaterialTheme.colorScheme.secondaryContainer,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(num, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp), fontWeight = FontWeight.Bold)
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
            }
            }

            // ----------------------------------------------------
            // CARD 7: مناسبت‌ها و تقویم رویدادها (CALENDAR OCCASIONS & HOLIDAYS)
            // ----------------------------------------------------
            if (isCardVisible("calendar")) {
            item {
                SettingsCollapsibleCard(
                    icon = Icons.Default.Event,
                    title = if (isPersian) "مناسبت‌ها و تقویم" else "Calendar & Occasions",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("calendar"),
                    onToggle = { toggleSettingsKey("calendar") },
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "مناسبت‌ها و تقویم" else "Calendar & Occasions",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (isPersian) "نمایش مناسبت‌ها در تقویم داشبورد" else "Show occasions in dashboard calendar",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Switch(
                                checked = showOccasions,
                                onCheckedChange = { viewModel.setShowOccasions(it) }
                            )
                        }

                        Text(
                            text = if (isPersian) "تنظیمات تفکیکی نمایش مناسبت‌ها:" else "Detailed Occasion Settings:",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        run {
                            val showShamsiEvents by viewModel.showShamsiEvents.collectAsStateWithLifecycle()
                            val showQamariEvents by viewModel.showQamariEvents.collectAsStateWithLifecycle()
                            val showGlobalEvents by viewModel.showGlobalEvents.collectAsStateWithLifecycle()
                            val showPersonalEvents by viewModel.showPersonalEvents.collectAsStateWithLifecycle()
                            val showOfficialHolidays by viewModel.showOfficialHolidays.collectAsStateWithLifecycle()

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (isPersian) "🗓️ مناسبت‌های شمسی و ایران باستان" else "Iranian & Solar Events", fontSize = 11.sp)
                                Switch(checked = showShamsiEvents, onCheckedChange = { viewModel.setShowShamsiEvents(it) })
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (isPersian) "🌙 مناسبت‌های قمری و مذهبی" else "Islamic & Lunar Events", fontSize = 11.sp)
                                Switch(checked = showQamariEvents, onCheckedChange = { viewModel.setShowQamariEvents(it) })
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (isPersian) "🌐 روزهای جهانی و میلادی" else "Global & Gregorian Days", fontSize = 11.sp)
                                Switch(checked = showGlobalEvents, onCheckedChange = { viewModel.setShowGlobalEvents(it) })
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (isPersian) "🎂 مناسبت‌های شخصی (تولد، سالگرد و...)" else "Personal Occasions", fontSize = 11.sp)
                                Switch(checked = showPersonalEvents, onCheckedChange = { viewModel.setShowPersonalEvents(it) })
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(text = if (isPersian) "🔴 تعطیلات رسمی کشوری" else "Official Public Holidays", fontSize = 11.sp)
                                Switch(checked = showOfficialHolidays, onCheckedChange = { viewModel.setShowOfficialHolidays(it) })
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Text(
                            text = if (isPersian) "📌 ثبت مناسبت شخصی جدید (نمایش در تقویم داشبورد):" else "📌 Add New Personal Occasion:",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = personalOccasionTitle,
                            onValueChange = { personalOccasionTitle = it },
                            label = { Text(if (isPersian) "عنوان مناسبت (مثلاً: تولد دکتر احمدی)" else "Event Title") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = personalOccasionDayStr,
                                onValueChange = { personalOccasionDayStr = it },
                                label = { Text(if (isPersian) "روز (۱-۳۱) *" else "Day *") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = personalOccasionMonthStr,
                                onValueChange = { personalOccasionMonthStr = it },
                                label = { Text(if (isPersian) "ماه (۱-۱۲) *" else "Month *") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = personalOccasionYearStr,
                                onValueChange = { personalOccasionYearStr = it },
                                label = { Text(if (isPersian) "سال (اختیاری)" else "Year (Optional)") },
                                modifier = Modifier.weight(1.2f),
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (isPersian)
                                        "راهنما: با خالی گذاشتن فیلد «سال»، مناسبت هر سال تکرار می‌شود (مانند تولد و سالگرد). در صورت درج سال (مثلاً ۱۴۰۴)، فقط برای همان ۱ سال بدون تکرار ثبت می‌گردد."
                                    else
                                        "Guide: Leave 'Year' empty for annual recurrence. Enter year (e.g. 1404) for one-time event.",
                                    fontSize = 10.5.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = when(personalOccasionType) {
                                    "تولد" -> "🎂 تولد"
                                    "فوت" -> "🕯️ فوت"
                                    "ازدواج" -> "💍 ازدواج"
                                    "طلاق" -> "💔 طلاق"
                                    else -> "📌 سایر"
                                },
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(if (isPersian) "نوع مناسبت" else "Event Type") },
                                trailingIcon = { IconButton(onClick = { occasionTypeExpanded = true }) { Icon(Icons.Default.ArrowDropDown, contentDescription = null) } },
                                modifier = Modifier.fillMaxWidth().clickable { occasionTypeExpanded = true }
                            )
                            DropdownMenu(
                                expanded = occasionTypeExpanded,
                                onDismissRequest = { occasionTypeExpanded = false }
                            ) {
                                listOf("تولد" to "🎂 تولد", "فوت" to "🕯️ فوت", "ازدواج" to "💍 ازدواج", "طلاق" to "💔 طلاق", "سایر" to "📌 سایر").forEach { (typeKey, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label, fontSize = 12.sp) },
                                        onClick = {
                                            personalOccasionType = typeKey
                                            occasionTypeExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = if (isPersian) "تعطیل رسمی است؟" else "Is Official Holiday?", fontSize = 11.5.sp)
                            Switch(
                                checked = personalOccasionIsHoliday,
                                onCheckedChange = { personalOccasionIsHoliday = it }
                            )
                        }
                        OutlinedTextField(
                            value = personalOccasionDesc,
                            onValueChange = { personalOccasionDesc = it },
                            label = { Text(if (isPersian) "توضیحات تکمیلی (اختیاری)" else "Description (Optional)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val d = personalOccasionDayStr.trim().toEnglishIntOrNull()
                                val m = personalOccasionMonthStr.trim().toEnglishIntOrNull()
                                val y = personalOccasionYearStr.trim().toEnglishIntOrNull()
                                if (personalOccasionTitle.isNotBlank() && d != null && d in 1..31 && m != null && m in 1..12) {
                                    viewModel.addPersonalOccasion(
                                        title = personalOccasionTitle,
                                        day = d,
                                        month = m,
                                        year = y,
                                        personalType = personalOccasionType,
                                        isHoliday = personalOccasionIsHoliday,
                                        description = personalOccasionDesc
                                    )
                                    android.widget.Toast.makeText(context, if (isPersian) "مناسبت شخصی با موفقیت ثبت شد" else "Personal event registered", android.widget.Toast.LENGTH_SHORT).show()
                                    personalOccasionTitle = ""
                                    personalOccasionDayStr = ""
                                    personalOccasionMonthStr = ""
                                    personalOccasionYearStr = ""
                                    personalOccasionDesc = ""
                                } else {
                                    android.widget.Toast.makeText(context, if (isPersian) "لطفاً عنوان و روز (۱ تا ۳۱) و ماه (۱ تا ۱۲) معتبر وارد کنید" else "Please enter valid title, day (1-31) & month (1-12)", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isPersian) "ثبت مناسبت شخصی" else "Register Event", fontWeight = FontWeight.Bold)
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Text(
                            text = if (isPersian) "📁 ورودی / خروجی فایل CSV تقویم:" else "📁 Import / Export Calendar CSV:",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showCsvImportChoiceDialog = true },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isPersian) "ورودی CSV تقویم" else "Import CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    val csvContent = viewModel.exportCalendarEventsToCsv()
                                    val file = java.io.File(context.cacheDir, "calendar_events.csv")
                                    val bom = "\uFEFF"
                                    file.writeText(bom + csvContent, java.nio.charset.StandardCharsets.UTF_8)
                                    val uri = com.example.reports.fileProviderUri(context, file)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(Intent.createChooser(shareIntent, "خروجی CSV مناسبت‌های تقویم"))
                                    } catch (e: Exception) {}
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isPersian) "خروجی CSV تقویم" else "Export CSV", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        // چک‌باکس «نمایش گزینه‌های پیشرفته در سایر بخش‌ها» حذف شد (درخواست ۱۷)
                        // مدیریت نمایش کارت‌ها از منوی مخفی (۳ ثانیه نگه‌داشتن چرخ‌دنده) انجام می‌شود
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (isPersian)
                                        "💡 برای مدیریت نمایش کارت‌های تنظیمات، آیکون چرخ‌دنده بالای صفحه را ۳ ثانیه نگه دارید."
                                    else
                                        "💡 Hold the settings gear at the top for 3 seconds to manage card visibility.",
                                    fontSize = 10.5.sp,
                                    lineHeight = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            }

// ----------------------------------------------------
            // SECTION 5.5: تنظیمات اختصاصی — الگوهای درمان، درآمد ثابت، سایز فونت
            // ----------------------------------------------------
            if (isCardVisible("app_settings")) {
            item {
                var showFixedIncomeFromSettings by remember { mutableStateOf(false) }
                var showSubAccountsFromSettings by remember { mutableStateOf(false) }
                var showApptTemplateDialog by remember { mutableStateOf(false) }

                SettingsCollapsibleCard(
                    icon = Icons.Default.Tune,
                    title = if (isPersian) "تنظیمات اختصاصی نرم‌افزار" else "Advanced App Settings",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("app_settings"),
                    onToggle = { toggleSettingsKey("app_settings") },
                    cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "تنظیمات اختصاصی نرم‌افزار مطب" else "Advanced App Settings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { showApptTemplateDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Message, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "الگوهای پیامک و اطلاع‌رسانی" else "SMS & Notification Templates", fontSize = 11.sp)
                            }
                            var showRegionDialog by remember { mutableStateOf(false) }
                            var showEditRegionDialog by remember { mutableStateOf(false) }
                            OutlinedButton(onClick = { showRegionDialog = true }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "منطقه جغرافیایی" else "Region Settings", fontSize = 11.sp)
                            }
                            
                            if (showEditRegionDialog) {
                                EditRegionAnatomyDialog(
                                    isPersian = isPersian,
                                    region = EndoAnatomyManager.selectedRegion,
                                    viewModel = viewModel,
                                    onDismiss = { showEditRegionDialog = false }
                                )
                            }

                            if (showRegionDialog) {
                                AlertDialog(
                                    onDismissRequest = { showRegionDialog = false },
                                    title = { Text(if (isPersian) "تنظیم منطقه جغرافیایی" else "Set Geographic Region", fontWeight = FontWeight.Bold) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(if (isPersian) "منطقه فعالیت خود را برای تعیین درصد فراوانی کانال‌های اضافی انتخاب کنید:" else "Select your region to adjust endodontic channel probabilities:")
                                            EndoAnatomyManager.getAvailableRegions().forEach { region ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().clickable {
                                                        EndoAnatomyManager.selectedRegion = region
                                                        showRegionDialog = false
                                                    }.padding(vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    RadioButton(
                                                        selected = EndoAnatomyManager.selectedRegion == region,
                                                        onClick = {
                                                            EndoAnatomyManager.selectedRegion = region
                                                            showRegionDialog = false
                                                        }
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(region)
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { showRegionDialog = false }) {
                                            Text(if (isPersian) "بستن" else "Close")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showRegionDialog = false; showEditRegionDialog = true }) {
                                            Text(if (isPersian) "ویرایش مقادیر پیش‌فرض" else "Edit Defaults")
                                        }
                                    }
                                )
                            }                            
                        }



                        
                        if (advancedSettingsRevealed) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { showFixedIncomeFromSettings = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isPersian) "درآمد ثابت ماهانه" else "Fixed Monthly Income", fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { viewModel.currentTab.value = "templates" }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "الگوهای آماده درمان" else "Treatment Templates", fontSize = 11.sp)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { showSubAccountsFromSettings = true },
                                modifier = if (advancedSettingsRevealed) Modifier.weight(1f) else Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "سرفصل‌های شخصی" else "Personal Sub-Accounts", fontSize = 11.sp)
                            }
                            if (advancedSettingsRevealed) {
                                OutlinedButton(onClick = { viewModel.currentTab.value = "drug_templates" }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.MedicalServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isPersian) "فایل‌های دارویی" else "Drug Templates", fontSize = 11.sp)
                                }
                            }
                        }

                        // Export/Import — الگوهای آماده درمان (یکجا)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { exportTemplatesToCsv(context, viewModel.templates.value) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "خروجی الگوهای درمان" else "Export Templates", fontSize = 10.sp)
                            }
                            OutlinedButton(
                                onClick = { showImportTemplatesPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "ورودی الگوهای درمان" else "Import Templates", fontSize = 10.sp)
                            }
                        }
                        // Export/Import — سرفصل‌های شخصی (یکجا)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { exportSubAccountsToCsv(context, viewModel.personalSubAccounts.value) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "خروجی سرفصل‌ها" else "Export Sub-Accounts", fontSize = 10.sp)
                            }
                            OutlinedButton(
                                onClick = { showImportSubAccountsPicker = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isPersian) "ورودی سرفصل‌ها" else "Import Sub-Accounts", fontSize = 10.sp)
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Text(
                            text = if (isPersian) "چیدمان و نمایش کارت‌های داشبورد اصلی:" else "Dashboard Widgets Display Preferences:",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (isPersian) "نمایش کارت خلاصه وضعیت مالی" else "Display Financial Summary Card", fontSize = 11.sp)
                            Switch(checked = showSummary, onCheckedChange = { showSummary = it })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (isPersian) "نمایش کلیدهای میانبر ثبت سریع" else "Display Quick Shortcuts Row", fontSize = 11.sp)
                            Switch(checked = showQuick, onCheckedChange = { showQuick = it })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(text = if (isPersian) "نمایش نمودارهای تحلیلی مالی" else "Display Data Analytical Charts", fontSize = 11.sp)
                            Switch(checked = showCharts, onCheckedChange = { showCharts = it })
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (isPersian) "سایز فونت کلی برنامه" else "App-wide Font Size",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isPersian) "روی صفحات دستورالعمل بالینی اثر ندارد — آن‌ها سایز فونت اختصاصی خودشان را دارند."
                                       else "Does not affect the clinical guideline pages — those have their own font control.",
                                fontSize = 9.sp,
                                color = Color.Gray
                            )
                            Slider(
                                value = fontScaleIdx.toFloat(),
                                onValueChange = {
                                    val newIdx = it.toInt()
                                    fontScaleIdx = newIdx
                                    viewModel.updateGlobalFontScale(newIdx)
                                },
                                valueRange = 0f..19f,
                                steps = 18
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = if (isPersian) "سایز فونت صفحات دستورالعمل/شرح بالینی" else "Clinical Guideline Pages Font Size",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Slider(
                                value = clinicalFontIdx.toFloat(),
                                onValueChange = {
                                    val newIdx = it.toInt()
                                    clinicalFontIdx = newIdx
                                    viewModel.updateClinicalFontSize(newIdx + 8)
                                },
                                valueRange = 0f..19f,
                                steps = 18
                            )
                        }
                    }
                }

                if (showFixedIncomeFromSettings) {
                    ManageFixedIncomesDialog(viewModel = viewModel, isPersian = isPersian, onDismiss = { showFixedIncomeFromSettings = false })
                }
                
                if (showApptTemplateDialog) {
                    SmsTemplatesDialog(
                        isPersian = isPersian,
                        clinicName = clinicName,
                        apptTemplate = apptTemplate, onApptChange = { apptTemplate = it },
                        invoiceTemplate = invoiceTemplate, onInvoiceChange = { invoiceTemplate = it },
                        treatmentPlanTemplate = treatmentPlanTemplate, onTreatmentPlanChange = { treatmentPlanTemplate = it },
                        installmentTemplate = installmentTemplate, onInstallmentChange = { installmentTemplate = it },
                        overdueInstallmentTemplate = overdueInstallmentTemplate, onOverdueInstallmentChange = { overdueInstallmentTemplate = it },
                        postOpTemplate = postOpTemplate, onPostOpChange = { postOpTemplate = it },
                        onDismiss = { showApptTemplateDialog = false }
                    )
                }
                if (showSubAccountsFromSettings) {
                    ManageSubAccountsDialog(viewModel = viewModel, isPersian = isPersian, onDismiss = { showSubAccountsFromSettings = false })
                }
            }
            }

// ----------------------------------------------------
            // SECTION 2: USER ROLE & ACCESS CONTROL (RBAC)
            // ----------------------------------------------------
            if (isCardVisible("rbac")) {
            item {
                SettingsCollapsibleCard(
                    icon = Icons.Default.Badge,
                    title = if (isPersian) "نقش کاربری" else "User Role",
                    isPersian = isPersian,
                    isExpanded = expandedSettingsKeys.contains("rbac"),
                    onToggle = { toggleSettingsKey("rbac") },
                    cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "نقش کاربری و سطح دسترسی فعال" else "User Role & Access Control",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = if (isPersian) "جهت تغییر نقش یا مدیریت حساب کاربری، به بخش حساب کاربری مراجعه کنید:" else "To change roles or manage your account, visit the Account Menu:",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val role = currentUserRole
                        Button(
                            onClick = onNavigateToAccount,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                        ) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (isPersian) "نقش فعلی: ${role.titleFa}" else "Current Role: ${role.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    if (role == UserRole.DOCTOR) {
                                        Icon(Icons.Default.Lock, contentDescription = "Protected", modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Text(
                                    text = if (isPersian) "رفتن به حساب کاربری" else "Go to Account Menu",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
            }

            // ----------------------------------------------------
            // CARD: چیدمان کارت‌های داشبورد (درخواست ۱۰) — ترتیب نمایش در داشبورد
            // ----------------------------------------------------
            if (isCardVisible("dashboard_layout")) {
                item {
                    SettingsCollapsibleCard(
                        icon = Icons.Default.Dashboard,
                        title = if (isPersian) "چیدمان کارت‌های داشبورد" else "Dashboard Card Order",
                        isPersian = isPersian,
                        isExpanded = expandedSettingsKeys.contains("dashboard_layout"),
                        onToggle = { toggleSettingsKey("dashboard_layout") },
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = if (isPersian) "ترتیب نمایش کارت‌ها را با دکمه‌های بالا/پایین تعیین کنید (از بالا به پایین):"
                                else "Set the card display order with the up/down buttons (top to bottom):",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // درخواست ۱-۱: سوییچ «دیدگاه پزشک/منشی» (VIEW_SWITCH) کارکرد مشخصی نداشت و حذف شد.
                            val dashKeys = listOf("HERO", "SUMMARY", "CHARTS", "QUICK_ACTIONS", "CALENDAR")
                            val dashLabels = mapOf(
                                "HERO" to (if (isPersian) "بنر بالای داشبورد" else "Hero Banner"),
                                "SUMMARY" to (if (isPersian) "خلاصه وضعیت مالی" else "Financial Summary"),
                                "CHARTS" to (if (isPersian) "نمودار مالی" else "Financial Chart"),
                                "QUICK_ACTIONS" to (if (isPersian) "ثبت سریع" else "Quick Actions"),
                                "CALENDAR" to (if (isPersian) "تقویم هفتگی" else "Weekly Calendar")
                            )
                            val currentDashOrder = remember(settings.dashboardCardOrder) {
                                val custom = settings.dashboardCardOrder.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                if (custom.isNotEmpty() && custom.all { dashKeys.contains(it) }) custom else dashKeys
                            }
                            var dashOrder by remember { mutableStateOf(currentDashOrder) }
                            fun moveDashItem(index: Int, delta: Int) {
                                val newIndex = (index + delta).coerceIn(0, dashOrder.size - 1)
                                if (newIndex == index) return
                                val list = dashOrder.toMutableList()
                                val item = list.removeAt(index)
                                list.add(newIndex, item)
                                dashOrder = list
                                viewModel.updateDashboardCardOrder(list.joinToString(","))
                            }
                            dashOrder.forEachIndexed { index, key ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("${index + 1}.", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Text(dashLabels[key] ?: key, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { moveDashItem(index, -1) }, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Up", modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(onClick = { moveDashItem(index, 1) }, enabled = index < dashOrder.size - 1, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Down", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            TextButton(onClick = {
                                dashOrder = dashKeys
                                viewModel.updateDashboardCardOrder("")
                            }) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isPersian) "بازنشانی به حالت پیش‌فرض" else "Reset to default", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

// ----------------------------------------------------
            // SECTION 6: پاکسازی تمام اطلاعات ورودی و دیتاها
            // (فقط با لمس ۳ثانیه‌ای چرخ‌دنده — بخشی از منوی مخفی تنظیمات)
            // ----------------------------------------------------

            if (isCardVisible("clear_data")) {
                item {
                    SettingsCollapsibleCard(
                        icon = Icons.Default.DeleteForever,
                        title = if (isPersian) "پاکسازی اطلاعات" else "Clear Data",
                        isPersian = isPersian,
                        isExpanded = expandedSettingsKeys.contains("clear_data"),
                        onToggle = { toggleSettingsKey("clear_data") },
                        cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)),
                        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Text(
                                    text = if (isPersian) "پاکسازی تمام اطلاعات ورودی و دیتاها" else "Clear All Input Data",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                text = if (isPersian) "حذف کامل کلیه بیماران، هزینه ها، فاکتورها، جلسات، درآمدها"
                                       else "Purge all patient profiles, treatments, payments, invoices, sessions and expenses.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Button(
                                onClick = { showClearDataConfirm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isPersian) "پاکسازی تمام اطلاعات ورودی و دیتاها" else "Clear All Input Data", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        viewModel.updateClinicSettings(
                            settings.copy(
                                clinicName = clinicName.trim(),
                                clinicPhone = clinicPhone.trim(),
                                clinicAddress = clinicAddress.trim(),
                                clinicMapLink = encodeClinicMapLinks(googleMapsUrl, wazeMapsUrl, neshanMapsUrl, baladMapsUrl),
                                showSummaryCard = showSummary,
                                showQuickActions = showQuick,
                                showCharts = showCharts,
                                globalFontScaleIndex = fontScaleIdx,
                                clinicalFontSizePt = clinicalFontIdx + 8,
                                appointmentTemplate = apptTemplate,
                                invoiceTemplate = invoiceTemplate,
                                treatmentPlanTemplate = treatmentPlanTemplate,
                                installmentTemplate = installmentTemplate,
                                overdueInstallmentTemplate = overdueInstallmentTemplate,
                                postOpTemplate = postOpTemplate
                            )
                        )
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (isPersian) "ذخیره تمامی تنظیمات مطب" else "Save All Settings", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // BACKUP PREVIEW DIALOG
    if (backupJsonPreview != null) {
        AlertDialog(
            onDismissRequest = { backupJsonPreview = null },
            title = { Text(if (isPersian) "پشتیبان‌گیری کامل انجام شد" else "Backup Created Successfully", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isPersian) "فایل نسخه پشتیبان آماده است:" else "Backup data successfully serialized:",
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = backupJsonPreview ?: "",
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { backupJsonPreview = null }) {
                    Text(if (isPersian) "تایید و بستن" else "OK")
                }
            }
        )
    }

    // RESTORE BACKUP DIALOG
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            title = { Text(if (isPersian) "بازگردانی دیتابیس از فایل بک‌آپ" else "Restore Database from Backup", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isPersian) " " else " :",
                        fontSize = 11.sp
                    )
                    OutlinedTextField(
                        value = restoreJsonInput,
                        onValueChange = { restoreJsonInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                    if (restoreStatusMsg != null) {
                        Text(
                            text = restoreStatusMsg!!,
                            color = if (restoreStatusMsg!!.contains("موفق")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val success = viewModel.restoreFromBackupJson(restoreJsonInput, backupConfig)
                            if (success) {
                                restoreStatusMsg = if (isPersian) "اطلاعات با موفقیت بازگردانی شد." else "Database restored successfully!"
                            } else {
                                restoreStatusMsg = if (isPersian) "خطا در فرمت یا ساختار فایل JSON بک‌آپ." else "Invalid JSON format."
                            }
                        }
                    }
                ) {
                    Text(if (isPersian) "شروع بازگردانی" else "Restore Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    // CLOUD RESTORE CONFIRMATION DIALOG
    val currentCloudRec = cloudRestoreConfirmRecord
    if (currentCloudRec != null) {
        AlertDialog(
            onDismissRequest = { cloudRestoreConfirmRecord = null },
            title = { Text(if (isPersian) "تایید بازیابی از نسخه ابری" else "Confirm Cloud Restore", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (isPersian) "آیا از بازیابی اطلاعات دیتابیس از این نسخه ابری مطمئن هستید؟" else "Restore database from this cloud backup record?", fontSize = 12.sp)
                    Text("دستگاه: ${currentCloudRec.deviceName}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("تاریخ: ${viewModel.formatDate(currentCloudRec.timestamp)}", fontSize = 11.sp)
                    Text("خلاصه دیتابیس: ${currentCloudRec.recordSummary}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val encryptedPayload = viewModel.downloadCloudBackupPayload(currentCloudRec.id)
                                val restored = viewModel.restoreFromBackupJson(encryptedPayload, backupConfig)
                                check(restored) { "Backup content was rejected" }
                                cloudRestoreConfirmRecord = null
                                android.widget.Toast.makeText(context, if (isPersian) "نسخه از سرور دریافت و بازیابی شد" else "Backup downloaded and restored", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (error: Exception) {
                                android.widget.Toast.makeText(context, if (isPersian) "بازیابی ابری ناموفق بود: ${error.message}" else "Cloud restore failed: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text(if (isPersian) "بازیابی ابری" else "Restore Cloud Backup")
                }
            },
            dismissButton = {
                TextButton(onClick = { cloudRestoreConfirmRecord = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    // CONFIRM DATA CLEAR / DUMMY PURGE DIALOG
    if (showClearDataConfirm) {
        AlertDialog(
            onDismissRequest = { showClearDataConfirm = false },
            title = { Text(if (isPersian) "تایید پاکسازی تمام اطلاعات ورودی و دیتاها" else "Confirm Clear All Input Data", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
            text = {
                Text(
                    if (isPersian) "آیا مطمئن هستید که می‌خواهید تمام بیماران، جلسات، فاکتورها، پرونده‌ها و هزینه‌های ثبت شده فرضی را حذف کرده و همه مبالغ را صفر کنید؟ این عمل غیرقابل بازگشت است."
                    else "Are you sure you want to delete all dummy patients, appointments, treatments, payments, invoices and zero out all values? This action cannot be undone.",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllClinicalData()
                        showClearDataConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "بله، پاکسازی و صفر شود" else "Yes, Clear All Data", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataConfirm = false }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }
}

// ==========================================
// 8. COGNITIVE CALENDAR SCHEDULER BOARD
// ==========================================
@Composable
fun InteractiveAppointmentsCalendar(
    viewModel: DentalViewModel,
    isPersian: Boolean
) {
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val showOccasions by viewModel.showOccasions.collectAsStateWithLifecycle()
    val clinicSettings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Calendar View Mode: "GRID" (Monthly Shamsi Grid) or "LIST" (Rolling Days)
    // درخواست ۱-۴: نمای پیش‌فرض تقویم «روزشمار» (LIST) باشد.
    var calendarViewMode by remember { mutableStateOf("LIST") }
    var showCalendarSettings by remember { mutableStateOf(false) }

    val calendarType by viewModel.calendarType.collectAsStateWithLifecycle()
    val calendarSystem = remember(calendarType) {
        when (calendarType) {
            "QAMARI" -> com.example.util.IslamicCalendarSystem
            "MILADI" -> com.example.util.GregorianCalendarSystem
            else -> com.example.util.JalaliCalendarSystem
        } as com.example.util.CalendarSystem
    }

    // Today Date
    val todaySys = remember(calendarSystem) { calendarSystem.getToday() }

    // Monthly Grid State
    var gridYear by remember(calendarSystem) { mutableStateOf(todaySys.first) }
    var gridMonth by remember(calendarSystem) { mutableStateOf(todaySys.second) }
    var selectedGridDay by remember(calendarSystem) { mutableStateOf(todaySys.third) }

    var daysCount by remember { mutableStateOf(7) }
    var expandedDayKeys by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val todayCal = remember {
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
    }

    val rollingDays = remember(todayCal, isPersian, daysCount) {
        (0 until daysCount).map { dayOffset ->
            val c = (todayCal.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
            }
            val dayOfWeekName = when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.SATURDAY -> if (isPersian) "شنبه" else "Sat"
                java.util.Calendar.SUNDAY -> if (isPersian) "یکشنبه" else "Sun"
                java.util.Calendar.MONDAY -> if (isPersian) "دوشنبه" else "Mon"
                java.util.Calendar.TUESDAY -> if (isPersian) "سه‌شنبه" else "Tue"
                java.util.Calendar.WEDNESDAY -> if (isPersian) "چهارشنبه" else "Wed"
                java.util.Calendar.THURSDAY -> if (isPersian) "پنج‌شنبه" else "Thu"
                java.util.Calendar.FRIDAY -> if (isPersian) "جمعه" else "Fri"
                else -> ""
            }
            val label = when (dayOffset) {
                0 -> if (isPersian) "امروز ($dayOfWeekName)" else "Today ($dayOfWeekName)"
                1 -> if (isPersian) "فردا ($dayOfWeekName)" else "Tomorrow ($dayOfWeekName)"
                else -> "$dayOfWeekName (${viewModel.formatDate(c.timeInMillis)})"
            }
            Triple(dayOffset, label, c)
        }
    }

    var selectedApptForReschedule by remember { mutableStateOf<Appointment?>(null) }
    var sessionApptToShow by remember { mutableStateOf<Appointment?>(null) }
    var showRescheduleHolidayConfirmDialog by remember { mutableStateOf<String?>(null) }
    var rescheduleConflictMessage by remember { mutableStateOf<String?>(null) }
    var showOccasionSearchDialog by remember { mutableStateOf(false) }

    if (showOccasionSearchDialog) {
        com.example.ui.components.OccasionSearchDialog(
            viewModel = viewModel,
            onDismiss = { showOccasionSearchDialog = false }
        )
    }
    
    // Reschedule time selection dialog state
    var rescheduleTargetCal by remember { mutableStateOf<java.util.Calendar?>(null) }
    var rescheduleHour by remember { mutableStateOf(16) }
    var rescheduleMinute by remember { mutableStateOf(0) }

    // تقویم پاپ‌آپ شمسی برای تغییر تاریخ نوبت (جایگزین DatePickerDialog میلادی)
    var showRescheduleJalaliPicker by remember { mutableStateOf(false) }
    if (showRescheduleJalaliPicker) {
        com.example.ui.screens.JalaliDatePickerDialog(
            initialTimestamp = selectedApptForReschedule?.date ?: System.currentTimeMillis(),
            isPersian = isPersian,
            onDateSelected = { jy, jm, jd ->
                val c = java.util.Calendar.getInstance().apply {
                    timeInMillis = com.example.util.PersianCalendarHelper.jalaliToTimestamp(jy, jm, jd)
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                rescheduleTargetCal = c
                val appt = selectedApptForReschedule
                if (appt != null) {
                    val apptCal = java.util.Calendar.getInstance().apply { timeInMillis = appt.date }
                    rescheduleHour = apptCal.get(java.util.Calendar.HOUR_OF_DAY)
                    rescheduleMinute = apptCal.get(java.util.Calendar.MINUTE)
                }
                showRescheduleJalaliPicker = false
            },
            onDismiss = { showRescheduleJalaliPicker = false }
        )
    }

    if (sessionApptToShow != null) {
        SessionDetailsDialog(
            appointment = sessionApptToShow!!,
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { sessionApptToShow = null }
        )
    }

    // Time Selection Dialog for Rescheduling
    if (selectedApptForReschedule != null && rescheduleTargetCal != null) {
        val targetAppt = selectedApptForReschedule!!
        val targetCal = rescheduleTargetCal!!

        val timePickerDialog = remember(rescheduleHour, rescheduleMinute) {
            android.app.TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    rescheduleHour = hourOfDay
                    rescheduleMinute = minute
                },
                rescheduleHour,
                rescheduleMinute,
                true
            )
        }

        val calculatedFinalMills = remember(rescheduleHour, rescheduleMinute, targetCal) {
            (targetCal.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, rescheduleHour)
                set(java.util.Calendar.MINUTE, rescheduleMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        val fullPreviewText = viewModel.formatDateFullVerbalWithTime(calculatedFinalMills)

        AlertDialog(
            onDismissRequest = { rescheduleTargetCal = null },
            title = {
                Text(
                    text = if (isPersian) "تنظیم ساعت جدید جلسه" else "Set New Session Time",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isPersian) "تغییر زمان نوبت: ${targetAppt.patientName}" else "Reschedule Appointment for: ${targetAppt.patientName}",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = if (isPersian) "تاریخ انتخاب شده: ${viewModel.formatDateFullVerbal(targetCal.timeInMillis)}" else "Selected Date: ${viewModel.formatDateFullVerbal(targetCal.timeInMillis)}",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )

                    val timeFormattedStr = String.format(java.util.Locale.US, "%02d:%02d", rescheduleHour, rescheduleMinute)
                    OutlinedTextField(
                        value = timeFormattedStr,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(if (isPersian) "ساعت جدید جلسه" else "New Session Time") },
                        modifier = Modifier.fillMaxWidth().clickable { timePickerDialog.show() },
                        trailingIcon = {
                            IconButton(onClick = { timePickerDialog.show() }) {
                                Icon(Icons.Default.AccessTime, contentDescription = "Pick Time")
                            }
                        }
                    )

                    // Quick Hour Chips
                    Text(if (isPersian) "انتخاب سریع ساعت:" else "Quick Time Select:", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20).forEach { hr ->
                            FilterChip(
                                selected = rescheduleHour == hr && rescheduleMinute == 0,
                                onClick = {
                                    rescheduleHour = hr
                                    rescheduleMinute = 0
                                },
                                label = { Text(String.format(java.util.Locale.US, "%02d:00", hr), fontSize = 10.sp) }
                            )
                        }
                    }

                    // Red calculated date/time preview text
                    Surface(
                        color = Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = fullPreviewText,
                            color = Color.Red,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val reason = viewModel.getHolidayOrNonWorkingReason(calculatedFinalMills)
                        if (reason != null) {
                            showRescheduleHolidayConfirmDialog = reason
                        } else {
                            val conflictResult = com.example.util.AppointmentConflictDetector.checkConflict(
                                targetDate = calculatedFinalMills,
                                durationMinutes = targetAppt.effectiveDurationMinutes,
                                excludeAppointmentId = targetAppt.id,
                                existingAppointments = appointments,
                                unit = targetAppt.normalizedUnit()
                            )
                            if (conflictResult.hasConflict) {
                                rescheduleConflictMessage = if (isPersian) conflictResult.messageFa else conflictResult.messageEn
                            } else {
                                val updatedAppt = targetAppt.copy(date = calculatedFinalMills)
                                viewModel.updateAppointment(updatedAppt)
                                selectedApptForReschedule = null
                                rescheduleTargetCal = null
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isPersian) "تایید و ثبت زمان جدید" else "Confirm & Reschedule", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { rescheduleTargetCal = null }, shape = RoundedCornerShape(8.dp)) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    if (showRescheduleHolidayConfirmDialog != null && selectedApptForReschedule != null && rescheduleTargetCal != null) {
        val reason = showRescheduleHolidayConfirmDialog!!
        val appt = selectedApptForReschedule!!
        val targetCal = rescheduleTargetCal!!
        val finalMills = (targetCal.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, rescheduleHour)
            set(java.util.Calendar.MINUTE, rescheduleMinute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val dateVerbal = viewModel.formatDateFullVerbalWithTime(finalMills)

        AlertDialog(
            onDismissRequest = { showRescheduleHolidayConfirmDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F))
                    Text(
                        text = if (isPersian) "تغییر زمان نوبت به روز تعطیل / غیرکاری" else "Reschedule to Off Day",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFFD32F2F)
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isPersian) "زمان جدید نوبت برای «${appt.patientName}» ($dateVerbal) مصادف با «$reason» می‌باشد."
                        else "New appointment date ($dateVerbal) is: '$reason'.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isPersian) "آیا از انتقال و تغییر زمان نوبت به این روز تعطیل یا غیرکاری مطب اطمینان دارید؟"
                        else "Are you sure you want to reschedule this appointment to an off day?",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val conflictResult = com.example.util.AppointmentConflictDetector.checkConflict(
                            targetDate = finalMills,
                            durationMinutes = appt.effectiveDurationMinutes,
                            excludeAppointmentId = appt.id,
                            existingAppointments = appointments,
                            unit = appt.normalizedUnit()
                        )
                        if (conflictResult.hasConflict) {
                            rescheduleConflictMessage = if (isPersian) conflictResult.messageFa else conflictResult.messageEn
                            showRescheduleHolidayConfirmDialog = null
                        } else {
                            val updatedAppt = appt.copy(date = finalMills)
                            viewModel.updateAppointment(updatedAppt)
                            selectedApptForReschedule = null
                            rescheduleTargetCal = null
                            showRescheduleHolidayConfirmDialog = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text(if (isPersian) "بله، جابه‌جایی نوبت" else "Yes, Reschedule", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRescheduleHolidayConfirmDialog = null }) {
                    Text(if (isPersian) "خیر / انصراف" else "Cancel")
                }
            }
        )
    }

    if (rescheduleConflictMessage != null) {
        AlertDialog(
            onDismissRequest = { rescheduleConflictMessage = null },
            title = {
                Text(
                    text = if (isPersian) "تداخل زمان نوبت" else "Appointment Time Conflict",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F)
                )
            },
            text = { Text(rescheduleConflictMessage ?: "", fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = { rescheduleConflictMessage = null }) {
                    Text(if (isPersian) "بستن" else "Close")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row: Title & View Mode Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPersian) "تقویم شمسی و مدیریت نوبت‌ها" else "Persian Calendar & Appointments Board",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Mode Toggle Switcher
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Surface(
                        color = if (calendarViewMode == "GRID") MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { calendarViewMode = "GRID" }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (calendarViewMode == "GRID") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isPersian) "تقویم ماهانه" else "Monthly Grid",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (calendarViewMode == "GRID") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        color = if (calendarViewMode == "LIST") MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { calendarViewMode = "LIST" }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.ViewList,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = if (calendarViewMode == "LIST") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isPersian) "روزشمار" else "Days List",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (calendarViewMode == "LIST") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

            }

            if (selectedApptForReschedule != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isPersian) "تغییر زمان نوبت ${selectedApptForReschedule?.patientName}. یک روز را در تقویم لمس کنید:"
                                else "Rescheduling ${selectedApptForReschedule?.patientName}. Pick a day:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { showRescheduleJalaliPicker = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isPersian) "انتخاب تاریخ دلخواه (تقویم میلادی/شمسی)" else "Pick Custom Date", fontSize = 10.sp)
                            }
                        }
                        IconButton(onClick = { selectedApptForReschedule = null }) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            

            // MODE 1: FULL SHAMSI MONTHLY CALENDAR GRID (تقویم ماهانه شمسی)
            if (calendarViewMode == "GRID") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Month & Year Selector Header
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (gridMonth > 1) {
                                        gridMonth--
                                    } else {
                                        gridMonth = 12
                                        gridYear--
                                    }
                                    selectedGridDay = 1
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "Previous Month", tint = MaterialTheme.colorScheme.primary)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${calendarSystem.getMonthName(gridMonth)} $gridYear",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable {
                                        gridYear = todaySys.first
                                        gridMonth = todaySys.second
                                        selectedGridDay = todaySys.third
                                    }
                                ) {
                                    Text(
                                        text = if (isPersian) "امروز" else "Today",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                // Occasion Search Button
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable {
                                        showOccasionSearchDialog = true
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(Icons.Default.Search, contentDescription = "Search Occasions", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(12.dp))
                                        Text(
                                            text = if (isPersian) "مناسبت‌ها" else "Occasions",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = {
                                    if (gridMonth < 12) {
                                        gridMonth++
                                    } else {
                                        gridMonth = 1
                                        gridYear++
                                    }
                                    selectedGridDay = 1
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "Next Month", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // Weekdays Headers Row (شنبه تا جمعه)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        calendarSystem.getWeekdayNames().forEachIndexed { index, dayName ->
                            val isFriday = index == 6
                            Text(
                                text = dayName,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = if (isFriday) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                    // Calendar Days Grid (7 Columns)
                    val daysInMonth = calendarSystem.getDaysInMonth(gridYear, gridMonth)
                    val startOffset = calendarSystem.getDayOfWeekIndex(gridYear, gridMonth, 1) // 0..6
                    val totalGridCells = startOffset + daysInMonth
                    val rowCount = (totalGridCells + 6) / 7

                    // Active Working Days Set
                    val activeWorkingDays = remember(clinicSettings.workingDays) {
                        clinicSettings.workingDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (r in 0 until rowCount) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (c in 0 until 7) {
                                    val cellIndex = r * 7 + c
                                    val dayNum = cellIndex - startOffset + 1

                                    if (dayNum in 1..daysInMonth) {
                                                                                val dayTs = calendarSystem.toTimestamp(gridYear, gridMonth, dayNum)
                                        val gridJDate = PersianCalendarHelper.timestampToJalali(dayTs)
                                        val isHoliday = viewModel.isCustomHoliday(gridJDate.year, gridJDate.month, gridJDate.day)
                                        
                                        val occasions = viewModel.getCustomOccasionsForDay(gridJDate.year, gridJDate.month, gridJDate.day)
                                        val hasPersonalOccasion = occasions.any { it.isPersonal }
                                        val isWorkingDay = c in activeWorkingDays
                                        val isToday = (gridYear == todaySys.first && gridMonth == todaySys.second && dayNum == todaySys.third)
                                        val isSelected = (dayNum == selectedGridDay)
                                        val dayAppts = appointments.filter { appt ->
                                            val tSys = calendarSystem.fromTimestamp(appt.date)
                                            tSys.first == gridYear && tSys.second == gridMonth && tSys.third == dayNum
                                        }

                                        val containerColor = when {
                                            hasPersonalOccasion && !isSelected -> Color(0xFFFFF3E0) // Soft orange for personal occasions
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            isToday -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                                            isHoliday -> Color(0xFFFFEBEE) // Red tint for official holiday / Friday
                                            !isWorkingDay -> Color(0xFFF2F2F2) // Grey tint for non-working day
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                        }

                                        val borderColor = when {
                                            hasPersonalOccasion -> Color(0xFFFF9800) // Orange border
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            isToday -> MaterialTheme.colorScheme.secondary
                                            isHoliday -> Color(0xFFE53935) // Red border for holidays
                                            !isWorkingDay -> Color(0xFFD0D0D0) // Grey border for non-working days
                                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                        }

                                        val textColor = when {
                                            isHoliday -> Color(0xFFD32F2F) // RED text for holidays
                                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                            !isWorkingDay -> Color(0xFF757575) // Muted text for off-days
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }

                                        Surface(
                                            color = containerColor,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(if (isSelected || isToday) 1.5.dp else 1.dp, borderColor),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(52.dp)
                                                .clickable {
                                                    selectedGridDay = dayNum
                                                    val targetAppt = selectedApptForReschedule
                                                    if (targetAppt != null) {
                                                        val newTs = calendarSystem.toTimestamp(gridYear, gridMonth, dayNum, rescheduleHour, rescheduleMinute)
                                                        val cCal = java.util.Calendar.getInstance().apply { timeInMillis = newTs }
                                                        rescheduleTargetCal = cCal
                                                    }
                                                }
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .padding(2.dp)
                                                    .fillMaxSize(),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "$dayNum",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = if (isHoliday || isToday || isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = textColor
                                                    )
                                                    if (isHoliday) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(5.dp)
                                                                .background(Color(0xFFE53935), CircleShape)
                                                        )
                                                    }
                                                }

                                                if (dayAppts.isNotEmpty()) {
                                                    Surface(
                                                        color = if (isHoliday) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "${dayAppts.size} " + if (isPersian) "نوبت" else "appt",
                                                            fontSize = 8.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp)
                                                        )
                                                    }
                                                } else {
                                                    
                                                    if (occasions.isNotEmpty() && showOccasions) {
                                                        Text(
                                                            text = occasions.first().titleFa.take(10),
                                                            fontSize = 7.5.sp,
                                                            maxLines = 1,
                                                            color = if (isHoliday) Color(0xFFD32F2F) else Color.Gray
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // Empty cell for alignment offset
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    // Selected Day Detailed Inspector Panel
                    val selectedWeekdayIndex = calendarSystem.getDayOfWeekIndex(gridYear, gridMonth, selectedGridDay)
                    val selectedWeekdayName = calendarSystem.getWeekdayNames()[selectedWeekdayIndex]
                    val selTs = calendarSystem.toTimestamp(gridYear, gridMonth, selectedGridDay)
                    val selJDate = PersianCalendarHelper.timestampToJalali(selTs)
                    val selectedOccasions = viewModel.getCustomOccasionsForDay(selJDate.year, selJDate.month, selJDate.day)
                    val isSelectedDayHoliday = viewModel.isCustomHoliday(selJDate.year, selJDate.month, selJDate.day)

                    val matchedGridAppts = appointments.filter { appt ->
                        val tSys = calendarSystem.fromTimestamp(appt.date)
                        tSys.first == gridYear && tSys.second == gridMonth && tSys.third == selectedGridDay
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📅 $selectedWeekdayName $selectedGridDay ${calendarSystem.getMonthName(gridMonth)} $gridYear",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = if (isSelectedDayHoliday) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                                )

                                val isSelectedDayWorkingDay = selectedWeekdayIndex in activeWorkingDays

                                Surface(
                                    color = when {
                                        isSelectedDayHoliday -> Color(0xFFE53935).copy(alpha = 0.15f)
                                        !isSelectedDayWorkingDay -> Color(0xFFFFB74D).copy(alpha = 0.2f)
                                        else -> MaterialTheme.colorScheme.primaryContainer
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = when {
                                            isSelectedDayHoliday -> "🔴 تعطیل رسمی / جمعه"
                                            !isSelectedDayWorkingDay -> "⏸️ روز غیرکاری مطب"
                                            else -> "🟢 روز کاری مطب"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            isSelectedDayHoliday -> Color(0xFFD32F2F)
                                            !isSelectedDayWorkingDay -> Color(0xFFE65100)
                                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Occasions / Holidays List Banner
                            if (selectedOccasions.isNotEmpty() && showOccasions) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    selectedOccasions.forEach { occ ->
                                        Surface(
                                            color = if (occ.isHoliday) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(0.5.dp, if (occ.isHoliday) Color(0xFFE53935) else MaterialTheme.colorScheme.outlineVariant)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    if (occ.isHoliday) Icons.Default.Warning else Icons.Default.Event,
                                                    contentDescription = null,
                                                    tint = if (occ.isHoliday) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = occ.titleFa,
                                                    fontSize = 10.5.sp,
                                                    fontWeight = if (occ.isHoliday) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (occ.isHoliday) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            // Appointments List
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isPersian) "نوبت‌های این روز (${matchedGridAppts.size} نوبت):" else "Appointments (${matchedGridAppts.size}):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (matchedGridAppts.isEmpty()) {
                                Text(
                                    text = if (isPersian) "هیچ نوبتی برای این تاریخ ثبت نشده است." else "No appointments scheduled for this date.",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            } else {
                                matchedGridAppts.forEach { appt ->
                                    val timeStr = viewModel.formatTime(appt.date)
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "👤 ${appt.patientName}  —  ⏰ $timeStr",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    OutlinedButton(
                                                        onClick = { sessionApptToShow = appt },
                                                        modifier = Modifier.height(26.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)
                                                    ) {
                                                        Text(if (isPersian) "مشاهده جلسه" else "View Session", fontSize = 10.sp)
                                                    }
                                                    OutlinedButton(
                                                        onClick = { selectedApptForReschedule = appt },
                                                        modifier = Modifier.height(26.dp),
                                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)
                                                    ) {
                                                        Text(if (isPersian) "تغییر زمان" else "Reschedule", fontSize = 10.sp)
                                                    }
                                                }
                                            }
                                            if (appt.treatmentType.isNotBlank()) {
                                                Text(
                                                    text = "شرح تراپی/درمان: ${appt.treatmentType}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (appt.note.isNotBlank()) {
                                                Text(
                                                    text = "یادداشت: ${appt.note}",
                                                    fontSize = 10.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // MODE 2: ROLLING DAYS LIST VIEW (نمای روزشمار)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Range selector chips (7, 14, 30 days)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPersian) "نمایش روزشمار:" else "Days Horizon:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(7, 14, 30).forEach { cnt ->
                                Surface(
                                    color = if (daysCount == cnt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable { daysCount = cnt }
                                ) {
                                    Text(
                                        text = "$cnt ${if (isPersian) "روز" else "Days"}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (daysCount == cnt) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    rollingDays.forEach { (_, dayLabel, dayCal) ->
                        val dayKey = dayCal.get(java.util.Calendar.YEAR) * 1000 + dayCal.get(java.util.Calendar.DAY_OF_YEAR)
                        val isExpanded = dayKey in expandedDayKeys
                        val jDate = PersianCalendarHelper.timestampToJalali(dayCal.timeInMillis)
                        val isHoliday = viewModel.isCustomHoliday(jDate.year, jDate.month, jDate.day)
                        val dayOccasions = viewModel.getCustomOccasionsForDay(jDate.year, jDate.month, jDate.day)

                        val matchedAppts = appointments.filter { appt ->
                            val apptCal = java.util.Calendar.getInstance().apply { timeInMillis = appt.date }
                            apptCal.get(java.util.Calendar.YEAR) == dayCal.get(java.util.Calendar.YEAR) &&
                            apptCal.get(java.util.Calendar.DAY_OF_YEAR) == dayCal.get(java.util.Calendar.DAY_OF_YEAR)
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp)),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isHoliday) Color(0xFFFFEBEE)
                                else if (selectedApptForReschedule != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            border = BorderStroke(1.dp, if (isHoliday) Color(0xFFE53935) else if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent)
                        ) {
                            Column {
                                // Main Row (Day Header + Quick Appointment Chips)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val targetAppt = selectedApptForReschedule
                                            if (targetAppt != null) {
                                                val apptCal = java.util.Calendar.getInstance().apply { timeInMillis = targetAppt.date }
                                                rescheduleTargetCal = dayCal
                                                rescheduleHour = apptCal.get(java.util.Calendar.HOUR_OF_DAY)
                                                rescheduleMinute = apptCal.get(java.util.Calendar.MINUTE)
                                            } else {
                                                expandedDayKeys = if (isExpanded) expandedDayKeys - dayKey else expandedDayKeys + dayKey
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.wrapContentWidth().padding(end = 8.dp)
                                    ) {
                                        Text(
                                            text = dayLabel,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = if (isHoliday) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                                        )
                                        if (isHoliday) {
                                            Surface(
                                                color = Color(0xFFE53935).copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = if (isPersian) "تعطیل" else "Off",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFFD32F2F),
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                    }

                                    if (matchedAppts.isEmpty()) {
                                        Text(
                                            text = if (isPersian) "بدون نوبت" else "Free Slot",
                                            fontSize = 11.sp,
                                            color = Color.LightGray,
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Row(
                                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            matchedAppts.forEach { appt ->
                                                val timeStr = viewModel.formatTime(appt.date)
                                                
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.clickable { sessionApptToShow = appt }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "[$timeStr] ${appt.patientName}",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                                        )
                                                        IconButton(
                                                            onClick = { selectedApptForReschedule = appt },
                                                            modifier = Modifier.size(20.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Edit,
                                                                contentDescription = "Reschedule",
                                                                modifier = Modifier.size(12.dp),
                                                                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Expand",
                                        tint = if (isHoliday) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Expanded Details Panel
                                AnimatedVisibility(visible = isExpanded) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (isHoliday) {
                                            Surface(
                                                color = Color(0xFFE53935).copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, Color(0xFFE53935).copy(alpha = 0.4f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(16.dp))
                                                    Text(
                                                        text = if (isPersian) "وضعیت تعطیلی: تعطیل رسمی کلینیک — نوبت‌دهی با هماهنگی قبلی" else "Holiday Status: Official Holiday",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFE53935)
                                                    )
                                                }
                                            }
                                        }

                                        if (showOccasions && dayOccasions.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = if (isPersian) "مناسبت‌های تقویم:" else "Occasions:",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                dayOccasions.forEach { occ ->
                                                    Text(
                                                        text = "• ${occ.titleFa}",
                                                        fontSize = 11.sp,
                                                        color = if (occ.isHoliday) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }

                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                                        Text(
                                            text = if (isPersian) "جزئیات کامل نوبت‌های این روز (${matchedAppts.size} نوبت):" else "Full Appointment Details (${matchedAppts.size}):",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )

                                        if (matchedAppts.isEmpty()) {
                                            Text(
                                                text = if (isPersian) "هیچ نوبتی برای این روز ثبت نشده است." else "No appointments scheduled for this date.",
                                                fontSize = 11.sp,
                                                color = Color.Gray
                                            )
                                        } else {
                                            matchedAppts.forEach { appt ->
                                                val timeStr = viewModel.formatTime(appt.date)
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surface,
                                                    shape = RoundedCornerShape(8.dp),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = "👤 ${appt.patientName}  —  ⏰ $timeStr",
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 12.sp,
                                                                color = MaterialTheme.colorScheme.primary
                                                            )
                                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                                OutlinedButton(
                                                                    onClick = { sessionApptToShow = appt },
                                                                    modifier = Modifier.height(26.dp),
                                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)
                                                                ) {
                                                                    Text(if (isPersian) "مشاهده جلسه" else "View Session", fontSize = 10.sp)
                                                                }
                                                                OutlinedButton(
                                                                    onClick = { selectedApptForReschedule = appt },
                                                                    modifier = Modifier.height(26.dp),
                                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 1.dp)
                                                                ) {
                                                                    Text(if (isPersian) "تغییر زمان" else "Reschedule", fontSize = 10.sp)
                                                                }
                                                            }
                                                        }
                                                        if (appt.treatmentType.isNotBlank()) {
                                                            Text(
                                                                text = "شرح تراپی/درمان: ${appt.treatmentType}",
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                        if (appt.note.isNotBlank()) {
                                                            Text(
                                                                text = "یادداشت: ${appt.note}",
                                                                fontSize = 10.sp,
                                                                color = Color.Gray
                                                            )
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
                }
            }
        }
    }
}

// ==========================================
// 10. DYNAMIC BANK ACCOUNTS MANAGER
// ==========================================
@Composable
fun BankAccountsManagerScreen(
    viewModel: DentalViewModel,
    isPersian: Boolean,
    onBack: () -> Unit
) {
    val bankAccounts by viewModel.bankAccounts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var accountToEdit by remember { mutableStateOf<BankAccount?>(null) }
    var accountToDelete by remember { mutableStateOf<BankAccount?>(null) }

    val totalBalance = remember(bankAccounts) { bankAccounts.sumOf { it.balance } }

    // «تنخواه گردان مطب» و «کیف پول» حساب‌های نقدی داخلی‌اند: در فهرست کارت‌های بانکی
    // نمایش داده نمی‌شوند (هیچ کارتی برایشان ساخته نمی‌شود) و در انتهای همین صفحه بخش
    // اختصاصی خودشان را دارند که تراکنش‌هایشان نیز قابل مشاهده است.
    val isCashAccount: (BankAccount) -> Boolean = { it.isCashFund }
    // نمایشِ کارت‌های مخفی‌شده (با میانبر مکث طولانی روی لوگو مخفی می‌شوند)
    var showHiddenCards by remember { mutableStateOf(false) }
    val allBankCards = remember(bankAccounts) {
        bankAccounts.filter { !isCashAccount(it) }
            .sortedWith(compareBy({ it.sortOrder }, { it.bankName }))
    }
    val hiddenCount = remember(allBankCards) { allBankCards.count { it.isHidden } }
    val pureBankAccounts = remember(allBankCards, showHiddenCards) {
        if (showHiddenCards) allBankCards else allBankCards.filter { !it.isHidden }
    }
    var cashAccountToShowTx by remember { mutableStateOf<BankAccount?>(null) }
    var showAddCashFund by remember { mutableStateOf(false) }
    var cashAccountToEdit by remember { mutableStateOf<BankAccount?>(null) }
    var cashAccountToDelete by remember { mutableStateOf<BankAccount?>(null) }

    if (accountToDelete != null) {
        val target = accountToDelete!!
        AlertDialog(
            onDismissRequest = { accountToDelete = null },
            title = {
                Text(
                    text = if (isPersian) "تایید حذف حساب بانکی" else "Confirm Account Deletion",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    if (isPersian) "آیا از حذف کارت بانکی ${target.bankName} (${target.cardHolder}) اطمینان دارید؟"
                    else "Are you sure you want to delete ${target.bankName} card?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteBankAccount(target)
                        accountToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف قطعی" else "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text(
                        text = if (isPersian) "مدیریت حساب‌ها و کارت‌های بانکی" else "Bank Cards & Accounts",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${bankAccounts.size} ${if (isPersian) "حساب فعال" else "Accounts"} | ${if (isPersian) "مجموع موجودی:" else "Total:"} ${formatAmount(totalBalance)} تومان",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (bankAccounts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreditCard,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp).align(Alignment.CenterHorizontally),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (isPersian) "هنوز هیچ کارت یا حساب بانکی ثبت نشده است" else "No bank accounts registered yet",
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = if (isPersian) "برای دریافت وجه درمان، ثبت تراکنش‌ها و اشتراک‌گذاری سریع مشخصات، کارت بانکی خود را اضافه کنید."
                                else "Add your bank card to receive patient payments and share details quickly.",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else if (pureBankAccounts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isPersian) "هیچ کارت بانکی ثبت نشده — با دکمه «افزودن حساب / کارت بانکی» یکی بسازید" else "No bank cards yet — add one with the Add Bank Account / Card button",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        )
                    }
                }
            } else {
                items(pureBankAccounts, key = { it.id }) { account ->
                    val idx = pureBankAccounts.indexOfFirst { it.id == account.id }
                    com.example.ui.components.IranianBankCard(
                        account = account,
                        clinicName = settings.clinicName,
                        onEdit = { accountToEdit = account },
                        onMoveUp = if (idx > 0) ({ viewModel.moveBankAccount(account, up = true) }) else null,
                        onMoveDown = if (idx < pureBankAccounts.lastIndex) ({ viewModel.moveBankAccount(account, up = false) }) else null,
                        onToggleHide = { viewModel.toggleBankAccountHidden(account) },
                        onShowTransactions = { cashAccountToShowTx = account }
                    )
                }
                if (hiddenCount > 0) {
                    item {
                        TextButton(
                            onClick = { showHiddenCards = !showHiddenCards },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (showHiddenCards) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (showHiddenCards)
                                    (if (isPersian) "پنهان‌کردن کارت‌های مخفی" else "Hide masked cards")
                                else
                                    (if (isPersian) "نمایش $hiddenCount کارت مخفی" else "Show $hiddenCount hidden card(s)"),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Dedicated Bottom Section for Cashbox & Petty Cash Accounts (صندوق‌ها)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "صندوق‌ها (صندوق نقدی و تنخواه گردان مطب)" else "Cashbox & Petty Cash Accounts",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { showAddCashFund = true }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (isPersian) "افزودن صندوق" else "Add fund", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            text = if (isPersian) "حساب‌های نقدی مطب جهت ثبت دریافتی‌های نقدی بیماران و هزینه‌های جاری تنخواه گردان:"
                                   else "Cash accounts for patient cash payments and petty cash expenses:",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )

                        val cashAccounts = remember(bankAccounts) {
                            bankAccounts.filter { isCashAccount(it) }
                        }

                        if (cashAccounts.isNotEmpty()) {
                            // ردیف‌های صندوق قابل انتخاب‌اند: با لمس، تراکنش‌های همان حساب نمایش داده می‌شود
                            cashAccounts.forEach { acc ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { cashAccountToShowTx = acc },
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Default.Payments, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                            Column {
                                                Text(acc.bankName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                Text(
                                                    text = "${formatAmount(acc.balance)} تومان" + if (acc.cardHolder.isNotBlank() && acc.cardHolder != acc.bankName) " (${acc.cardHolder})" else "",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(if (isPersian) "برای مشاهده گردش حساب لمس کنید" else "Tap to view transactions", fontSize = 9.5.sp, color = Color.Gray)
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            IconButton(
                                                onClick = { cashAccountToEdit = acc },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Edit Fund", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            }
                                            IconButton(
                                                onClick = { cashAccountToDelete = acc },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete Fund", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // سامانهٔ هوشمند پیامک‌های بانکی — صف پیامک‌های ثبت‌نشده + اسکن تاریخچه
            item {
                val pendingSmsCount by viewModel.unregisteredBankSmsCount.collectAsStateWithLifecycle()
                val scanScope = rememberCoroutineScope()
                val smsContext = LocalContext.current
                var showUnregisteredSmsQueue by remember { mutableStateOf(false) }
                var showSmsDisclosure by remember { mutableStateOf(false) }
                // تسویه‌ی روزانه‌ی تراکنش‌های کارتخوان (پوز) — بایگانی و واریز جمع روزانه
                var showPosSettlement by remember { mutableStateOf(false) }
                var smsScanning by remember { mutableStateOf(false) }
                var smsScanStatus by remember { mutableStateOf("") }
                var selectedScanDays by remember { mutableIntStateOf(30) } // Default 1 month (30 days), options: 7, 30, 90
                // بازهٔ دلخواه «از … تا …» — وقتی فعال باشد جای بازه‌های آماده را می‌گیرد
                var useCustomRange by remember { mutableStateOf(false) }
                var scanFromMillis by remember { mutableLongStateOf(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000) }
                var scanToMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
                var showScanFromPicker by remember { mutableStateOf(false) }
                var showScanToPicker by remember { mutableStateOf(false) }
                // فیلتر نوع تراکنش برای اسکن: null=همه، DEPOSIT=فقط واریزی، WITHDRAWAL=فقط برداشتی
                var scanTypeFilter by remember { mutableStateOf<String?>(null) }
                // حداقل مبلغ تراکنش (تومان) — تراکنش‌های ریزتر از این اصلاً وارد صف نمی‌شوند
                var minScanAmountStr by remember { mutableStateOf("") }

                val smsPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    val granted = grants.values.all { it }
                    if (granted) {
                        // مجوز اعطا شد → اسکن پس‌زمینه تاریخچه پیامک‌ها (بدون ANR) با بازهٔ انتخابی
                        smsScanning = true
                        val filterLabel = when (scanTypeFilter) {
                            "DEPOSIT" -> if (isPersian) "فقط واریزی‌ها" else "deposits only"
                            "WITHDRAWAL" -> if (isPersian) "فقط برداشتی‌ها" else "withdrawals only"
                            else -> if (isPersian) "واریز و برداشت" else "all types"
                        }
                        scanScope.launch {
                            // درخواست: پیش از هر اسکن جدید، صف قبلیِ «ثبت‌نشده‌ها» پاک شود و
                            // آخرین وضعیت ثبت‌شده‌ها/نادیده‌گرفته‌ها/حذف‌شده‌ها از سرور دریافت گردد
                            // تا نتایج تازه‌ی اسکن با اطلاعات به‌روز (محلی + سرور) مقایسه شوند.
                            smsScanStatus = if (isPersian) "در حال به‌روزرسانی از سرور و پاکسازی صف قبلی..." else "Syncing with server and clearing previous queue..."
                            viewModel.prepareForNewSmsScan()
                            val rangeLabel = if (useCustomRange) {
                                val f = com.example.util.PersianCalendarHelper.timestampToJalali(scanFromMillis)
                                val t = com.example.util.PersianCalendarHelper.timestampToJalali(scanToMillis)
                                if (isPersian) "از $f تا $t" else "from $f to $t"
                            } else {
                                if (isPersian) "$selectedScanDays روز گذشته" else "last $selectedScanDays days"
                            }
                            smsScanStatus = if (isPersian) "در حال اسکن پیامک‌های قبلی ($rangeLabel — $filterLabel)..." else "Scanning SMS history ($rangeLabel — $filterLabel)..."
                            val result = if (useCustomRange) {
                                // بازهٔ دلخواه: از ابتدای روزِ «از» تا انتهای روزِ «تا»
                                com.example.util.BankSmsEngine.scanHistoricalSmsInRange(
                                    context = smsContext,
                                    fromMillis = startOfDayMillis(scanFromMillis),
                                    toMillis = endOfDayMillis(scanToMillis),
                                    typeFilter = scanTypeFilter,
                                    minAmountTomans = minScanAmountStr.trim().toLongOrNull() ?: 0L
                                ) { scanned, found ->
                                    smsScanStatus = (if (isPersian) "اسکن‌شده: $scanned | کشف‌شده: $found" else "Scanned: $scanned | Found: $found")
                                }
                            } else {
                                com.example.util.BankSmsEngine.scanHistoricalSmsBackfill(
                                    context = smsContext,
                                    fromDaysAgo = selectedScanDays,
                                    toDaysAgo = 0,
                                    typeFilter = scanTypeFilter,
                                    minAmountTomans = minScanAmountStr.trim().toLongOrNull() ?: 0L
                                ) { scanned, found ->
                                    smsScanStatus = (if (isPersian) "اسکن‌شده: $scanned | کشف‌شده: $found" else "Scanned: $scanned | Found: $found")
                                }
                            }
                            viewModel.enqueueScannedBankSms(result.second)
                            smsScanning = false
                            smsScanStatus = if (isPersian) "اسکن کامل شد ($filterLabel): ${result.first} پیامک بررسی و ${result.second.size} تراکنش بانکی کشف و در صف ثبت‌نشده‌ها لیست شد"
                                           else "Scan complete ($filterLabel): ${result.first} SMS scanned, ${result.second.size} bank transactions listed in the queue"
                        }
                    } else {
                        Toast.makeText(smsContext, if (isPersian) "دسترسی به پیامک اعطا نشد" else "SMS permission denied", Toast.LENGTH_SHORT).show()
                    }
                }

                fun launchSmsPermissions() {
                    smsPermissionLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.RECEIVE_SMS,
                            android.Manifest.permission.READ_SMS
                        )
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Sms, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = if (isPersian) "سامانهٔ هوشمند پیامک‌های بانکی" else "Smart Bank SMS System",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Button(
                            onClick = { showUnregisteredSmsQueue = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.SmsFailed, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isPersian) "لیست پیامک‌های بانکی — ثبت‌نشده ($pendingSmsCount) / ثبت‌شده / نادیده‌گرفته" else "Bank SMS Lists — Pending ($pendingSmsCount) / Registered / Ignored",
                                fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }

                        if (smsScanning) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(smsScanStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (isPersian) "فیلتر نوع تراکنش (واریزی / برداشتی):" else "Transaction Type Filter:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf<Pair<String?, String>>(
                                        null to (if (isPersian) "همه" else "All"),
                                        "DEPOSIT" to (if (isPersian) "فقط واریزی" else "Deposits"),
                                        "WITHDRAWAL" to (if (isPersian) "فقط برداشتی" else "Withdrawals")
                                    ).forEach { (typeKey, label) ->
                                        FilterChip(
                                            selected = scanTypeFilter == typeKey,
                                            onClick = { scanTypeFilter = typeKey },
                                            label = { Text(label, fontSize = 10.5.sp, fontWeight = if (scanTypeFilter == typeKey) FontWeight.Bold else FontWeight.Normal) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Text(
                                    text = if (isPersian) "بازه زمانی اسکن تاریخچه:" else "Scan History Range:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        7 to (if (isPersian) "۱ هفته اخیر" else "Last 7 Days"),
                                        30 to (if (isPersian) "۱ ماه اخیر" else "Last 1 Month"),
                                        90 to (if (isPersian) "۳ ماه اخیر" else "Last 3 Months")
                                    ).forEach { (days, label) ->
                                        FilterChip(
                                            selected = !useCustomRange && selectedScanDays == days,
                                            onClick = {
                                                useCustomRange = false
                                                selectedScanDays = days
                                            },
                                            label = { Text(label, fontSize = 10.5.sp, fontWeight = if (!useCustomRange && selectedScanDays == days) FontWeight.Bold else FontWeight.Normal) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                // حداقل مبلغ تراکنش — پیامک‌های کم‌مبلغ (کارمزد، شارژ خرد)
                                // اصلاً وارد صف نمی‌شوند.
                                OutlinedTextField(
                                    value = minScanAmountStr,
                                    onValueChange = { v -> minScanAmountStr = v.filter(Char::isDigit).take(12) },
                                    label = {
                                        Text(
                                            if (isPersian) "حداقل مبلغ تراکنش (تومان) — اختیاری" else "Minimum amount (Toman) — optional",
                                            fontSize = 10.sp
                                        )
                                    },
                                    placeholder = { Text(if (isPersian) "مثلاً ۱۰۰۰۰۰" else "e.g. 100000", fontSize = 10.sp) },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                    ),
                                    trailingIcon = if (minScanAmountStr.isNotBlank()) {
                                        {
                                            IconButton(onClick = { minScanAmountStr = "" }) {
                                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    } else null,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (minScanAmountStr.trim().toLongOrNull() != null) {
                                    Text(
                                        text = if (isPersian)
                                            "تراکنش‌های کمتر از ${formatAmount(minScanAmountStr.trim().toLong())} تومان نادیده گرفته می‌شوند."
                                        else
                                            "Transactions below ${formatAmount(minScanAmountStr.trim().toLong())} Toman are skipped.",
                                        fontSize = 9.5.sp,
                                        color = Color.Gray
                                    )
                                }

                                // بازهٔ دلخواه «از … تا …»
                                FilterChip(
                                    selected = useCustomRange,
                                    onClick = { useCustomRange = !useCustomRange },
                                    leadingIcon = {
                                        Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(14.dp))
                                    },
                                    label = {
                                        Text(
                                            if (isPersian) "بازهٔ دلخواه (از … تا …)" else "Custom range (from … to …)",
                                            fontSize = 10.5.sp,
                                            fontWeight = if (useCustomRange) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (useCustomRange) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { showScanFromPicker = true },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(if (isPersian) "از تاریخ" else "From", fontSize = 9.sp, color = Color.Gray)
                                                Text(
                                                    com.example.util.PersianCalendarHelper.timestampToJalali(scanFromMillis).toString(),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { showScanToPicker = true },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(if (isPersian) "تا تاریخ" else "To", fontSize = 9.sp, color = Color.Gray)
                                                Text(
                                                    com.example.util.PersianCalendarHelper.timestampToJalali(scanToMillis).toString(),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    if (startOfDayMillis(scanFromMillis) > endOfDayMillis(scanToMillis)) {
                                        Text(
                                            if (isPersian) "«از تاریخ» بعد از «تا تاریخ» است — بازه خودکار جابه‌جا می‌شود."
                                            else "\"From\" is after \"To\" — the range will be swapped automatically.",
                                            fontSize = 9.5.sp,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        val granted = smsContext.checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            // مجوز موجود است؛ لانچر بلافاصله granted=true برمی‌گرداند و اسکن آغاز می‌شود
                                            launchSmsPermissions()
                                        } else {
                                            // ابتدا افشای شفاف حریم خصوصی (الزام گوگل‌پلی)، سپس درخواست مجوز
                                            showSmsDisclosure = true
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (useCustomRange) {
                                            val f = com.example.util.PersianCalendarHelper.timestampToJalali(scanFromMillis)
                                            val t = com.example.util.PersianCalendarHelper.timestampToJalali(scanToMillis)
                                            if (isPersian) "اسکن پیامک‌های بانکی ($f تا $t)" else "Scan SMS History ($f to $t)"
                                        } else {
                                            if (isPersian) "اسکن پیامک‌های بانکی ($selectedScanDays روز گذشته)" else "Scan SMS History ($selectedScanDays days)"
                                        },
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            if (smsScanStatus.isNotEmpty()) {
                                Text(smsScanStatus, fontSize = 10.5.sp, color = Color.Gray)
                            }
                        }

                        if (showScanFromPicker) {
                            com.example.ui.screens.JalaliDatePickerDialog(
                                initialTimestamp = scanFromMillis,
                                isPersian = isPersian,
                                onDateSelected = { jy, jm, jd ->
                                    scanFromMillis = com.example.util.PersianCalendarHelper.jalaliToTimestamp(jy, jm, jd)
                                    showScanFromPicker = false
                                },
                                onDismiss = { showScanFromPicker = false }
                            )
                        }
                        if (showScanToPicker) {
                            com.example.ui.screens.JalaliDatePickerDialog(
                                initialTimestamp = scanToMillis,
                                isPersian = isPersian,
                                onDateSelected = { jy, jm, jd ->
                                    scanToMillis = com.example.util.PersianCalendarHelper.jalaliToTimestamp(jy, jm, jd)
                                    showScanToPicker = false
                                },
                                onDismiss = { showScanToPicker = false }
                            )
                        }
                        if (showSmsDisclosure) {
                            com.example.ui.screens.ProminentSmsDisclosureDialog(
                                isPersian = isPersian,
                                onAccept = {
                                    showSmsDisclosure = false
                                    launchSmsPermissions()
                                },
                                onDismiss = { showSmsDisclosure = false }
                            )
                        }
                        if (showUnregisteredSmsQueue) {
                            com.example.ui.screens.UnregisteredBankSmsManagerDialog(
                                viewModel = viewModel,
                                isPersian = isPersian,
                                onDismiss = { showUnregisteredSmsQueue = false }
                            )
                        }

                        // کارتخوان: تسویه‌ی روزانه + بایگانی
                        Button(
                            onClick = { showPosSettlement = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
                        ) {
                            Icon(Icons.Default.PointOfSale, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isPersian) "کارتخوان — تسویه روزانه و بایگانی" else "Card Reader — Daily Settlement", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (showPosSettlement) {
                            com.example.ui.screens.PosSettlementDialog(
                                viewModel = viewModel,
                                isPersian = isPersian,
                                onDismiss = { showPosSettlement = false }
                            )
                        }
                    }
                }
            }

            // دکمه افزودن حساب و ساخت کارت بانکی — پایین لیست (درخواست: انتقال دکمه به پایین)
            item {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPersian) "افزودن حساب / کارت بانکی" else "Add Bank Account / Card", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }

    // مشاهده تراکنش‌های صندوق نقدی / تنخواه گردان
    if (cashAccountToShowTx != null) {
        AccountTransactionsDialog(
            account = cashAccountToShowTx!!,
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { cashAccountToShowTx = null }
        )
    }

    if (showAddCashFund) {
        CashFundEditorDialog(
            existing = null,
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { showAddCashFund = false }
        )
    }
    if (cashAccountToEdit != null) {
        CashFundEditorDialog(
            existing = cashAccountToEdit,
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { cashAccountToEdit = null }
        )
    }
    if (cashAccountToDelete != null) {
        val target = cashAccountToDelete!!
        AlertDialog(
            onDismissRequest = { cashAccountToDelete = null },
            title = {
                Text(
                    text = if (isPersian) "تایید حذف صندوق" else "Confirm fund deletion",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    if (isPersian) "آیا از حذف صندوق «${target.bankName}» اطمینان دارید؟ تراکنش‌های قبلی در گردش حساب این صندوق دیگر دیده نمی‌شوند."
                    else "Delete cash fund «${target.bankName}»? Previous transactions will no longer appear under this fund."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteBankAccount(target)
                        cashAccountToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف قطعی" else "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { cashAccountToDelete = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    if (accountToEdit != null) {
        EditBankAccountDialog(
            account = accountToEdit!!,
            viewModel = viewModel,
            isPersian = isPersian,
            clinicName = settings.clinicName,
            onDismiss = { accountToEdit = null }
        )
    }

    if (showAddDialog) {
        AddBankAccountDialog(
            viewModel = viewModel,
            isPersian = isPersian,
            clinicName = settings.clinicName,
            onDismiss = { showAddDialog = false }
        )
    }
}

/**
 * نمایش تراکنش‌های متصل به یک حساب (صندوق نقدی / تنخواه گردان / کارت بانکی):
 * دریافتی‌های بیماران (+)، پرداختی‌های هزینه‌های مطب (−) و تراکنش‌های شخصی — به‌ترتیب تاریخ.
 */
@Composable
fun AccountTransactionsDialog(
    account: BankAccount,
    viewModel: DentalViewModel,
    isPersian: Boolean,
    onDismiss: () -> Unit
) {
    val payments by viewModel.allPayments.collectAsStateWithLifecycle()
    val expensePayments by viewModel.allExpensePayments.collectAsStateWithLifecycle()
    val personalTxs by viewModel.personalTransactions.collectAsStateWithLifecycle()
    val clinicExpenses by viewModel.clinicExpenses.collectAsStateWithLifecycle()
    val patients by viewModel.patients.collectAsStateWithLifecycle()

    data class AccountTxRow(
        val date: Long,
        val title: String,
        val detail: String,
        val amount: Long,
        val isCredit: Boolean
    )

    val txRows = remember(payments, expensePayments, personalTxs, clinicExpenses, account.id, patients) {
        val rows = mutableListOf<AccountTxRow>()
        payments.filter { !it.isDeleted && it.bankAccountId == account.id }.forEach { p ->
            val patient = patients.firstOrNull { it.id == p.patientId }
            val patientName = patient?.name ?: (if (isPersian) "ناشناس" else "Unknown")
            rows.add(
                AccountTxRow(
                    date = p.date,
                    title = if (isPersian) "دریافتی از بیمار - $patientName" else "Receipt from patient - $patientName",
                    detail = when (p.type) {
                        "CASH" -> if (isPersian) "نقدی" else "Cash"
                        "CHEQUE" -> if (isPersian) "چک صیادی" else "Sayadi cheque"
                        "POS" -> if (isPersian) "کارتخوان" else "POS"
                        else -> if (isPersian) "انتقال وجه" else "Fund transfer"
                    },
                    amount = p.amount,
                    isCredit = true
                )
            )
        }
        expensePayments.filter { !it.isDeleted && it.bankAccountId == account.id }.forEach { ep ->
            val expense = clinicExpenses.firstOrNull { it.id == ep.clinicExpenseId }
            rows.add(
                AccountTxRow(
                    date = ep.date,
                    title = if (isPersian) "پرداختی هزینه مطب" else "Clinic expense payment",
                    detail = listOfNotNull(expense?.category, ep.note).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { ep.method },
                    amount = ep.amount,
                    isCredit = false
                )
            )
        }
        personalTxs.filter { !it.isDeleted && it.bankAccountId == account.id }.forEach { t ->
            rows.add(
                AccountTxRow(
                    date = t.date,
                    title = if (isPersian) "تراکنش شخصی" else "Personal transaction",
                    detail = t.description,
                    amount = t.amount,
                    isCredit = t.type == "INCOME"
                )
            )
        }
        rows.sortedByDescending { it.date }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(account.bankName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        text = if (isPersian) "موجودی فعلی: ${formatAmount(account.balance)} تومان" else "Balance: ${formatAmount(account.balance)}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (txRows.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                        Text(
                            text = if (isPersian) "هنوز تراکنشی برای این حساب ثبت نشده است" else "No transactions recorded for this account yet",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    txRows.forEach { tx ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tx.title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    if (tx.detail.isNotBlank()) {
                                        Text(tx.detail, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
                                    }
                                    Text(
                                        text = viewModel.formatDate(tx.date),
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                                Text(
                                    text = (if (tx.isCredit) "+" else "−") + formatAmount(tx.amount),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = if (tx.isCredit) Color(0xFF2E7D32) else Color(0xFFC2410C)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (isPersian) "بستن" else "Close") }
        }
    )
}

@Composable
fun CashFundEditorDialog(
    existing: BankAccount?,
    viewModel: DentalViewModel,
    isPersian: Boolean,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.bankName.orEmpty()) }
    var holder by remember { mutableStateOf(existing?.cardHolder.orEmpty()) }
    var balanceStr by remember { mutableStateOf(if (existing != null) existing.balance.toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (existing == null) {
                        if (isPersian) "افزودن صندوق جدید" else "Add cash fund"
                    } else {
                        if (isPersian) "ویرایش صندوق" else "Edit cash fund"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isPersian) "نام صندوق" else "Fund name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = holder,
                    onValueChange = { holder = it },
                    label = { Text(if (isPersian) "مسئول / دارنده (اختیاری)" else "Holder (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = balanceStr,
                    onValueChange = { balanceStr = it },
                    label = { Text(if (isPersian) "موجودی (تومان)" else "Balance (Toman)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) return@Button
                    val bal = balanceStr.toEnglishLong()
                    val holderName = holder.trim().ifBlank { trimmed }
                    if (existing == null) {
                        viewModel.addBankAccount(
                            bankName = trimmed,
                            cardHolder = holderName,
                            cardNumber = "",
                            accountNumber = "CASH-EXTRA-${System.currentTimeMillis()}",
                            balance = bal,
                            smsKeywords = ""
                        )
                    } else {
                        viewModel.updateBankAccount(
                            existing.copy(
                                bankName = trimmed,
                                cardHolder = holderName,
                                balance = bal
                            ),
                            updateBalance = bal != existing.balance
                        )
                    }
                    onDismiss()
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (existing == null) (if (isPersian) "ثبت صندوق" else "Save fund")
                    else (if (isPersian) "ذخیره تغییرات" else "Save changes"),
                    fontWeight = FontWeight.Bold
                )
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
fun AddBankAccountDialog(
    viewModel: DentalViewModel,
    isPersian: Boolean,
    clinicName: String,
    onDismiss: () -> Unit
) {
    var cardNo by remember { mutableStateOf("") }
    var bankName by remember { mutableStateOf("") }
    var cardHolder by remember { mutableStateOf("") }
    var cvv2 by remember { mutableStateOf("") }
    var expireMonth by remember { mutableStateOf("") }
    var expireYear by remember { mutableStateOf("") }
    var accNo by remember { mutableStateOf("") }
    var iban by remember { mutableStateOf("") }
    var balanceStr by remember { mutableStateOf("") }
    var smsKeywords by remember { mutableStateOf("") }

    // Auto-detect bank from card number
    val detectedBank = remember(cardNo, iban) {
        com.example.util.IranianBankHelper.detectBank(cardNo) ?: com.example.util.IranianBankHelper.detectBankFromIban(iban)
    }

    LaunchedEffect(detectedBank) {
        if (detectedBank != null && bankName.isBlank()) {
            bankName = detectedBank.nameFa
        }
    }

    val isValidLuhn = remember(cardNo) {
        val clean = com.example.util.IranianBankHelper.cleanDigits(cardNo)
        if (clean.length == 16) com.example.util.IranianBankHelper.isValidCardNumber(clean) else null
    }

    val previewAccount = remember(bankName, cardHolder, cardNo, accNo, balanceStr, cvv2, expireMonth, expireYear, iban) {
        BankAccount(
            id = 0,
            bankName = bankName.ifBlank { detectedBank?.nameFa ?: "بانک" },
            cardHolder = cardHolder,
            cardNumber = cardNo,
            accountNumber = accNo,
            balance = balanceStr.toEnglishLong(),
            smsKeywords = smsKeywords,
            cvv2 = cvv2,
            expireMonth = expireMonth,
            expireYear = expireYear,
            iban = iban
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = if (isPersian) "افزودن کارت یا حساب بانکی" else "Add Bank Card / Account",
                    fontWeight = FontWeight.Bold
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
                // Live Card Preview
                Text(
                    text = if (isPersian) "پیش‌نمایش زنده کارت بانکی:" else "Live Card Preview:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                com.example.ui.components.IranianBankCard(
                    account = previewAccount,
                    clinicName = clinicName,
                    showActions = false
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 16-Digit Card Number with Live Bank Detection
                OutlinedTextField(
                    value = cardNo,
                    onValueChange = { input ->
                        val clean = com.example.util.IranianBankHelper.cleanDigits(input)
                        cardNo = clean
                        val detected = com.example.util.IranianBankHelper.detectBank(clean)
                        if (detected != null) {
                            bankName = detected.nameFa
                        }
                    },
                    label = { Text(if (isPersian) "شماره ۱۶ رقمی کارت بانکی" else "16-Digit Card Number") },
                    placeholder = { Text("مثلاً ۶۰۳۷۹۹۱۸...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    trailingIcon = {
                        if (detectedBank != null) {
                            Surface(
                                color = detectedBank.primaryColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Text(
                                    text = detectedBank.nameFa,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = detectedBank.primaryColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    },
                    supportingText = {
                        if (isValidLuhn != null) {
                            if (isValidLuhn) {
                                Text("✓ شماره کارت معتبر است", color = Color(0xFF2E7D32), fontSize = 10.sp)
                            } else {
                                Text("⚠️ الگوریتم شماره کارت نامعتبر است", color = MaterialTheme.colorScheme.error, fontSize = 10.sp)
                            }
                        }
                    }
                )

                // Bank Name & Cardholder Name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = { Text(if (isPersian) "نام بانک" else "Bank Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cardHolder,
                        onValueChange = { cardHolder = it },
                        label = { Text(if (isPersian) "نام دارنده کارت" else "Card Holder") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                // CVV2 & Expiry Date (Month / Year)
                var cvvVisible by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = cvv2,
                        onValueChange = { cvv2 = com.example.util.IranianBankHelper.cleanDigits(it).take(4) },
                        label = { Text("CVV2") },
                        placeholder = { Text("۳ یا ۴ رقم") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        visualTransformation = if (cvvVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { cvvVisible = !cvvVisible }) {
                                Icon(if (cvvVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null)
                            }
                        }
                    )
                    OutlinedTextField(
                        value = expireMonth,
                        onValueChange = { expireMonth = com.example.util.IranianBankHelper.cleanDigits(it).take(2) },
                        label = { Text(if (isPersian) "ماه انقضا" else "Exp Month") },
                        placeholder = { Text("۰۱ تا ۱۲") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = expireYear,
                        onValueChange = { expireYear = com.example.util.IranianBankHelper.cleanDigits(it).take(4) },
                        label = { Text(if (isPersian) "سال انقضا" else "Exp Year") },
                        placeholder = { Text("مثلاً ۰۶ یا ۱۴۰۶") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }

                // Account Number & IBAN (Shaba)
                OutlinedTextField(
                    value = accNo,
                    onValueChange = { accNo = it },
                    label = { Text(if (isPersian) "شماره حساب بانکی" else "Account Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                OutlinedTextField(
                    value = iban,
                    onValueChange = { input ->
                        var formatted = input.trim().uppercase()
                        if (!formatted.startsWith("IR") && formatted.length >= 2 && formatted.all { it.isDigit() }) {
                            formatted = "IR$formatted"
                        }
                        iban = formatted
                        val detected = com.example.util.IranianBankHelper.detectBankFromIban(formatted)
                        if (detected != null && bankName.isBlank()) {
                            bankName = detected.nameFa
                        }
                    },
                    label = { Text(if (isPersian) "شماره شبا (IBAN)" else "IBAN (Shaba)") },
                    placeholder = { Text("IR...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Initial Balance
                OutlinedTextField(
                    value = balanceStr,
                    onValueChange = { balanceStr = it },
                    label = { Text(if (isPersian) "موجودی اولیه حساب (تومان)" else "Starting Balance (Toman)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                // SMS Keywords
                OutlinedTextField(
                    value = smsKeywords,
                    onValueChange = { smsKeywords = it },
                    label = { Text(if (isPersian) "کلمات کلیدی پیامک بانک (با ویرگول)" else "SMS Keywords") },
                    placeholder = { Text(if (isPersian) "واریز,بانک ملی" else "deposit,bank") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bal = balanceStr.toEnglishLong()
                    val bName = bankName.ifBlank { detectedBank?.nameFa ?: "بانک" }
                    if (bName.isNotEmpty()) {
                        viewModel.addBankAccount(
                            bankName = bName,
                            cardHolder = cardHolder,
                            cardNumber = cardNo,
                            accountNumber = accNo,
                            balance = bal,
                            smsKeywords = smsKeywords,
                            cvv2 = cvv2,
                            expireMonth = expireMonth,
                            expireYear = expireYear,
                            iban = iban
                        )
                        onDismiss()
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isPersian) "ذخیره کارت بانکی" else "Save Bank Card", fontWeight = FontWeight.Bold)
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
fun EditBankAccountDialog(
    account: BankAccount,
    viewModel: DentalViewModel,
    isPersian: Boolean,
    clinicName: String = "",
    onDismiss: () -> Unit
) {
    var bankName by remember { mutableStateOf(account.bankName) }
    var cardHolder by remember { mutableStateOf(account.cardHolder) }
    var cardNo by remember { mutableStateOf(account.cardNumber) }
    var cvv2 by remember { mutableStateOf(account.cvv2) }
    var expireMonth by remember { mutableStateOf(account.expireMonth) }
    var expireYear by remember { mutableStateOf(account.expireYear) }
    var accNo by remember { mutableStateOf(account.accountNumber) }
    var iban by remember { mutableStateOf(account.iban) }
    var balanceStr by remember { mutableStateOf(account.balance.toString()) }
    var smsKeywords by remember { mutableStateOf(account.smsKeywords) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val detectedBank = remember(cardNo, iban) {
        com.example.util.IranianBankHelper.detectBank(cardNo) ?: com.example.util.IranianBankHelper.detectBankFromIban(iban)
    }

    val previewAccount = remember(bankName, cardHolder, cardNo, accNo, balanceStr, cvv2, expireMonth, expireYear, iban) {
        account.copy(
            bankName = bankName,
            cardHolder = cardHolder,
            cardNumber = cardNo,
            accountNumber = accNo,
            balance = balanceStr.toEnglishLong(),
            smsKeywords = smsKeywords,
            cvv2 = cvv2,
            expireMonth = expireMonth,
            expireYear = expireYear,
            iban = iban
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(
                    if (isPersian) "تایید حذف حساب بانکی" else "Confirm Account Deletion",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    if (isPersian) "آیا از حذف کارت/حساب بانکی ${account.bankName} - ${account.cardHolder} اطمینان دارید؟"
                    else "Are you sure you want to delete this bank card/account?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteBankAccount(account)
                        showDeleteConfirm = false
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف قطعی" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    if (isPersian) "ویرایش کارت / حساب بانکی" else "Edit Bank Account",
                    fontWeight = FontWeight.Bold
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
                // Live Card Preview
                Text(
                    text = if (isPersian) "پیش‌نمایش کارت بانکی:" else "Card Preview:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                com.example.ui.components.IranianBankCard(
                    account = previewAccount,
                    clinicName = clinicName,
                    showActions = false
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = cardNo,
                    onValueChange = { input ->
                        val clean = com.example.util.IranianBankHelper.cleanDigits(input)
                        cardNo = clean
                        val detected = com.example.util.IranianBankHelper.detectBank(clean)
                        if (detected != null && bankName.isBlank()) {
                            bankName = detected.nameFa
                        }
                    },
                    label = { Text(if (isPersian) "شماره کارت (۱۶ رقمی)" else "16-Digit Card Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = { Text(if (isPersian) "نام بانک" else "Bank Name") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = cardHolder,
                        onValueChange = { cardHolder = it },
                        label = { Text(if (isPersian) "نام دارنده کارت" else "Card Holder") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                var cvvVisible by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = cvv2,
                        onValueChange = { cvv2 = com.example.util.IranianBankHelper.cleanDigits(it).take(4) },
                        label = { Text("CVV2") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        visualTransformation = if (cvvVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { cvvVisible = !cvvVisible }) {
                                Icon(if (cvvVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, contentDescription = null)
                            }
                        }
                    )
                    OutlinedTextField(
                        value = expireMonth,
                        onValueChange = { expireMonth = com.example.util.IranianBankHelper.cleanDigits(it).take(2) },
                        label = { Text(if (isPersian) "ماه" else "Month") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = expireYear,
                        onValueChange = { expireYear = com.example.util.IranianBankHelper.cleanDigits(it).take(4) },
                        label = { Text(if (isPersian) "سال" else "Year") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    )
                }

                OutlinedTextField(
                    value = accNo,
                    onValueChange = { accNo = it },
                    label = { Text(if (isPersian) "شماره حساب" else "Account Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                OutlinedTextField(
                    value = iban,
                    onValueChange = { iban = it.trim().uppercase() },
                    label = { Text(if (isPersian) "شماره شبا (IBAN)" else "IBAN (Shaba)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = balanceStr,
                    onValueChange = { balanceStr = it },
                    label = { Text(if (isPersian) "موجودی حساب (تومان)" else "Balance (Toman)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )

                OutlinedTextField(
                    value = smsKeywords,
                    onValueChange = { smsKeywords = it },
                    label = { Text(if (isPersian) "کلمات کلیدی پیامک بانک (با ویرگول)" else "SMS Keywords") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPersian) "حذف این کارت / حساب بانکی" else "Delete Bank Account", fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val bal = balanceStr.toEnglishLong()
                    if (bankName.isNotEmpty()) {
                        viewModel.updateBankAccount(
                            account.copy(
                                bankName = bankName,
                                cardHolder = cardHolder,
                                cardNumber = cardNo,
                                accountNumber = accNo,
                                balance = bal,
                                smsKeywords = smsKeywords,
                                cvv2 = cvv2,
                                expireMonth = expireMonth,
                                expireYear = expireYear,
                                iban = iban
                            ),
                            updateBalance = bal != account.balance
                        )
                        onDismiss()
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isPersian) "ذخیره تغییرات" else "Save Changes", fontWeight = FontWeight.Bold)
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
fun SessionDetailsDialog(
    appointment: com.example.data.Appointment,
    viewModel: com.example.ui.DentalViewModel,
    isPersian: Boolean,
    onDismiss: () -> Unit
) {
    val allAppointments by viewModel.appointments.collectAsStateWithLifecycle()
    val currentAppt = allAppointments.find { it.id == appointment.id } ?: appointment
    var showManageTherapiesDialog by remember { mutableStateOf(false) }
    
    if (showManageTherapiesDialog) {
        com.example.ui.screens.AppointmentSessionDialog(
            appointment = currentAppt,
            viewModel = viewModel,
            onDismiss = { showManageTherapiesDialog = false }
        )
    }

    val allTreatments by viewModel.allTreatments.collectAsStateWithLifecycle()
    var treatmentToEditInSession by remember { mutableStateOf<Treatment?>(null) }
    var txToDeleteInSession by remember { mutableStateOf<Treatment?>(null) }
    var clinicalProtocolTitleInSession by remember { mutableStateOf<String?>(null) }

    // ——— حذف جلسه: تأیید دو مرحله‌ای + تایپ کلمهٔ «حذف» ———
    // 0 = بسته، 1 = هشدار اول، 2 = تأیید نهایی با تایپ کلمه
    var deleteStep by remember { mutableIntStateOf(0) }
    var deleteConfirmText by remember { mutableStateOf("") }
    val isCompletedOrArchived = currentAppt.isArchived || currentAppt.isCompleted
    val sessionTreatments = androidx.compose.runtime.remember(allTreatments, currentAppt.plannedTreatmentIds, currentAppt.patientId) {
        val ids = currentAppt.plannedTreatmentIds.split(",").mapNotNull { it.toLongOrNull() }.toSet()
        if (ids.isNotEmpty()) {
            allTreatments.filter { it.id in ids }
        } else {
            emptyList() // Only list selected therapies
        }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isPersian) "جزییات جلسه: ${appointment.patientName}" else "Session: ${appointment.patientName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // حذف جلسه — فقط برای جلسات «تکمیل‌شده» و «آرشیوشده».
                        // جلسهٔ در جریان نباید از اینجا حذف شود.
                        if (isCompletedOrArchived) {
                            IconButton(onClick = { deleteStep = 1 }) {
                                Icon(
                                    Icons.Default.DeleteForever,
                                    contentDescription = if (isPersian) "حذف جلسه" else "Delete session",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(text = viewModel.formatDate(appointment.date) + " - " + appointment.treatmentType, color = Color.Gray, fontSize = 12.sp)
                    TextButton(onClick = { showManageTherapiesDialog = true }) {
                        Text(if (isPersian) "ویرایش تراپی‌های این جلسه" else "Edit Session Therapies", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                if (sessionTreatments.isEmpty()) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (isPersian) "هیچ درمان ثبت شده‌ای برای این جلسه یافت نشد" else "No planned treatments found for this session", color = Color.Gray)
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessionTreatments) { treatment ->
                            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = treatment.title,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.threeSecondLongPress(
                                            onLongClick = {
                                                clinicalProtocolTitleInSession = treatment.title
                                            }
                                        )
                                    )
                                    if (treatment.selectedTeeth.isNotEmpty()) {
                                        Text(text = if (isPersian) "دندان‌ها: ${treatment.selectedTeeth}" else "Teeth: ${treatment.selectedTeeth}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    val displayNotes = treatment.notes.cleanNotesForDisplay()
                                    if (displayNotes.isNotEmpty()) {
                                        Text(text = displayNotes, fontSize = 11.sp, color = Color.Gray)
                                    }
                                    val rawStages = treatment.stages.ifBlank {
                                        "جلسه اول: معاینه و آماده‌سازی - جلسه دوم: اجراء و درمان - جلسه سوم: تحویل و نهایی‌سازی"
                                    }
                                    val allStages = parseStagesList(rawStages)
                                    val completedSet = parseStageMarkers(treatment.completedStages).toSet()
                                    val initialCompletedSet = remember(treatment.id) { completedSet }
                                    val remainingStages = allStages.filter { !initialCompletedSet.contains(it) }

                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (isPersian) "مراحل و گام‌های تراپی درمانی:" else "Therapy Stages Breakdown:",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            val progressPercent = if (allStages.isNotEmpty()) (completedSet.size * 100) / allStages.size else 0
                                            Text(
                                                text = "${completedSet.size} از ${allStages.size} انجام شد ($progressPercent٪)",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (progressPercent == 100) Color(0xFF2E7D32) else MaterialTheme.colorScheme.secondary
                                            )
                                        }

                                        remainingStages.forEach { stage ->
                                            val isChecked = completedSet.contains(stage)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                                    .clickable {
                                                        // انتساب به همین جلسه تا حذف جلسه بتواند برش گرداند
                                                        viewModel.toggleTreatmentStage(treatment, stage, currentAppt.id)
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = {
                                                        viewModel.toggleTreatmentStage(treatment, stage, currentAppt.id)
                                                    }
                                                )
                                                Text(
                                                    text = stage,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${formatAmount(treatment.netCost)} ${viewModel.label("currency")}",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        OutlinedButton(
                                            onClick = { treatmentToEditInSession = treatment },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(if (isPersian) "ویرایش" else "Edit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                var showConfirmDeleteAppt by remember { mutableStateOf(false) }
                var showNextSessionDialog by remember { mutableStateOf(false) }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showNextSessionDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPersian) "برنامه‌ریزی جلسه بعدی" else "Schedule Next Session", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { showConfirmDeleteAppt = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPersian) "حذف نوبت" else "Delete Appt", fontSize = 11.sp)
                    }
                }

                if (showConfirmDeleteAppt) {
                    AlertDialog(
                        onDismissRequest = { showConfirmDeleteAppt = false },
                        title = { Text(if (isPersian) "تایید حذف نوبت" else "Confirm Delete Appointment", fontWeight = FontWeight.Bold) },
                        text = { Text(if (isPersian) "آیا از حذف این نوبت از تقویم اطمینان دارید؟ این عملیات غیرقابل بازگشت است." else "Are you sure you want to delete this appointment?") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.deleteAppointment(appointment)
                                    showConfirmDeleteAppt = false
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(if (isPersian) "حذف قطعی" else "Confirm Delete")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDeleteAppt = false }) {
                                Text(if (isPersian) "انصراف" else "Cancel")
                            }
                        }
                    )
                }

                if (showNextSessionDialog) {
                    var nextDaysOffset by remember { mutableStateOf("7") }
                    var nextNote by remember { mutableStateOf("ادامه مراحل درمان") }
                    var showNonCalendarHolidayWarning by remember { mutableStateOf<Pair<String, Long>?>(null) }

                    AlertDialog(
                        onDismissRequest = { showNextSessionDialog = false },
                        title = { Text(if (isPersian) "برنامه‌ریزی جلسه بعدی درمانی" else "Schedule Next Session", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(text = "بیمار: ${appointment.patientName}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                OutlinedTextField(
                                    value = nextDaysOffset,
                                    onValueChange = { nextDaysOffset = it },
                                    label = { Text(if (isPersian) "چند روز آینده؟ (مثلاً ۷)" else "Days from today") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = nextNote,
                                    onValueChange = { nextNote = it },
                                    label = { Text(if (isPersian) "یادداشت جلسه بعد" else "Next session note") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val days = nextDaysOffset.toLongOrNull() ?: 7L
                                    val targetDate = System.currentTimeMillis() + (days * 86400000L)
                                    val reason = viewModel.getHolidayOrNonWorkingReason(targetDate)
                                    if (reason != null) {
                                        showNonCalendarHolidayWarning = reason to targetDate
                                    } else {
                                        viewModel.addAppointment(
                                            com.example.data.Appointment(
                                                patientId = appointment.patientId,
                                                patientName = appointment.patientName,
                                                date = targetDate,
                                                treatmentType = if (isPersian) "ادامه تراپی درمانی" else "Therapy Follow-up",
                                                note = nextNote,
                                                unit = appointment.unit,
                                                durationMinutes = appointment.effectiveDurationMinutes
                                            )
                                        )
                                        showNextSessionDialog = false
                                    }
                                }
                            ) {
                                Text(if (isPersian) "ثبت نوبت بعدی" else "Save Next Appointment")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNextSessionDialog = false }) {
                                Text(if (isPersian) "انصراف" else "Cancel")
                            }
                        }
                    )

                    if (showNonCalendarHolidayWarning != null) {
                        val (reason, targetDate) = showNonCalendarHolidayWarning!!
                        val dateVerbal = viewModel.formatDateFullVerbal(targetDate)
                        AlertDialog(
                            onDismissRequest = { showNonCalendarHolidayWarning = null },
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFD32F2F))
                                    Text(
                                        text = if (isPersian) "امکان ثبت نوبت در روز تعطیل وجود ندارد" else "Cannot Book on Off Day Here",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color(0xFFD32F2F)
                                    )
                                }
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = if (isPersian) "تاریخ محاسبه شده ($dateVerbal) مصادف با «$reason» می‌باشد."
                                        else "Calculated date ($dateVerbal) is: '$reason'.",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (isPersian) "طبق قوانین برنامه، ثبت نوبت در روزهای تعطیل یا غیرکاری کلینیک فقط از طریق صفحه «تقویم و نوبت‌ها» با تایید مجدد امکان‌پذیر است."
                                        else "Booking appointments on off days is only permitted via the Calendar & Appointments screen.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showNonCalendarHolidayWarning = null
                                        showNextSessionDialog = false
                                        viewModel.currentTab.value = "appointments"
                                    }
                                ) {
                                    Text(if (isPersian) "انتقال به صفحه تقویم و نوبت‌ها" else "Go to Calendar Screen", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                OutlinedButton(onClick = { showNonCalendarHolidayWarning = null }) {
                                    Text(if (isPersian) "انصراف" else "Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (treatmentToEditInSession != null) {
        EditTreatmentDialog(
            treatment = treatmentToEditInSession!!,
            viewModel = viewModel,
            onDelete = {
                txToDeleteInSession = treatmentToEditInSession
                treatmentToEditInSession = null
            },
            onDismiss = { treatmentToEditInSession = null }
        )
    }
    if (txToDeleteInSession != null) {
        AlertDialog(
            onDismissRequest = { txToDeleteInSession = null },
            title = { Text(if (isPersian) "تایید حذف طرح درمان" else "Confirm Delete Treatment", fontWeight = FontWeight.Bold) },
            text = { Text(if (isPersian) "آیا از حذف درمان «${txToDeleteInSession!!.title}» اطمینان دارید؟ این عملیات غیرقابل بازگشت است." else "Delete treatment '${txToDeleteInSession!!.title}'? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTreatment(txToDeleteInSession!!)
                        txToDeleteInSession = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(if (isPersian) "حذف" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { txToDeleteInSession = null }) { Text(if (isPersian) "انصراف" else "Cancel") }
            }
        )
    }

    if (clinicalProtocolTitleInSession != null) {
        ClinicalProtocolDialog(
            therapyTitle = clinicalProtocolTitleInSession!!,
            isPersian = isPersian,
            onDismiss = { clinicalProtocolTitleInSession = null }
        )
    }

    // ============================================================
    // حذف جلسه — مرحلهٔ ۱: هشدار
    // ============================================================
    if (deleteStep == 1) {
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
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
                            "جلسهٔ «${currentAppt.patientName}» در تاریخ ${viewModel.formatDate(currentAppt.date)} حذف می‌شود."
                        else
                            "The session for ${currentAppt.patientName} on ${viewModel.formatDate(currentAppt.date)} will be deleted.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (isPersian)
                            "این کار برگشت‌پذیر نیست و سابقهٔ این جلسه از آرشیو هم پاک می‌شود. طرح‌های درمانی بیمار حذف نمی‌شوند."
                        else
                            "This cannot be undone; the session is removed from the archive as well. The patient's treatment plans are not deleted.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteConfirmText = ""
                        deleteStep = 2
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "ادامه" else "Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteStep = 0 }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }

    // ============================================================
    // حذف جلسه — مرحلهٔ ۲: تایپ کلمهٔ «حذف»
    // ============================================================
    if (deleteStep == 2) {
        // کلمهٔ تأیید باید دقیقاً «حذف» تایپ شود (فاصله‌های اضافی نادیده گرفته می‌شود).
        val requiredWord = if (isPersian) "حذف" else "DELETE"
        val typedOk = deleteConfirmText.trim() == requiredWord
        AlertDialog(
            onDismissRequest = { deleteStep = 0 },
            icon = {
                Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
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
                            viewModel.deleteAppointment(currentAppt)
                            deleteStep = 0
                            deleteConfirmText = ""
                            onDismiss()
                        }
                    },
                    enabled = typedOk,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isPersian) "حذف قطعی" else "Delete permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteStep = 0; deleteConfirmText = "" }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }
}

@Composable
fun SessionsArchiveScreen(
    viewModel: DentalViewModel,
    isPersian: Boolean,
    onBack: () -> Unit
) {
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val allTreatments by viewModel.allTreatments.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var selectedApptForDetails by remember { mutableStateOf<Appointment?>(null) }

    val filteredAppointments = remember(appointments, searchQuery, selectedFilter) {
        appointments.filter { appt ->
            val matchesQuery = appt.patientName.contains(searchQuery, ignoreCase = true) || appt.treatmentType.contains(searchQuery, ignoreCase = true) || appt.note.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "COMPLETED" -> appt.treatmentType.contains("انجام شده", ignoreCase = true) || appt.treatmentType.contains("Completed", ignoreCase = true)
                "IN_PROGRESS" -> appt.treatmentType.contains("در حال انجام", ignoreCase = true) || appt.treatmentType.contains("In Progress", ignoreCase = true)
                else -> true
            }
            matchesQuery && matchesFilter
        }.sortedByDescending { it.date }
    }

    if (selectedApptForDetails != null) {
        SessionDetailsDialog(
            appointment = selectedApptForDetails!!,
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { selectedApptForDetails = null }
        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = if (isPersian) "آرشیو و سوابق کامل جلسات درمانی" else "Treatment Sessions Archive",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "${filteredAppointments.size} " + if (isPersian) "جلسه ثبت شده" else "sessions",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(if (isPersian) "جستجو در آرشیو نوبت‌ها (نام بیمار، نوع درمان...)" else "Search sessions archive...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedFilter == "ALL",
                onClick = { selectedFilter = "ALL" },
                label = { Text(if (isPersian) "همه جلسات" else "All Sessions", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == "COMPLETED",
                onClick = { selectedFilter = "COMPLETED" },
                label = { Text(if (isPersian) "تکمیل شده" else "Completed", fontSize = 11.sp) }
            )
            FilterChip(
                selected = selectedFilter == "IN_PROGRESS",
                onClick = { selectedFilter = "IN_PROGRESS" },
                label = { Text(if (isPersian) "در حال انجام" else "In Progress", fontSize = 11.sp) }
            )
        }

        if (filteredAppointments.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.LightGray)
                    Text(if (isPersian) "هیچ جلسه‌ای در آرشیو یافت نشد" else "No sessions found in archive", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredAppointments) { appt ->
                    val apptTreatments = remember(allTreatments, appt.plannedTreatmentIds, appt.patientId) {
                        val ids = appt.plannedTreatmentIds.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                        if (ids.isNotEmpty()) allTreatments.filter { it.id in ids } else allTreatments.filter { it.patientId == appt.patientId }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedApptForDetails = appt },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(text = appt.patientName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = viewModel.formatTime(appt.date),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${viewModel.formatDate(appt.date)} • ${if (appt.treatmentType.isNotBlank()) appt.treatmentType else (if (isPersian) "جلسه درمانی" else "Therapy Session")}",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                                if (apptTreatments.isNotEmpty()) {
                                    Text(
                                        text = if (isPersian) "درمان‌های مرتبط: ${apptTreatments.joinToString(", ") { it.title }}" else "Treatments: ${apptTreatments.joinToString(", ") { it.title }}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = "View Details", tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// CLINICAL & AI DIALOG COMPONENTS
// ==========================================

@Composable
fun DigitalConsentSignatureDialog(
    patient: Patient,
    isPersian: Boolean,
    onDismiss: () -> Unit,
    onSaveSignature: (templateName: String, base64Image: String) -> Unit
) {
    val context = LocalContext.current
    val legalTemplates = remember { com.example.data.LegalConsentKnowledgeBase.getTemplates() }
    var selectedTemplateId by remember { mutableStateOf(legalTemplates.first().id) }
    val currentTemplate = remember(selectedTemplateId) { com.example.data.LegalConsentKnowledgeBase.getTemplateById(selectedTemplateId) }

    var nationalIdInput by remember { mutableStateOf(patient.patientCode) }
    var fatherNameInput by remember { mutableStateOf("") }
    var signerRole by remember { mutableStateOf("خود بیمار") }

    val pathList = remember { mutableStateListOf<Path>() }
    var currentPath by remember { mutableStateOf<Path?>(null) }
    var drawTrigger by remember { mutableIntStateOf(0) }
    var signatureBoxSizePx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    val fullLegalText = remember(selectedTemplateId, patient.name, nationalIdInput, fatherNameInput, signerRole) {
        com.example.data.LegalConsentKnowledgeBase.buildFinalLegalText(
            template = currentTemplate,
            patientName = patient.name,
            patientNationalId = nationalIdInput,
            patientFatherName = fatherNameInput,
            signerRole = signerRole,
            doctorName = "",
            medicalNo = "",
            clinicName = "",
            treatmentTitle = currentTemplate.title
        )
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Gavel, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Column {
                            Text(
                                text = if (isPersian) "فرم رسمی رضایت‌نامه و برائت‌نامه پزشکی" else "Official Medical Consent & Waiver",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "مطابق مواد ۴۹۵ و ۴۹۷ قانون مجازات اسلامی و مواد ۷ و ۱۰ قانون تجارت الکترونیک",
                                fontSize = 9.5.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                // 1. Spec fields: National ID, Father Name, Signer Role
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("مشخصات قانونی امضاکننده سند:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = nationalIdInput,
                                onValueChange = { nationalIdInput = it },
                                label = { Text("کد ملی امضاکننده", fontSize = 10.sp) },
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                            )
                            OutlinedTextField(
                                value = fatherNameInput,
                                onValueChange = { fatherNameInput = it },
                                label = { Text("نام پدر", fontSize = 10.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("سمت امضاکننده:", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            FilterChip(
                                selected = signerRole == "خود بیمار",
                                onClick = { signerRole = "خود بیمار" },
                                label = { Text("خود بیمار", fontSize = 10.sp) }
                            )
                            FilterChip(
                                selected = signerRole == "ولی قانونی / سرپرست",
                                onClick = { signerRole = "ولی قانونی / سرپرست" },
                                label = { Text("ولی قانونی / سرپرست", fontSize = 10.sp) }
                            )
                        }
                    }
                }

                // 2. Select Legal Template
                Text(text = "انتخاب نوع فرم حقوقی و برائت‌نامه:", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(4.dp)
                ) {
                    legalTemplates.forEach { t ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTemplateId = t.id }
                                .padding(vertical = 3.dp, horizontal = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedTemplateId == t.id,
                                onClick = { selectedTemplateId = t.id }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text(text = t.title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(text = t.legalArticles, fontSize = 9.sp, color = Color.Gray)
                            }
                        }
                    }
                }

                // 3. Full Legal Text Display Box
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = "📜 متن قانونی و برائت‌نامه (ثبت الکترونیکی):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(text = currentTemplate.legalArticles, fontSize = 9.sp, color = Color.Gray)
                        }
                        Text(
                            text = fullLegalText,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 15.sp
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = Color.LightGray)
                        Text(
                            text = "✓ امضاکننده با ثبت امضا و اثر انگشت الکترونیکی زیر، تمامی مفاد فوق را تایید و برائت صریح پزشک معالج را صادر می‌نماید.",
                            fontSize = 9.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 4. Fingerprint Notice Box + Touch Signature Box
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "✍️ کادر امضای لمسی دیجیتال (با انگشت یا قلم):", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(text = "رسمیت قانونی - ماده ۷ و ۱۰ تجارت الکترونیک", fontSize = 9.sp, color = Color.Gray)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        .onSizeChanged { signatureBoxSizePx = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val newP = Path().apply { moveTo(offset.x, offset.y) }
                                    currentPath = newP
                                    drawTrigger++
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    currentPath?.let { p ->
                                        p.lineTo(change.position.x, change.position.y)
                                    }
                                    drawTrigger++
                                },
                                onDragEnd = {
                                    currentPath?.let { p ->
                                        pathList.add(p)
                                        currentPath = null
                                    }
                                    drawTrigger++
                                }
                            )
                        }
                ) {
                    val dummy = drawTrigger
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (dummy >= 0) {
                            pathList.forEach { path ->
                                drawPath(path = path, color = Color.Blue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
                            }
                            currentPath?.let { path ->
                                drawPath(path = path, color = Color.Blue, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
                            }
                        }
                    }
                    if (pathList.isEmpty() && currentPath == null) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(36.dp))
                            Text(
                                text = "✍️ امضای لمسی بیمار یا ولی قانونی را روی این کادر ثبت نمایید",
                                fontSize = 12.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            if (pathList.isNotEmpty()) {
                                pathList.removeAt(pathList.size - 1)
                                drawTrigger++
                            }
                        },
                        enabled = pathList.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("بازگشت یک مرحله", fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = {
                            pathList.clear()
                            drawTrigger++
                        },
                        enabled = pathList.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("پاک‌کردن امضا", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(if (isPersian) "انصراف" else "Cancel")
                    }
                    Button(
                        onClick = {
                            val bmpWidth = if (signatureBoxSizePx.width > 0) signatureBoxSizePx.width else 800
                            val bmpHeight = if (signatureBoxSizePx.height > 0) signatureBoxSizePx.height else 400
                            val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bmp)
                            canvas.drawColor(android.graphics.Color.TRANSPARENT)
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.BLUE
                                style = android.graphics.Paint.Style.STROKE
                                strokeWidth = 8f
                                isAntiAlias = true
                            }
                            pathList.forEach { p ->
                                canvas.drawPath(p.asAndroidPath(), paint)
                            }
                            val path = com.example.ui.screens.saveBitmapToLocalFile(
                                context = context,
                                bitmap = bmp,
                                maxDim = 1000,
                                format = android.graphics.Bitmap.CompressFormat.PNG,
                                quality = 100
                            )
                            val legalTitleStr = "${currentTemplate.title} | کد ملی: $nationalIdInput | سمت: $signerRole"
                            onSaveSignature(legalTitleStr, path)
                        },
                        enabled = pathList.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isPersian) "ثبت رسمی و ذخیره سند حقوقی" else "Save Legal Consent")
                    }
                }
            }
        }
    }
}

@Composable
fun BeforeAfterComparisonDialog(
    docList: List<DisplayDoc>,
    isPersian: Boolean,
    patient: com.example.data.Patient? = null,
    viewModel: DentalViewModel? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var docListState by remember { mutableStateOf(docList.toMutableList()) }

    var pendingRawData by remember { mutableStateOf<String?>(null) }
    var pendingPhotoTitle by remember { mutableStateOf("تصویر قبل از درمان") }
    var pendingToothNote by remember { mutableStateOf("") }

    // Filter out signatures, consents, and radiography scans so only treatment before/after photos are used
    val filteredBeforeAfterDocs = remember(docListState) {
        docListState.filter { doc ->
            !doc.title.contains("امضا") && !doc.title.contains("رضایت") && !doc.title.contains("اسکن") && !doc.title.contains("OPG") && !doc.title.contains("PA") && !doc.title.contains("CBCT")
        }
    }

    var beforeDoc by remember(filteredBeforeAfterDocs) { mutableStateOf(filteredBeforeAfterDocs.getOrNull(0)) }
    var afterDoc by remember(filteredBeforeAfterDocs) { mutableStateOf(filteredBeforeAfterDocs.getOrNull(1) ?: filteredBeforeAfterDocs.getOrNull(0)) }
    var comparisonMode by remember { mutableIntStateOf(0) } // 0 = Side by Side, 1 = Split Slider
    var sliderRatio by remember { mutableFloatStateOf(0.5f) }

    // Camera and Gallery Launchers for uploading new before/after photos
    val beforeAfterEditLauncher = rememberPhotoEditLauncher(PhotoAssetKind.CLINICAL_PHOTO) { localPath ->
        pendingRawData = localPath
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { beforeAfterEditLauncher(it) }
    }

    val cameraLauncher = rememberEditableCameraLauncher(context) { uri ->
        beforeAfterEditLauncher(uri)
    }

    val beforeBmp = remember(beforeDoc) {
        val path = beforeDoc?.fileId
        decodeLocalThumbnail(path)
    }

    val afterBmpRaw = remember(afterDoc) {
        val path = afterDoc?.fileId
        decodeLocalThumbnail(path)
    }

    // Standardize & harmonize resolution of after image to match before image dimensions
    val afterBmp = remember(beforeBmp, afterBmpRaw) {
        if (beforeBmp != null && afterBmpRaw != null) {
            Bitmap.createScaledBitmap(afterBmpRaw, beforeBmp.width, beforeBmp.height, true)
        } else {
            afterBmpRaw
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Compare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(if (isPersian) "مقایسه هوشمند تصاویر قبل و بعد درمان" else "Before & After Photo Comparison")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Top Camera & Gallery Buttons to immediately capture or select before/after photos
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { cameraLauncher() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("عکس با دوربین", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("انتخاب از گالری", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = comparisonMode == 0,
                        onClick = { comparisonMode = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text("پهلو به پهلو", fontSize = 10.5.sp)
                    }
                    SegmentedButton(
                        selected = comparisonMode == 1,
                        onClick = { comparisonMode = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text("اسلایدر تعاملی", fontSize = 10.5.sp)
                    }
                }

                if (comparisonMode == 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                                Text("قبل از درمان (Before)", fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                if (beforeBmp != null) {
                                    Image(bitmap = beforeBmp.asImageBitmap(), contentDescription = "Before", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text("تصویر قبل انتخاب نشده", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(color = Color(0xFF43A047), shape = RoundedCornerShape(4.dp)) {
                                Text("بعد از درمان (After)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                if (afterBmp != null) {
                                    Image(bitmap = afterBmp.asImageBitmap(), contentDescription = "After", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    Text("تصویر بعد انتخاب نشده", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("⬅️ انگشت خود را روی اسلایدر برای مقایسه جابه‌جا کنید ➡️", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            if (beforeBmp != null && afterBmp != null) {
                                Image(bitmap = afterBmp.asImageBitmap(), contentDescription = "After Image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val splitX = size.width * sliderRatio
                                    clipRect(left = 0f, top = 0f, right = splitX, bottom = size.height) {
                                        drawImage(
                                            image = beforeBmp.asImageBitmap(),
                                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                                        )
                                    }
                                    drawLine(
                                        color = Color.White,
                                        start = androidx.compose.ui.geometry.Offset(splitX, 0f),
                                        end = androidx.compose.ui.geometry.Offset(splitX, size.height),
                                        strokeWidth = 4f
                                    )
                                    drawCircle(
                                        color = Color.White,
                                        radius = 16f,
                                        center = androidx.compose.ui.geometry.Offset(splitX, size.height / 2f)
                                    )
                                }

                                Text(
                                    text = "BEFORE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Text(
                                    text = "AFTER",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .background(Color(0xFF43A047).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            } else {
                                Text("جهت مقایسه اسلایدر، عکس قبل و بعد را از گالری یا دوربین بالا اضافه نمایید", fontSize = 11.sp, color = Color.Gray, textAlign = TextAlign.Center)
                            }
                        }

                        Slider(
                            value = sliderRatio,
                            onValueChange = { sliderRatio = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Helper to save bitmap to cache for sharing
                fun saveBmpToCache(bmp: Bitmap, fileName: String): java.io.File {
                    val f = java.io.File(context.cacheDir, fileName)
                    java.io.FileOutputStream(f).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    return f
                }

                // Separate sharing options for Before, After, and Combined image
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (beforeBmp != null) {
                                val file = saveBmpToCache(beforeBmp, "before_photo.jpg")
                                com.example.reports.shareImageNativeChooser(context, file)
                            } else {
                                android.widget.Toast.makeText(context, "تصویر قبل وجود ندارد", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("📤 تصویر قبل", fontSize = 9.5.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            if (afterBmp != null) {
                                val file = saveBmpToCache(afterBmp, "after_photo.jpg")
                                com.example.reports.shareImageNativeChooser(context, file)
                            } else {
                                android.widget.Toast.makeText(context, "تصویر بعد وجود ندارد", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(2.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("📤 تصویر بعد", fontSize = 9.5.sp)
                    }

                    Button(
                        onClick = {
                            if (beforeBmp != null && afterBmp != null) {
                                val combinedWidth = beforeBmp.width + afterBmp.width
                                val combinedHeight = beforeBmp.height.coerceAtLeast(afterBmp.height)
                                val combined = Bitmap.createBitmap(combinedWidth, combinedHeight, Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(combined)
                                canvas.drawBitmap(beforeBmp, 0f, 0f, null)
                                canvas.drawBitmap(afterBmp, beforeBmp.width.toFloat(), 0f, null)
                                val file = saveBmpToCache(combined, "before_after_combined.jpg")
                                com.example.reports.shareImageNativeChooser(context, file)
                            } else {
                                android.widget.Toast.makeText(context, "برای ارسال ترکیبی هر دو تصویر قبل و بعد را انتخاب کنید", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(2.dp),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("📤 ترکیبی (قبل+بعد)", fontSize = 9.5.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(if (isPersian) "بستن" else "Close")
            }
        }
    )

    if (pendingRawData != null) {
        AlertDialog(
            onDismissRequest = { pendingRawData = null },
            title = { Text(if (isPersian) "مشخصات تصویر درمان" else "Photo Details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isPersian) "نوع تصویر:" else "Photo Type:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = pendingPhotoTitle == "تصویر قبل از درمان",
                            onClick = { pendingPhotoTitle = "تصویر قبل از درمان" },
                            label = { Text("قبل درمان", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = pendingPhotoTitle == "تصویر بعد از درمان",
                            onClick = { pendingPhotoTitle = "تصویر بعد از درمان" },
                            label = { Text("بعد درمان", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = pendingPhotoTitle == "تصویر بالینی",
                            onClick = { pendingPhotoTitle = "تصویر بالینی" },
                            label = { Text("بالینی", fontSize = 11.sp) }
                        )
                    }

                    OutlinedTextField(
                        value = pendingToothNote,
                        onValueChange = { pendingToothNote = it },
                        label = { Text(if (isPersian) "شماره دندان و/یا نام درمان" else "Tooth / Treatment Note") },
                        placeholder = { Text(if (isPersian) "مثال: دندان ۱۶ - پرکردن کامپوزیت" else "e.g. Tooth 16 - Composite") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (viewModel != null) {
                        val isUploadingPhoto by viewModel.isMediaUploading.collectAsStateWithLifecycle()
                        DocumentUploadProgressBar(isUploading = isUploadingPhoto, isPersian = isPersian)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (patient != null && viewModel != null) {
                            viewModel.addPatientDocument(
                                patientId = patient.id,
                                localFilePath = pendingRawData ?: "",
                                docType = "PHOTO",
                                title = pendingPhotoTitle,
                                notes = pendingToothNote
                            )
                        } else {
                            val newDoc = DisplayDoc(pendingPhotoTitle, System.currentTimeMillis(), pendingToothNote, fileId = pendingRawData, docId = null)
                            val updated = docListState.toMutableList().apply { add(newDoc) }
                            docListState = updated
                        }
                        android.widget.Toast.makeText(context, "تصویر با موفقیت در پرونده ذخیره شد", android.widget.Toast.LENGTH_SHORT).show()
                        pendingRawData = null
                        pendingToothNote = ""
                    }
                ) {
                    Text(if (isPersian) "ذخیره در پرونده" else "Save to Patient File")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRawData = null }) {
                    Text(if (isPersian) "انصراف" else "Cancel")
                }
            }
        )
    }
}

fun formatIranianPhoneNumber(rawPhone: String): String {
    val digits = rawPhone.filter { it.isDigit() }
    val normalized = when {
        digits.startsWith("0098") -> "+98${digits.removePrefix("0098")}"
        digits.startsWith("98") -> "+98${digits.removePrefix("98")}"
        digits.startsWith("0") -> "+98${digits.removePrefix("0")}"
        else -> "+98$digits" // Assuming local format if no code
    }
    return normalized
}

fun openWhatsAppChatWithMessage(context: Context, rawPhone: String, message: String) {
    val normalized = formatIranianPhoneNumber(rawPhone)
    val textToSend = if (normalized.isNotBlank() && normalized != "+98") "گیرنده: $normalized\n\n$message" else message
    com.example.reports.shareTextNativeChooser(context, textToSend, "اشتراک‌گذاری پیام")
}

fun openTelegramChatWithMessage(context: Context, rawPhone: String, message: String) {
    openWhatsAppChatWithMessage(context, rawPhone, message)
}

fun openBaleChatWithMessage(context: Context, rawPhone: String, message: String) {
    openWhatsAppChatWithMessage(context, rawPhone, message)
}

fun openRubikaChatWithMessage(context: Context, rawPhone: String, message: String) {
    openWhatsAppChatWithMessage(context, rawPhone, message)
}

fun openMessengerChat(context: Context, rawPhone: String, message: String, messenger: String) {
    when (messenger.trim()) {
        "پیامک", "SMS" -> com.example.reports.sendSmsPrefilled(context, rawPhone, message)
        else -> openWhatsAppChatWithMessage(context, rawPhone, message)
    }
}

@Composable
fun EditRegionAnatomyDialog(isPersian: Boolean, region: String, viewModel: com.example.ui.DentalViewModel, onDismiss: () -> Unit) {
    var selectedTooth by remember { mutableStateOf<Int?>(null) }
    
    if (selectedTooth == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (isPersian) "تنظیمات کانال‌های ریشه: $region" else "Root Canal Settings: $region", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isPersian) "یک دندان را برای ویرایش کانال‌های آن انتخاب کنید:" else "Select a tooth to edit its canals:", fontSize = 12.sp)
                    val teethList = (11..18).toList() + (21..28).toList() + (31..38).toList() + (41..48).toList()
                    teethList.chunked(4).forEach { rowTeeth ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            rowTeeth.forEach { t ->
                                OutlinedButton(onClick = { selectedTooth = t }, modifier = Modifier.weight(1f).padding(2.dp), contentPadding = PaddingValues(2.dp)) {
                                    Text("$t", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(if (isPersian) "بستن" else "Close") } }
        )
    } else {
        val tooth = selectedTooth!!
        var canals by remember(tooth) { mutableStateOf(EndoAnatomyManager.canalSpecsForTooth(tooth)) }
        var showAddCanal by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { selectedTooth = null },
            title = { Text(if (isPersian) "ویرایش دندان $tooth" else "Edit Tooth $tooth", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    canals.forEachIndexed { index, canal ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("کانال: ${canal.name}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    IconButton(onClick = { canals = canals.filterIndexed { i, _ -> i != index } }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                    }
                                }
                                
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = canal.minLength.toString(),
                                        onValueChange = { canals = canals.toMutableList().apply { set(index, canal.copy(minLength = it.toDoubleOrNull() ?: 0.0)) } },
                                        label = { Text("Min L (mm)", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                                    )
                                    OutlinedTextField(
                                        value = canal.maxLength.toString(),
                                        onValueChange = { canals = canals.toMutableList().apply { set(index, canal.copy(maxLength = it.toDoubleOrNull() ?: 0.0)) } },
                                        label = { Text("Max L (mm)", fontSize = 10.sp) },
                                        modifier = Modifier.weight(1f),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                                    )
                                }
                                OutlinedTextField(
                                    value = canal.mafFile,
                                    onValueChange = { canals = canals.toMutableList().apply { set(index, canal.copy(mafFile = it)) } },
                                    label = { Text("MAF", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                                )
                                OutlinedTextField(
                                    value = canal.probabilityNote ?: "",
                                    onValueChange = { canals = canals.toMutableList().apply { set(index, canal.copy(probabilityNote = it)) } },
                                    label = { Text("احتمال / فراوانی", fontSize = 10.sp) },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                                )
                            }
                        }
                    }
                    
                    TextButton(onClick = { 
                        canals = canals + CanalAnatomySpec(name = "New", minLength = 20.0, maxLength = 24.0, avgLength = 22.0, refPoint = "Cusp Tip", mafFile = "#30", isDefault = false, probabilityNote = "")
                    }) {
                        Text(if (isPersian) "+ افزودن کانال جدید" else "+ Add New Canal")
                    }
                }
            },
            confirmButton = { 
                TextButton(onClick = { 
                    EndoAnatomyManager.saveOverrideForTooth(region, tooth, canals)
                    viewModel.updateClinicSettings(viewModel.settings.value.copy(endoAnatomyOverrides = EndoAnatomyManager.getOverridesJsonString()))
                    selectedTooth = null
                }) { 
                    Text(if (isPersian) "ذخیره تغییرات دندان" else "Save Tooth") 
                } 
            },
            dismissButton = { 
                TextButton(onClick = { selectedTooth = null }) { 
                    Text(if (isPersian) "بازگشت" else "Back") 
                } 
            }
        )
    }
}

@Composable
fun SmsTemplatesDialog(
    isPersian: Boolean,
    clinicName: String,
    apptTemplate: String, onApptChange: (String) -> Unit,
    invoiceTemplate: String, onInvoiceChange: (String) -> Unit,
    treatmentPlanTemplate: String, onTreatmentPlanChange: (String) -> Unit,
    installmentTemplate: String, onInstallmentChange: (String) -> Unit,
    overdueInstallmentTemplate: String = "", onOverdueInstallmentChange: (String) -> Unit = {},
    postOpTemplate: String, onPostOpChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isPersian) "الگوهای پیامک و اطلاع‌رسانی" else "SMS & Notification Templates", fontWeight = FontWeight.Bold, fontSize = 14.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 0.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) { Text(if (isPersian) "یادآوری نوبت" else "Reminder", fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) { Text(if (isPersian) "صورتحساب" else "Invoice", fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) { Text(if (isPersian) "طرح درمان" else "Plan", fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
                    Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }) { Text(if (isPersian) "سررسید اقساط" else "Installment", fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
                    Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }) { Text(if (isPersian) "اقساط معوق" else "Overdue", fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
                    Tab(selected = selectedTab == 5, onClick = { selectedTab = 5 }) { Text(if (isPersian) "توصیه مراقبتی" else "Post-Op", fontSize = 10.sp, modifier = Modifier.padding(8.dp)) }
                }

                Column(modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
                    when (selectedTab) {
                        0 -> TemplateEditor(
                            isPersian = isPersian,
                            value = apptTemplate,
                            onValueChange = onApptChange,
                            vars = "{clinic} : نام مطب | {patient} : نام بیمار | {date} : تاریخ نوبت | {time} : ساعت نوبت",
                            preview = apptTemplate.replace("{clinic}", clinicName).replace("{patient}", "آقای علی کریمی").replace("{date}", "۱۴ مهر").replace("{time}", "۱۶:۳۰")
                        )
                        1 -> TemplateEditor(
                            isPersian = isPersian,
                            value = invoiceTemplate,
                            onValueChange = onInvoiceChange,
                            vars = "{clinic} : نام مطب | {patient} : نام بیمار | {amount} : مبلغ قابل پرداخت | {paid} : مبلغ پرداخت شده",
                            preview = invoiceTemplate.replace("{clinic}", clinicName).replace("{patient}", "آقای علی کریمی").replace("{amount}", "۲,۵۰۰,۰۰۰ تومان").replace("{paid}", "۱,۰۰۰,۰۰۰ تومان")
                        )
                        2 -> TemplateEditor(
                            isPersian = isPersian,
                            value = treatmentPlanTemplate,
                            onValueChange = onTreatmentPlanChange,
                            vars = "{clinic} : نام مطب | {patient} : نام بیمار | {treatments} : لیست درمان‌ها",
                            preview = treatmentPlanTemplate.replace("{clinic}", clinicName).replace("{patient}", "آقای علی کریمی").replace("{treatments}", "عصب‌کشی دندان ۶ بالا, ترمیم کامپوزیت")
                        )
                        3 -> TemplateEditor(
                            isPersian = isPersian,
                            value = installmentTemplate,
                            onValueChange = onInstallmentChange,
                            vars = "{clinic} : مطب | {patient} : بیمار | {amount} : مبلغ قسط | {date} : سررسید",
                            preview = installmentTemplate.replace("{clinic}", clinicName).replace("{patient}", "آقای علی کریمی").replace("{amount}", "۱,۵۰۰,۰۰۰ تومان").replace("{date}", "۳۰ آبان")
                        )
                        4 -> TemplateEditor(
                            isPersian = isPersian,
                            value = overdueInstallmentTemplate,
                            onValueChange = onOverdueInstallmentChange,
                            vars = "{clinic} : مطب | {patient} : بیمار | {amount} : مبلغ قسط | {date} : سررسید | {delay_days} : روز تاخیر",
                            preview = overdueInstallmentTemplate.replace("{clinic}", clinicName).replace("{patient}", "آقای علی کریمی").replace("{amount}", "۱,۵۰۰,۰۰۰ تومان").replace("{date}", "۳۰ آبان").replace("{delay_days}", "۱۲")
                        )
                        5 -> TemplateEditor(
                            isPersian = isPersian,
                            value = postOpTemplate,
                            onValueChange = onPostOpChange,
                            vars = "{clinic} : مطب | {patient} : بیمار | {instructions} : توصیه‌ها",
                            preview = postOpTemplate.replace("{clinic}", clinicName).replace("{patient}", "آقای علی کریمی").replace("{instructions}", "از خوردن غذای سفت تا ۲ ساعت خودداری کنید.")
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(if (isPersian) "تایید" else "OK") }
        }
    )
}

@Composable
fun TemplateEditor(isPersian: Boolean, value: String, onValueChange: (String) -> Unit, vars: String, preview: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (isPersian) "متغیرهای قابل استفاده:" else "Available variables:", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        Text(vars, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(140.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
        Text(if (isPersian) "پیش‌نمایش:" else "Preview:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Text(preview, modifier = Modifier.padding(12.dp), fontSize = 12.sp)
        }
    }
}


@Composable
fun DrugTemplatesEditorScreen(viewModel: DentalViewModel) {
    val templates by viewModel.drugTemplates.collectAsStateWithLifecycle(initialValue = emptyList())
    val isPersian by viewModel.isPersian.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(if (isPersian) "مدیریت فایل‌های دارویی" else "Drug Templates", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.currentTab.value = "more" }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val categories = templates.groupBy { it.category }
            categories.forEach { (cat, list) ->
                val catName = when(cat) {
                    "ANALGESIC" -> if (isPersian) "مسکن‌ها" else "Analgesics"
                    "ANTIBIOTIC" -> if (isPersian) "آنتی‌بیوتیک‌ها" else "Antibiotics"
                    "MOUTHWASH" -> if (isPersian) "دهان‌شویه‌ها" else "Mouthwashes"
                    else -> if (isPersian) "سایر داروها" else "Other"
                }
                item {
                    Text(catName, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(list) { template ->
                    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(template.title, fontWeight = FontWeight.Bold)
                                Text("${template.dosage} - ${template.timing}", fontSize = 12.sp, color = Color.Gray)
                                if (template.instructions.isNotBlank()) {
                                    Text(template.instructions, fontSize = 12.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                }
                            }
                            IconButton(onClick = { viewModel.deleteDrugTemplate(template) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            var category by remember { mutableStateOf("OTHER") }
            var title by remember { mutableStateOf("") }
            var dosage by remember { mutableStateOf("") }
            var timing by remember { mutableStateOf("") }
            var instructions by remember { mutableStateOf("") }
            var durDays by remember { mutableStateOf("5") }

            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text(if (isPersian) "داروی جدید" else "New Drug") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("ANALGESIC" to "مسکن", "ANTIBIOTIC" to "آنتی‌بیوتیک", "MOUTHWASH" to "دهان‌شویه", "OTHER" to "سایر").forEach { (k, v) ->
                                FilterChip(
                                    selected = category == k,
                                    onClick = { category = k },
                                    label = { Text(if (isPersian) v else k, fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("نام و دوز اصلی") })
                        OutlinedTextField(value = dosage, onValueChange = { dosage = it }, label = { Text("مقدار مصرف") })
                        OutlinedTextField(value = timing, onValueChange = { timing = it }, label = { Text("زمان مصرف") })
                        OutlinedTextField(value = instructions, onValueChange = { instructions = it }, label = { Text("دستورات متفرقه") })
                        OutlinedTextField(value = durDays, onValueChange = { durDays = it }, label = { Text("طول درمان پیش‌فرض (روز)") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val d = durDays.toIntOrNull() ?: 5
                        viewModel.saveDrugTemplate(com.example.data.DrugTemplate(category = category, title = title, dosage = dosage, timing = timing, instructions = instructions, defaultDurationDays = d))
                        showAddDialog = false
                    }) { Text("ذخیره") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("انصراف") }
                }
            )
        }
    }
}
