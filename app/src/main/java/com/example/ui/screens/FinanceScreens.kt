@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.ui.DentalViewModel

/**
 * صفحه‌های ماژول «بیشتر»: هزینه‌های مطب، تراکنش‌های شخصی، تعمیر و نگهداری تجهیزات.
 */

// هزینه‌های مطب — چیدمان: (۱) فرمِ «ثبت هزینه جاری مطب»، (۲) «پرداخت هزینه‌ها» زیرِ
// فرم و بالای صفحه؛ انتخابِ طرف‌حساب از بینِ طرف‌حساب‌های موجود و پرداختِ کل/جزئی.
/**
 * هابِ «هزینه‌های مطب» در مدل طرف‌حساب (طرف‌حساب چندطرف‌حسابی):
 * هر بخش (لابراتوار / فنی مهندسی و تعمیرات / فروشگاه مواد دندانپزشکی / جاری / سایر)
 * چند «طرف حساب» دارد — مثل «لابراتوار آ»، «فنی مهندسی ب»، «فروشگاه آ». فاکتور،
 * پرداخت و چکِ هر طرف‌حساب کاملاً جدا ثبت و محاسبه می‌شود و مانده فقط از توالیِ
 * فاکتورها منهای پرداختی‌ها به‌دست می‌آید (بدون ذخیرهٔ وضعیت تسویه).
 */
@Composable
fun ExpensesScreen(viewModel: DentalViewModel) {
    var selectedSectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val section = selectedSectionKey?.let { PartySection.fromKey(it) }
    if (section == null) {
        ExpensesHubScreen(viewModel, onOpenSection = { selectedSectionKey = it.key })
    } else {
        PartySectionDetailScreen(viewModel, section, onBack = { selectedSectionKey = null })
    }
}

