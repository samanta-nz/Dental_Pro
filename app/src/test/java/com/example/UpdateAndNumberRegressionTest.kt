package com.example

import com.example.data.*
import com.example.ui.screens.*
import com.example.util.AppUpdateManager
import com.example.util.Supabase
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class UpdateAndNumberRegressionTest {
    @Test fun signedPersianAmountsAndIntegerLimitsArePreserved() {
        assertEquals(-2500L, "−۲٬۵۰۰ تومان".toEnglishLongOrNull())
        assertEquals(2500L, "٢,٥٠٠ تومان".toEnglishLongOrNull())
        assertNull("1.5".toEnglishLongOrNull())
        assertNull("۱۲٫۵".toEnglishLongOrNull())
        assertNull("9999999999999999999999".toEnglishLongOrNull())
        assertNull("2147483648".toEnglishIntOrNull())
        assertNull("12-34".toEnglishLongOrNull())
    }
    @Test fun teethCountsDeduplicateAndRespectFDINumbering() {
        assertEquals(2, parseTeethCount("۱۱،۱۲،۱۱"))
        assertEquals(9, parseTeethCount("11-21"))
        assertEquals(4, parseTeethCount("11-14"))
    }
    @Test fun pendingAndBouncedExpenseChequesDoNotSettleInvoices() {
        assertFalse(ExpensePayment(clinicExpenseId = 1, amount = 100, method = "CHEQUE").countsAsPartyPayment())
        assertFalse(ExpensePayment(clinicExpenseId = 1, amount = 100, method = "CHEQUE", chequeStatus = "BOUNCED").countsAsPartyPayment())
        assertTrue(ExpensePayment(clinicExpenseId = 1, amount = 100, method = "CHEQUE", chequeStatus = "TRANSFERRED").countsAsPartyPayment())
    }
    @Test fun updateMetadataRejectsHttpAndMissingIntegrityAndIgnoresDowngrades() {
        fun row(url: String = "https://example.com/release.apk", hash: String = "a".repeat(64)) = JsonParser.parseString("""{"version_code":70002,"version_name":"7.0.2","apk_url":"$url","apk_sha256":"$hash","apk_size_bytes":12345,"is_mandatory":false}""").asJsonObject
        val info = AppUpdateManager.parseUpdate(row(), 70001, "7.0.1")!!
        assertEquals(AppUpdateManager.UpdateType.PATCH, info.updateType)
        assertFalse(info.isMandatory)
        assertNull(AppUpdateManager.parseUpdate(row(), 80000, "8.0.0"))
        for (bad in listOf(row(url = "http://example.com/app.apk"), row(hash = ""))) {
            try { AppUpdateManager.parseUpdate(bad, 70001, "7.0.1"); fail("Unsafe metadata accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun googleDriveParsingRequiresARealGoogleHostname() {
        assertNull(AppUpdateManager.extractDriveFileId("https://evil.example/drive.google.com/file/d/abcdefghijklm/view"))
        assertEquals("abcdefghijklm", AppUpdateManager.extractDriveFileId("https://drive.google.com/file/d/abcdefghijklm/view"))
    }
    @Test fun whatsappFieldNameRoundTripsAgainstTheRealSchema() {
        assertEquals("sent_whatsapp", Supabase.toSnakeCase("sentWhatsApp"))
        assertEquals("sentWhatsApp", Supabase.toCamelCase("sent_whatsapp"))
    }
}
