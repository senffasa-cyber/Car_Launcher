# محدودیت‌ها، کارهای ناکام، و چیزهایی که فقط روی دستگاه معلوم می‌شود

این فایل را جدی بخوانید اگر می‌خواهید پروژه را ادامه دهید. بخشی از آن «ایراد طراحی» است، بخشی «چیزی که
در این محیط ممکن نبود».

## ۱) ساخت: کامپایل در CI سبز است؛ اجرای واقعی روی دستگاه نه

`./gradlew assembleDebug` و `assembleRelease` حالا واقعاً اجرا می‌شوند — در GitHub Actions
(`.github/workflows/build-apk.yml`، Gradle ۸.۲ / JDK ۱۷ / Platform 34) و هر دو سبزاند. این بخش را به‌ همین
خاطر نگه داشته‌ام: برای کسی که پروژه را ادامه می‌دهد، دانستن اینکه **کدام دسته خطا فقط در کامپایل دیده شد**
ارزش دارد. اولین build واقعی **۱۰۴ خطا** داد و چهار دور طول کشید تا صفر شود. خطاها تصادفی نبودند؛ همه از
شش خانواده‌اند:

1. **بسته‌ی اشتباه، نام درست.** `import android.media.session.MediaMetadata` — فقط
   `MediaController`/`MediaSession`/`PlaybackState` در `.session`‌اند؛ `MediaMetadata` در `android.media`
   است. نه‌تنها `check_project`، هیچ ابزار متن‌محوری این را نمی‌گیرد.
2. **تعارض امضای JVM.** `var headingUp` خودش `setHeadingUp(Z)V` تولید می‌کند؛ `fun setHeadingUp(on: Boolean)`
   کنارش خطاست، هرچند هر دو declaration به‌تنهایی درست‌اند. حالا `check_project` این الگو را می‌گیرد
   (بخش `jvm signature clashes`) و رفتار به setter منتقل شد.
3. **سایه‌پذیری داخل `apply`.** داخل `GradientDrawable().apply { … }` نام `colors` به `getColors()` خودِ
   drawable می‌رسد، نه `Palette.colors` — خطای نوعی، ولی با پیامی که جای اشتباه را نشان می‌دهد.
   بخش `apply shadowing` برای همین اضافه شد.
4. **غلط‌های املائیِ نوعِ «فرض‌کردن API».** `ViewPager2.recycledViewPool` وجود ندارد ( RecyclerView داخلی‌اش
   `getChildAt(0)` است)، `LruCache.trimMemory()` نیست (`trimToSize`)، `Notification.getStyle()` و
   `Notification.CATEGORY_GUIDANCE` روی SDK عمومی @hide‌اند، `android.app.NotificationManagerCompat`
   اصلاً وجود ندارد، `Instrumentation.sendKeyCode` هم نیست (`sendKeyDownUpSync`، و نه روی نخ اصلی)، و
   `AppWidgetHost.getAppWidgetInfo` متد `AppWidgetManager` است نه host.
5. **تبدیل‌های عددی.** `Double` به‌جای `Float` در پروژسیون مینکیتور و `Rect.set`، `Views.dp(ctx, 420)`
   (پارامتر `Float` است)، `Long` به `roundToInt()` که روی `Long` تعریف نشده.
6. **ساختار فایل.** یک `private fun behaviour(): List<View>() {` (نوع بازگشتی با پرانتز فراخوانی) باعث شد
   پارسر کل فایل را از دست بدهد و ۸ خطای بی‌ربط در فاصله‌ی ۳۰۰ خطی تولید کند؛ و `const val`هایی که یک
   جابه‌جایی، آن‌ها را از `companion object` به بدنه‌ی کلاس برده بود — که هم `const` را غیرقانونی می‌کند هم
   `SettingsActivity.open(...)` را از دسترس خارج.

هر دسته که پیدا شد، به ابزار ایستا اضافه شد تا تکرار نشود: `check_setters`، `check_layoutparams_receiver`،
`check_member_shapes`، `check_apply_shadowing`، `check_jvm_signature_clashes` — و همه‌شان با یک فایل آزمونِ
عمدی (مثبت و منفی) راستی‌آزمایی شدند، چون بررسی‌ای که هیچ‌چیز را نمی‌گیرد بدتر از نبودنش است.

