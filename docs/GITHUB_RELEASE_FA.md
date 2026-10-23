# ساخت و انتشار با GitHub Actions

دو workflow اضافه شده است:

- `Android checks`: با push و Pull Request، آزمون‌ها و Lint اجرا می‌شوند و APK آزمایشی همراه گزارش‌ها در Artifacts قرار می‌گیرد. این خروجی برای نصب نسخهٔ نهایی نیست.
- `Signed Android release`: با تگ `v7.0.1` یا اجرای دستی از شاخهٔ `main`، آزمون‌ها تکرار و APK با کلید خصوصی امضا می‌شود. پس از بررسی امضا، نسخه، بسته، هش و هم‌ترازی ۱۶ کیلوبایتی، APK در GitHub Releases منتشر می‌شود.

## تنظیم یک‌بارهٔ کلید امضا

در مخزن، مسیر **Settings → Secrets and variables → Actions → New repository secret** را باز کنید:

1. `ANDROID_KEYSTORE_BASE64`: متن Base64 فایل `dentalpro-release.jks` از بکاپ خصوصی کلید.
2. `ANDROID_KEYSTORE_PROPERTIES`: محتوای فایل `keystore.properties` داخل همان بکاپ. مسیر `storeFile` در runner به‌صورت خودکار تنظیم می‌شود.

این دو مقدار، خود کلید و رمزهای خصوصی هستند؛ آن‌ها را در کد، Issue، Release یا چت عمومی قرار ندهید. بکاپ خصوصی کلید باید خارج از گیت‌هاب هم نگهداری شود. برای نسخه‌های بعدی از همین کلید استفاده کنید.

با GitHub CLI نیز می‌توان Secrets را بدون نمایش محتوا ثبت کرد. پس از استخراج بکاپ خصوصی در پوشه‌ای خارج از مخزن:

```bash
gh auth login
python3 scripts/configure-github-signing.py /private/path/dentalpro-release.jks /private/path/keystore.properties
```

اگر متغیر محیطی `GH_TOKEN` نامعتبر تنظیم شده، ابتدا آن را از نشست خود حذف کنید تا CLI از ورود معتبر استفاده کند.

## انتشار

پس از قرارگرفتن تغییرات در `main` و ثبت Secrets، از **Actions → Signed Android release → Run workflow** روی شاخهٔ `main` اجرا کنید. نسخه از `gradle.properties` خوانده می‌شود و تگ متناظر هنگام انتشار ساخته می‌شود. راه دیگر، push تگ متناظر با نسخه است. اجرای دوباره روی Release موجود آن را بازنویسی نمی‌کند.

برای نسخه‌های بعد، `VERSION_CODE` را افزایش دهید، `VERSION_NAME` را تنظیم کنید و گزارش همان نسخه را در `docs/RELEASE_<VERSION>_FA.md` قرار دهید.

برای اعلام نسخه در آپدیتر داخلی، Secret سوم یعنی `SUPABASE_SERVICE_ROLE_KEY` را در همین مخزن ثبت و گزینهٔ `publish_supabase` را هنگام اجرای دستی فعال کنید. کلید service-role فقط در مرحلهٔ انتشار استفاده می‌شود و وارد APK نمی‌شود. انتشار در سوپابیس بعد از آپلود موفق APK و همراه اندازه و SHA-256 انجام می‌شود. این workflow مهاجرت جداول یا حذف داده‌های مطب را اجرا نمی‌کند.

نصب نخست ۷.۰.۱ به‌علت کلید جدید، نصب تازه است. پیش از حذف نسخهٔ قبلی، دادهٔ ارسال‌نشده و رادیوگرافی قدیمیِ مفقود در باکت را از گوشی حفظ کنید. نصب و ورود واقعی گوگل روی گوشی همچنان باید بررسی شود.
