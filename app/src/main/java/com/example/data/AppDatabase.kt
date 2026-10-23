package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Patient?

    @Query("SELECT * FROM patients WHERE id = :id LIMIT 1")
    suspend fun getPatientById(id: Long): Patient?
    
    @Query("SELECT * FROM patients")
    suspend fun getAllList(): List<Patient>

    @Query("SELECT * FROM patients ORDER BY name ASC")
    fun getAllPatients(): Flow<List<Patient>>

    @Query("SELECT * FROM patients WHERE isArchived = 1 ORDER BY name ASC")
    fun getAllArchivedPatients(): Flow<List<Patient>>

    @Query("SELECT * FROM patients WHERE isArchived = 1")
    suspend fun getArchivedList(): List<Patient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatient(patient: Patient): Long

    @Delete
    suspend fun deletePatient(patient: Patient)
}

@Dao
interface TreatmentDao {
    @Query("SELECT * FROM treatments WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Treatment?

    @Query("SELECT * FROM treatments WHERE id = :id LIMIT 1")
    suspend fun getTreatmentById(id: Long): Treatment?
    
    @Query("SELECT * FROM treatments")
    suspend fun getAllList(): List<Treatment>

    @Query("SELECT * FROM treatments ORDER BY date DESC")
    fun getAllTreatments(): Flow<List<Treatment>>

    @Query("SELECT * FROM treatments WHERE patientId = :patientId ORDER BY date DESC")
    fun getTreatmentsForPatient(patientId: Long): Flow<List<Treatment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreatment(treatment: Treatment): Long

    @Delete
    suspend fun deleteTreatment(treatment: Treatment)

    @Query("DELETE FROM treatments WHERE patientId = :patientId")
    suspend fun deleteTreatmentsForPatient(patientId: Long)

    @Query("SELECT * FROM treatments WHERE patientId = :patientId")
    suspend fun getTreatmentsForPatientOnce(patientId: Long): List<Treatment>
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Payment?
    
    @Query("SELECT * FROM payments")
    suspend fun getAllList(): List<Payment>

    @Query("SELECT * FROM payments ORDER BY date DESC")
    fun getAllPayments(): Flow<List<Payment>>

    @Query("SELECT * FROM payments WHERE patientId = :patientId ORDER BY date DESC")
    fun getPaymentsForPatient(patientId: Long): Flow<List<Payment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: Payment): Long

    @Query("SELECT * FROM payments WHERE id = :id LIMIT 1")
    suspend fun getPaymentById(id: Long): Payment?

    @Update
    suspend fun updatePayment(payment: Payment)

    @Delete
    suspend fun deletePayment(payment: Payment)

    @Query("DELETE FROM payments WHERE patientId = :patientId")
    suspend fun deletePaymentsForPatient(patientId: Long)

    @Query("SELECT * FROM payments WHERE patientId = :patientId")
    suspend fun getPaymentsForPatientOnce(patientId: Long): List<Payment>
}

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Appointment?
    
    @Query("SELECT * FROM appointments")
    suspend fun getAllList(): List<Appointment>

    @Query("SELECT * FROM appointments ORDER BY date ASC")
    fun getAllAppointments(): Flow<List<Appointment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppointment(appointment: Appointment): Long

    @Update
    suspend fun updateAppointment(appointment: Appointment)

    @Delete
    suspend fun deleteAppointment(appointment: Appointment)

    @Query("DELETE FROM appointments WHERE patientId = :patientId OR ((patientId IS NULL OR patientId = 0) AND patientName = :patientName)")
    suspend fun deleteAppointmentsForPatient(patientId: Long, patientName: String)

    @Query("SELECT * FROM appointments WHERE patientId = :patientId OR ((patientId IS NULL OR patientId = 0) AND patientName = :patientName)")
    suspend fun getAppointmentsForPatientOnce(patientId: Long, patientName: String): List<Appointment>
}

@Dao
interface VendorDao {
    @Query("SELECT * FROM vendors WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Vendor?

    @Query("SELECT * FROM vendors WHERE id = :id LIMIT 1")
    suspend fun getVendorById(id: Long): Vendor?

    @Query("SELECT * FROM vendors ORDER BY name ASC")
    fun getAllVendors(): Flow<List<Vendor>>

    @Query("SELECT * FROM vendors")
    suspend fun getAllList(): List<Vendor>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVendor(vendor: Vendor): Long

    @Delete
    suspend fun deleteVendor(vendor: Vendor)
}

@Dao
interface LabOrderDao {
    @Query("SELECT * FROM lab_orders WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): LabOrder?

    @Query("SELECT * FROM lab_orders WHERE id = :id LIMIT 1")
    suspend fun getLabOrderById(id: Long): LabOrder?

    @Query("SELECT * FROM lab_orders ORDER BY dueDate ASC")
    fun getAllLabOrders(): Flow<List<LabOrder>>

    @Query("SELECT * FROM lab_orders")
    suspend fun getAllList(): List<LabOrder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabOrder(order: LabOrder): Long

    @Delete
    suspend fun deleteLabOrder(order: LabOrder)
}

@Dao
interface EquipmentMaintenanceDao {
    @Query("SELECT * FROM equipment_maintenance WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): EquipmentMaintenance?

    @Query("SELECT * FROM equipment_maintenance WHERE id = :id LIMIT 1")
    suspend fun getMaintenanceById(id: Long): EquipmentMaintenance?

    @Query("SELECT * FROM equipment_maintenance ORDER BY date DESC")
    fun getAllMaintenance(): Flow<List<EquipmentMaintenance>>

    @Query("SELECT * FROM equipment_maintenance")
    suspend fun getAllList(): List<EquipmentMaintenance>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMaintenance(maintenance: EquipmentMaintenance): Long

    @Delete
    suspend fun deleteMaintenance(maintenance: EquipmentMaintenance)
}

@Dao
interface ClinicExpenseDao {
    @Query("SELECT * FROM clinic_expenses WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): ClinicExpense?

    @Query("SELECT * FROM clinic_expenses WHERE id = :id LIMIT 1")
    suspend fun getExpenseById(id: Long): ClinicExpense?

    @Query("SELECT * FROM clinic_expenses ORDER BY date DESC")
    fun getAllExpenses(): Flow<List<ClinicExpense>>

    @Query("SELECT * FROM clinic_expenses")
    suspend fun getAllList(): List<ClinicExpense>

    /** هزینه‌هایی که از روی یک سفارش لابراتوار ساخته شده‌اند — برای آبشاریِ حذف/ویرایش سفارش. */
    @Query("SELECT * FROM clinic_expenses WHERE labOrderId = :labOrderId")
    suspend fun getExpensesForLabOrder(labOrderId: Long): List<ClinicExpense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ClinicExpense): Long

