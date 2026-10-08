# ساخت، امضا، نصب، دسترسی‌ها

## ۰. کوتاه‌ترین راه: APK آماده از GitHub

اگر فقط APK می‌خواهید و لازم نیست خودتان بسازید: در ریپو تب **Actions** ← انتخاب آخرین run سبز ← پایین
صفحه ← artifact به نام `carlauncher-apk`. داخلش دو فایل است: `carlauncher-release.apk` (۰٫۹ مگابایت،
با R8 و کوچک‌سازی منابع — همان که باید نصب کرد) و `carlauncher-debug.apk` (۳٫۶ مگابایت، برای logcat).
اگر روزی اندازه‌ی نسخه‌ی release از ~۱٫۵ مگابایت رد شد، یعنی چیزی به اشتباه داخل APK رفته (معمولاً یک
کتابخانه‌ی تازه). artifact سی روز می‌ماند؛ برای همیشه، tag با پیشوند `v` بزنید — همان workflow یک GitHub
Release با هر دو APK منتشر می‌کند: `…/releases/latest`. با ابزار هم:

```bash
gh run list --workflow build-apk.yml --limit 1        # run id
gh run download <run-id> -n carlauncher-apk
```

**نکته‌ی امضا:** هر run کلید `debug.keystore` تازه‌ای می‌سازد، پس APK دو run مختلف امضای متفاوت دارند و
نصب روی نسخه‌ی قبلی (`adb install -r`) با `INSTALL_FAILED_UPDATE_INCOMPATIBLE` رد می‌شود. برای به‌روزرسانی بدون حذف،
کلید ثابت را در Secret با نام `CAR_LAUNCHER_KEYSTORE` (به‌علاوه‌ی سه‌تایر alias/پسورد) بگذارید؛ workflow آن را
به `app/build.gradle` وصل می‌کند. برای build دستی، بخش ۲ را ببینید.

## ۱. پیش‌نیازها

| چیزی | نسخه | نکته |
|---|---|---|
| JDK | ۱۷ | اگر Android Studio دارید، همان `jbr` داخلی‌اش کافی است |
| Android SDK | Platform 34 + Build‑Tools 34.0.0 | `compileSdk 34`؛ نیازی به SDK 35 نیست |
| Gradle | 8.2 (با wrapper) | اگر `gradle-wrapper.jar` در ریپو نیست، اسکریپت `scripts/` یک‌بار می‌سازدش |
| فایل‌های محلی | `local.properties` با `sdk.dir=…` | یا متغیر محیطی `ANDROID_HOME` |

```bash
# یک‌بار، فقط اگر ./gradlew وجود ندارد
bash scripts/bootstrap-gradle.sh              # لینوکس/مک
powershell -ExecutionPolicy Bypass -File scripts\bootstrap-gradle.ps1   # ویندوز

./gradlew assembleRelease          # APK نهایی
./gradlew installRelease           # ساخت + نصب روی دستگاهی که adb وصل است
./gradlew lintRelease              # اختیاری؛ lint عمداً abortOnError=false است
```

خروجی: `app/build/outputs/apk/release/app-release.apk`

## ۲. امضا

`app/build.gradle` به‌صورت پیش‌فرض با **keystore دیباگ** امضا می‌کند تا نصب روی هده‌یونیت بدون هیچ
آماده‌سازی کار کند (روی دستگاهی که Play Store ندارد، امضای دیباگ هیچ مانعی ندارد). برای امضای جدی:

```bash
export CAR_LAUNCHER_KEYSTORE=/path/release.keystore
export CAR_LAUNCHER_KEY_ALIAS=car
export CAR_LAUNCHER_KEY_PASSWORD='…'
export CAR_LAUNCHER_STORE_PASSWORD='…'
./gradlew assembleRelease
```

