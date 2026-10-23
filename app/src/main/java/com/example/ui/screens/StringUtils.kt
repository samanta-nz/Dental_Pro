package com.example.ui.screens

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.LabService
import com.example.data.Treatment

fun formatAmount(amount: Long): String {
    return com.example.util.PersianNumberFormatter.formatAmount(amount)
}

@Composable
fun RealTimeAmountHelper(
    amountStr: String,
    isPersian: Boolean = true
) {
    val value = amountStr.toEnglishLongOrNull() ?: 0L
    if (value > 0L && value <= Long.MAX_VALUE / 10L) {
        val rial = Math.multiplyExact(value, 10L)
        val formattedRial = com.example.util.PersianNumberFormatter.formatAmount(rial)
        val formattedToman = com.example.util.PersianNumberFormatter.formatAmount(value)
        val inWords = try {
            com.example.util.PersianNumberFormatter.numberToPersianWords(value)
        } catch (_: Exception) { "" }

        Column(modifier = Modifier.padding(top = 2.dp, start = 4.dp)) {
            Text(
                text = if (isPersian) "معادل: $formattedToman تومان ($formattedRial ریال)" else "Approx: $formattedToman Toman ($formattedRial Rials)",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            if (inWords.isNotBlank()) {
                Text(
                    text = inWords,
                    fontSize = 9.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun isLabRelatedTreatment(tx: Treatment, services: List<LabService>): Boolean {
    val keywords = listOf("روکش", "پروتز", "ایمپلنت", "لمینت", "بلیچینگ", "دنچر", "بریج", "قالب", "ارتودنسی", "آندوسکوپی", "فریم", "crown", "bridge", "implant", "denture", "veneer", "laminate", "prosthesis")
    val titleLower = tx.title.lowercase()
    if (keywords.any { titleLower.contains(it) }) return true
    return services.any { s -> titleLower.contains(s.serviceName.lowercase()) || s.serviceName.lowercase().contains(titleLower) }
}

// تبدیلِ ارقام از مرجعِ واحدِ PersianNumberFormatter می‌آید تا نسخه‌های کپی‌شده
// حذف شوند؛ سپس فقط ارقامِ لاتین نگه داشته می‌شوند.
fun String.toEnglishLong(): Long = this.toEnglishLongOrNull() ?: 0L

fun String.toEnglishLongOrNull(): Long? {
    if (this.isBlank()) return null
    val clean = com.example.util.PersianNumberFormatter.toAsciiDigits(this)
        .replace("−", "-").replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")
        .replace(Regex("(?i)(تومان|ریال|toman|rial)"), "").replace(Regex("[,٬\\s]"), "")
    if (!Regex("[+-]?[0-9]+").matches(clean)) return null
    return clean.toLongOrNull()
}

fun String.toEnglishIntOrNull(): Int? {
    return this.toEnglishLongOrNull()?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
}

fun String.cleanNotesForDisplay(): String {
    return this.trim()
}

fun Modifier.threeSecondLongPress(onLongClick: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectTapGestures(
            onLongPress = { onLongClick() }
        )
    }


fun parseTeethCount(toothNumStr: String): Int {
    val normalized = com.example.util.PersianNumberFormatter.toAsciiDigits(toothNumStr).replace('،', ',')
    val teeth = mutableSetOf<Int>()
    fun valid(n: Int) = n / 10 in 1..4 && n % 10 in 1..8 || n / 10 in 5..8 && n % 10 in 1..5
    normalized.split(Regex("[/,\\s]+")).forEach { token ->
        val range = token.split('-').mapNotNull { it.toIntOrNull() }
        if (range.size == 2 && range.all { it in 11..85 }) {
            for (n in minOf(range[0], range[1])..maxOf(range[0], range[1])) if (valid(n)) teeth.add(n)
        } else if (range.size == 1 && valid(range[0])) teeth.add(range[0])
    }
    return teeth.size.coerceAtLeast(1)
}

fun honorificPatientName(name: String, gender: String = ""): String {
    if (name.isBlank()) return name
    val clean = name.trim()
    if (clean.startsWith("آقای") || clean.startsWith("خانم") || clean.startsWith("Mr.") || clean.startsWith("Ms.")) {
        return clean
    }
    // Fallback if legacy record without prefix:
    val prefix = if (gender == "زن" || gender.lowercase() == "female" || gender == "خانم" || gender == "Ms.") "خانم " else "آقای "
    return prefix + clean
}


/**
 * قالب‌بندی ورودی عددی تاریخ شمسی با جداساز ثابت «/» — سال اول (YYYY/MM/DD)
 * مناسب «تاریخ / سال تولد»: فقط ارقام پذیرفته می‌شود و «/» پس از سال (۴ رقم) و ماه (۲ رقم)
 * خودکار درج می‌گردد. ورودِ فقط سال (مثلاً ۱۳۷۵) همچنان معتبر است.
 */
fun formatJalaliYearFirstInput(raw: String): String {
    val digits = raw.replace(Regex("[^0-9]"), "").take(8)
    val sb = StringBuilder()
    digits.forEachIndexed { index, c ->
        if (index == 4 || index == 6) sb.append('/')
        sb.append(c)
    }
    return sb.toString()
}

/**
 * قالب‌بندی ورودی عددی تاریخ شمسی با جداساز ثابت «/» — روز/ماه/سال (DD/MM/YYYY)
 * مناسب فرم نوبت: در بخش سال، پیشوند «14» خودکار وارد می‌شود؛ کاربر فقط دو رقم آخرِ سال را
 * می‌زند (مثلاً زدن «۰۵» سال ۱۴۰۵ را کامل می‌کند).
 */
fun formatJalaliDayFirstInput(raw: String): String {
    val digits = raw.replace(Regex("[^0-9]"), "").take(8)
    if (digits.isEmpty()) return ""
    val day = digits.take(2)
    val month = digits.drop(2).take(2)
    val yearDigits = digits.drop(4)
    // اگر سال از قبل کامل/نیمه‌کامل است (مثلاً هنگام ویرایش تاریخِ انتخاب‌شده از تقویم) دست‌نخورده
    // می‌ماند؛ اما تایپِ از صفر فقط دو رقم آخر را می‌گیرد و «14» خودکار جلو می‌نشیند.
    val yearStr = when {
        yearDigits.isEmpty() -> ""
        yearDigits.length >= 3 -> yearDigits.take(4)
        else -> "14" + yearDigits.take(2)
    }
    val sb = StringBuilder(day)
    if (digits.length > 2) sb.append('/').append(month)
    if (yearStr.isNotEmpty()) sb.append('/').append(yearStr)
    return sb.toString()
}

/**
 * تبدیل متن تاریخ روز/ماه/سال (با جداساز «/») به سه‌تایی (روز، ماه، سال) شمسی.
 * سالِ دو رقمی به‌صورت خودکار 14xx تفسیر می‌شود. در صورت نامعتبر بودن، null برمی‌گردد.
 */
fun parseJalaliDayFirstInput(text: String): Triple<Int, Int, Int>? {
    val digits = text.replace(Regex("[^0-9]"), "")
    if (digits.length < 6) return null
    val day = digits.take(2).toIntOrNull() ?: return null
    val month = digits.substring(2, 4).toIntOrNull() ?: return null
    val yearDigits = digits.substring(4)
    val year = when (yearDigits.length) {
        4 -> yearDigits.toIntOrNull()
        2 -> ("14" + yearDigits).toIntOrNull()
        else -> null
    } ?: return null
    if (day !in 1..31 || month !in 1..12 || year !in 1300..1499) return null
    return Triple(day, month, year)
}