@Composable
private fun ExpensesHubScreen(
    viewModel: DentalViewModel,
    onOpenSection: (PartySection) -> Unit
) {
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val parties by viewModel.partyAccounts.collectAsStateWithLifecycle()
    val expenses by viewModel.clinicExpenses.collectAsStateWithLifecycle()
    val expensePayments by viewModel.allExpensePayments.collectAsStateWithLifecycle()
    val currency = viewModel.label("currency")
    fun t(fa: String, en: String) = if (isPersian) fa else en

    val activeExpenses = expenses.filter { !it.isDeleted }
    val activePayments = expensePayments.filter { !it.isDeleted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = t("هزینه‌های مطب", "Clinic Expenses"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = t(
                "یک بخش را انتخاب کنید. هر بخش چند «طرف حساب» (طرف‌حساب) دارد — مثل «لابراتوار آ»، «فنی مهندسی ب»، «فروشگاه آ». فاکتور، پرداخت و چک هر طرف‌حساب کاملاً جدا ثبت و محاسبه می‌شود.",
                "Pick a section. Each section holds multiple party accounts (e.g. Lab A, Eng B, Store A). Invoices, payments and cheques are tracked per party."
            ),
            fontSize = 11.sp,
            color = Color.Gray
        )

        // چهار کارتِ اصلیِ بخش‌ها — دو ردیفِ دوتایی
        PartySection.primary.chunked(2).forEach { rowSections ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowSections.forEach { s ->
                    PartySectionCard(
                        section = s,
                        ledger = partySectionLedger(s, parties, activeExpenses, activePayments),
                        partyCount = parties.count { it.section.trim() == s.key },
                        currency = currency,
                        isPersian = isPersian,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenSection(s) }
                    )
                }
                if (rowSections.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        // کارتِ باریکِ «سایر»
        val otherLedger = partySectionLedger(PartySection.OTHER, parties, activeExpenses, activePayments)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            onClick = { onOpenSection(PartySection.OTHER) }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Column {
                        Text(PartySection.OTHER.title(isPersian), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(
                            t("افزودن سرفصل و ثبت سایر هزینه‌های مطب", "Add heading & register other expenses"),
                            fontSize = 9.5.sp, color = Color.Gray
                        )
                    }
                }
                Text(
                    "${formatAmount(otherLedger.orders)} $currency",
                    fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PartySectionCard(
    section: PartySection,
    ledger: PartyLedger,
    partyCount: Int,
    currency: String,
    isPersian: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    val remaining = ledger.remaining
    val icon = when (section) {
        PartySection.LAB -> Icons.Default.Science
        PartySection.ENGINEERING -> Icons.Default.Handyman
        PartySection.STORE -> Icons.Default.MedicalServices
        PartySection.RUNNING -> Icons.Default.Receipt
        PartySection.SALARY -> Icons.Default.Groups
        PartySection.OTHER -> Icons.Default.MoreHoriz
    }
    Card(
        modifier = modifier.height(132.dp).clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                Text(
                    section.title(isPersian),
                    fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${formatAmount(ledger.orders)} $currency", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.error, maxLines = 1)
                Text(
                    text = when {
                        remaining > 0L -> "${t("مانده:", "Due:")} ${formatAmount(remaining)} $currency"
                        remaining < 0L -> "${t("بستانکار:", "Credit:")} ${formatAmount(-remaining)} $currency"
                        else -> "${t("مانده:", "Due:")} 0 $currency"
                    },
                    fontSize = 9.5.sp,
                    color = when {
                        remaining > 0L -> Color(0xFFC2410C)
                        remaining < 0L -> Color(0xFF6A1B9A)
                        else -> Color(0xFF2E7D32)
                    }
                )
                Text("${t("طرف‌حساب‌ها:", "Parties:")} ${if (isPersian) com.example.util.PersianNumberFormatter.toPersianDigits(partyCount.toString()) else partyCount}", fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun PartySectionDetailScreen(
    viewModel: DentalViewModel,
    section: PartySection,
    onBack: () -> Unit
) {
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val parties by viewModel.partyAccounts.collectAsStateWithLifecycle()
    val allExpensesRaw by viewModel.clinicExpenses.collectAsStateWithLifecycle()
    val expensePayments by viewModel.allExpensePayments.collectAsStateWithLifecycle()
    val currency = viewModel.label("currency")
    fun t(fa: String, en: String) = if (isPersian) fa else en

    val sectionPartiesRaw = parties.filter { it.section.trim() == section.key }
    // «حقوق پرسنل»: منبع واحد = لیست پرسنل مطب؛ عکس‌ها و افزودن/حذف از همان‌جا هماهنگ می‌شود.
    val staffMembers by viewModel.allStaffMembers.collectAsStateWithLifecycle()
    LaunchedEffect(section, staffMembers) {
        if (section == PartySection.SALARY) viewModel.syncSalaryParties()
    }
    val sectionParties = (
        if (section == PartySection.SALARY) {
            val staffNames = staffMembers.filter { !it.isDeleted }.map { it.fullName.trim().lowercase() }.toSet()
            sectionPartiesRaw.filter { it.name.trim().lowercase() in staffNames }
        } else sectionPartiesRaw
    ).sortedBy { it.name }

    var showAddParty by remember { mutableStateOf(false) }
    var selectedParty by remember { mutableStateOf<PartyAccount?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("بازگشت", "Back"))
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = section.title(isPersian),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // به‌درخواست کاربر: در بخش‌های «لابراتوار» و «فنی مهندسی و تعمیرات» دکمهٔ
            // «افزودن طرف حساب» حذف شد — طرف‌حساب‌ها از بخش‌های اصلی خودشان (لابراتوار/تعمیرات)
            // ساخته می‌شوند تا منبع داده در کل اپ یکی باشد. «حقوق پرسنل» هم با لیست پرسنل مطب همگام است.
            if (section != PartySection.LAB && section != PartySection.ENGINEERING && section != PartySection.SALARY) {
                Button(
                    onClick = { showAddParty = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        if (section == PartySection.RUNNING) Icons.Default.Add else Icons.Default.PersonAdd,
                        contentDescription = null, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (section == PartySection.RUNNING) t("افزودن هزینه", "Add expense")
                        else t("افزودن طرف حساب", "Add party account"),
                        fontWeight = FontWeight.Bold, fontSize = 11.sp
                    )
                }
            }
        }

        if (section == PartySection.LAB || section == PartySection.ENGINEERING || section == PartySection.SALARY) {
            Text(
                text = when (section) {
                    PartySection.LAB -> t(
                        "لابراتوارها از بخش «لابراتوار» اپ (دکمهٔ لابراتوار جدید) ساخته می‌شوند و این‌جا فقط صورتحساب آن‌ها نمایش داده می‌شود.",
                        "Labs are created in the app's Lab section; only their statements are shown here."
                    )
                    PartySection.ENGINEERING -> t(
                        "تکنسین‌ها/تعمیرکاران از بخش «تعمیرات و نگهداری» اپ ساخته می‌شوند و این‌جا فقط صورتحساب آن‌ها نمایش داده می‌شود.",
                        "Technicians are created in the Maintenance section; only their statements are shown here."
                    )
                    else -> t(
                        "پرسنل و عکس‌ها خودکار با «لیست پرسنل مطب» هماهنگ‌اند (افزودن/حذف از همان‌جا). برای هر نفر فیش حقوقی و پرداخت (کارت به کارت، نقدی، انتقال چک) ثبت می‌شود.",
                        "Personnel & photos sync automatically with the clinic staff list. Each person gets payslips and payments (card, cash, cheque)."
                    )
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = t(
                "طرف‌حساب‌ها این بخش — با لمس هر طرف‌حساب، دفتر آن (فاکتورها، پرداخت‌ها، چک‌ها و مانده) باز می‌شود:",
                "Party accounts in this section — tap a party to open its ledger:"
            ),
            fontSize = 10.5.sp,
            color = Color.Gray
        )

        if (sectionParties.isEmpty()) {
            Text(
                text = when (section) {
                    PartySection.LAB -> t(
                        "هنوز لابراتواری ثبت نشده است. لابراتوارها از بخش «لابراتوار» اپ ساخته می‌شوند.",
                        "No labs yet. Labs are created in the app's Lab section."
                    )
                    PartySection.ENGINEERING -> t(
                        "هنوز تکنسین/تعمیرکاری ثبت نشده است. تکنسین‌ها از بخش «تعمیرات و نگهداری» اپ ساخته می‌شوند.",
                        "No technicians yet. Technicians are created in the Maintenance section."
                    )
                    PartySection.SALARY -> t(
                        "هنوز پرسنلی ثبت نشده است. پرسنل از «لیست پرسنل مطب» (بخش پرسنل) اضافه می‌شوند و خودکار این‌جا می‌آیند.",
                        "No personnel yet. Add staff in the clinic staff list; they appear here automatically."
                    )
                    else -> t(
                        "هنوز طرف‌حسابی در این بخش ثبت نشده است. با دکمهٔ «افزودن» اولین مورد را بسازید.",
                        "No party accounts yet. Use \"Add\" to create the first one."
                    )
                },
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        sectionParties.forEach { party ->
            val ledger = partyLedgerOf(party, allExpensesRaw, expensePayments)
            val invoiceCount = allExpensesRaw.count { !it.isDeleted && it.belongsToParty(party) }
            val balanceColor = when {
                ledger.isPartyDebtor -> Color(0xFF6A1B9A)
                ledger.remaining > 0L -> Color(0xFFC2410C)
                else -> Color(0xFF2E7D32)
            }
            Card(
                modifier = Modifier.fillMaxWidth().clickable { selectedParty = party },
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (section == PartySection.SALARY) {
                                // عکس پرسنل — هماهنگ با لیست پرسنل مطب
                                val staff = staffMembers.firstOrNull {
                                    !it.isDeleted && it.fullName.trim().equals(party.name.trim(), ignoreCase = true)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!staff?.photoFileId.isNullOrBlank()) {
                                        SmartImage(
                                            fileId = staff?.photoFileId,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                            if (isUtilityParty(party)) {
                                Icon(
                                    utilityIconOf(party.name),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(party.displayLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                // به‌درخواست کاربر: کدهای خودکار (LABA، ENGB و…) نمایش داده نمی‌شوند.
                                if (party.phone.isNotBlank()) {
                                    Text(
                                        text = party.phone,
                                        fontSize = 9.5.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${formatAmount(ledger.orders)} $currency", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Text(
                                text = when {
                                    ledger.isPartyDebtor -> "${t("بستانکار", "Credit")}: ${formatAmount(ledger.partyDebtAmount)} $currency"
                                    ledger.remaining > 0L -> "${t("مانده", "Due")}: ${formatAmount(ledger.debtAmount)} $currency"
                                    else -> "${t("مانده", "Due")}: 0 $currency"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = balanceColor
                            )
                        }
                    }
                    Text(
                        text = "${t("فاکتورها:", "Invoices:")} ${if (isPersian) com.example.util.PersianNumberFormatter.toPersianDigits(invoiceCount.toString()) else invoiceCount}" +
                            "  |  ${t("پرداختی:", "Paid:")} ${formatAmount(ledger.paid)} $currency",
                        fontSize = 9.5.sp,
                        color = Color.Gray
                    )
                }
            }
        }
        // به‌درخواست کاربر: در «هزینه‌های جاری مطب» قابلیت افزودن در انتهای لیست هم باشد.
        if (section == PartySection.RUNNING) {
            OutlinedButton(
                onClick = { showAddParty = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(t("افزودن هزینهٔ جدید", "Add new expense"), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showAddParty) {
        AddPartyAccountDialog(
            viewModel = viewModel,
            section = section,
            isPersian = isPersian,
            onDismiss = { showAddParty = false }
        )
    }
    if (selectedParty != null) {
        PartyLedgerDialog(
            viewModel = viewModel,
            party = selectedParty!!,
            isPersian = isPersian,
            currency = currency,
            onDismiss = { selectedParty = null }
        )
    }
}

/**
 * آیکونِ مرتبطِ هر عنوانِ «هزینه‌های جاری مطب» — اجاره، شارژ ساختمان و قبوض.
 * بر اساس کلیدواژهٔ عنوان تشخیص داده می‌شود (بدون تغییر دیتابیس).
 */
fun utilityIconOf(title: String): androidx.compose.ui.graphics.vector.ImageVector {
    val t = title.trim()
    return when {
        t.contains("اجاره") -> Icons.Default.Home
        t.contains("شارژ ساختمان") -> Icons.Default.Apartment
        t.contains("برق") -> Icons.Default.Bolt
        t.contains("گاز") -> Icons.Default.LocalFireDepartment
        t.contains("آب") -> Icons.Default.WaterDrop
        t.contains("تلفن") -> Icons.Default.Phone
        t.contains("موبایل") -> Icons.Default.Smartphone
        t.contains("اینترنت") -> Icons.Default.Wifi
        else -> Icons.Default.ReceiptLong
    }
}

/** آیا این طرف‌حسابِ بخشِ جاری، از عناوین استانداردِ اجاره/شارژ/قبض است؟ */
fun isUtilityParty(party: com.example.data.PartyAccount): Boolean =
    party.section.trim() == PartySection.RUNNING.key &&
        listOf("اجاره", "شارژ ساختمان", "قبض").any { party.name.contains(it) }

/** دیالوگ افزودن طرف حساب جدید به یک بخش — با فرم اختصاصیِ هر بخش. */
@Composable
private fun AddPartyAccountDialog(
    viewModel: DentalViewModel,
    section: PartySection,
    isPersian: Boolean,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en

    // ——— فرم اختصاصی هر بخش (درخواست کاربر): فنی مهندسی / لابراتوار / فروشگاه /
    //     هزینه‌های جاری (اجاره، شارژ و قبوض) — هرکدام با فیلدهای متناسب خودشان ———
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }   // رابط لابراتوار / تخصص فنی
    var address by remember { mutableStateOf("") }
    var subcode by remember { mutableStateOf("") }   // کد طرف‌حساب نزد پیمانکار
    var notes by remember { mutableStateOf("") }

    val runningDefaults = listOf(
        "اجاره مطب", "شارژ ساختمان مطب", "قبض برق مطب", "قبض گاز مطب",
        "قبض آب مطب", "قبض تلفن مطب", "قبض موبایل مطب", "قبض اینترنت مطب"
    )

    val dialogTitle = when (section) {
        PartySection.RUNNING -> t("افزودن هزینه (هزینه‌های جاری مطب)", "Add expense (running costs)")
        PartySection.LAB -> t("افزودن لابراتوار", "Add dental lab")
        PartySection.ENGINEERING -> t("افزودن فنی / تعمیرات", "Add engineering & repair")
        PartySection.STORE -> t("افزودن فروشگاه مواد دندانپزشکی", "Add dental supply store")
        else -> t("افزودن طرف حساب", "Add party account")
    }
    val nameLabel = when (section) {
        PartySection.RUNNING -> t("عنوان هزینه (اجاره، شارژ، قبض…)", "Expense title (rent, charge, bills…)")
        PartySection.LAB -> t("نام لابراتوار", "Lab name")
        PartySection.ENGINEERING -> t("نام تعمیرگاه / تکنسین", "Workshop / technician name")
        PartySection.STORE -> t("نام فروشگاه", "Store name")
        else -> t("نام طرف حساب", "Party name")
    }

    fun composedNotes(): String = listOf(
        contact.takeIf { it.isNotBlank() }?.let {
            when (section) {
                PartySection.LAB -> "رابط: $it"
                PartySection.ENGINEERING -> "تخصص: $it"
                else -> "کد طرف‌حساب: $it"
            }
        },
        subcode.takeIf { it.isNotBlank() && section != PartySection.ENGINEERING }?.let { "کد طرف‌حساب: $it" },
        address.takeIf { it.isNotBlank() }?.let { "آدرس: $it" },
        notes.takeIf { it.isNotBlank() }
    ).filterNotNull().joinToString(" | ")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (section == PartySection.RUNNING) Icons.Default.ReceiptLong else Icons.Default.PersonAdd,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                )
                Text(dialogTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                Text(
                    text = t("بخش: ${section.title(isPersian)}", "Section: ${section.title(isPersian)}"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                // عنوان هزینه — در بخش جاری با پیشنهادِ عناوین استاندارد (با آیکان مرتبط)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(nameLabel) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                if (section == PartySection.RUNNING) {
                    Text(t("عناوین استاندارد:", "Standard titles:"), fontSize = 10.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        runningDefaults.forEach { title ->
                            FilterChip(
                                selected = name.trim() == title,
                                onClick = { name = title },
                                label = { Text(title, fontSize = 9.sp) },
                                leadingIcon = { Icon(utilityIconOf(title), contentDescription = null, modifier = Modifier.size(13.dp)) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text(t("تلفن (اختیاری — برای ارسال صورتحساب)", "Phone (optional — for statements)")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )

                when (section) {
                    PartySection.LAB -> {
                        OutlinedTextField(
                            value = contact, onValueChange = { contact = it },
                            label = { Text(t("نام رابط / تکنسین لابراتوار", "Contact person at the lab")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = subcode, onValueChange = { subcode = it },
                            label = { Text(t("کد لابراتوار نزد شما (اختیاری)", "Your internal lab code (optional)")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = address, onValueChange = { address = it },
                            label = { Text(t("آدرس لابراتوار", "Lab address")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    PartySection.ENGINEERING -> {
                        OutlinedTextField(
                            value = contact, onValueChange = { contact = it },
                            label = { Text(t("تخصص (تعمیرات یونیت، تجهیزات، تاسیسات…)", "Specialty (dental unit, equipment, facilities…)")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = address, onValueChange = { address = it },
                            label = { Text(t("آدرس / محل سرویس", "Address / service location")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    PartySection.STORE -> {
                        OutlinedTextField(
                            value = contact, onValueChange = { contact = it },
                            label = { Text(t("نام فروشنده / رابط", "Sales contact")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                        OutlinedTextField(
                            value = address, onValueChange = { address = it },
                            label = { Text(t("آدرس فروشگاه / سایت", "Store address / website")) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    else -> {}
                }

                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text(t("یادداشت (اختیاری)", "Notes (optional)")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )

                Text(
                    text = t("پیش‌نمایش: «${partyDisplayLabel(section.faTitle, name.trim())}»", "Preview: \"${partyDisplayLabel(section.faTitle, name.trim())}\""),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.addPartyAccount(section, name, phone, notes = composedNotes())
                    onDismiss()
                },
                enabled = name.trim().isNotBlank()
            ) { Text(t("ثبت", "Save"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("انصراف", "Cancel")) }
        }
    )
}

/** یک سطر دفتر طرف‌حساب: تاریخ، شرح، مبلغ (مثبت = فاکتور، منفی = پرداخت). */
private data class PartyLedgerRowUi(val date: Long, val label: String, val amount: Long)

/**
 * دفتر یک طرف حساب — فاکتورها (+) و پرداختی‌ها (−) به‌ترتیب تاریخ با ترازِ تجمعی.
 * مانده فقط از همین توالی محاسبه می‌شود؛ «تسویه» به‌عنوان وضعیت ذخیره/نمایش داده نمی‌شود.
 */
@Composable
private fun PartyLedgerDialog(
    viewModel: DentalViewModel,
    party: PartyAccount,
    isPersian: Boolean,
    currency: String,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    val allExpensesRaw by viewModel.clinicExpenses.collectAsStateWithLifecycle()
    val expensePayments by viewModel.allExpensePayments.collectAsStateWithLifecycle()
    val bankAccounts by viewModel.bankAccounts.collectAsStateWithLifecycle()
    val allPayments by viewModel.allPayments.collectAsStateWithLifecycle()

    val partyInvoices = allExpensesRaw.filter { !it.isDeleted && it.belongsToParty(party) }.sortedBy { it.date }
    val partyInvoiceIds = partyInvoices.map { it.id }.toSet()
    val partyPayments = expensePayments
        .filter { !it.isDeleted && it.countsAsPartyPayment() && ((it.partyAccountId != null && it.partyAccountId == party.id) || it.clinicExpenseId in partyInvoiceIds) }
        .sortedBy { it.date }
    val ledger = PartyLedger(partyInvoices.sumOf { it.amount }, partyPayments.sumOf { it.amount })

    var showInvoiceForm by remember { mutableStateOf(false) }
    var showPayslipForm by remember { mutableStateOf(false) }
    var showPayForm by remember { mutableStateOf(false) }
    var showChequePicker by remember { mutableStateOf(false) }

    val rows = remember(partyInvoices, partyPayments) {
        val list = mutableListOf<PartyLedgerRowUi>()
        partyInvoices.forEach { inv ->
            val invLabel = inv.invoiceNumber.ifBlank { t("فاکتور", "Invoice") }
            list.add(PartyLedgerRowUi(inv.date, "$invLabel — ${inv.description.ifBlank { "—" }}", inv.amount))
        }
        partyPayments.forEach { pay ->
            val methodLabel = when (pay.method) {
                "CASH" -> t("نقدی", "Cash")
                "POS" -> t("کارتخوان", "POS")
                "CHEQUE" -> t("چک", "Cheque")
                "INTERNET" -> t("پرداخت اینترنتی", "Online")
                else -> t("کارت به کارت", "Card")
            }
            list.add(PartyLedgerRowUi(pay.date, "${t("پرداخت", "Payment")} ($methodLabel) — ${pay.note.ifBlank { "—" }}", -pay.amount))
        }
        list.sortedBy { it.date }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                // به‌درخواست کاربر: بدون پرانتز و بدون کد خودکار — «صورتحساب: لابراتوار ایکس»
                Text(t("صورتحساب: ${party.displayLabel}", "Statement: ${party.displayLabel}"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = t("دفتر طرف‌حساب — فاکتورها و پرداختی‌ها به‌ترتیب تاریخ", "Party ledger — invoices & payments by date"),
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val balanceColor = when {
                    ledger.isPartyDebtor -> Color(0xFF6A1B9A)
                    ledger.remaining > 0L -> Color(0xFFC2410C)
                    else -> Color(0xFF2E7D32)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = balanceColor.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, balanceColor.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${t("جمع فاکتورها:", "Invoices:")} ${formatAmount(ledger.orders)} $currency" +
                                "  |  ${t("پرداختی:", "Paid:")} ${formatAmount(ledger.paid)} $currency",
                            fontSize = 10.5.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = when {
                                ledger.isPartyDebtor ->
                                    t(
                                        "${party.displayLabel} به ما بدهکار است: ${formatAmount(ledger.partyDebtAmount)} $currency",
                                        "${party.displayLabel} owes us: ${formatAmount(ledger.partyDebtAmount)} $currency"
                                    )
                                ledger.remaining > 0L ->
                                    "${t("مانده بدهی ما:", "Our debt:")} ${formatAmount(ledger.debtAmount)} $currency"
                                else ->
                                    "${t("مانده:", "Balance:")} 0 $currency"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = balanceColor
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScrollCompat(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // به‌درخواست کاربر:
                    // • لابراتوار/فنی‌مهندسی: «صدور فاکتور» حذف — فاکتورها از بخش اصلی خودشان صادر می‌شوند.
                    // • قبوض/اجاره/شارژ: «صدور فاکتور» و «انتقال چک» حذف — فقط صفحهٔ پرداخت اختصاصی قبض.
                    val isLabOrEng = party.section.trim() == PartySection.LAB.key ||
                        party.section.trim() == PartySection.ENGINEERING.key
                    val isUtility = isUtilityParty(party)
                    val isSalary = party.section.trim() == PartySection.SALARY.key
                    if (isSalary) {
                        FilterChip(selected = false, onClick = { showPayslipForm = true }, label = { Text(t("فیش حقوقی", "Payslip"), fontSize = 9.5.sp) })
                    }
                    if (!isLabOrEng && !isUtility && !isSalary) {
                        FilterChip(selected = false, onClick = { showInvoiceForm = true }, label = { Text(t("صدور فاکتور", "Invoice"), fontSize = 9.5.sp) })
                    }
                    FilterChip(selected = false, onClick = { showPayForm = true }, label = { Text(t("ثبت پرداخت", "Pay"), fontSize = 9.5.sp) })
                    if (!isUtility) {
                        FilterChip(selected = false, onClick = { showChequePicker = true }, label = { Text(t("انتقال چک", "Cheque"), fontSize = 9.5.sp) })
                    }
                }

                Text(
                    text = t("توالی حساب (تراز تجمعی):", "Ledger sequence (running balance):"),
                    fontSize = 10.5.sp,
                    color = Color.Gray
                )

                if (rows.isEmpty()) {
                    Text(
                        text = t("هنوز فاکتور یا پرداختی برای این طرف‌حساب ثبت نشده است.", "No invoices or payments for this party yet."),
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                var running = 0L
                rows.forEach { r ->
                    running += r.amount
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = viewModel.formatDate(r.date),
                            fontSize = 9.5.sp,
                            color = Color.Gray,
                            modifier = Modifier.width(78.dp)
                        )
                        Text(
                            text = r.label,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (r.amount >= 0) "+${formatAmount(r.amount)}" else "-${formatAmount(-r.amount)}",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (r.amount >= 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${formatAmount(running)} $currency",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ارسال صورت‌حساب و فاکتور این طرف‌حساب — با همان مولد استاندارد اسناد
                ExpensePartySendActionsRow(
                    partyName = party.displayLabel,
                    expenses = partyInvoices,
                    payments = partyPayments,
                    viewModel = viewModel,
                    isPersian = isPersian
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("بستن", "Close")) }
        },
        dismissButton = {}
    )

    if (showInvoiceForm) {
        AddPartyInvoiceDialog(
            viewModel = viewModel,
            party = party,
            isPersian = isPersian,
            currency = currency,
            onDismiss = { showInvoiceForm = false }
        )
    }
    if (showPayslipForm) {
        PayslipDialog(
            viewModel = viewModel,
            party = party,
            isPersian = isPersian,
            currency = currency,
            onDismiss = { showPayslipForm = false }
        )
    }
    if (showPayForm) {
        if (isUtilityParty(party)) {
            // صفحهٔ پرداخت اختصاصی قبض — با مشخصات قبض و فقط دو روش پرداخت
            PayUtilityBillDialog(
                viewModel = viewModel,
                party = party,
                bankAccounts = bankAccounts.filter { !it.isDeleted },
                isPersian = isPersian,
                currency = currency,
                onDismiss = { showPayForm = false }
            )
        } else {
            PayPartyDialog(
                viewModel = viewModel,
                party = party,
                invoices = partyInvoices,
                bankAccounts = bankAccounts.filter { !it.isDeleted },
                isPersian = isPersian,
                currency = currency,
                onDismiss = { showPayForm = false }
            )
        }
    }
    if (showChequePicker) {
        PickChequeForPartyDialog(
            viewModel = viewModel,
            party = party,
            allPayments = allPayments,
            isPersian = isPersian,
            currency = currency,
            onDismiss = { showChequePicker = false }
        )
    }
}

/**
 * صفحهٔ پرداخت اختصاصی قبوض (برق، گاز، آب، تلفن، موبایل، اینترنت، اجاره، شارژ) —
 * به‌درخواست کاربر: فقط دو روش «کارت به کارت» و «پرداخت اینترنتی»؛ مشخصات قبض
 * (شناسه قبض، شناسه پرداخت، مبلغ قبض، تاریخ قبض) هنگام پرداخت وارد می‌شود.
 */
@Composable
private fun PayUtilityBillDialog(
    viewModel: DentalViewModel,
    party: PartyAccount,
    bankAccounts: List<BankAccount>,
    isPersian: Boolean,
    currency: String,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    var billId by remember { mutableStateOf("") }
    var payId by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var billDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var method by remember { mutableStateOf("CARD_TRANSFER") }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(utilityIconOf(party.name), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(t("پرداخت قبض — ${party.displayLabel}", "Pay bill — ${party.displayLabel}"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(
                    value = billId, onValueChange = { billId = it },
                    label = { Text(t("شناسه قبض", "Bill ID")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = payId, onValueChange = { payId = it },
                    label = { Text(t("شناسه پرداخت", "Payment ID")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                com.example.ui.components.AmountInputField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = "${t("مبلغ قبض", "Bill amount")} ($currency)",
                    currency = currency,
                    isPersian = isPersian
                )
                com.example.ui.components.JalaliDateField(
                    timestamp = billDate,
                    onDateSelected = { billDate = it },
                    isPersian = isPersian,
                    label = t("تاریخ قبض", "Bill date"),
                    formatDate = { viewModel.formatDate(it) }
                )

                Text(t("روش پرداخت:", "Payment method:"), fontSize = 10.5.sp, color = Color.Gray)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "CARD_TRANSFER" to t("کارت به کارت", "Card transfer"),
                        "INTERNET" to t("پرداخت اینترنتی", "Online payment")
                    ).forEach { (key, labelTxt) ->
                        FilterChip(
                            selected = method == key,
                            onClick = { method = key },
                            label = { Text(labelTxt, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (bankAccounts.isNotEmpty()) {
                    Text(t("حساب بانکی کسر شونده:", "Debit bank account:"), fontSize = 10.sp, color = Color.Gray)
                    bankAccounts.forEach { acc ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedAccountId == acc.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, if (selectedAccountId == acc.id) MaterialTheme.colorScheme.primary else Color.Transparent),
                            onClick = { selectedAccountId = if (selectedAccountId == acc.id) null else acc.id }
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(acc.bankName, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("${formatAmount(acc.balance)} $currency", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = com.example.ui.components.parseGroupedAmount(amountStr)
                    if (amount > 0L) {
                        val note = listOf(
                            billId.takeIf { it.isNotBlank() }?.let { "${t("شناسه قبض", "Bill ID")}: $it" },
                            payId.takeIf { it.isNotBlank() }?.let { "${t("شناسه پرداخت", "Payment ID")}: $it" },
                            "${t("تاریخ قبض", "Bill date")}: ${viewModel.formatDate(billDate)}"
                        ).filterNotNull().joinToString(" | ")
                        viewModel.addPartyPayment(party, amount, method, selectedAccountId, date = billDate, note = note)
                        onDismiss()
                    }
                },
                enabled = com.example.ui.components.parseGroupedAmount(amountStr) > 0L
            ) { Text(t("ثبت پرداخت قبض", "Pay bill"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("انصراف", "Cancel")) }
        }
    )
}

/**
 * فیش حقوقی «حقوق پرسنل» — استاندارد ولی ساده (به‌درخواست کاربر):
 * حقوق پایه + مزایا + اضافه‌کار − بیمه/کسورات = خالص پرداختی.
 * تاریخ واریز حقوق ثبت می‌شود و یادآوری یک روز قبل ارسال می‌شود.
 */
@Composable
private fun PayslipDialog(
    viewModel: DentalViewModel,
    party: PartyAccount,
    isPersian: Boolean,
    currency: String,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    var baseStr by remember { mutableStateOf("") }
    var benefitsStr by remember { mutableStateOf("") }
    var overtimeStr by remember { mutableStateOf("") }
    var deductionsStr by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("") }
    var depositDate by remember { mutableStateOf(System.currentTimeMillis()) }

    val base = com.example.ui.components.parseGroupedAmount(baseStr)
    val benefits = com.example.ui.components.parseGroupedAmount(benefitsStr)
    val overtime = com.example.ui.components.parseGroupedAmount(overtimeStr)
    val deductions = com.example.ui.components.parseGroupedAmount(deductionsStr)
    val net = (base + benefits + overtime - deductions).coerceAtLeast(0L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(t("فیش حقوقی — ${party.name}", "Payslip — ${party.name}"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = period, onValueChange = { period = it },
                    label = { Text(t("دورهٔ حقوق (مثلاً ۱۴۰۵/۰۶)", "Pay period (e.g. 1405/06)")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                com.example.ui.components.AmountInputField(
                    value = baseStr, onValueChange = { baseStr = it },
                    label = t("حقوق پایه", "Base salary"), currency = currency, isPersian = isPersian
                )
                com.example.ui.components.AmountInputField(
                    value = benefitsStr, onValueChange = { benefitsStr = it },
                    label = t("مزایا (بن، پاداش…)", "Benefits"), currency = currency, isPersian = isPersian
                )
                com.example.ui.components.AmountInputField(
                    value = overtimeStr, onValueChange = { overtimeStr = it },
                    label = t("اضافه‌کار", "Overtime"), currency = currency, isPersian = isPersian
                )
                com.example.ui.components.AmountInputField(
                    value = deductionsStr, onValueChange = { deductionsStr = it },
                    label = t("بیمه / کسورات", "Insurance / deductions"), currency = currency, isPersian = isPersian
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ) {
                    Text(
                        "${t("خالص پرداختی:", "Net pay:")} ${formatAmount(net)} $currency",
                        fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                com.example.ui.components.JalaliDateField(
                    timestamp = depositDate,
                    onDateSelected = { depositDate = it },
                    isPersian = isPersian,
                    label = t("تاریخ واریز حقوق", "Salary deposit date"),
                    formatDate = { viewModel.formatDate(it) }
                )
                Text(
                    t("یک روز قبل از تاریخ واریز، اعلان یادآوری دریافت می‌کنید. پرداخت از «ثبت پرداخت» (کارت به کارت، نقدی) یا «انتقال چک» انجام می‌شود.",
                        "You'll get a reminder one day before the deposit date. Pay via \"Pay\" (card, cash) or cheque transfer."),
                    fontSize = 9.5.sp, color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val details = listOfNotNull(
                        period.trim().takeIf { it.isNotBlank() }?.let { "${t("دوره", "Period")}: $it" },
                        base.takeIf { it > 0 }?.let { "${t("حقوق پایه", "Base")}: ${formatAmount(it)}" },
                        benefits.takeIf { it > 0 }?.let { "${t("مزایا", "Benefits")}: ${formatAmount(it)}" },
                        overtime.takeIf { it > 0 }?.let { "${t("اضافه‌کار", "Overtime")}: ${formatAmount(it)}" },
                        deductions.takeIf { it > 0 }?.let { "${t("بیمه/کسورات", "Deductions")}: ${formatAmount(it)}" },
                        "${t("خالص", "Net")}: ${formatAmount(net)}"
                    ).joinToString(" | ")
                    viewModel.addPartyInvoice(
                        party,
                        net,
                        description = t("فیش حقوقی", "Payslip"),
                        date = depositDate,
                        invoiceDetails = details
                    )
                    onDismiss()
                },
                enabled = net > 0L
            ) { Text(t("ثبت فیش حقوقی", "Save payslip"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("انصراف", "Cancel")) }
        }
    )
}

/** صدور فاکتور برای طرف‌حساب — بدون پرداختِ هم‌زمان (خواستهٔ کاربر). */
@Composable
private fun AddPartyInvoiceDialog(
    viewModel: DentalViewModel,
    party: PartyAccount,
    isPersian: Boolean,
    currency: String,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    var amountStr by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var invoiceDate by remember { mutableStateOf(System.currentTimeMillis()) }

    // فرم استانداردِ اختصاصیِ قبوض/اجاره: دوره، شماره اشتراک/شناسه و تاریخ سررسید
    val isUtility = isUtilityParty(party)
    var period by remember { mutableStateOf("") }
    var subscriptionNo by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf(System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (isUtility) utilityIconOf(party.name) else Icons.Default.ReceiptLong,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                )
                Text(t("صدور فاکتور — ${party.displayLabel}", "Invoice — ${party.displayLabel}"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = t(
                        "فاکتور بدون پرداختِ هم‌زمان ثبت می‌شود؛ پرداخت بعداً از «ثبت پرداخت»، صندوق نقدی، پیامک بانکی یا انتقال چک انجام می‌شود.",
                        "The invoice is recorded without a simultaneous payment; pay later via \"Pay\", cash funds, bank SMS or cheque transfer."
                    ),
                    fontSize = 10.sp,
                    color = Color.Gray
                )
                if (isUtility) {
                    OutlinedTextField(
                        value = period, onValueChange = { period = it },
                        label = { Text(t("دوره / ماه مصرف (مثلاً ۱۴۰۵/۰۶)", "Period (e.g. 1405/06)")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = subscriptionNo, onValueChange = { subscriptionNo = it },
                        label = { Text(t("شماره اشتراک / شناسه قبض", "Subscription / bill ID")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    com.example.ui.components.JalaliDateField(
                        timestamp = dueDate,
                        onDateSelected = { dueDate = it },
                        isPersian = isPersian,
                        label = t("تاریخ سررسید", "Due date"),
                        formatDate = { viewModel.formatDate(it) }
                    )
                }
                com.example.ui.components.AmountInputField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = "${t("مبلغ فاکتور", "Invoice amount")} ($currency)",
                    currency = currency,
                    isPersian = isPersian
                )
                com.example.ui.components.JalaliDateField(
                    timestamp = invoiceDate,
                    onDateSelected = { invoiceDate = it },
                    isPersian = isPersian,
                    label = t("تاریخ فاکتور", "Invoice date"),
                    formatDate = { viewModel.formatDate(it) }
                )
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text(t("شرح (اختیاری)", "Description (optional)")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = com.example.ui.components.parseGroupedAmount(amountStr)
                    if (amount > 0L) {
                        val details = if (isUtility) {
                            listOf(
                                period.takeIf { it.isNotBlank() }?.let { "${t("دوره", "Period")}: $it" },
                                subscriptionNo.takeIf { it.isNotBlank() }?.let { "${t("شناسه", "ID")}: $it" },
                                t("سررسید", "Due") + ": " + viewModel.formatDate(dueDate)
                            ).filterNotNull().joinToString(" | ")
                        } else ""
                        viewModel.addPartyInvoice(party, amount, desc.trim(), date = invoiceDate, invoiceDetails = details)
                        onDismiss()
                    }
                },
                enabled = com.example.ui.components.parseGroupedAmount(amountStr) > 0L
            ) { Text(t("ثبت فاکتور", "Save Invoice"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("انصراف", "Cancel")) }
        }
    )
}

/**
 * پرداخت به طرف حساب — کلیِ طرف‌حساب یا روی یک فاکتور مشخص.
 * روش‌ها: نقدی از صندوق‌ها (bankAccountId = صندوق/تنخواه)، کارت به کارت، کارتخوان (پوز).
 * چک از «انتقال چک» و برداشت پیامک بانکی از همان مسیر پیامک انجام می‌شود.
 */
@Composable
private fun PayPartyDialog(
    viewModel: DentalViewModel,
    party: PartyAccount,
    invoices: List<ClinicExpense>,
    bankAccounts: List<BankAccount>,
    isPersian: Boolean,
    currency: String,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    var amountStr by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CARD_TRANSFER") }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var selectedInvoiceId by remember { mutableStateOf<Long?>(null) } // null = پرداخت کلیِ طرف‌حساب
    var payDate by remember { mutableStateOf(System.currentTimeMillis()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(t("ثبت پرداخت — ${party.displayLabel}", "Pay — ${party.displayLabel}"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(t("طرف‌حساب: ${party.displayLabel}", "Party: ${party.displayLabel}"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                Text(t("هدف پرداخت:", "Payment target:"), fontSize = 10.5.sp, color = Color.Gray)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScrollCompat(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = selectedInvoiceId == null, onClick = { selectedInvoiceId = null }, label = { Text(t("کلیِ طرف‌حساب", "Whole party"), fontSize = 10.sp) })
                    invoices.take(8).forEach { inv ->
                        FilterChip(
                            selected = selectedInvoiceId == inv.id,
                            onClick = { selectedInvoiceId = inv.id },
                            label = { Text("${inv.invoiceNumber.ifBlank { inv.id.toString() }} (${formatAmount(inv.amount)})", fontSize = 9.sp, maxLines = 1) }
                        )
                    }
                }

                com.example.ui.components.AmountInputField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = "${t("مبلغ پرداخت", "Amount")} ($currency)",
                    currency = currency,
                    isPersian = isPersian
                )
                com.example.ui.components.JalaliDateField(
                    timestamp = payDate,
                    onDateSelected = { payDate = it },
                    isPersian = isPersian,
                    label = t("تاریخ پرداخت", "Payment date"),
                    formatDate = { viewModel.formatDate(it) }
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // «حقوق پرسنل»: فقط کارت به کارت و نقدی (چک از «انتقال چک») — به‌درخواست کاربر.
                    val methodOptions = if (party.section.trim() == PartySection.SALARY.key)
                        listOf(
                            "CARD_TRANSFER" to t("کارت به کارت", "Card"),
                            "CASH" to t("نقدی", "Cash")
                        )
                    else listOf(
                        "CASH" to t("نقدی (صندوق)", "Cash (fund)"),
                        "CARD_TRANSFER" to t("کارت به کارت", "Card"),
                        "POS" to t("کارتخوان", "POS")
                    )
                    methodOptions.forEach { (key, labelTxt) ->
                        FilterChip(
                            selected = method == key,
                            onClick = { method = key },
                            label = { Text(labelTxt, fontSize = 9.5.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // نقدی → صندوق‌ها جلوتر؛ کارت/پوز → حساب‌های بانکی (صندوق هم قابل انتخاب است)
                val pool = if (method == "CASH") {
                    (bankAccounts.filter { it.isCashFund } + bankAccounts.filter { !it.isCashFund }).distinctBy { it.id }
                } else bankAccounts
                if (pool.isNotEmpty()) {
                    Text(
                        text = if (method == "CASH") t("صندوق نقدی کسر شونده:", "Debit cash fund:")
                        else t("حساب بانکی کسر شونده:", "Debit bank account:"),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                    pool.forEach { acc ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedAccountId == acc.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, if (selectedAccountId == acc.id) MaterialTheme.colorScheme.primary else Color.Transparent),
                            onClick = { selectedAccountId = acc.id }
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(acc.bankName, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("${formatAmount(acc.balance)} $currency", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = com.example.ui.components.parseGroupedAmount(amountStr)
                    if (amount > 0L) {
                        val inv = invoices.firstOrNull { it.id == selectedInvoiceId }
                        if (inv != null) {
                            viewModel.payPartyInvoice(party, inv, amount, method, selectedAccountId, date = payDate)
                        } else {
                            viewModel.addPartyPayment(party, amount, method, selectedAccountId, date = payDate)
                        }
                        onDismiss()
                    }
                },
                enabled = com.example.ui.components.parseGroupedAmount(amountStr) > 0L
            ) { Text(t("ثبت پرداخت", "Pay"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("انصراف", "Cancel")) }
        }
    )
}

/** انتخاب چکِ قابل‌انتقال (در جریان وصول) و انتقال آن به این طرف‌حساب — با اثرِ حسابداری. */
@Composable
private fun PickChequeForPartyDialog(
    viewModel: DentalViewModel,
    party: PartyAccount,
    allPayments: List<Payment>,
    isPersian: Boolean,
    currency: String,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    val transferable = allPayments.filter { !it.isDeleted && it.type == "CHEQUE" && it.chequeStatus == "PENDING" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(t("انتقال چک به ${party.displayLabel}", "Transfer cheque to ${party.displayLabel}"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (transferable.isEmpty()) {
                    Text(
                        text = t(
                            "چکِ قابل انتقالی (در جریان وصول) وجود ندارد. چک‌ها از «مدیریت چک‌های صیادی» به همین طرف‌حساب قابل انتقال‌اند.",
                            "No transferable (in-progress) cheques. Transfer happens from the Sayad cheques manager to this party."
                        ),
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                transferable.forEach { cheque ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        onClick = {
                            viewModel.transferChequeToParty(cheque, party)
                            onDismiss()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${cheque.chequeBank.orEmpty().ifBlank { t("چک", "Cheque") }} ${cheque.chequeNumber.orEmpty()}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("${t("سررسید:", "Due:")} ${cheque.chequeDueDate?.let { viewModel.formatDate(it) } ?: "—"}", fontSize = 9.5.sp, color = Color.Gray)
                            }
                            Text("${formatAmount(cheque.amount)} $currency", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text(
                    text = t(
                        "با انتقال، مبلغ چک بلافاصله به‌عنوان پرداختِ همین طرف‌حساب در دفتر ثبت می‌شود و وضعیت چک «انتقال یافته» می‌گیرد.",
                        "On transfer, the cheque amount is posted immediately to this party's ledger and the cheque becomes \"Transferred\"."
                    ),
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("بستن", "Close")) }
        },
        dismissButton = {}
    )
}


@Composable
private fun EditClinicExpenseDialog(
    expense: ClinicExpense,
    currency: String,
    isPersian: Boolean,
    viewModel: DentalViewModel,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en

    var category by remember { mutableStateOf(expense.category) }
    var partyName by remember { mutableStateOf(expense.subAccountName) }
    var amountStr by remember { mutableStateOf(expense.amount.toString()) }
    var desc by remember { mutableStateOf(expense.description) }
    var method by remember { mutableStateOf(expense.paymentMethod) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(t("ویرایش هزینه مطب", "Edit Clinic Expense"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(t("عنوان طرف‌حساب", "Party title")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = partyName,
                    onValueChange = { partyName = it },
                    label = { Text(t("نام طرف‌حساب", "Party name")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (category.isNotBlank() || partyName.isNotBlank()) {
                    Text(
                        text = com.example.data.partyDisplayLabel(category, partyName),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it.filter(Char::isDigit) },
                    label = { Text("${t("مبلغ", "Amount")} ($currency)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                RealTimeAmountHelper(amountStr, isPersian)
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text(t("توضیحات", "Description")) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(t("نحوه پرداخت:", "Payment method:"), fontSize = 11.sp, color = Color.Gray)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScrollCompat(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "CARD_TRANSFER" to t("کارت به کارت / شبا", "Card/IBAN"),
                        "POS" to t("کارتخوان", "POS"),
                        "CASH" to t("نقدی", "Cash"),
                        "CHEQUE" to t("چک", "Cheque"),
                        "OTHER" to t("سایر", "Other")
                    ).forEach { (key, labelTxt) ->
                        FilterChip(
                            selected = method == key,
                            onClick = { method = key },
                            label = { Text(labelTxt, fontSize = 10.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountStr.trim().toLongOrNull() ?: 0L
                    if (amount > 0 && category.isNotBlank()) {
                        viewModel.updateExpense(
                            expense.copy(
                                category = category.trim(),
                                subAccountName = partyName.trim(),
                                amount = amount,
                                description = desc.trim(),
                                paymentMethod = method
                            )
                        )
                        onDismiss()
                    }
                },
                enabled = (amountStr.trim().toLongOrNull() ?: 0L) > 0 && category.isNotBlank()
            ) {
                Text(t("ذخیره تغییرات", "Save Changes"), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("انصراف", "Cancel"))
            }
        }
    )
}

@Composable
private fun PayExpenseDialog(
    expense: ClinicExpense,
    alreadyPaid: Long,
    bankAccounts: List<BankAccount>,
    isPersian: Boolean,
    currency: String,
    viewModel: DentalViewModel,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en
    val remaining = expense.amount - alreadyPaid
    var payAmountStr by remember(expense.id) { mutableStateOf(remaining.coerceAtLeast(0L).toString()) }
    var method by remember(expense.id) { mutableStateOf("CARD_TRANSFER") }
    var selectedAccountId by remember(expense.id) { mutableStateOf<Long?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("پرداخت هزینه", "Pay Expense"), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    text = "${com.example.data.partyLabelOf(expense)} — ${expense.description.ifBlank { "—" }}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (remaining < 0L) {
                        t(
                            "مبلغ کل: ${formatAmount(expense.amount)} $currency | پرداخت‌شده: ${formatAmount(alreadyPaid)} $currency | بدهکار به ما: ${formatAmount(-remaining)} $currency",
                            "Total: ${formatAmount(expense.amount)} | Paid: ${formatAmount(alreadyPaid)} | Owes us: ${formatAmount(-remaining)} $currency"
                        )
                    } else {
                        t(
                            "مبلغ کل: ${formatAmount(expense.amount)} $currency | پرداخت‌شده: ${formatAmount(alreadyPaid)} $currency | مانده: ${formatAmount(remaining)} $currency",
                            "Total: ${formatAmount(expense.amount)} | Paid: ${formatAmount(alreadyPaid)} | Remaining: ${formatAmount(remaining)} $currency"
                        )
                    },
                    fontSize = 10.sp,
                    color = Color.Gray
                )
                // هشدار اضافه‌پرداخت — ثبت مجاز است ولی کاربر باید آگاهانه انجامش دهد
                val enteredAmount = payAmountStr.trim().toLongOrNull() ?: 0L
                val overpay = alreadyPaid + enteredAmount - expense.amount
                if (enteredAmount > 0L && overpay > 0L) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF6A1B9A).copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = t(
                                "این پرداخت ${formatAmount(overpay)} $currency بیشتر از مبلغ هزینه است؛ طرف‌حساب به همین اندازه به ما بدهکار می‌شود.",
                                "This payment exceeds the expense by ${formatAmount(overpay)} $currency; the party ends up owing us that amount."
                            ),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6A1B9A),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = payAmountStr, onValueChange = { payAmountStr = it.filter(Char::isDigit) },
                    label = { Text("${t("مبلغ پرداخت", "Payment Amount")} ($currency)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "CASH" to t("نقدی", "Cash"),
                        "CARD_TRANSFER" to t("کارت به کارت", "Card"),
                        "POS" to t("پوز", "POS"),
                        "CHEQUE" to t("چک", "Cheque")
                    ).forEach { (key, labelTxt) ->
                        FilterChip(
                            selected = method == key,
                            onClick = { method = key },
                            label = { Text(labelTxt, fontSize = 9.5.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (method == "CARD_TRANSFER" || method == "POS") {
                    Text(t("حساب بانکی کسر شونده:", "Debit bank account:"), fontSize = 10.sp, color = Color.Gray)
                    bankAccounts.forEach { acc ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedAccountId == acc.id) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, if (selectedAccountId == acc.id) MaterialTheme.colorScheme.primary else Color.Transparent),
                            onClick = { selectedAccountId = acc.id }
                        ) {
                            Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(acc.bankName, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("${formatAmount(acc.balance)} $currency", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = payAmountStr.trim().toLongOrNull() ?: 0L
                    // اضافه‌پرداخت مجاز است: طرف‌حساب بستانکار می‌شود و مانده منفی
                    // نمایش داده می‌شود. قبلاً هر مبلغ بیشتر از مانده کلاً رد می‌شد.
                    if (amount >= 1L) {
                        viewModel.insertExpensePayment(
                            ExpensePayment(
                                clinicExpenseId = expense.id,
                                amount = amount,
                                method = method,
                                bankAccountId = if (method == "CASH" || method == "CHEQUE") null else selectedAccountId,
                                note = t("پرداخت دستی هزینه: ${com.example.data.partyLabelOf(expense)}", "Manual expense payment: ${com.example.data.partyLabelOf(expense)}")
                            )
                        )
                        onDismiss()
                    }
                },
                enabled = (payAmountStr.trim().toLongOrNull() ?: 0L) >= 1L
            ) { Text(t("ثبت پرداخت", "Pay"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("انصراف", "Cancel")) }
        }
    )
}

// ==========================================
// تراکنش‌های شخصی (برداشت/درآمد شخصی/هزینه شخصی)
// ==========================================
@Composable
fun PersonalScreen(viewModel: DentalViewModel) {
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val transactions by viewModel.personalTransactions.collectAsStateWithLifecycle()
    val bankAccounts by viewModel.bankAccounts.collectAsStateWithLifecycle()
    val subAccounts by viewModel.personalSubAccounts.collectAsStateWithLifecycle()
    val personProfiles by viewModel.personProfiles.collectAsStateWithLifecycle()
    val childrenExpenses by viewModel.childrenExpenses.collectAsStateWithLifecycle()
    val currency = viewModel.label("currency")

    fun t(fa: String, en: String) = if (isPersian) fa else en

    // دفتر تمام‌صفحهٔ لینک‌شده به کادرهای بالای صفحه (هزینه / درآمد / اشخاص)
    var ledgerMode by remember { mutableStateOf<String?>(null) }

    var showForm by remember { mutableStateOf(false) }
    var showManageSubAccounts by remember { mutableStateOf(false) }
    var showPersonsDialog by remember { mutableStateOf(false) }

    // ——— وضعیت فرم جدید (طبق درخواست کاربر) ———
    // «تراکنش جدید برداشت از مطب» حذف شد؛ فقط هزینه شخصی (راست) و درآمد شخصی.
    var type by remember { mutableStateOf("EXPENSE") }
    // فیلد ۱: کشوی حساب‌های ستاره‌دار
    var starredPick by remember { mutableStateOf<String?>(null) }
    var starredExpanded by remember { mutableStateOf(false) }
    // فیلد ۲: فیلد آزاد با اتوکامپلت (به پایین فیلد جدید منتقل شد)
    var subAccount by remember { mutableStateOf("") }
    var subAccountExpanded by remember { mutableStateOf(false) }
    // فیلد ۳: کشوی حساب اشخاص
    var personPick by remember { mutableStateOf<String?>(null) }
    // فیلد ۴ به بعد: مبلغ / تاریخ / توضیحات / حساب بانکی
    var amountStr by remember { mutableStateOf("") }
    var txDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var desc by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }

    // فقط «زیرحساب»ها (برگ‌های درخت) در تراکنش‌ها شرکت می‌کنند — سرفصل/حساب‌های
    // صرفاً گروه‌بندی از فهرست انتخاب کنار گذاشته می‌شوند.
    val distinctSubs = remember(subAccounts) {
        val alive = subAccounts.filter { !it.isDeleted && it.category != com.example.data.PERSONS_CATEGORY }
        val usedAsParent = (alive.map { it.parentName.trim() } + alive.map { it.grandParentName.trim() })
            .filter { it.isNotBlank() }.toSet()
        alive
            .filter { it.name.trim() !in usedAsParent }
            .distinctBy { "${it.category.trim()}|${it.grandParentName.trim()}|${it.parentName.trim()}|${it.name.trim()}" }
    }
    fun displayNameOf(sub: com.example.data.PersonalSubAccount): String =
        listOf(sub.grandParentName.trim(), sub.parentName.trim(), sub.name.trim())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
    val starredSubs = remember(distinctSubs) { distinctSubs.filter { it.isQuickSelect } }
    val incomeSubs = remember(distinctSubs) { distinctSubs.filter { it.category == "INCOME" } }

    // اشخاصِ انتخاب‌پذیر در فیلد ۳ — ستاره‌دار/اولویت‌دار اول لیست
    val personNames = remember(personProfiles) {
        personProfiles
            .sortedWith(
                compareByDescending<com.example.data.PersonProfile> { it.isStarred }
                    .thenBy { it.priority }
                    .thenBy { it.fullName }
            )
            .map { it.fullName }
    }

    val active = transactions.filter { !it.isDeleted }
    val totalIncome = active.filter { it.type == "INCOME" }.sumOf { it.amount }
    val totalExpense = active.filter { it.type == "EXPENSE" }.sumOf { it.amount }
    val personsCount = personNames.size + childrenExpenses.filter { !it.isDeleted && it.childName.isNotBlank() }
        .map { it.childName.trim() }.distinct().count()

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { showForm = !showForm },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            Icon(if (showForm) Icons.Default.Close else Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (showForm) t("بستن فرم تراکنش", "Close Form") else t("تراکنش جدید", "New Transaction"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(t("تراکنش‌های شخصی", "Personal Transactions"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showManageSubAccounts = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("سرفصل‌ها", "Categories"), fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { showPersonsDialog = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("حساب اشخاص", "Persons"), fontSize = 11.sp)
                }
            }
        }

        // ——— سه کادر بالای صفحه ———
        // «برداشت از مطب» → «اشخاص» تغییر یافت و به دفتر تمام‌صفحهٔ حساب اشخاص لینک شد.
        // کادر هزینه و درآمد هم به دفاتر تمام‌صفحهٔ خودشان لینک می‌شوند (فیلتر + گزارش PDF).
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PersonalKpiLink(t("هزینه", "Expense"), totalExpense, MaterialTheme.colorScheme.error, currency, Modifier.weight(1f)) { ledgerMode = "EXPENSE" }
                PersonalKpiLink(t("درآمد", "Income"), totalIncome, Color(0xFF2E7D32), currency, Modifier.weight(1f)) { ledgerMode = "INCOME" }
                PersonalKpiLinkCount(t("اشخاص", "Persons"), personsCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { ledgerMode = "PERSONS" }
            }
        }

        if (showForm) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // «برداشت از مطب» حذف شد؛ هزینه شخصی به سمت راست منتقل شد.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "EXPENSE" to t("هزینه شخصی", "Personal Expense"),
                            "INCOME" to t("درآمد شخصی", "Personal Income")
                        ).forEach { (key, labelTxt) ->
                            FilterChip(
                                selected = type == key,
                                onClick = {
                                    type = key
                                    // تغییر نوع، انتخاب‌های وابسته به نوع را پاک می‌کند
                                    personPick = null
                                },
                                label = { Text(labelTxt, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // ——— فیلد ۱: کشوی حساب‌های ستاره‌دار (فقط هزینه شخصی) ———
                    // لمسِ این فیلد، فیلدِ آزادِ پایین را غیرفعال می‌کند (و برعکس).
                    if (type == "EXPENSE" && starredSubs.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = starredExpanded && subAccount.isBlank(),
                            onExpandedChange = { if (subAccount.isBlank()) starredExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = starredPick ?: t("انتخاب نشده", "Not selected"),
                                onValueChange = { },
                                readOnly = true,
                                enabled = subAccount.isBlank(),
                                label = { Text(t("حساب ستاره‌دار (انتخاب از علاقه‌مندی‌ها)", "Starred account"), fontSize = 11.sp) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = starredExpanded && subAccount.isBlank()) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(
                                expanded = starredExpanded && subAccount.isBlank(),
                                onDismissRequest = { starredExpanded = false }
                            ) {
                                starredSubs.forEach { sub ->
                                    val dn = displayNameOf(sub)
                                    DropdownMenuItem(
                                        text = { Text(dn, fontSize = 12.sp) },
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFF9A825), modifier = Modifier.size(14.dp)) },
                                        onClick = {
                                            // لمس یکی، دیگری را غیرفعال/پاک می‌کند
                                            starredPick = dn
                                            subAccount = ""
                                            starredExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ——— فیلد ۲ (به پایین فیلد جدید منتقل شد): حساب با اتوکامپلت حین تایپ ———
                    val suggestionBase = if (type == "INCOME") incomeSubs else distinctSubs
                    val suggestions = remember(subAccount, suggestionBase, type) {
                        val q = subAccount.trim()
                        if (q.isBlank()) suggestionBase.map(::displayNameOf)
                        else suggestionBase.map(::displayNameOf).filter { it.contains(q, ignoreCase = true) }
                    }
                    ExposedDropdownMenuBox(
                        expanded = subAccountExpanded && starredPick == null && suggestions.isNotEmpty(),
                        onExpandedChange = { },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = subAccount,
                            onValueChange = {
                                subAccount = it
                                // تایپ در این فیلد، انتخاب فیلد ستاره‌دار را غیرفعال می‌کند
                                if (it.isNotBlank()) starredPick = null
                                subAccountExpanded = true
                            },
                            enabled = starredPick == null,
                            label = {
                                Text(
                                    if (type == "INCOME") t("نوع درآمد (اتوکامپلت — تایپ کنید یا انتخاب کنید)", "Income type (autocomplete)")
                                    else t("حساب شخصی (اتوکامپلت — تایپ کنید یا انتخاب کنید)", "Personal account (autocomplete)"),
                                    fontSize = 11.sp
                                )
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = subAccountExpanded && starredPick == null) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = subAccountExpanded && starredPick == null && suggestions.isNotEmpty(),
                            onDismissRequest = { subAccountExpanded = false }
                        ) {
                            suggestions.take(12).forEach { dn ->
                                DropdownMenuItem(
                                    text = { Text(dn, fontSize = 12.sp) },
                                    onClick = { subAccount = dn; subAccountExpanded = false }
                                )
                            }
                        }
                    }

                    // ——— فیلد ۳: کشوی حساب اشخاص (فقط هزینه شخصی؛ اختیاری) ———
                    if (type == "EXPENSE" && personNames.isNotEmpty()) {
                        var personExpandedLocal by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = personExpandedLocal,
                            onExpandedChange = { personExpandedLocal = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = personPick ?: "",
                                onValueChange = { },
                                readOnly = true,
                                label = { Text(t("حساب اشخاص (اختیاری — تراکنش به نام شخص ثبت می‌شود)", "Person account (optional)"), fontSize = 11.sp) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = personExpandedLocal) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                singleLine = true
                            )
                            ExposedDropdownMenu(expanded = personExpandedLocal, onDismissRequest = { personExpandedLocal = false }) {
                                DropdownMenuItem(
                                    text = { Text(t("بدون شخص", "No person"), fontSize = 11.sp, color = Color.Gray) },
                                    onClick = { personPick = null; personExpandedLocal = false }
                                )
                                personNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name, fontSize = 12.sp) },
                                        onClick = { personPick = name; personExpandedLocal = false }
                                    )
                                }
                            }
                        }
                    }

                    // ——— فیلد ۴: مبلغ — کیبورد عددی + جداکنندهٔ ۳رقمی زنده + حروفی قرمز ———
                    com.example.ui.components.AmountInputField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = "${t("مبلغ", "Amount")} ($currency)",
                        currency = currency,
                        isPersian = isPersian
                    )

                    // ——— فیلد ۵: تاریخ تراکنش با تقویم انتخابی نرم‌افزار ———
                    com.example.ui.components.JalaliDateField(
                        timestamp = txDate,
                        onDateSelected = { txDate = it },
                        isPersian = isPersian,
                        label = t("تاریخ تراکنش", "Transaction date"),
                        formatDate = { viewModel.formatDate(it) }
                    )

                    // ——— فیلد ۶: توضیحات ———
                    OutlinedTextField(
                        value = desc, onValueChange = { desc = it },
                        label = { Text(t("توضیحات", "Description")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )

                    if (bankAccounts.isNotEmpty()) {
                        Text(
                            if (type == "INCOME") t("حساب مقصد (موجودی را افزایش می‌دهد):", "Destination account (increases balance):")
                            else t("حساب بانکی مرتبط (موجودی را کم می‌کند):", "Related bank account (decreases balance):"),
                            fontSize = 10.sp, color = Color.Gray
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScrollCompat(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = selectedAccountId == null,
                                onClick = { selectedAccountId = null },
                                label = { Text(t("بدون حساب", "None"), fontSize = 10.sp) }
                            )
                            bankAccounts.filter { !it.isDeleted }.forEach { acc ->
                                FilterChip(
                                    selected = selectedAccountId == acc.id,
                                    onClick = { selectedAccountId = acc.id },
                                    label = { Text(acc.bankName, fontSize = 10.sp) }
                                )
                            }
                        }
                    }

                    val amountValue = com.example.ui.components.parseGroupedAmount(amountStr)
                    val accountChosen = if (type == "EXPENSE") (starredPick != null || subAccount.isNotBlank()) else subAccount.isNotBlank()
                    Button(
                        onClick = {
                            if (amountValue > 0L && accountChosen) {
                                viewModel.addPersonalTransactionFull(
                                    type = type,
                                    subAccountName = starredPick ?: subAccount.trim(),
                                    amount = amountValue,
                                    description = desc.trim(),
                                    bankAccountId = selectedAccountId,
                                    date = txDate,
                                    personName = personPick.orEmpty()
                                )
                                amountStr = ""; desc = ""; starredPick = null; subAccount = ""; personPick = null
                                txDate = System.currentTimeMillis()
                                showForm = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = amountValue > 0L && accountChosen
                    ) { Text(t("ثبت تراکنش", "Save Transaction"), fontWeight = FontWeight.Bold) }
                }
            }
        }

        // لیست تراکنش‌های قبلی از این صفحه حذف شد و به کادرهای بالا لینک شد:
        Text(
            text = t(
                "لیست کامل تراکنش‌ها به کادرهای بالا منتقل شد — با لمس هر کادر، دفترِ تمام‌صفحه با فیلتر تاریخ/شخص/حساب و گزارش PDF باز می‌شود.",
                "The full transaction list moved to the top boxes — tap a box to open the full-screen ledger with date/person/account filters and a PDF report."
            ),
            fontSize = 10.5.sp,
            color = Color.Gray
        )
    }

    if (ledgerMode != null) {
        PersonalLedgerFullScreen(viewModel = viewModel, mode = ledgerMode!!, onDismiss = { ledgerMode = null })
    }

    if (showManageSubAccounts) {
        ManageSubAccountsDialog(
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { showManageSubAccounts = false }
        )
    }

    if (showPersonsDialog) {
        PersonsAccountsDialog(
            viewModel = viewModel,
            isPersian = isPersian,
            onDismiss = { showPersonsDialog = false }
        )
    }
}

/** کادر KPI قابل‌لمس (مبلغ) — لینک به دفتر تمام‌صفحه. */
@Composable
private fun PersonalKpiLink(title: String, value: Long, color: Color, currency: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
            Text(formatAmount(value), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Text(currency, fontSize = 8.5.sp, color = color.copy(alpha = 0.8f))
        }
    }
}

/** کادر KPI قابل‌لمس (شمارنده) — لینک به دفتر تمام‌صفحهٔ اشخاص. */
@Composable
private fun PersonalKpiLinkCount(title: String, count: Int, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
            Text(
                if (count > 0) count.toString() else "—",
                fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = color
            )
            Text("👥", fontSize = 8.5.sp)
        }
    }
}

@Composable
fun EditPersonalTxDialog(
    tx: PersonalTransaction,
    bankAccounts: List<BankAccount>,
    currency: String,
    isPersian: Boolean,
    viewModel: DentalViewModel,
    onDismiss: () -> Unit
) {
    fun t(fa: String, en: String) = if (isPersian) fa else en

    var type by remember { mutableStateOf(tx.type) }
    var subAccount by remember { mutableStateOf(tx.subAccountName) }
    var amountStr by remember { mutableStateOf(tx.amount.toString()) }
    var desc by remember { mutableStateOf(tx.description) }
    var selectedAccountId by remember { mutableStateOf(tx.bankAccountId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(t("ویرایش تراکنش شخصی", "Edit Personal Transaction"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "DRAW" to t("برداشت از مطب", "Draw"),
                        "INCOME" to t("درآمد شخصی", "Income"),
                        "EXPENSE" to t("هزینه شخصی", "Expense")
                    ).forEach { (key, labelTxt) ->
                        FilterChip(
                            selected = type == key,
                            onClick = { type = key },
                            label = { Text(labelTxt, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutlinedTextField(
                    value = subAccount, onValueChange = { subAccount = it },
                    label = { Text(t("سرفصل", "Ledger Head")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = amountStr, onValueChange = { amountStr = it.filter(Char::isDigit) },
                    label = { Text("${t("مبلغ", "Amount")} ($currency)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                RealTimeAmountHelper(amountStr, isPersian)
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text(t("توضیحات", "Description")) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                if (bankAccounts.isNotEmpty()) {
                    Text(t("حساب بانکی مرتبط (اختیاری):", "Related bank account (optional):"), fontSize = 10.sp, color = Color.Gray)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScrollCompat(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedAccountId == null,
                            onClick = { selectedAccountId = null },
                            label = { Text(t("بدون حساب", "None"), fontSize = 10.sp) }
                        )
                        bankAccounts.forEach { acc ->
                            FilterChip(
                                selected = selectedAccountId == acc.id,
                                onClick = { selectedAccountId = acc.id },
                                label = { Text(acc.bankName, fontSize = 10.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountStr.trim().toLongOrNull() ?: 0L
                    if (amount > 0) {
                        viewModel.updatePersonalTx(
                            tx.copy(
                                type = type,
                                subAccountName = subAccount.trim().ifBlank { if (isPersian) "سایر" else "Other" },
                                bankAccountId = selectedAccountId,
                                amount = amount,
                                description = desc.trim()
                            )
                        )
                        onDismiss()
                    }
                },
                enabled = (amountStr.trim().toLongOrNull() ?: 0L) > 0
            ) {
                Text(t("ذخیره تغییرات", "Save Changes"), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("انصراف", "Cancel"))
            }
        }
    )
}

// ==========================================
// تعمیر و نگهداری تجهیزات
// ==========================================
@Composable
fun MaintenanceScreen(viewModel: DentalViewModel) {
    val isPersian = viewModel.isPersian.collectAsStateWithLifecycle().value
    val maintenanceList by viewModel.maintenanceList.collectAsStateWithLifecycle()
    val currency = viewModel.label("currency")

    fun t(fa: String, en: String) = if (isPersian) fa else en

    var showForm by remember { mutableStateOf(false) }
    var showTechForm by remember { mutableStateOf(false) }
    // فاکتور کامل تعمیرات — به‌درخواست کاربر با فیلدهای اختصاصی
    var equipment by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf("") }
    var workDone by remember { mutableStateOf("") }
    var partsDesc by remember { mutableStateOf("") }
    var partsCostStr by remember { mutableStateOf("") }
    var laborCostStr by remember { mutableStateOf("") }
    var warranty by remember { mutableStateOf("") }
    var techName by remember { mutableStateOf("") }
    var serviceDate by remember { mutableStateOf(System.currentTimeMillis()) }
    var hasNextService by remember { mutableStateOf(false) }
    var nextServiceDate by remember { mutableStateOf(System.currentTimeMillis() + 90L * 24 * 60 * 60 * 1000) }

    // تکنسین‌ها = طرف‌حساب‌های بخش «فنی مهندسی و تعمیرات» (منبع واحد در کل اپ)
    val parties by viewModel.partyAccounts.collectAsStateWithLifecycle()
    val technicians = parties
        .filter { it.section.trim() == PartySection.ENGINEERING.key }
        .map { it.name.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    val active = maintenanceList.filter { !it.isDeleted }.sortedByDescending { it.date }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(t("تعمیر و نگهداری", "Maintenance"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showTechForm = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(t("تکنسین جدید", "New technician"), fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                }
                Button(
                    onClick = { showForm = !showForm },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(if (showForm) Icons.Default.Close else Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(if (showForm) t("بستن", "Close") else t("فاکتور تعمیرات", "New invoice"), fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
                }
            }
        }

        if (showForm) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        t("فاکتور تعمیرات / سرویس تجهیزات", "Maintenance / repair invoice"),
                        fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.primary
                    )
                    // تکنسین — انتخاب از تکنسین‌های ثبت‌شده (طرف‌حساب فنی‌مهندسی)
                    var techExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = techExpanded, onExpandedChange = { techExpanded = it }) {
                        OutlinedTextField(
                            value = techName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(t("تکنسین / شرکت تعمیراتی", "Technician / repair company")) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = techExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true
                        )
                        ExposedDropdownMenu(expanded = techExpanded, onDismissRequest = { techExpanded = false }) {
                            if (technicians.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(t("تکنسینی ثبت نشده — از دکمهٔ «تکنسین جدید» اضافه کنید", "No technicians — use \"New technician\"")) },
                                    onClick = { techExpanded = false; showTechForm = true }
                                )
                            }
                            technicians.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { techName = name; techExpanded = false })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = equipment, onValueChange = { equipment = it },
                        label = { Text(t("دستگاه / تجهیز (مثلاً یونیت ۱، اتوکلاو)", "Equipment (e.g. Unit 1, Autoclave)")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = issue, onValueChange = { issue = it },
                        label = { Text(t("شرح ایراد / مشکل", "Issue description")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = workDone, onValueChange = { workDone = it },
                        label = { Text(t("کار انجام‌شده", "Work done")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = partsDesc, onValueChange = { partsDesc = it },
                        label = { Text(t("قطعات تعویض‌شده (اختیاری)", "Replaced parts (optional)")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            com.example.ui.components.AmountInputField(
                                value = partsCostStr,
                                onValueChange = { partsCostStr = it },
                                label = t("مبلغ قطعات", "Parts cost"),
                                currency = currency,
                                isPersian = isPersian
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            com.example.ui.components.AmountInputField(
                                value = laborCostStr,
                                onValueChange = { laborCostStr = it },
                                label = t("اجرت تعمیر", "Labor cost"),
                                currency = currency,
                                isPersian = isPersian
                            )
                        }
                    }
                    val partsCost = com.example.ui.components.parseGroupedAmount(partsCostStr)
                    val laborCost = com.example.ui.components.parseGroupedAmount(laborCostStr)
                    val totalCost = partsCost + laborCost
                    Text(
                        "${t("مبلغ کل فاکتور:", "Invoice total:")} ${formatAmount(totalCost)} $currency",
                        fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedTextField(
                        value = warranty, onValueChange = { warranty = it },
                        label = { Text(t("گارانتی تعمیر (مثلاً ۳ ماه — اختیاری)", "Warranty (optional)")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    com.example.ui.components.JalaliDateField(
                        timestamp = serviceDate,
                        onDateSelected = { serviceDate = it },
                        isPersian = isPersian,
                        label = t("تاریخ سرویس", "Service date"),
                        formatDate = { viewModel.formatDate(it) }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = hasNextService, onCheckedChange = { hasNextService = it })
                        Text(t("یادآوری سرویس بعدی", "Next service reminder"), fontSize = 11.sp)
                    }
                    if (hasNextService) {
                        com.example.ui.components.JalaliDateField(
                            timestamp = nextServiceDate,
                            onDateSelected = { nextServiceDate = it },
                            isPersian = isPersian,
                            label = t("تاریخ سرویس بعدی", "Next service date"),
                            formatDate = { viewModel.formatDate(it) }
                        )
                    }
                    Button(
                        onClick = {
                            if (equipment.isNotBlank()) {
                                val packedDesc = listOfNotNull(
                                    issue.trim().takeIf { it.isNotBlank() }?.let { "${t("ایراد", "Issue")}: $it" },
                                    workDone.trim().takeIf { it.isNotBlank() }?.let { "${t("کار انجام‌شده", "Work done")}: $it" },
                                    partsDesc.trim().takeIf { it.isNotBlank() }?.let { "${t("قطعات", "Parts")}: $it" },
                                    partsCost.takeIf { it > 0 }?.let { "${t("مبلغ قطعات", "Parts cost")}: ${formatAmount(it)}" },
                                    laborCost.takeIf { it > 0 }?.let { "${t("اجرت تعمیر", "Labor")}: ${formatAmount(it)}" },
                                    warranty.trim().takeIf { it.isNotBlank() }?.let { "${t("گارانتی", "Warranty")}: $it" }
                                ).joinToString(" | ")
                                viewModel.addMaintenanceWithInvoice(
                                    equipment = equipment.trim(),
                                    desc = packedDesc,
                                    cost = totalCost,
                                    date = serviceDate,
                                    nextCheck = if (hasNextService) nextServiceDate else null,
                                    technician = techName.trim().ifBlank { null },
                                    invoiceNum = null
                                )
                                equipment = ""; issue = ""; workDone = ""; partsDesc = ""
                                partsCostStr = ""; laborCostStr = ""; warranty = ""; techName = ""
                                hasNextService = false
                                showForm = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = equipment.isNotBlank()
                    ) { Text(t("ثبت فاکتور تعمیرات", "Save invoice"), fontWeight = FontWeight.Bold) }
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            if (active.isEmpty()) {
                item {
                    Text(
                        text = t("هنوز سرویسی ثبت نشده است.", "No maintenance records yet."),
                        fontSize = 11.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            items(active, key = { it.id }) { m ->
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.equipmentName, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                text = listOfNotNull(
                                    m.description.takeIf { it.isNotBlank() },
                                    m.technicianName?.takeIf { it.isNotBlank() }?.let { if (isPersian) "تکنسین: $it" else "Tech: $it" },
                                    viewModel.formatDate(m.date)
                                ).joinToString(" — "),
                                fontSize = 10.sp,
                                color = Color.Gray,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            m.nextServiceDate?.let {
                                Text(
                                    text = "${t("سرویس بعدی:", "Next service:")} ${viewModel.formatDate(it)}",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Text(
                            text = "${formatAmount(m.cost)} $currency",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    // «تکنسین جدید» — با مشخصات کامل؛ به‌عنوان طرف‌حساب فنی‌مهندسی ذخیره می‌شود
    // تا در کل اپ (فاکتور تعمیرات و صورتحساب هزینه‌ها) منبع واحد باشد.
    if (showTechForm) {
        var tName by remember { mutableStateOf("") }
        var tSpecialty by remember { mutableStateOf("") }
        var tPhone by remember { mutableStateOf("") }
        var tAddress by remember { mutableStateOf("") }
        var tNotes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTechForm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(t("تکنسین / شرکت تعمیراتی جدید", "New technician / repair company"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                    OutlinedTextField(
                        value = tName, onValueChange = { tName = it },
                        label = { Text(t("نام تکنسین / شرکت", "Technician / company name")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = tSpecialty, onValueChange = { tSpecialty = it },
                        label = { Text(t("تخصص (یونیت، اتوکلاو، تاسیسات…)", "Specialty")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = tPhone, onValueChange = { tPhone = it },
                        label = { Text(t("تلفن", "Phone")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = tAddress, onValueChange = { tAddress = it },
                        label = { Text(t("آدرس / محل سرویس", "Address / service location")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = tNotes, onValueChange = { tNotes = it },
                        label = { Text(t("یادداشت (اختیاری)", "Notes (optional)")) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val composed = listOfNotNull(
                            tSpecialty.trim().takeIf { it.isNotBlank() }?.let { "تخصص: $it" },
                            tAddress.trim().takeIf { it.isNotBlank() }?.let { "آدرس: $it" },
                            tNotes.trim().takeIf { it.isNotBlank() }
                        ).joinToString(" | ")
                        viewModel.addPartyAccount(PartySection.ENGINEERING, tName.trim(), tPhone.trim(), notes = composed)
                        techName = tName.trim()
                        showTechForm = false
                    },
                    enabled = tName.trim().isNotBlank()
                ) { Text(t("ثبت تکنسین", "Save"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showTechForm = false }) { Text(t("انصراف", "Cancel")) }
            }
        )
    }
}
@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.horizontalScroll(rememberScrollState())

// حساب اشخاص — بخشِ مستقل با افزودنِ شخصِ جدید در انتخاب‌کننده + سوابقِ گزارش‌پذیر.
// هر تراکنش دو سند دارد: (۱) رکوردِ children_expenses (مبنای گزارش) و (۲) تراکنشِ
// شخصیِ «حساب اشخاص: نام». سندهای هم‌مبلغِ هم‌تاریخ برای جلوگیری از دوباره‌شماری تجمیع می‌شوند.
private const val PERSON_TX_PREFIX = "حساب اشخاص: "

private data class PersonLedgerRecord(val date: Long, val description: String, val amount: Long)

@Composable
fun PersonsAccountsDialog(
    viewModel: DentalViewModel,
    isPersian: Boolean,
    onDismiss: () -> Unit
) {
    val currency = viewModel.label("currency")
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val childrenExpenses by viewModel.childrenExpenses.collectAsStateWithLifecycle()
    val personalTransactions by viewModel.personalTransactions.collectAsStateWithLifecycle()
    val personProfiles by viewModel.personProfiles.collectAsStateWithLifecycle()

    fun t(fa: String, en: String) = if (isPersian) fa else en

    // ——— فرم ثبت شخص: نام، نام خانوادگی، عنوان و ماهیت ———
    var newFirstName by remember { mutableStateOf("") }
    var newLastName by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var newNature by remember { mutableStateOf(PersonNature.DEBTOR) }
    // مبلغ اولیه (افتتاحیه) + ستاره + شمارهٔ اولویت — طبق درخواست کاربر
    var newOpeningStr by remember { mutableStateOf("") }
    var newPriorityStr by remember { mutableStateOf("") }
    var newStarred by remember { mutableStateOf(false) }
    var editingPersonId by remember { mutableStateOf(0L) }

    var justAdded by remember { mutableStateOf<String?>(null) }
    var selectedPerson by remember { mutableStateOf<String?>(null) }

    fun resetPersonForm() {
        newFirstName = ""; newLastName = ""; newTitle = ""
        newNature = PersonNature.DEBTOR; editingPersonId = 0L
        newOpeningStr = ""; newPriorityStr = ""; newStarred = false
    }

    // جریات هر شخص — سندهای یکتا و مرتب‌شده بر اساس تاریخ
    val personRecords: Map<String, List<PersonLedgerRecord>> = remember(childrenExpenses, personalTransactions) {
        val map = LinkedHashMap<String, MutableList<PersonLedgerRecord>>()
        val seen = mutableSetOf<String>()
        fun put(person: String, r: PersonLedgerRecord) {
            val key = "${com.example.util.PersianCalendarHelper.jalaliKeyOf(r.date)}|${r.amount}|${r.description}"
            if (seen.add(key)) {
                map.getOrPut(person) { mutableListOf() }.add(r)
            }
        }
        childrenExpenses
            .filter { !it.isDeleted && it.childName.isNotBlank() }
            .forEach { e ->
                put(e.childName.trim(), PersonLedgerRecord(e.date, e.title.trim().ifBlank { t("برداشت بانکی", "Bank withdrawal") }, e.amount))
            }
        personalTransactions
            .filter { !it.isDeleted && it.subAccountName.startsWith(PERSON_TX_PREFIX) }
            .forEach { tx ->
                val person = tx.subAccountName.removePrefix(PERSON_TX_PREFIX).trim()
                if (person.isBlank()) return@forEach
                val rawDesc = tx.description.trim()
                val desc = rawDesc.substringBefore(" — $person").trim().ifBlank { t("برداشت بانکی", "Bank withdrawal") }
                put(person, PersonLedgerRecord(tx.date, desc, tx.amount))
            }
        map.mapValues { entry -> entry.value.sortedBy { it.date } }
    }

    // لیست اشخاص: پرونده‌های ثبت‌شده + نام‌های قدیمی تنظیمات + سوابق تراکنش‌ها
    val personNames = remember(settings, personRecords, personProfiles) {
        // ستاره‌دارها اول، بعد شمارهٔ اولویت (کوچک‌تر = بالاتر)، بعد الفبایی
        (personProfiles.map { it.fullName } +
            settings.childrenNames.split(",", "،").map { it.trim() }.filter { it.isNotBlank() } +
            personRecords.keys)
            .filter { it.isNotBlank() }
            .distinctBy { it.trim().lowercase() }
            .sortedWith(
                compareByDescending<String> { name ->
                    personProfiles.firstOrNull { it.fullName.equals(name, ignoreCase = true) }?.isStarred == true
                }.thenBy { name ->
                    personProfiles.firstOrNull { it.fullName.equals(name, ignoreCase = true) }?.priority ?: 0
                }.thenBy { it }
            )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.People, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(t("حساب اشخاص", "Person Accounts"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val currentPerson = selectedPerson
                if (currentPerson == null) {
                    // ——— فرم ثبت/ویرایش شخص: نام، نام خانوادگی، عنوان، ماهیت ———
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (editingPersonId == 0L) t("ثبت شخص جدید", "Add a new person")
                                       else t("ویرایش مشخصات شخص", "Edit person"),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = newFirstName,
                                    onValueChange = { newFirstName = it },
                                    label = { Text(t("نام", "First name"), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                )
                                OutlinedTextField(
                                    value = newLastName,
                                    onValueChange = { newLastName = it },
                                    label = { Text(t("نام خانوادگی", "Last name"), fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                                )
                            }
                            OutlinedTextField(
                                value = newTitle,
                                onValueChange = { newTitle = it },
                                label = { Text(t("عنوان شخص (مثلاً تأمین‌کننده، همکار)", "Person title"), fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                            )

                            // ——— ماهیت شخص: بدهکار / بستانکار / عضو خانواده ———
                            Text(t("ماهیت شخص", "Person nature"), fontSize = 10.sp, color = Color.Gray)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(PersonNature.DEBTOR, PersonNature.CREDITOR, PersonNature.NONE).forEach { nature ->
                                    val isSel = newNature == nature
                                    FilterChip(
                                        selected = isSel,
                                        onClick = { newNature = nature },
                                        label = {
                                            Text(
                                                if (nature == PersonNature.NONE) t("عضو خانواده", "Family") else nature.label(isPersian),
                                                fontSize = 10.5.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = if (nature == PersonNature.DEBTOR)
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                                            else
                                                Color(0xFF2E7D32).copy(alpha = 0.18f)
                                        )
                                    )
                                }
                            }

                            // ——— مبلغ اولیه (افتتاحیه) + اولویت + ستاره ———
                            com.example.ui.components.AmountInputField(
                                value = newOpeningStr,
                                onValueChange = { newOpeningStr = it },
                                label = t("مبلغ اولیه (افتتاحیه)", "Opening balance"),
                                currency = currency,
                                isPersian = isPersian
                            )
                            OutlinedTextField(
                                value = newPriorityStr,
                                onValueChange = { newPriorityStr = it.filter(Char::isDigit).take(3) },
                                label = { Text(t("شمارهٔ اولویت (کوچک‌تر = اول لیست‌ها)", "Priority (lower = first)"), fontSize = 10.sp) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(onClick = { newStarred = !newStarred }) {
                                    Icon(
                                        if (newStarred) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = t("ستاره‌دار", "Starred"),
                                        tint = if (newStarred) Color(0xFFF9A825) else Color.Gray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(t("ستاره‌دار — در تراکنش‌ها اول لیست ظاهر می‌شود", "Starred — appears first in transaction pickers"), fontSize = 10.sp, color = Color.Gray)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val profile = PersonProfile(
                                            id = editingPersonId,
                                            firstName = newFirstName.trim(),
                                            lastName = newLastName.trim(),
                                            title = newTitle.trim(),
                                            nature = newNature,
                                            openingBalance = com.example.ui.components.parseGroupedAmount(newOpeningStr),
                                            priority = newPriorityStr.toIntOrNull() ?: 0,
                                            isStarred = newStarred
                                        )
                                        if (profile.fullName.isNotBlank()) {
                                            viewModel.savePersonProfile(profile)
                                            justAdded = profile.fullName
                                            resetPersonForm()
                                        }
                                    },
                                    enabled = newFirstName.isNotBlank() || newLastName.isNotBlank(),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (editingPersonId == 0L) t("ثبت شخص", "Save person") else t("به‌روزرسانی", "Update"),
                                        fontSize = 11.sp
                                    )
                                }
                                if (editingPersonId != 0L) {
                                    OutlinedButton(
                                        onClick = { resetPersonForm() },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(t("انصراف", "Cancel"), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                    justAdded?.let { name ->
                        Text(
                            text = t("✅ شخص «$name» به حساب اشخاص اضافه شد.", "✅ Person \"$name\" added."),
                            fontSize = 10.5.sp,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = t("لیست اشخاص — برای مشاهده جریات، روی نام شخص لمس کنید:", "Persons — tap a name to see its ledger:"),
                        fontSize = 10.5.sp,
                        color = Color.Gray
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = false)) {
                        if (personNames.isEmpty()) {
                            item {
                                Text(
                                    text = t("هنوز شخصي ثبت نشده است؛ نام شخص جدید را در فیلد بالا وارد کنید.", "No persons yet; type a new person name above."),
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                        // به‌درخواست کاربر: لیست در سه بخش — بدهکاران، بستانکاران، اعضای خانواده.
                        fun natureOfPerson(name: String): PersonNature {
                            val p = personProfiles.firstOrNull { it.fullName.equals(name, ignoreCase = true) }
                            return when (p?.nature) {
                                PersonNature.NONE -> PersonNature.NONE
                                PersonNature.CREDITOR -> PersonNature.CREDITOR
                                PersonNature.DEBTOR -> PersonNature.DEBTOR
                                else -> {
                                    val tot = personRecords[name].orEmpty().sumOf { it.amount } + (p?.openingBalance ?: 0L)
                                    if (tot > 0L) PersonNature.CREDITOR else PersonNature.DEBTOR
                                }
                            }
                        }
                        listOf(
                            Triple(t("بدهکاران", "Debtors"), PersonNature.DEBTOR, Color(0xFFC62828)),
                            Triple(t("بستانکاران", "Creditors"), PersonNature.CREDITOR, Color(0xFF2E7D32)),
                            Triple(t("اعضای خانواده", "Family members"), PersonNature.NONE, Color(0xFF1565C0))
                        ).forEach { (secTitle, secNature, secColor) ->
                            val secNames = personNames.filter { natureOfPerson(it) == secNature }
                            if (secNames.isEmpty() && personNames.isEmpty()) return@forEach
                            item(key = "psec-$secTitle") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Text(secTitle, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = secColor)
                                    Text(
                                        if (isPersian) "«${secNames.size} نفر»" else "(${secNames.size})",
                                        fontSize = 9.5.sp, color = Color.Gray
                                    )
                                }
                            }
                            items(secNames, key = { "psec-$secTitle-$it" }) { person ->
                            val recs = personRecords[person].orEmpty()
                            // پروندهٔ شخص (عنوان و ماهیت) — اگر شخص فقط از سوابق قدیمی
                            // آمده باشد، پرونده‌ای ندارد و مقادیر خالی می‌مانند.
                            val profile = personProfiles.firstOrNull {
                                it.fullName.equals(person, ignoreCase = true)
                            }
                            val total = recs.sumOf { it.amount } + (profile?.openingBalance ?: 0L)
                            val natureColor = when (profile?.nature) {
                                PersonNature.CREDITOR -> Color(0xFF2E7D32)
                                PersonNature.DEBTOR -> MaterialTheme.colorScheme.error
                                else -> Color.Gray
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { selectedPerson = person },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // طبق درخواست: در حالت بسته فقط عنوان (نام) شخص نشان داده می‌شود
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (profile?.isStarred == true) {
                                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFF9A825), modifier = Modifier.size(14.dp))
                                        }
                                        Text(person, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${formatAmount(total)} $currency",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp,
                                            color = natureColor
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    val p = profile ?: PersonProfile.fromLegacyName(person)
                                                    newFirstName = p.firstName
                                                    newLastName = p.lastName
                                                    newTitle = p.title
                                                    newNature = p.nature
                                                    newOpeningStr = if (p.openingBalance > 0L) formatAmount(p.openingBalance) else ""
                                                    newPriorityStr = if (p.priority > 0) p.priority.toString() else ""
                                                    newStarred = p.isStarred
                                                    editingPersonId = p.id
                                                },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = t("ویرایش", "Edit"),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                } else {
                    // ——— ریز حساب شخص انتخاب‌شده (سوابق مستند) ———
                    val recs = personRecords[currentPerson].orEmpty()
                    val currentProfile = personProfiles.firstOrNull {
                        it.fullName.equals(currentPerson, ignoreCase = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { selectedPerson = null }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Column {
                            Text("👤 $currentPerson", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            val subtitle = listOfNotNull(
                                currentProfile?.title?.takeIf { it.isNotBlank() },
                                currentProfile?.nature?.let { n ->
                                    if (n == PersonNature.NONE) t("عضو خانواده — هزینه", "Family member — expense")
                                    else n.label(isPersian)
                                }
                            ).joinToString(" • ")
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Text(
                        text = t("ریز حساب شخص — به ترتیب تاریخ، با تراز تجمعی:", "Person sub-ledger — by date, with running balance:"),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f, fill = false)) {
                        if (recs.isEmpty()) {
                            item {
                                Text(
                                    text = t("تراکنشی برای این شخص ثبت نشده است.", "No transactions recorded for this person."),
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                        val withRunning = recs.mapIndexed { i, r -> r to recs.take(i + 1).sumOf { it.amount } }
                        items(withRunning, key = { "${it.first.date}-${it.first.amount}-${it.first.description}" }) { (rec, running) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = viewModel.formatDate(rec.date),
                                    fontSize = 9.5.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.width(78.dp)
                                )
                                Text(
                                    text = rec.description,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "-${formatAmount(rec.amount)}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = "${formatAmount(running)} $currency",
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (recs.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = t("جمع کل حساب", "Account total"),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp
                                    )
                                    Text(
                                        text = "${formatAmount(recs.sumOf { it.amount })} $currency",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("بستن", "Close")) }
        }
    )
}