    @Update
    suspend fun updateExpense(expense: ClinicExpense)

    @Delete
    suspend fun deleteExpense(expense: ClinicExpense)
}

@Dao
interface ExpensePaymentDao {
    @Query("SELECT * FROM expense_payments WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): ExpensePayment?

    @Query("SELECT * FROM expense_payments ORDER BY date DESC")
    fun getAllExpensePayments(): Flow<List<ExpensePayment>>

    @Query("SELECT * FROM expense_payments")
    suspend fun getAllList(): List<ExpensePayment>

    @Query("SELECT * FROM expense_payments WHERE clinicExpenseId = :expenseId ORDER BY date DESC")
    fun getPaymentsForExpense(expenseId: Long): Flow<List<ExpensePayment>>

    @Query("SELECT * FROM expense_payments WHERE clinicExpenseId = :expenseId ORDER BY date DESC")
    suspend fun getPaymentsForExpenseOnce(expenseId: Long): List<ExpensePayment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpensePayment(payment: ExpensePayment): Long

    @Query("SELECT * FROM expense_payments WHERE id = :id LIMIT 1")
    suspend fun getExpensePaymentById(id: Long): ExpensePayment?

    @Delete
    suspend fun deleteExpensePayment(payment: ExpensePayment)
}

@Dao
interface PartyAccountDao {
    @Query("SELECT * FROM party_accounts WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): PartyAccount?

    @Query("SELECT * FROM party_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PartyAccount?

    @Query("SELECT * FROM party_accounts ORDER BY section ASC, name ASC")
    fun getAllPartyAccounts(): Flow<List<PartyAccount>>

    @Query("SELECT * FROM party_accounts")
    suspend fun getAllList(): List<PartyAccount>

    @Query("SELECT * FROM party_accounts WHERE section = :section")
    suspend fun getBySection(section: String): List<PartyAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPartyAccount(account: PartyAccount): Long

    @Delete
    suspend fun deletePartyAccount(account: PartyAccount)
}

@Dao
interface PersonalTransactionDao {
    @Query("SELECT * FROM personal_transactions WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): PersonalTransaction?

    @Query("SELECT * FROM personal_transactions ORDER BY date DESC")
    fun getAllTransactions(): Flow<List<PersonalTransaction>>

    @Query("SELECT * FROM personal_transactions")
    suspend fun getAllList(): List<PersonalTransaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(tx: PersonalTransaction): Long

    @Query("SELECT * FROM personal_transactions WHERE id = :id LIMIT 1")
    suspend fun getTransactionById(id: Long): PersonalTransaction?

    @Delete
    suspend fun deleteTransaction(tx: PersonalTransaction)
}

@Dao
interface TreatmentTemplateDao {
    @Query("SELECT * FROM treatment_templates WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): TreatmentTemplate?

    @Query("SELECT * FROM treatment_templates ORDER BY isStarred DESC, sortOrder ASC, id ASC")
    fun getAllTemplates(): Flow<List<TreatmentTemplate>>

    @Query("SELECT * FROM treatment_templates")
    suspend fun getAllList(): List<TreatmentTemplate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: TreatmentTemplate): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<TreatmentTemplate>)

    @Delete
    suspend fun deleteTemplate(template: TreatmentTemplate)
}

@Dao
interface PrescriptionDao {
    @Query("SELECT * FROM prescriptions WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Prescription?

    @Query("SELECT * FROM prescriptions WHERE patientId = :patientId ORDER BY date DESC")
    fun getPrescriptionsForPatient(patientId: Long): Flow<List<Prescription>>

    @Query("SELECT * FROM prescriptions WHERE treatmentId = :treatmentId ORDER BY date DESC")
    fun getPrescriptionsForTreatment(treatmentId: Long): Flow<List<Prescription>>

    @Query("SELECT * FROM prescriptions WHERE treatmentId = :treatmentId ORDER BY date DESC")
    suspend fun getPrescriptionsForTreatmentOnce(treatmentId: Long): List<Prescription>

    @Query("SELECT * FROM prescriptions ORDER BY date DESC")
    fun getAllPrescriptions(): Flow<List<Prescription>>

    @Query("SELECT * FROM prescriptions")
    suspend fun getAllList(): List<Prescription>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrescription(prescription: Prescription): Long

    @Delete
    suspend fun deletePrescription(prescription: Prescription)

    @Query("DELETE FROM prescriptions WHERE patientId = :patientId")
    suspend fun deletePrescriptionsForPatient(patientId: Long)

    @Query("DELETE FROM prescriptions WHERE treatmentId = :treatmentId")
    suspend fun deletePrescriptionsForTreatment(treatmentId: Long)

    @Query("SELECT * FROM prescriptions WHERE patientId = :patientId")
    suspend fun getPrescriptionsForPatientOnce(patientId: Long): List<Prescription>
}

@Dao
interface BankAccountDao {
    @Query("SELECT * FROM bank_accounts WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): BankAccount?

    @Query("SELECT * FROM bank_accounts ORDER BY sortOrder ASC, bankName ASC")
    fun getAllBankAccounts(): Flow<List<BankAccount>>

    @Query("SELECT * FROM bank_accounts")
    suspend fun getAllList(): List<BankAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBankAccount(account: BankAccount): Long

    @Query("SELECT * FROM bank_accounts WHERE id = :id LIMIT 1")
    suspend fun getBankAccountById(id: Long): BankAccount?

    @Query("UPDATE bank_accounts SET balance = balance + :delta WHERE id = :id")
    suspend fun adjustBalance(id: Long, delta: Long): Int

    @Delete
    suspend fun deleteBankAccount(account: BankAccount)
}

@Dao
interface PersonalSubAccountDao {
    @Query("SELECT * FROM personal_sub_accounts WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): PersonalSubAccount?

    @Query("SELECT * FROM personal_sub_accounts ORDER BY name ASC")
    fun getAllSubAccounts(): Flow<List<PersonalSubAccount>>

    @Query("SELECT * FROM personal_sub_accounts")
    suspend fun getAllList(): List<PersonalSubAccount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubAccount(sub: PersonalSubAccount): Long

    @Delete
    suspend fun deleteSubAccount(sub: PersonalSubAccount)
}

@Dao
interface ChildrenExpenseDao {
    @Query("SELECT * FROM children_expenses WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): ChildrenExpense?

    @Query("SELECT * FROM children_expenses ORDER BY date DESC")
    fun getAllChildrenExpenses(): Flow<List<ChildrenExpense>>

    @Query("SELECT * FROM children_expenses")
    suspend fun getAllList(): List<ChildrenExpense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChildrenExpense(expense: ChildrenExpense): Long

    @Query("SELECT * FROM children_expenses WHERE id = :id LIMIT 1")
    suspend fun getChildrenExpenseById(id: Long): ChildrenExpense?

    @Delete
    suspend fun deleteChildrenExpense(expense: ChildrenExpense)
}

@Dao
interface LabServiceDao {
    @Query("SELECT * FROM lab_services WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): LabService?

    @Query("SELECT * FROM lab_services ORDER BY serviceName ASC")
    fun getAllLabServices(): Flow<List<LabService>>

    @Query("SELECT * FROM lab_services")
    suspend fun getAllList(): List<LabService>

    @Query("SELECT * FROM lab_services WHERE labName = :labName ORDER BY serviceName ASC")
    fun getServicesForLab(labName: String): Flow<List<LabService>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabService(service: LabService): Long

    @Delete
    suspend fun deleteLabService(service: LabService)
}

@Dao
interface FixedIncomeDao {
    @Query("SELECT * FROM fixed_incomes WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): FixedIncome?

    @Query("SELECT * FROM fixed_incomes ORDER BY id DESC")
    fun getAllFixedIncomes(): Flow<List<FixedIncome>>

    @Query("SELECT * FROM fixed_incomes")
    suspend fun getAllList(): List<FixedIncome>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFixedIncome(income: FixedIncome): Long

    @Delete
    suspend fun deleteFixedIncome(income: FixedIncome)
}

@Dao
interface TreatmentStageDao {
    @Query("SELECT * FROM treatment_stages WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): TreatmentStage?

    @Query("SELECT * FROM treatment_stages WHERE treatmentId = :treatmentId ORDER BY stageIndex ASC")
    fun getStagesForTreatment(treatmentId: Long): Flow<List<TreatmentStage>>

    @Query("SELECT * FROM treatment_stages WHERE treatmentId = :treatmentId ORDER BY stageIndex ASC")
    suspend fun getStagesForTreatmentOnce(treatmentId: Long): List<TreatmentStage>

    @Query("SELECT * FROM treatment_stages ORDER BY treatmentId ASC, stageIndex ASC")
    fun getAllStages(): Flow<List<TreatmentStage>>

    @Query("SELECT * FROM treatment_stages")
    suspend fun getAllList(): List<TreatmentStage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStage(stage: TreatmentStage): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStages(stages: List<TreatmentStage>)

    @Update
    suspend fun updateStage(stage: TreatmentStage)

    @Delete
    suspend fun deleteStage(stage: TreatmentStage)

    @Query("DELETE FROM treatment_stages WHERE treatmentId = :treatmentId")
    suspend fun deleteStagesForTreatment(treatmentId: Long)
}

@Dao
interface PatientDocumentDao {
    @Query("SELECT * FROM patient_documents WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): PatientDocument?

    @Query("SELECT * FROM patient_documents WHERE patientId = :patientId ORDER BY createdAt DESC")
    fun getDocumentsForPatient(patientId: Long): Flow<List<PatientDocument>>

    @Query("SELECT * FROM patient_documents ORDER BY createdAt DESC")
    fun getAllDocuments(): Flow<List<PatientDocument>>

    @Query("SELECT * FROM patient_documents")
    suspend fun getAllList(): List<PatientDocument>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(doc: PatientDocument): Long

    @Delete
    suspend fun deleteDocument(doc: PatientDocument)

    @Query("DELETE FROM patient_documents WHERE patientId = :patientId")
    suspend fun deleteDocumentsForPatient(patientId: Long)

    @Query("SELECT * FROM patient_documents WHERE patientId = :patientId")
    suspend fun getDocumentsForPatientOnce(patientId: Long): List<PatientDocument>
}

@Dao
interface VendorInvoiceDao {
    @Query("SELECT * FROM vendor_invoices WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): VendorInvoice?

    @Query("SELECT * FROM vendor_invoices WHERE vendorId = :vendorId ORDER BY date DESC")
    fun getInvoicesForVendor(vendorId: Long): Flow<List<VendorInvoice>>

    @Query("SELECT * FROM vendor_invoices WHERE vendorId = :vendorId ORDER BY date DESC")
    suspend fun getInvoicesForVendorOnce(vendorId: Long): List<VendorInvoice>

    @Query("SELECT * FROM vendor_invoices ORDER BY date DESC")
    fun getAllInvoices(): Flow<List<VendorInvoice>>

    @Query("SELECT * FROM vendor_invoices")
    suspend fun getAllList(): List<VendorInvoice>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: VendorInvoice): Long

    @Update
    suspend fun updateInvoice(invoice: VendorInvoice)

    @Delete
    suspend fun deleteInvoice(invoice: VendorInvoice)

    @Query("DELETE FROM vendor_invoices WHERE vendorId = :vendorId")
    suspend fun deleteInvoicesForVendor(vendorId: Long)
}

@Dao
interface ClinicSettingsDao {
    @Query("SELECT * FROM clinic_settings WHERE id = 1")
    fun getSettings(): Flow<ClinicSettings?>

    @Query("SELECT * FROM clinic_settings WHERE id = 1")
    suspend fun getSettingsOnce(): ClinicSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: ClinicSettings): Long

    @Query("UPDATE clinic_settings SET globalFontScaleIndex = :index WHERE id = 1")
    suspend fun updateGlobalFontScale(index: Int)

    @Query("UPDATE clinic_settings SET clinicalFontSizePt = :sizePt WHERE id = 1")
    suspend fun updateClinicalFontSize(sizePt: Int)

    @Query("UPDATE clinic_settings SET clinicName = :clinicName, doctorName = :doctorName, staffNames = :staffNames, showSummaryCard = :showSummary, showQuickActions = :showQuick, showCharts = :showCharts WHERE id = 1")
    suspend fun updateGeneralSettings(clinicName: String, doctorName: String, staffNames: String, showSummary: Int, showQuick: Int, showCharts: Int)

}

@Dao
interface InstallmentDao {
    @Query("SELECT * FROM installments WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): Installment?

    @Query("SELECT * FROM installments ORDER BY dueDate ASC")
    fun getAllInstallments(): Flow<List<Installment>>

    @Query("SELECT * FROM installments")
    suspend fun getAllList(): List<Installment>

    @Query("SELECT * FROM installments WHERE patientId = :patientId ORDER BY dueDate ASC")
    fun getInstallmentsForPatient(patientId: Long): Flow<List<Installment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstallment(installment: Installment): Long

    @Update
    suspend fun updateInstallment(installment: Installment)

    @Delete
    suspend fun deleteInstallment(installment: Installment)

    @Query("DELETE FROM installments WHERE patientId = :patientId")
    suspend fun deleteInstallmentsForPatient(patientId: Long)

    @Query("SELECT * FROM installments WHERE patientId = :patientId")
    suspend fun getInstallmentsForPatientOnce(patientId: Long): List<Installment>
}

@Dao
interface AppCalendarEventDao {
    @Query("SELECT * FROM app_calendar_events WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): AppCalendarEvent?

    @Query("SELECT * FROM app_calendar_events")
    fun getAllCalendarEvents(): Flow<List<AppCalendarEvent>>

    @Query("SELECT * FROM app_calendar_events")
    suspend fun getAllList(): List<AppCalendarEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarEvents(events: List<AppCalendarEvent>)

    @Query("DELETE FROM app_calendar_events WHERE category != 'شخصی'")
    suspend fun deleteNonPersonalCalendarEvents()

    @Query("SELECT * FROM app_calendar_events WHERE category != 'شخصی'")
    suspend fun getNonPersonalCalendarEventsOnce(): List<AppCalendarEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarEvent(event: AppCalendarEvent): Long

    @Delete
    suspend fun deleteCalendarEvent(event: AppCalendarEvent)
}

@Dao
interface FinancialLedgerDao {
    @Query("SELECT * FROM financial_ledger WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): FinancialLedgerEntry?

    @Query("SELECT * FROM financial_ledger ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<FinancialLedgerEntry>>

    @Query("SELECT * FROM financial_ledger")
    suspend fun getAllList(): List<FinancialLedgerEntry>

    @Query("SELECT * FROM financial_ledger WHERE sourceType = :sourceType AND sourceId = :sourceId LIMIT 1")
    suspend fun findBySource(sourceType: String, sourceId: Long): FinancialLedgerEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FinancialLedgerEntry): Long

    @Query("DELETE FROM financial_ledger WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteBySource(sourceType: String, sourceId: Long)

    @Query("DELETE FROM financial_ledger WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("SELECT COALESCE(SUM(amountDelta), 0) FROM financial_ledger WHERE status = 'POSTED' AND occurredAt BETWEEN :from AND :to")
    suspend fun postedNet(from: Long, to: Long): Long
}

@Dao
interface AuditEventDao {
    @Query("SELECT * FROM audit_events WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): AuditEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: AuditEvent): Long

    @Query("SELECT * FROM audit_events ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<AuditEvent>>

    @Query("SELECT * FROM audit_events")
    suspend fun getAllList(): List<AuditEvent>
}



@Dao
interface StaffMemberDao {
    @Query("SELECT * FROM staff_members WHERE isDeleted = 0 ORDER BY fullName ASC")
    fun getAllStaff(): Flow<List<StaffMember>>

    @Query("SELECT * FROM staff_members")
    suspend fun getAllList(): List<StaffMember>

    @Query("SELECT * FROM staff_members WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): StaffMember?

    @Query("SELECT * FROM staff_members WHERE updatedAt > :since")
    suspend fun getChangedSince(since: Long): List<StaffMember>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStaff(staff: StaffMember): Long

    @Update
    suspend fun updateStaff(staff: StaffMember)

    @Delete
    suspend fun deleteStaff(staff: StaffMember)
}

@Dao
interface PendingSyncOpDao {
    @Query("SELECT * FROM pending_sync_ops WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingOpsAfter(afterId: Long, limit: Int = 50): List<PendingSyncOp>

    @Query("SELECT * FROM pending_sync_ops ORDER BY id ASC LIMIT :limit")
    suspend fun getPendingOps(limit: Int = 100): List<PendingSyncOp>

    @Query("SELECT COUNT(*) FROM pending_sync_ops")
    fun getCount(): kotlinx.coroutines.flow.Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(op: PendingSyncOp): Long

    @Delete
    suspend fun delete(op: PendingSyncOp)

    @Query("DELETE FROM pending_sync_ops WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM pending_sync_ops")
    suspend fun countOnce(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM pending_sync_ops WHERE collectionTable = :table AND recordSyncId = :syncId)")
    suspend fun hasPending(table: String, syncId: String): Boolean

    @Query("UPDATE pending_sync_ops SET attemptCount = attemptCount + 1, lastError = :error WHERE id = :id")
    suspend fun recordFailure(id: Long, error: String)

    @Query("DELETE FROM pending_sync_ops")
    suspend fun clearAll()
}

@Dao
interface DrugTemplateDao {
    @Query("SELECT * FROM drug_templates WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): DrugTemplate?

    @Query("SELECT * FROM drug_templates ORDER BY category, title")
    fun getAll(): kotlinx.coroutines.flow.Flow<List<DrugTemplate>>

    @Query("SELECT * FROM drug_templates")
    suspend fun getAllList(): List<DrugTemplate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: DrugTemplate): Long

    @Delete
    suspend fun delete(template: DrugTemplate)
}

@Dao
interface UnregisteredBankSmsDao {
    @Query("SELECT * FROM unregistered_bank_sms WHERE status = 'PENDING' AND isDeleted = 0 ORDER BY createdAt DESC")
    fun getPendingSmsFlow(): kotlinx.coroutines.flow.Flow<List<com.example.data.UnregisteredBankSms>>

    @Query("SELECT * FROM unregistered_bank_sms WHERE status = 'PENDING' AND isDeleted = 0 ORDER BY createdAt DESC")
    suspend fun getPendingSmsList(): List<com.example.data.UnregisteredBankSms>

    @Query("SELECT COUNT(*) FROM unregistered_bank_sms WHERE status = 'PENDING' AND isDeleted = 0")
    fun getPendingCountFlow(): kotlinx.coroutines.flow.Flow<Int>

    // لیست تک‌سطری «ثبت‌شده‌ها» (مبلغ | بابت خلاصه | تاریخ)
    @Query("SELECT * FROM unregistered_bank_sms WHERE status = 'ASSIGNED' AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun getRegisteredSmsFlow(): kotlinx.coroutines.flow.Flow<List<com.example.data.UnregisteredBankSms>>

    // لیست مجزای «نادیده‌گرفته‌شده‌ها» (بعداً قابل بازگردانی/ثبت)
    @Query("SELECT * FROM unregistered_bank_sms WHERE status = 'IGNORED' AND isDeleted = 0 ORDER BY updatedAt DESC")
    fun getIgnoredSmsFlow(): kotlinx.coroutines.flow.Flow<List<com.example.data.UnregisteredBankSms>>

    @Query("SELECT * FROM unregistered_bank_sms WHERE smsNativeId = :nativeId LIMIT 1")
    suspend fun getByNativeId(nativeId: String): com.example.data.UnregisteredBankSms?

    // تشخیص تکراری بر اساس تاریخ/ساعت پیامک (نه فقط هش محتوا) — برای اسکن مجدد:
    // اگر پیامکی با همین تاریخ/ساعت قبلاً در حساب‌ها ثبت شده (ASSIGNED)، نادیده گرفته شده
    // (IGNORED) یا حذف شده (isDeleted) باشد، دیگر کاندیدِ تازه‌ای با همان تاریخ/ساعت نباید
    // دوباره به صف «ثبت‌نشده‌ها» اضافه شود — حتی اگر متن دقیق پیامک کمی متفاوت پارس شده باشد.
    @Query("SELECT * FROM unregistered_bank_sms WHERE dateStr = :dateStr AND timeStr = :timeStr AND (status = 'ASSIGNED' OR status = 'IGNORED' OR isDeleted = 1) LIMIT 1")
    suspend fun findProcessedByDateTime(dateStr: String, timeStr: String): com.example.data.UnregisteredBankSms?

    @Query("SELECT * FROM unregistered_bank_sms WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): com.example.data.UnregisteredBankSms?

    @Query("SELECT * FROM unregistered_bank_sms WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): com.example.data.UnregisteredBankSms?

    @Query("SELECT * FROM unregistered_bank_sms")
    suspend fun getAllList(): List<com.example.data.UnregisteredBankSms>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sms: com.example.data.UnregisteredBankSms): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(sms: com.example.data.UnregisteredBankSms): Long

    @Query("UPDATE unregistered_bank_sms SET status = 'ASSIGNED', assignedAccountId = :bankAccountId WHERE (accountNumberRaw = :accountNumberRaw OR :accountNumberRaw = '') AND status = 'PENDING'")
    suspend fun bulkAssignByAccountNumber(accountNumberRaw: String, bankAccountId: Long): Int

    @Query("UPDATE unregistered_bank_sms SET status = 'ASSIGNED', assignedAccountId = :bankAccountId WHERE id = :id")
    suspend fun markAssignedById(id: Long, bankAccountId: Long?)

    @Query("UPDATE unregistered_bank_sms SET status = 'IGNORED' WHERE id = :id")
    suspend fun markIgnored(id: Long)

    // سطل آشغال (درخواست باگ ۵): حذف نرم با isDeleted=1 — ردیف برای سینک ابری باقی می‌ماند
    @Query("SELECT * FROM unregistered_bank_sms WHERE isDeleted = 1 ORDER BY updatedAt DESC")
    fun getDeletedSmsFlow(): kotlinx.coroutines.flow.Flow<List<com.example.data.UnregisteredBankSms>>

    @Query("SELECT * FROM unregistered_bank_sms WHERE isDeleted = 1")
    suspend fun getDeletedList(): List<com.example.data.UnregisteredBankSms>

    @Query("DELETE FROM unregistered_bank_sms WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM unregistered_bank_sms WHERE syncId = :syncId")
    suspend fun deleteBySyncId(syncId: String)

    @Query("DELETE FROM unregistered_bank_sms")
    suspend fun clearAll()
}

@Database(
    entities = [
        Patient::class,
        Treatment::class,
        Payment::class,
        Appointment::class,
        Vendor::class,
        LabOrder::class,
        EquipmentMaintenance::class,
        ClinicExpense::class,
        ExpensePayment::class,
        PartyAccount::class,
        PersonalTransaction::class,
        TreatmentTemplate::class,
        BankAccount::class,
        PersonalSubAccount::class,
        ChildrenExpense::class,
        ClinicSettings::class,
        LabService::class,
        FixedIncome::class,
        Installment::class,
        AppCalendarEvent::class,
        Prescription::class,
        TreatmentStage::class,
        PatientDocument::class,
        VendorInvoice::class, DrugTemplate::class,
        FinancialLedgerEntry::class,
        AuditEvent::class,
        StaffMember::class,
        PendingSyncOp::class,
        UnregisteredBankSms::class
    ],
    version = 17, // = AppDatabase.DB_VERSION (باید هم‌مقدار بماند)
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao

    abstract fun treatmentDao(): TreatmentDao
    abstract fun paymentDao(): PaymentDao
    abstract fun appointmentDao(): AppointmentDao
    abstract fun vendorDao(): VendorDao
    abstract fun labOrderDao(): LabOrderDao
    abstract fun labServiceDao(): LabServiceDao
    abstract fun equipmentMaintenanceDao(): EquipmentMaintenanceDao
    abstract fun clinicExpenseDao(): ClinicExpenseDao
    abstract fun expensePaymentDao(): ExpensePaymentDao
    abstract fun partyAccountDao(): PartyAccountDao
    abstract fun personalTransactionDao(): PersonalTransactionDao
    abstract fun treatmentTemplateDao(): TreatmentTemplateDao
    abstract fun prescriptionDao(): PrescriptionDao
    abstract fun bankAccountDao(): BankAccountDao
    abstract fun unregisteredBankSmsDao(): UnregisteredBankSmsDao
    abstract fun personalSubAccountDao(): PersonalSubAccountDao
    abstract fun childrenExpenseDao(): ChildrenExpenseDao
    abstract fun clinicSettingsDao(): ClinicSettingsDao
    abstract fun fixedIncomeDao(): FixedIncomeDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun appCalendarEventDao(): AppCalendarEventDao
    abstract fun treatmentStageDao(): TreatmentStageDao
    abstract fun patientDocumentDao(): PatientDocumentDao
    abstract fun vendorInvoiceDao(): VendorInvoiceDao
    abstract fun financialLedgerDao(): FinancialLedgerDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun drugTemplateDao(): DrugTemplateDao
    abstract fun staffMemberDao(): StaffMemberDao
    abstract fun pendingSyncOpDao(): PendingSyncOpDao
    companion object {
        // ============================================================
        //  مهاجرت‌های اسکیمای Room — یکپارچه و به‌ترتیب
        // ------------------------------------------------------------
        //  همهٔ مسیرها اینجا به‌ترتیبِ صعودی و به‌صورت idempotent (ADD COLUMN
        //  داخلِ try/catch) تعریف شده‌اند و در [ALL_MIGRATIONS] یک‌جا به Room
        //  داده می‌شوند. زنجیره کامل است (۱ تا ۱۳ بدون شکاف) تا نصب‌های موجود
        //  بدونِ از دست رفتنِ داده ارتقاء یابند؛ خلاصهٔ کاملِ همین تغییرات به‌صورت
        //  SQL در supabase/schema_room_sqlite.sql هم موجود است.
        //  helperِ کوچکِ افزودنِ ستون: خطای «ستون از قبل هست» را بی‌صدا رد می‌کند.
        // ============================================================
        private fun androidx.sqlite.db.SupportSQLiteDatabase.addColumn(table: String, columnDef: String) {
            try {
                execSQL("ALTER TABLE $table ADD COLUMN $columnDef")
            } catch (e: Exception) {
                android.util.Log.w("AppDatabase", "$table column already present: ${e.message}")
            }
        }

        // v2: ردِ پیامکِ منبعِ تراکنش‌ها (برای جلوگیری از ثبت تکراری از روی SMS).
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("Payment", "sourceSmsNativeId TEXT NOT NULL DEFAULT ''")
                db.addColumn("ExpensePayment", "sourceSmsNativeId TEXT NOT NULL DEFAULT ''")
                db.addColumn("PersonalTransaction", "sourceSmsNativeId TEXT NOT NULL DEFAULT ''")
            }
        }

        // v3: صف پیامک‌های بانکی + فیلدهای سینکِ ابری (syncId/ownerId/updatedAt/…).
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("unregistered_bank_sms", "assignedAccountSyncId TEXT NOT NULL DEFAULT ''")
                db.addColumn("unregistered_bank_sms", "registeredForSummary TEXT NOT NULL DEFAULT ''")
                db.addColumn("unregistered_bank_sms", "ownerId TEXT NOT NULL DEFAULT ''")
                db.addColumn("unregistered_bank_sms", "syncId TEXT NOT NULL DEFAULT ''")
                db.addColumn("unregistered_bank_sms", "updatedAt INTEGER NOT NULL DEFAULT 0")
                db.addColumn("unregistered_bank_sms", "isDeleted INTEGER NOT NULL DEFAULT 0")
                // هر ردیفِ قدیمی یک syncId یکتا می‌گیرد تا در سرور قابلِ upsert باشد.
                try { db.execSQL("UPDATE unregistered_bank_sms SET syncId = lower(hex(randomblob(16))) WHERE syncId = ''") } catch (e: Exception) {}
                try { db.execSQL("UPDATE unregistered_bank_sms SET updatedAt = createdAt WHERE updatedAt = 0") } catch (e: Exception) {}
            }
        }

        // v4: ساختارِ درختیِ سرفصل‌های حساب شخصی — ستونِ والد.
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("personal_sub_accounts", "parentName TEXT NOT NULL DEFAULT ''")
            }
        }

        // v5: تنظیماتِ مطب — پرسنلِ فعال، چیدمانِ داشبورد، لینکِ لوکیشن، آواتار.
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("clinic_settings", "activeDoctorSyncId TEXT NOT NULL DEFAULT ''")
                db.addColumn("clinic_settings", "activeAssistantSyncId TEXT NOT NULL DEFAULT ''")
                db.addColumn("clinic_settings", "dashboardCardOrder TEXT NOT NULL DEFAULT ''")
                db.addColumn("clinic_settings", "settingsCardVisibility TEXT NOT NULL DEFAULT ''")
                db.addColumn("clinic_settings", "clinicMapLink TEXT NOT NULL DEFAULT ''")
                db.addColumn("clinic_settings", "avatarFileId TEXT")
            }
        }

        // v6: مدتِ جلسهٔ نوبت (دقیقه).
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("appointments", "durationMinutes INTEGER NOT NULL DEFAULT 30")
            }
        }

        // v7: بایگانیِ بیمار.
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("patients", "isArchived INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v8: وضعیتِ چکِ پرداختِ هزینه.
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("expense_payments", "chequeStatus TEXT NOT NULL DEFAULT 'PENDING'")
            }
        }

        // v9: پُلِ بی‌اثرِ شکافِ ۸→۹ (پیش‌تر گم بود و باعثِ پاک‌شدنِ خاموشِ داده می‌شد).
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { /* no-op عمدی */ }
        }

        // v10: انتسابِ مراحلِ انجام‌شده به جلسه (برای برگرداندنِ دقیق هنگامِ حذفِ جلسه).
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("treatments", "stageSessions TEXT NOT NULL DEFAULT ''")
            }
        }

        // v11: «صادره از» بیمار (از سه رقمِ اولِ کدِملی).
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("patients", "issuancePlace TEXT NOT NULL DEFAULT ''")
            }
        }

        // v12: ردِ اسنادِ انتقالِ چک + دو تأییدِ یک‌بارمصرفِ بانکی.
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("payments", "transferExpensePaymentId INTEGER")
                db.addColumn("payments", "transferExpenseId INTEGER")
                db.addColumn("payments", "transferTransactionId INTEGER")
                db.addColumn("payments", "transferChildrenExpenseId INTEGER")
                db.addColumn("payments", "bankReceiptConfirmed INTEGER NOT NULL DEFAULT 0")
                db.addColumn("payments", "bankTransferConfirmed INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v13: مخفی‌سازی و ترتیبِ کارت‌های بانکی.
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("bank_accounts", "isHidden INTEGER NOT NULL DEFAULT 0")
                db.addColumn("bank_accounts", "sortOrder INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v14: هابِ «هزینه‌های مطب» (گروهِ اصلیِ هزینه) + سطحِ سومِ درختِ حساب‌های شخصی/اشخاص.
        private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("clinic_expenses", "expenseGroup TEXT NOT NULL DEFAULT ''")
                db.addColumn("personal_sub_accounts", "grandParentName TEXT NOT NULL DEFAULT ''")
            }
        }

        // v15: طرف‌حساب‌ها (طرف‌حساب چندطرف‌حسابی) — جدول party_accounts + پیوند فاکتور/پرداخت/چک.
        // نام ستون‌ها camelCase است (همان قرارداد Room در بقیهٔ مهاجرت‌ها؛ سینک ابری با
        // jsonKeysToCamel نگاشت می‌شود). کلینیک‌اکسپنس در مدل جدید «فاکتورِ طرف‌حساب» است و
        // ExpensePayment می‌تواند «پرداختِ کلیِ طرف‌حساب» باشد (clinicExpenseId = 0 سنتینل).
        private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `party_accounts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `section` TEXT NOT NULL DEFAULT '',
                        `code` TEXT NOT NULL DEFAULT '',
                        `title` TEXT NOT NULL DEFAULT '',
                        `name` TEXT NOT NULL DEFAULT '',
                        `phone` TEXT NOT NULL DEFAULT '',
                        `notes` TEXT NOT NULL DEFAULT '',
                        `invoiceSeq` INTEGER NOT NULL DEFAULT 0,
                        `ownerId` TEXT NOT NULL DEFAULT '',
                        `syncId` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        `isDeleted` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_party_accounts_section` ON `party_accounts` (`section`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_party_accounts_name` ON `party_accounts` (`name`)")
                } catch (e: Exception) {
                    android.util.Log.w("AppDatabase", "party_accounts index already present: ${e.message}")
                }
                db.addColumn("clinic_expenses", "partyAccountId INTEGER")
                db.addColumn("clinic_expenses", "partyAccountSyncId TEXT NOT NULL DEFAULT ''")
                db.addColumn("clinic_expenses", "invoiceNumber TEXT NOT NULL DEFAULT ''")
                db.addColumn("expense_payments", "partyAccountId INTEGER")
                db.addColumn("expense_payments", "partyAccountSyncId TEXT NOT NULL DEFAULT ''")
                db.addColumn("payments", "transferPartyPaymentId INTEGER")
            }
        }

        /**
         * ۱۵ → ۱۶: دو ستون جدید روی personal_sub_accounts برای «حساب اشخاص»:
         *  - openingBalance: مبلغ اولیه (افتتاحیه) شخص
         *  - priority: شمارهٔ اولویت نمایش (کوچک‌تر = بالاتر در انتخابگرها)
         * سینک ابری با EntityMapper/toMap + parsePulledEntity خودکار این ستون‌ها را
         * هم می‌برد (نگاشت بازتابی)، پس تغییر دیگری لازم نیست.
         */
        private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.addColumn("personal_sub_accounts", "openingBalance INTEGER NOT NULL DEFAULT 0")
                db.addColumn("personal_sub_accounts", "priority INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bank_accounts ADD COLUMN openingBalance INTEGER")
                db.execSQL("ALTER TABLE financial_ledger ADD COLUMN sourceSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE payments ADD COLUMN transferExpensePaymentSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE payments SET transferExpensePaymentSyncId = COALESCE((SELECT syncId FROM expense_payments WHERE id = payments.transferExpensePaymentId), '')")
                db.execSQL("ALTER TABLE payments ADD COLUMN transferExpenseSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE payments SET transferExpenseSyncId = COALESCE((SELECT syncId FROM clinic_expenses WHERE id = payments.transferExpenseId), '')")
                db.execSQL("ALTER TABLE payments ADD COLUMN transferTransactionSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE payments SET transferTransactionSyncId = COALESCE((SELECT syncId FROM personal_transactions WHERE id = payments.transferTransactionId), '')")
                db.execSQL("ALTER TABLE payments ADD COLUMN transferChildrenExpenseSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE payments SET transferChildrenExpenseSyncId = COALESCE((SELECT syncId FROM children_expenses WHERE id = payments.transferChildrenExpenseId), '')")
                db.execSQL("ALTER TABLE payments ADD COLUMN transferPartyPaymentSyncId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE payments SET transferPartyPaymentSyncId = COALESCE((SELECT syncId FROM expense_payments WHERE id = payments.transferPartyPaymentId), '')")
                for ((kind, table) in listOf("PATIENT_PAYMENT" to "payments", "EXPENSE_PAYMENT" to "expense_payments", "PERSONAL_TRANSACTION" to "personal_transactions")) {
                    db.execSQL("UPDATE financial_ledger SET sourceSyncId = COALESCE((SELECT syncId FROM $table WHERE id = financial_ledger.sourceId), '') WHERE sourceType = ?", arrayOf(kind))
                }
            }
        }

        /** فهرستِ واحد و مرتبِ همهٔ مهاجرت‌ها — تنها منبعِ داده‌شده به Room. */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
            MIGRATION_15_16, MIGRATION_16_17
        )

        const val SCHEMA_VERSION = 1
        /** نسخهٔ فعلیِ طرحِ Room — باید با مقدارِ @Database(version=...) هم‌مقدار بماند. */
        const val DB_VERSION = 17
        private const val DATABASE_NAME = "dental_pro_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                buildDatabase(context).also { db ->
                    db.openHelper.readableDatabase.query("PRAGMA user_version").use { it.moveToFirst() }
                    INSTANCE = db
                }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            val appContext = context.applicationContext
            val databasePassphrase = com.example.security.DatabaseEncryptionManager.passphrase(appContext)
            // Refuse either a schema migration or plaintext-to-SQLCipher conversion unless a
            // Keystore-protected local recovery point was created first.
            com.example.security.DatabaseEncryptionManager.prepareEncryptedDatabase(appContext, DATABASE_NAME, databasePassphrase)

            // تورِ ایمنی: قبل از اجرای هر مهاجرت، یک پشتیبانِ کاملِ قابل‌بازیابی
            // بگیر. اگر با وجودِ زنجیرهٔ کاملِ مهاجرت‌ها باز هم مسیری از قلم افتاده
            // باشد و Room به fallbackِ مخرب بیفتد، دادهٔ کاربر از این پشتیبان قابل
            // بازگردانی می‌ماند و هرگز خاموش پاک نمی‌شود.
            try {
                com.example.security.DatabaseEncryptionManager.backupBeforeMigrationIfNeeded(
                    appContext,
                    DATABASE_NAME,
                    databasePassphrase,
                    DB_VERSION
                )
            } catch (e: Throwable) {
                android.util.Log.w("AppDatabase", "Pre-migration backup completed with warning: ${e.message}")
            }

            val sqlCipherFactory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(databasePassphrase)
            return Room.databaseBuilder(
                appContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
            .openHelperFactory(sqlCipherFactory)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        }
    }
}
