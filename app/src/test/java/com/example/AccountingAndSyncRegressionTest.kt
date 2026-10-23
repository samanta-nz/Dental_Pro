package com.example

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.sync.SyncEngine
import com.example.sync.SyncTransport
import com.example.util.StorageCategory
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AccountingAndSyncRegressionTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: DentalRepository
    private lateinit var context: Context
    private var bank = 0L
    private var patient = 0L
    @Before fun setup() = runBlocking<Unit> {
        context = ApplicationProvider.getApplicationContext()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val capabilities = android.net.NetworkCapabilities()
        org.robolectric.Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = DentalRepository(db)
        bank = repo.insertBankAccount(BankAccount(bankName = "Test", balance = 1000))
        patient = repo.insertPatient(Patient(name = "Patient"))
        db.pendingSyncOpDao().clearAll()
    }
    @After fun cleanup() { db.close() }
    private suspend fun balance() = db.bankAccountDao().getBankAccountById(bank)!!.balance

    @Test fun staleBankPresentationEditPreservesNewPaymentsAndOpeningBalance() = runBlocking<Unit> {
        val stale = db.bankAccountDao().getBankAccountById(bank)!!
        val id = repo.insertPayment(Payment(patientId = patient, amount = 500, type = "CARD", bankAccountId = bank))
        repo.insertBankAccount(stale.copy(isHidden = true, sortOrder = 3), updateBalance = false)
        val saved = db.bankAccountDao().getBankAccountById(bank)!!
        assertEquals(1500L, saved.balance); assertEquals(1000L, saved.openingBalance)
        assertTrue(saved.isHidden); assertEquals(3, saved.sortOrder)
        repo.deletePayment(db.paymentDao().getPaymentById(id)!!)
        assertEquals(1000L, balance())
        repo.insertBankAccount(saved.copy(balance = 2000), updateBalance = true)
        assertEquals(2000L, balance())
        assertEquals(2000L, db.bankAccountDao().getBankAccountById(bank)!!.openingBalance)
    }
    @Test fun cardInsertEditRetryAndDeleteNeverDoubleCount() = runBlocking<Unit> {
        val id = repo.insertPayment(Payment(patientId = patient, amount = 500, type = "CARD", bankAccountId = bank))
        assertEquals(1500L, balance())
        var payment = db.paymentDao().getPaymentById(id)!!
        repo.updatePayment(payment.copy(amount = 300))
        assertEquals(1300L, balance())
        payment = db.paymentDao().getPaymentById(id)!!
        repo.updatePayment(payment)
        assertEquals(1300L, balance())
        val ledger = db.financialLedgerDao().findBySource("PATIENT_PAYMENT", id)!!
        assertEquals(payment.syncId, ledger.sourceSyncId)
        repo.deletePayment(payment)
        assertEquals(1000L, balance())
    }
    @Test fun chequeCashAndPatientSettlementFollowConfirmedStatus() = runBlocking<Unit> {
        val id = repo.insertPayment(Payment(patientId = patient, amount = 200, type = "CHEQUE", bankAccountId = bank))
        val pending = db.paymentDao().getPaymentById(id)!!
        assertEquals(1000L, balance()); assertFalse(pending.isEffectivelyPaid)
        repo.updatePayment(pending.copy(chequeStatus = "CLEARED"))
        assertEquals(1200L, balance())
        repo.updatePayment(pending.copy(chequeStatus = "TRANSFERRED"))
        assertEquals(1000L, balance()); assertTrue(db.paymentDao().getPaymentById(id)!!.isEffectivelyPaid)
        repo.updatePayment(pending.copy(chequeStatus = "BOUNCED"))
        assertEquals(1000L, balance()); assertFalse(db.paymentDao().getPaymentById(id)!!.isEffectivelyPaid)
        repo.deletePayment(db.paymentDao().getPaymentById(id)!!)
        assertEquals(1000L, balance())
    }
    @Test fun deletingPendingExpenseDoesNotCreditTheBank() = runBlocking<Unit> {
        val expense = repo.insertExpense(ClinicExpense(category = "Test", amount = 400))
        repo.insertExpensePayment(ExpensePayment(clinicExpenseId = expense, amount = 400, method = "CHEQUE", bankAccountId = bank))
        assertEquals(1000L, balance())
        repo.deleteExpense(db.clinicExpenseDao().getExpenseById(expense)!!)
        assertEquals(1000L, balance())
    }
    @Test fun changingExpenseBankReversesOnlyTheOldImpact() = runBlocking<Unit> {
        val other = repo.insertBankAccount(BankAccount(bankName = "Other", balance = 5000))
        val id = repo.insertExpensePayment(ExpensePayment(clinicExpenseId = 0, amount = 200, bankAccountId = bank))
        assertEquals(800L, balance())
        val current = db.expensePaymentDao().getExpensePaymentById(id)!!
        repo.insertExpensePayment(current.copy(bankAccountId = other, amount = 100))
        assertEquals(1000L, balance())
        assertEquals(4900L, db.bankAccountDao().getBankAccountById(other)!!.balance)
        repo.deleteExpensePayment(db.expensePaymentDao().getExpensePaymentById(id)!!)
        assertEquals(5000L, db.bankAccountDao().getBankAccountById(other)!!.balance)
    }
    @Test fun clinicDrawDecreasesBankAndDeletingItRestoresBalance() = runBlocking<Unit> {
        val id = repo.insertTransaction(PersonalTransaction(type = "DRAW", amount = 300, bankAccountId = bank))
        assertEquals(700L, balance())
        repo.deleteTransaction(db.personalTransactionDao().getTransactionById(id)!!)
        assertEquals(1000L, balance())
    }
    @Test fun deletingPatientKeepsOtherSameNamedPatientsAppointments() = runBlocking<Unit> {
        val p1 = Patient(name = "Same"); val p2 = Patient(name = "Same")
        val id1 = repo.insertPatient(p1); val id2 = repo.insertPatient(p2)
        repo.insertAppointment(Appointment(patientId = id1, patientName = "Same", treatmentType = "Test", date = 1))
        repo.insertAppointment(Appointment(patientId = id2, patientName = "Same", treatmentType = "Test", date = 2))
        repo.deletePatientCascading(p1.copy(id = id1))
        val remaining = db.appointmentDao().getAllList()
        assertEquals(1, remaining.size); assertEquals(id2, remaining.single().patientId)
    }
    @Test fun overflowingPaymentRollsBackBothBankAndPayment() = runBlocking<Unit> {
        try { repo.insertPayment(Payment(patientId = patient, amount = Long.MAX_VALUE, type = "CARD", bankAccountId = bank)); fail("Overflow must be rejected") }
        catch (_: ArithmeticException) { }
        assertEquals(1000L, balance()); assertTrue(db.paymentDao().getAllList().isEmpty())
    }
    private class FakeTransport : SyncTransport {
        var fail = false; var uploadFail = false
        val sent = mutableListOf<Map<String, Any>>()
        override suspend fun ensureAccountId() = "owner"
        override suspend fun upsert(table: String, syncId: String, data: Map<String, Any>) { if (fail) error("Network unavailable"); sent.add(data) }
        override suspend fun delete(table: String, syncId: String) { if (fail) error("Network unavailable") }
        override suspend fun upload(file: File, bucket: String, category: StorageCategory): String? = if (uploadFail) null else StorageCategory.objectPathFor("owner", category, file)
    }
    private fun engine(t: FakeTransport) = SyncEngine(false, context, repo, db, observeNetwork = false, transport = t)
    @Test fun changingGoogleAccountCannotSendThePreviousAccountsPendingRecords() = runBlocking<Unit> {
        val p = Patient(name = "Previous clinic"); repo.insertPatient(p)
        val prefs = context.getSharedPreferences("dental_pro_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("auth_account_id", "previous-owner").commit()
        val t = FakeTransport()
        try {
            try { engine(t).pushPending(); fail("Wrong-account sync must be rejected") }
            catch (_: com.example.sync.AccountMismatchException) { }
            assertEquals(1, db.pendingSyncOpDao().countOnce()); assertTrue(t.sent.isEmpty())
        } finally { prefs.edit().remove("auth_account_id").commit() }
    }
    @Test fun failedWritesRemainQueuedAfterSixAttemptsAndThenRecover() = runBlocking<Unit> {
        val p = Patient(name = "Pending"); repo.insertPatient(p)
        val t = FakeTransport().apply { fail = true }; val engine = engine(t)
        repeat(7) { engine.pushPending() }
        val op = db.pendingSyncOpDao().getPendingOps().single()
        assertEquals(7, op.attemptCount); assertNotNull(op.lastError); assertNull(engine.lastSyncedAt.value)
        t.fail = false; engine.pushPending()
        assertEquals(0, db.pendingSyncOpDao().countOnce()); assertEquals(1, t.sent.size)
    }
    @Test fun failedRemoteDeletionIsNeverAcknowledged() = runBlocking<Unit> {
        repo.enqueueSyncOp("patients", "deleted-record", "DELETE")
        val t = FakeTransport().apply { fail = true }; engine(t).pushPending()
        assertEquals(1, db.pendingSyncOpDao().countOnce())
        t.fail = false; engine(t).pushPending()
        assertEquals(0, db.pendingSyncOpDao().countOnce())
    }
    @Test fun failedImageUploadKeepsTheOperationAndNeverSendsALocalPath() = runBlocking<Unit> {
        val image = File.createTempFile("patient", ".jpg", context.filesDir).apply { writeText("image-data") }
        val p = Patient(name = "Photo", photoFileId = image.path); repo.insertPatient(p)
        val t = FakeTransport().apply { uploadFail = true }; engine(t).pushPending()
        assertEquals(1, db.pendingSyncOpDao().countOnce()); assertTrue(t.sent.isEmpty())
        t.uploadFail = false; engine(t).pushPending()
        assertEquals(0, db.pendingSyncOpDao().countOnce())
        assertFalse((t.sent.single()["photoFileId"] as String).startsWith("/"))
        assertEquals(t.sent.single()["photoFileId"], db.patientDao().getBySyncId(p.syncId)!!.photoFileId)
        image.delete()
    }
    @Test fun failedDocumentUploadCannotAcknowledgeAMetadataOnlyRecord() = runBlocking<Unit> {
        val image = File.createTempFile("document", ".jpg", context.filesDir).apply { writeText("document-data") }
        repo.insertPatientDocument(PatientDocument(patientId = patient, uri = image.path, docType = "RADIOGRAPH"))
        val t = FakeTransport().apply { uploadFail = true }
        engine(t).pushPending()
        assertEquals(1, db.pendingSyncOpDao().countOnce())
        assertTrue(t.sent.isEmpty())
        t.uploadFail = false; engine(t).pushPending()
        assertEquals(0, db.pendingSyncOpDao().countOnce())
        assertTrue((t.sent.single()["fileId"] as String).startsWith("owner/"))
        image.delete()
    }
    @Test fun signatureAndChequeImagesUsePersistentRemoteReferences() = runBlocking<Unit> {
        val image = File.createTempFile("signature", ".png", context.filesDir).apply { writeText("signature-data") }
        val p = Patient(name = "Signed", signatureFileId = image.path); repo.insertPatient(p)
        val t = FakeTransport(); engine(t).pushPending()
        assertFalse(db.patientDao().getBySyncId(p.syncId)!!.signatureFileId!!.startsWith("/"))
        repo.insertPayment(Payment(patientId = patient, amount = 100, type = "CHEQUE", chequePhotoFileId = image.path))
        engine(t).pushPending()
        assertTrue(db.paymentDao().getAllList().single().chequePhotoFileId!!.startsWith("owner/"))
        image.delete()
    }
    @Test fun pullCannotOverwritePendingLocalEdits() = runBlocking<Unit> {
        val p = Patient(name = "Local edit"); repo.insertPatient(p)
        engine(FakeTransport()).pullDocument("patients", p.syncId, Gson().toJson(p.copy(name = "Remote old", updatedAt = Long.MAX_VALUE)), Long.MAX_VALUE, Gson())
        assertEquals("Local edit", db.patientDao().getBySyncId(p.syncId)!!.name)
    }
    @Test fun importedLedgerResolvesTheSourceBySyncIdRatherThanAnotherDevicesInteger() = runBlocking<Unit> {
        val id = repo.insertPayment(Payment(patientId = patient, amount = 50, type = "CASH"))
        val payment = db.paymentDao().getPaymentById(id)!!
        db.pendingSyncOpDao().clearAll()
        val entry = FinancialLedgerEntry(sourceType = "PATIENT_PAYMENT", sourceId = 9999, sourceSyncId = payment.syncId, amountDelta = 50, syncId = "ledger:PATIENT_PAYMENT:${payment.syncId}")
        engine(FakeTransport()).pullDocument("financial_ledger", entry.syncId, Gson().toJson(entry), Long.MAX_VALUE, Gson())
        assertEquals(id, db.financialLedgerDao().getBySyncId(entry.syncId)!!.sourceId)
    }
    @Test fun contentAddressedImageCacheRejectsChangedBytes() {
        val image = File.createTempFile("cache", ".jpg", context.filesDir).apply { writeText("correct") }
        val path = StorageCategory.objectPathFor("owner", StorageCategory.PATIENTS, image)
        assertTrue(SyncEngine.isValidCachedFile(image, path))
        image.writeText("corrupted"); assertFalse(SyncEngine.isValidCachedFile(image, path)); image.delete()
    }
}