یک تصمیم طراحی هم از همین دور بیرون آمد: `MediaHub` از پوشش‌های `androidx.media` به `android.media.session`
مستقیم منتقل شد. `MediaSessionManager.getActiveSessions()` همان `List<MediaController>` را برمی‌گرداند و
پلِ reflection برای `MediaSessionCompat.Token` فقط کد مرده بود. با آن رفتن، وابستگی
`androidx.media:media` هم حذف شد و فهرست کتابخانه‌ها به پنج AndroidX بعلاوه‌ی coroutines رسید.

**هنوز آزمون‌نشده:** رفتار در زمان اجرا. کامپایل سبز یعنی type‌ها درست‌اند، نه اینکه کارت وسط موقع پخش
موسیقی جابه‌جا می‌شود، کاشی‌های نشان روی شبکه‌ی خودرو لود می‌شوند، یا `CallOverlayService` روی فریمور چینی
اجازه‌ی overlay می‌گیرد. §۲ و §۳ همان‌هاست. `lintDebug` هم در CI اجرا می‌شود ولی **غیربازدارنده** است
(`checkReleaseBuilds false`)؛ یعنی هشدارهایش را باید در گزارش lint دید، نه در وضعیت run.

## ۲) چیزهایی که فقط روی دستگاه معنی دارند

* **اندازه‌ی واقعی المان‌ها**: چیدمان برای ۱۲۰۸×۷۲۰ تنظیم شده ولی `density` این پنل‌ها بین سازنده‌ها
  از ۱۲۰ تا ۳۲۰ dpi نوسان دارد. اگر همه‌چیز ریز/درشت بود، تنظیمات → چیدمان → «اندازه‌ی آیکون Dock»،
  «بزرگی متن» و «فاصله‌ی حاشیه».
* **نوار وضعیت**: فرض بر این است که نوار وضعیت **پنهان** است و `TopBar` جای آن را می‌گیرد. اگر دستگاه
  نوارش را همیشه باز نگه می‌دارد، `prefs.showStatusBarSpace` را روشن کنید.
* **کلیدهای فیزیکی فرمان**: نگاشت `KEYCODE_MEDIA_*` روی این یونیت‌ها یک‌دست نیست؛ `wheelKeyMusic` فقط
  کلیدهای استاندارد رسانه را می‌گیرد و کلیدهای OEM با `keyevent:<code>` قابل ست‌کردن‌اند (کد را با
  `getevent` پیدا کنید).
* **TYPE_APPLICATION_OVERLAY** روی بعضی فریمورهای چینی بی‌صدا نادیده گرفته می‌شود؛ اگر بنر تماس دیده
  نشد، `SYSTEM_ALERT_WINDOW` را با `appops` بدهید.
* **`FOREGROUND_SERVICE_LOCATION`** (اپ ۲۹) اگر_rom_ ناقص باشد باعث می‌شود `startForeground` خطا بدهد؛
  لاگ را با `logcat -s LauncherService` نگاه کنید.
* **ویجت‌ها**: `AppWidgetHost` فقط **یک** اسلات دارد (عمدًا) چون هر host روی این رم گران است. بسیاری از
  ویجت‌های موبایل روی لانچر خودرو بی‌فایده‌اند.
* **RTL**: چیدمان RTL است اما کاروسل وسط جهت فیزیکی می‌خواند (سوایپ چپ = کارت بعدی). اگر برعکس خواستید، روی خودِ pager در `home/CardPager.kt`
  `layoutDirection = View.LAYOUT_DIRECTION_LTR` بگذارید.

## ۳) محدودیت‌های طراحی‌شده (باگ نیست)

* **مسیریابی واقعی انجام نمی‌شود.** موتور نقشه کاشی می‌خواند و مسیر OSRM را فقط **می‌کشد**؛ هیچ
  «چرخش‌به‌چرخش» و راهنمای صوتی نداریم و نخواهد داشت — آن کار نشان/گوگل‌مپ است و لانچر هم همان‌جا
  دست‌به‌دست می‌کند (`docs/maps-navigation.md`). دلیلش فنی است: یک موتور ناوبری درست یعنی GUID-پیمایش،
  شبکه‌ی جاده، و رمِ بیشتر.
