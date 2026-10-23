-- Historical LOCAL SQLite migration notes, moved out of the Supabase SQL directory.
-- Current truth: app/schemas/com.example.data.AppDatabase/17.json and AppDatabase.ALL_MIGRATIONS.
-- Do not execute this SQLite reference on PostgreSQL.

-- ============================================================================
--  DentalPro — SQLite / Room schema migrations (consolidated)
-- ----------------------------------------------------------------------------
--  این فایل، خلاصهٔ یکپارچهٔ همهٔ تغییراتِ اسکیمای دیتابیسِ محلی (Room/SQLCipher)
--  است؛ دقیقاً معادلِ زنجیرهٔ مهاجرت‌های تعریف‌شده در
--  app/src/main/java/com/example/data/AppDatabase.kt (ALL_MIGRATIONS).
--
--  نکتهٔ مهم دربارهٔ «منبعِ حقیقت»:
--    طرحِ کاملِ جدول‌ها (CREATE TABLE) را Room از روی @Entityها می‌سازد؛ این فایل
--    جدول‌ها را از صفر نمی‌سازد بلکه تغییراتِ افزایشیِ نسخه‌به‌نسخه را مستند می‌کند
--    تا مرجعِ خواناِ انسانی برای ساختارِ دیتابیس باشد. برای بازتولیدِ کاملِ طرح روی
--    یک بکندِ Postgres، فایلِ supabase/dentalpro_all_in_one.sql را ببینید.
--
--  قاعدهٔ نسخه‌گذاری:
--    هر تغییرِ اسکیما => بالا بردنِ AppDatabase.DB_VERSION و @Database(version=…)
--    یک واحد + افزودنِ یک بلوکِ v(n-1) -> v(n) در این فایل و یک Migration متناظر.
--
--  نسخهٔ فعلیِ طرح: 13
-- ============================================================================

-- ─────────────────────────────────────────────────────────────
-- v1 -> v2 : ردِ پیامکِ منبعِ تراکنش‌ها (جلوگیری از ثبتِ تکراری از روی SMS)
-- ─────────────────────────────────────────────────────────────
ALTER TABLE Payment              ADD COLUMN sourceSmsNativeId TEXT NOT NULL DEFAULT '';
ALTER TABLE ExpensePayment       ADD COLUMN sourceSmsNativeId TEXT NOT NULL DEFAULT '';
ALTER TABLE PersonalTransaction  ADD COLUMN sourceSmsNativeId TEXT NOT NULL DEFAULT '';

-- ─────────────────────────────────────────────────────────────
-- v2 -> v3 : صفِ پیامک‌های بانکی + فیلدهای سینکِ ابری
-- ─────────────────────────────────────────────────────────────
ALTER TABLE unregistered_bank_sms ADD COLUMN assignedAccountSyncId TEXT NOT NULL DEFAULT '';
ALTER TABLE unregistered_bank_sms ADD COLUMN registeredForSummary  TEXT NOT NULL DEFAULT '';
ALTER TABLE unregistered_bank_sms ADD COLUMN ownerId               TEXT NOT NULL DEFAULT '';
ALTER TABLE unregistered_bank_sms ADD COLUMN syncId                TEXT NOT NULL DEFAULT '';
ALTER TABLE unregistered_bank_sms ADD COLUMN updatedAt             INTEGER NOT NULL DEFAULT 0;
ALTER TABLE unregistered_bank_sms ADD COLUMN isDeleted             INTEGER NOT NULL DEFAULT 0;
-- هر ردیفِ قدیمی یک syncId یکتا می‌گیرد تا در سرور قابلِ upsert باشد.
UPDATE unregistered_bank_sms SET syncId = lower(hex(randomblob(16))) WHERE syncId = '';
UPDATE unregistered_bank_sms SET updatedAt = createdAt WHERE updatedAt = 0;

-- ─────────────────────────────────────────────────────────────
-- v3 -> v4 : ساختارِ درختیِ سرفصل‌های حساب شخصی
-- ─────────────────────────────────────────────────────────────
ALTER TABLE personal_sub_accounts ADD COLUMN parentName TEXT NOT NULL DEFAULT '';

-- ─────────────────────────────────────────────────────────────
-- v4 -> v5 : تنظیماتِ مطب (پرسنلِ فعال، چیدمانِ داشبورد، لینکِ لوکیشن، آواتار)
-- ─────────────────────────────────────────────────────────────
ALTER TABLE clinic_settings ADD COLUMN activeDoctorSyncId     TEXT NOT NULL DEFAULT '';
ALTER TABLE clinic_settings ADD COLUMN activeAssistantSyncId  TEXT NOT NULL DEFAULT '';
ALTER TABLE clinic_settings ADD COLUMN dashboardCardOrder     TEXT NOT NULL DEFAULT '';
ALTER TABLE clinic_settings ADD COLUMN settingsCardVisibility TEXT NOT NULL DEFAULT '';
ALTER TABLE clinic_settings ADD COLUMN clinicMapLink          TEXT NOT NULL DEFAULT '';
ALTER TABLE clinic_settings ADD COLUMN avatarFileId           TEXT;