نکته‌ی مهم: اگر بعداً نسخه‌ی با امضای دیگر نصب می‌کنید، اول نسخه‌ی قبلی را حذف کنید؛ در غیر این صورت
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` می‌گیرید و تنظیمات کاربر هم پاک می‌شود. همین دلیلِ توصیه‌ی بالای این
بخش است (artifact هر run کلید تازه دارد)، و همین است که `CAR_LAUNCHER_KEYSTORE` را به یک secret جدی تبدیل
می‌کند — وگرنه هر APK تازه از CI یعنی حذف و نصبِ دوباره و رفتن چیدمان کارت‌ها.

## ۳. نصب و بالا آوردن

```bash
adb devices
adb install -r -g app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.arena.carlauncher/.home.HomeActivity
```

`-g` بیشتر دسترسی‌های زمان‌اجرا (از جمله `ACCESS_FINE_LOCATION`) را می‌دهد؛ دسترسی‌های ویژه را نمی‌دهد.

صفحه‌ی خانه: دکمه‌ی Home را بزنید و **Always / Car Launcher** را انتخاب کنید. اگر این گفت‌وگو باز نشد
(بعضی فریمورها لانچر پیش‌فرض را قفل کرده‌اند):

```bash
adb shell cmd package set-home-activities com.arena.carlauncher/.home.HomeActivity
```

برنامه‌ی لانچر فعلی دستگاه را **غیرفعال** نکنید مگر اینکه مطمئن باشید می‌توانید دوباره فعالش کنید؛
روی بعضی هده‌یونیت‌ها تنها راه برگشت به تنظیمات، همان لانچر کارخانه‌ای است.

## ۴. دسترسی‌ها

سنجه‌ی واقعی لانچر، صفحه‌ی **تنظیمات → دسترسی‌ها** است (`PermissionHub`)؛ این جدول همان‌هاست با دستور
adb معادل، برای کسانی که می‌خواهند نصب را اسکریپت‌محور کنند:

| ردیف | چرا لازم است | دستور adb |
|---|---|---|
| موقعیت (critical) | سرعت‌سنج، کارت سفر، نقطه‌ی نقشه، تم خودکار، قفل رانندگی | `adb shell pm grant com.arena.carlauncher android.permission.ACCESS_FINE_LOCATION` |
| دسترسی به اعلان (critical) | تنها راه دیدن آهنگ در حال پخش روی اکثر هده‌یونیت‌ها (MediaSession‌ها خیلی‌جا خالی است) | `adb shell settings put secure enabled_notification_listeners com.arena.carlauncher/.media.MediaNotificationListener` (روی بعضی فریمورها پس از ری‌بوت لازم است؛ بعضی‌ها `cmd notification_allow_listener` را هم می‌خواهند) |
| صفحه‌ی خانه (critical) | دکمه‌ی Home، قفل صفحه، رفتار لانچر | `adb shell cmd package set-home-activities com.arena.carlauncher/.home.HomeActivity` |
| استفاده از برنامه‌ها | شناسایی برنامه‌ی پیش‌زمینه برای کارت‌های هوشمند | `adb shell appops set com.arena.carlauncher PACKAGE_USAGE_STATS allow` |
| سرویس دسترس‌پذیری | نسخه‌ی قابل‌اعتمادِ «چه برنامه‌ای بالاست»؛ اختیاری و اضافه‌بر روی usage‑stats | `adb shell settings put secure enabled_accessibility_services com.arena.carlauncher/.home.ForegroundAppTracker` |
| نمایش روی سایر برنامه‌ها | بنر تماس و برخی لایه‌ها | `adb shell appops set com.arena.carlauncher SYSTEM_ALERT_WINDOW allow` |
| نوشتن تنظیمات | کاشی روشنایی، «خاموش کردن صفحه» | `adb shell appops set com.arena.carlauncher WRITE_SETTINGS allow` |
| معافیت از بهینه‌سازی باتری | تا لانچر و `LauncherService` کشته نشوند | `adb shell dumpsys deviceidle whitelist +com.arena.carlauncher` |

پس از هر بار دادن دسترسی، لانچر را یک‌بار ببندید و باز کنید:

```bash
adb shell am force-stop com.arena.carlauncher
adb shell am start -n com.arena.carlauncher/.home.HomeActivity
```

دو دسترسی (اعلان و دسترس‌پذیری) **توسط اندروید باز نمی‌شوند** و فقط از صفحه‌ی تنظیمات خود دستگاه قابل
دادن‌اند؛ اگر adb در دسترس نیست، از همان صفحه‌ی دسترسی‌ها واردشان شوید.

## ۵. نصب به‌عنوان برنامه‌ی سیستمی (اختیاری، نیازمند root)

اگر هده‌یونیت شما اجازه‌ی تغییر لانچر پیش‌فرض را نمی‌دهد، یا لانچر کارخانه‌ای روی صفحه‌ی قفل می‌نشیند،
می‌توانید همین APK را در پارتیشن سیستم بگذارید. **پشتیبان بگیرید** و با مسئولیت خودتان:

```bash
adb root && adb remount
adb push app/build/outputs/apk/release/app-release.apk /system/priv-app/CarLauncher/CarLauncher.apk
adb shell chmod 644 /system/priv-app/CarLauncher/CarLauncher.apk
adb reboot
```

روی دستگاه‌های `system-as-root`/dynamic-partition مسیر `/product/priv-app/…` هم ممکن است لازم باشد.
حذف‌کردن از سیستم یعنی بوت‌لوپ اگر لانچر پیش‌فرض به آن اشاره کرده باشد؛ قبلش لانچر کارخانه‌ای را
به‌عنوان خانه انتخاب کنید.

## ۶. عیب‌یابی سریع

| نشانه | علت احتمالی | کار |
|---|---|---|
| صفحه‌ی خانه‌ی دستگاه عوض نمی‌شود | لانچر پیش‌فرض قفل شده | دستور `set-home-activities` بالا، یا نصب به‌عنوان سیستمی |
| کارت موسیقی خالی می‌ماند | دسترسی اعلان نداده‌اید، یا برنامه‌ی موسیقی MediaSession نمی‌سازد | ردیف اعلان در تنظیمات؛ سپس تنظیمات → رسانه → «منبع رسانه» را دستی روی همان پخش‌کننده بگذارید |
| سرعت‌سنج صفر است | GPS روشن نیست یا داخل پارکینگ سیگنال ندارد | منبع سرعت را در تنظیمات → خودرو روی CAN بگذارید (اگر بریج دارید) و جایی برانید |
| نقشه کاشی نشان نمی‌دهد | اینترنت/فیلترینگ، یا سرور کاشی Rate‑Limit داده | تنظیمات → نقشه → منبع کاشی را عوض کنید (CARTO/Esri) یا آفلاین را ببینید |
| داغ کردن/کند شدن | کش‌نبودن کاشی + جی‌پی‌اس با نرخ ۱ هرتز وقتی صفحه روشن است | کاشی‌ها را پیش‌بارگذاری کنید؛ با خاموش شدن صفحه، لانچر خودکار اشتراک GPS را آزاد می‌کند (`LocationHub.pauseForIdle`)؛ اگر GPS نمی‌خواهید، دسترسی موقعیت را جمع کنید (سرعت‌سنج `--` می‌شود) |
| لانچر مدام ری‌استارت می‌شود | فریمور لانچر پیش‌فرض را «اجباری» کرده | `adb shell am restart` … و اگر نشد، نصب سیستمی |