* **بدون Play Services** — یعنی نه Google geofencing، نه Cast، نه Android Auto، نه Voice. عمداً: این
  یونیت‌ها اغلب هیچ‌کدام را ندارند و هر فراخوانی `GoogleApiClient` یعنی timeout و ANR.
* **بدون ringer/تماس‌سازی**: بنر تماس فقط **پاسخ/قطع** می‌دهد (و فقط اگر `ANSWER_PHONE_CALLS` داشته
  باشید؛ در غیر این صورت تماس را به dialer واگذار می‌کند). شماره‌گیری از لانچر با `ACTION_DIAL` است —
  dialer باز می‌شود و کاربر تأیید می‌کند.
* **تاریخ شمسی با جدول نجومی نیست**: از الگوریتم ۲۸۲۰ ساله استفاده می‌کند. برای ۵۱ سالِ بین ۱۲۵۰ و
  ۱۳۳۸ شمسی، یک روز جابه‌جا است (مستند در `tools/validate_jalali.py`)؛ از ۱۳۳۹ به بعد دقیق. اگر someday
  نیاز به تقویم نجومی شد، یک جدول Nowruz بگذارید، نه تغییر الگوریتم.
* **مصرف داده**: نقشه با پیش‌بارگذاری ۳×۳ ≈ ۹ کاشی در هر حرکت؛ در شهر با ترافیک زیاد، ۲۰۰–۴۰۰ مگابایت
  در ماه قابل انتظار است. `docs/networking.md` + سرور کاشی محلی تنها جواب واقعی است.
* **cacheDir پاک می‌شود**: کش کاشی و تصویر در `cacheDir` است؛ بعضی ROMها هر از گاهی (و «پاک‌کننده‌ها»
  همیشه) آن را می‌برند. برای کش ماندگار، مسیر را به `getExternalFilesDir` ببرید (تصمیم عمداً متفاوت:
  در فضای محدودِ داخلی، دیسک را با کش پیر نکنیم).
* **هیچ داده‌ای آپلود نمی‌شود** و هیچ آنالیتیکی نیست؛ اما جست‌وجوی مکان یعنی فرستادنِ `query + lat/lon`
  به `nominatim.openstreetmap.org`. اگر این را نمی‌خواهید، `Nominatim.BASE` را روی سرور خودتان بگذارید
  یا جست‌وجو را از `PlaceSearchActivity` بردارید (کارت ناوبری بدون آن کار می‌کند).

## ۴) کارهای ناتمام / پیشنهاد بعدی

1. `./gradlew assembleDebug` (یا `bash scripts/bootstrap-gradle.sh` اول، اگر wrapper نیست) و رفع
   خطاهای کامپایل — این اولین قدم است، نه آخرین.
2. یک `androidTest` برای `Jalali` و `Mercator` روی دستگاه واقعی (الگوریتم‌ها روی host با پایتون سنجیده
   شده‌اند ولی `Locale`/`Configuration` روی فریمور چینی را باید دید).
3. **حالت خواب**: `ScreenOffHelper` و `CallOverlayService` آماده‌اند؛ «خاموش شدن صفحه با تأخیر» فقط از
   اکشن `screen:off` و `idleDimSec` (کاهش نور) در دسترس است، نه یک UI مستقل.
4. **پروفایل مصرف**: `CarApp.onTrimMemory` کش‌ها را نصف می‌کند (`ImageCache.trim()` و
   `MapEngine.trimMemory()`)، ولی هیچ سنجشی در اپ نیست — لاگ heap یا hprof خودکار. برای دیدن عدد واقعی
   باید از بیرون نگاه کرد: `adb shell dumpsys meminfo com.arena.carlauncher`. اگر یک روز این اپ همان‌جا
   بمیر که دستگاه‌های کم‌رم می‌میرند، آن خط hprof ارزش نوشتن دارد.
5. `MediaHub` از **سه** مسیر تغذیه می‌شود (`MediaSessionManager.getActiveSessions`,
   `MediaNotificationListener`, `MediaController` transport) — اگر پخش‌کننده‌ای در هیچ‌کدام نبود،
   `pref_media_source` را دستی روی همان بسته قفل کنید.
6. **امضا**: `signingConfigs.release` به `debug.keystore` fallback می‌کند تا build شما نشکند؛ برای
   انتشار، keystore واقعی و `storeFile` جدا بگذارید.