-- ─────────────────────────────────────────────────────────────
-- v5 -> v6 : مدتِ جلسهٔ نوبت (دقیقه)
-- ─────────────────────────────────────────────────────────────
ALTER TABLE appointments ADD COLUMN durationMinutes INTEGER NOT NULL DEFAULT 30;

-- ─────────────────────────────────────────────────────────────
-- v6 -> v7 : بایگانیِ بیمار
-- ─────────────────────────────────────────────────────────────
ALTER TABLE patients ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0;

-- ─────────────────────────────────────────────────────────────
-- v7 -> v8 : وضعیتِ چکِ پرداختِ هزینه
-- ─────────────────────────────────────────────────────────────
ALTER TABLE expense_payments ADD COLUMN chequeStatus TEXT NOT NULL DEFAULT 'PENDING';

-- ─────────────────────────────────────────────────────────────
-- v8 -> v9 : (no-op) فقط برای پیوستنِ زنجیره — پیش‌تر این مسیر گم بود و باعثِ
--            سقوط به fallbackِ مخرب و پاک‌شدنِ خاموشِ داده می‌شد.
-- ─────────────────────────────────────────────────────────────
-- (بدونِ تغییرِ اسکیما)

-- ─────────────────────────────────────────────────────────────
-- v9 -> v10 : انتسابِ مراحلِ انجام‌شده به جلسه
-- ─────────────────────────────────────────────────────────────
ALTER TABLE treatments ADD COLUMN stageSessions TEXT NOT NULL DEFAULT '';

-- ─────────────────────────────────────────────────────────────
-- v10 -> v11 : «صادره از» بیمار
-- ─────────────────────────────────────────────────────────────
ALTER TABLE patients ADD COLUMN issuancePlace TEXT NOT NULL DEFAULT '';

-- ─────────────────────────────────────────────────────────────
-- v11 -> v12 : ردِ اسنادِ انتقالِ چک + دو تأییدِ یک‌بارمصرفِ بانکی
--   bankReceiptConfirmed  = تیکِ اول «تأیید دریافت بانکی»
--   bankTransferConfirmed = تیکِ دوم «تأیید انتقال بانکی» (پیش‌نیاز: تیکِ اول)
-- ─────────────────────────────────────────────────────────────
ALTER TABLE payments ADD COLUMN transferExpensePaymentId  INTEGER;
ALTER TABLE payments ADD COLUMN transferExpenseId         INTEGER;
ALTER TABLE payments ADD COLUMN transferTransactionId     INTEGER;
ALTER TABLE payments ADD COLUMN transferChildrenExpenseId INTEGER;
ALTER TABLE payments ADD COLUMN bankReceiptConfirmed      INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN bankTransferConfirmed     INTEGER NOT NULL DEFAULT 0;

-- ─────────────────────────────────────────────────────────────
-- v12 -> v13 : مخفی‌سازی و ترتیبِ کارت‌های بانکی
-- ─────────────────────────────────────────────────────────────
ALTER TABLE bank_accounts ADD COLUMN isHidden  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE bank_accounts ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0;

-- ─────────────────────────────────────────────────────────────
-- v13 -> v14 : هابِ «هزینه‌های مطب» + سطحِ سومِ درختِ حساب‌های شخصی/اشخاص
--   clinic_expenses.expenseGroup     = گروهِ اصلی: LAB/CONSUMABLES/EQUIPMENT/RUNNING/OTHER
--   personal_sub_accounts.grandParentName = سرشاخهٔ ریشه (سطحِ سومِ درخت)
-- ─────────────────────────────────────────────────────────────
ALTER TABLE clinic_expenses       ADD COLUMN expenseGroup    TEXT NOT NULL DEFAULT '';
ALTER TABLE personal_sub_accounts ADD COLUMN grandParentName TEXT NOT NULL DEFAULT '';

-- ============================================================================
--  یادداشتِ نسخهٔ جاری (منطقِ کاربردیِ چک — بدونِ تغییرِ اسکیما):
--   • تیکِ «تأیید دریافت بانکی» تا قبل از انتقالِ چک باید زده شود؛ اگر نباشد،
--     کارتِ چک در مدیریت چک‌ها با هالهٔ زردِ ۱۰٪ متمایز می‌شود.
--   • تیکِ «تأیید انتقال بانکی» فقط پس از تیکِ اول و پس از انتقال ظاهر می‌شود؛
--     با زدنش، تا وصول/برگشتِ چک هیچ ویرایش/حذفی ممکن نیست.
--   • هر چکِ «در جریان» که ۲ روز از سررسیدش گذشته و برگشت نخورده، خودکار
--     «پاس‌شده» می‌شود (autoClearOverdueCheques در DentalViewModel).
--  این‌ها همگی روی ستون‌های موجودِ نسخهٔ ۱۲/۱۳ کار می‌کنند و ستونِ جدیدی لازم ندارند.
-- ============================================================================
