# ماشین لانچر — Car Launcher for Android head units

یک لانچر کامل برای صفحه‌ی مالتیمدیای خودرو (هده‌یونیت‌های Android مبتنی بر SPRD/Unisoc)؛ طراحی‌شده برای
پنل افقی ۱۲۰۸×۷۲۰ و ۲٫۷ گیگ رم، با سه خواست اصلی که در وسط صفحه ثابت می‌مانند:

1. **پخش‌کننده‌ی موسیقی وسط صفحه** — به‌محض اینکه هر برنامه‌ای شروع به پخش کند، کارت موسیقی (تصویر آلبوم،
   نوار زمان، پخش/بعدی/قبلی، صدا) به مرکز می‌آید.
2. **نقشه (نشان و گوگل‌مپ) وسط صفحه** — وقتی مسیریابی شروع شود یا خودرو در حال حرکت با مقصد باشد، کارت
   نقشه‌ی زنده‌ی لانچر وسط می‌نشیند و لمس «برو» کار هدایت را به نشان / گوگل‌مپ / Waze می‌سپارد.
3. **ساعت** — دیجیتال + صفحه‌ی عقربه‌ای، تاریخ شمسی (محاسبه‌ی داخلی، بدون شبکه)، روز هفته، و حلقه‌ی
   پیشرفت سال.

بقیه‌ی امکانات (که خودم اضافه کردم): کارت خودرو با سرعت‌سنج و دماسنج مایع خنک‌کننده، فشار باد تایرها،
کارت سفر (مسافت/میانگین/بیشینه/درجا)، کارت هوا از Open-Meteo، کاشی‌های تهویه‌ی مطبوع قابل ویرایش،
نوار برنامه‌های پایین (Dock) با ویرایش لمسی، شبکه‌ی همه‌ی برنامه‌ها با جست‌وجو، تم روز/شب/AMOLED خودکار،
حرکت‌های انگشتی قابل تنظیم، قفل ساده‌شده هنگام رانندگی، بنر تماس روی صفحه، و پشتیبانی از یک ویجت بیرونی.

> ⚠ **مهم:** این پروژه در محیطی نوشته شده که **هیچ ابزار ساختی (JDK / Android SDK / Gradle) نداشت**،
> بنابراین **کامپایل نشده است**. به‌جای کامپایل، شش بررسی ایستا نوشته و اجرا شده‌اند (ذیل همین
> فایل؛ `check_project` خودش هفت بخش دارد).
> انتظار داشته باشید که در اولین `./gradlew assembleRelease` چند خطای نوعی یا جا‌افتاده پیدا شود؛
> فایل‌های `tools/check_*.py` همان‌ها را تا حد ممکن از پیش گرفته‌اند. جزئیات: `docs/limitations.md`.

## ساخت و نصب (خلاصه)

```bash
# یک‌بار: ساخت wrapper (اگر gradle-wrapper.jar در ریپو نیست)
bash scripts/bootstrap-gradle.sh          # یا scripts/bootstrap-gradle.ps1 در ویندوز

# ساخت
./gradlew assembleRelease                  # APK در app/build/outputs/apk/release/

# نصب روی دستگاه (از طریق adb روی لپ‌تاپ یا شبکه‌ی Wi‑Fi adb دستگاه)
adb install -r -g app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.arena.carlauncher/.home.HomeActivity
```

نصب به‌عنوان صفحه‌ی خانه: دکمه‌ی Home را بزنید و «همیشه / Car Launcher» را انتخاب کنید؛ یا از
تنظیمات → دسترسی‌ها → ردیف «صفحه‌ی خانه». دستورهای adb برای دادن دسترسی‌ها در `docs/build-install.md`
هستند (دسترسی اعلان، استفاده، بازنویسی تنظیمات، بهینه‌سازی باتری).

## مستندات

| فایل | موضوع |
|---|---|
| `docs/build-install.md` | پیش‌نیازها، ساخت، امضا، نصب، دسترسی‌ها، نصب به‌عنوان سیستمی (root) |
| `docs/features.md` | هر صفحه/کارت/حرکت انگشتی و قانون تعویض کارت وسط |
| `docs/maps-navigation.md` | نشان و گوگل‌مپ، کاشی‌ها، آفلاین، سیاست مصرف سرور |
| `docs/vehicle-bus.md` | قرارداد گذرگاه خودرو: اکشن، نام کلیدهای extra، JSON، کاشی تهویه |
| `docs/networking.md` | HTTP بدون کتابخانه، timeoutها، TLS/cleartext، مصرف داده |
| `docs/limitations.md` | محدودیت‌های صادقانه و کارهای ناکام |

## فهرست فایل‌ها

```
app/src/main/java/com/arena/carlauncher/
  CarApp.kt                 شروع‌کننده‌ی همه‌ی هاب‌ها، کانال‌های اعلان
  data/                     LauncherPrefs (تنظیمات)، مدل‌ها، AppRepository
  theme/                    Palette (تاریک/روشن/AMOLED)، DayNightController
  util/                     Format، Jalali (تقویم شمسی)، ImageCache
  media/                    MediaHub، MediaNotificationListener، SessionBridge
  loc/                      LocationHub (تنها مصرف‌کننده‌ی GPS)
  vehicle/                  VehicleHub، CarPropertyBridge، TripComputer، ClimateControl
  map/                      TileMapView‌-محور: MapEngine، Mercator، TileSources، GeoServices،
                            NavAppRepository، PlacePicker، PlaceSearchActivity
  weather/                  WeatherRepository (Open-Meteo)
  actions/                  ActionRouter — زبان مشترک فرمان‌ها (کارت/رسانه/نقشه/تم/صفحه/…)
  services/                 LauncherService (foreground)، ScreenOffHelper، CallOverlayService
  home/                     HomeActivity، CenterCardCoordinator، کاروسل، Dock، همه‌ی برنامه‌ها،
                            GestureLayout، ForegroundAppTracker/Watcher، WidgetSlot
  ui/                       Views، TimeTicker، کارت‌ها (ui/card)، ویجت‌های رسم (ui/widget)
  receivers/                BootReceiver
  permission/               PermissionHub
tools/check_project.py      بررسی ایستا: منابع، مانیفست، توازن کروشه، گارد نسخه، XML، gradle، setterها
tools/check_members.py      بررسی ایستا: وجود هر عضو روی object/classهای خودی
tools/check_docs.py         بررسی ایستا: ارجاع‌های docs/ به فایل و کلاس واقعی می‌رسند
tools/validate_jalali.py    mirror از Jalali.kt + آزمون ۷۳٬۴۱۴ روزه
tools/preview_icons.py      صفحه‌ی contact-sheet برای نگاه‌کردن به آیکون‌ها
tools/gen_icons.py          تولید ۷۴ آیکون برداری
tools/gen_strings.py        تولید strings (en + fa) و بررسی برابری پارامترها
docs/                       مستندات فارسی
```

## بررسی‌های ایستا (جایگزین کامپایل)

```bash
python3 tools/gen_icons.py && python3 tools/gen_strings.py   # منابع را بازتولید می‌کند
python3 tools/check_project.py     # منابع، مانیفست، کروشه‌ها، گارد نسخه، XML، gradle، setterها
python3 tools/check_members.py     # هر SomeObject.member روی object/class خودی تعریف شده باشد
python3 tools/check_docs.py        # ارجاع‌های همین README و docs/ به فایل/کلاس واقعی برسند
python3 tools/validate_jalali.py   # ۷۳٬۴۱۴ روز تقویم شمسی، رفت‌وبرگشت دقیق
```

هر پنج با **۰ خطا** تمام می‌شوند. این بررسی‌ها عمداً همان دسته اشتباه را می‌گیرند که در پروژه‌ای با رابط
کاملاً کدنوشده رایج است:

* هر `R.string.*` / `R.drawable.*` در کاتلین و `@string/…` در XML باید به فایلی در `res/` برسند؛
* هر `SomeObject.member` باید روی object/class تعریف‌شده در همین پروژه وجود داشته باشد (نه در کلاس‌های
  Android که اینجا قابل دیدن نیستند)؛
* هر فایل کاتلین باید کروشه‌های متوازن داشته باشد (با یک توکنایزر که کامنت، رشته و `"""…"""` را حذف
  می‌کند — این چند باگ واقعی را گرفت)؛
* هر عضو جدیدتر از `minSdk 24` باید در تابعش گارد `Build.VERSION` یا `try/catch` داشته باشد؛
* هیچ `set(v)` نباید پارامتر خودش را نادیده بگیرد (۱۴ سوئیچ تنظیمات با همین باگ، «روشن/خاموش» نمی‌شدند)؛
* فایل‌های `res/**/*.xml` باید پارس شوند، نامشان `[a-z0-9_]` باشد و پیشوند `android:` فقط جایی برسد که
  namespace اعلام شده؛
* هر ارجاع مستندات به فایل/کلاس باید وجود داشته باشد (این سه جمله‌ی غلط را از docs گرفت).

## کنترل از بیرون (Tasker / MacroDroid / ماکروی OEM)

هر اکشنِ جدول `docs/features.md` از بیرون هم قابل صدا زدن است؛ `HomeActivity` در `onResume`/`onNewIntent`
کلید `arena.car.ACTION` را به `ActionRouter` می‌دهد:

```bash
adb shell am start -n com.arena.carlauncher/.home.HomeActivity --es arena.car.ACTION "map:home"
adb shell am start -n com.arena.carlauncher/.home.HomeActivity --es arena.car.ACTION "open:com.spotify.music"
adb shell am broadcast -a com.arena.carlauncher.action.REFRESH -p com.arena.carlauncher
adb shell am broadcast -a arena.car.VEHICLE --ef speed 47.5 --ef coolant 88   # داده‌ی خودرو
```

برای راه‌اندازی سرویس پس‌زمینه (پخش، تماس، گذرگاه خودرو) نیازی به StartService خودتان نیست:
`LauncherService` هنگام بوت بالا می‌آید (`BootReceiver`) و با `START_STICKY` زنده می‌ماند؛ اگر لازم شد:
`adb shell am start-foreground-service -n com.arena.carlauncher/.services.LauncherService`.

## طراحی، در یک نگاه

* **بدون Compose، بدون Glide، بدون OkHttp، بدون SDK نقشه.** شش وابستگی (پنج AndroidX به‌علاوه coroutines)؛ برای دستگاهی که
  باید همیشه مقیم RAM بماند و ۲٫۷ گیگ رمش با سه برنامه‌ی ناوبری پر می‌شود.
* **HTTP بدون کتابخانه:** `HttpURLConnection` با timeout (کاشی ۴s/۶s، JSON ۵s/۸s)، بدون TLS pinning و
  بدون `network_security_config` — چون `targetSdk` عمداً ۲۹ است، محدودیت پیش‌فرض cleartext اعمال
  نمی‌شود و سرور کاشی محلی `http://192.168.x.x/…` بدون تنظیم اضافه کار می‌کند. جزئیات:
  `docs/networking.md`.
* **تقویم شمسی محاسبه‌شده است، نه جدول‌lookup:** `android.icu.util.PersianCalendar` در انبار عمومی
  اندروید وجود ندارد (hidden/deprecated است) و `Calendar` با locale هم روی فریمورهای چینی معمولاً
  همان میلادی برمی‌گرداند. `util/Jalali.kt` الگوریتم jalaali (جدول ۲۸۲۰ ساله) + تبدیل‌های Hinnant را
  پیاده کرده و `tools/validate_jalali.py` همان محاسبه را در پایتون بازسازی و روی **۷۳٬۴۱۴ روز**
  (۱۹۰۰–۲۱۰۰ میلادی) می‌آزماید: ۰ خطا، رفت‌وبرگشت دقیق، هم‌خوانی با Nowruzهای منتشرشده. انحراف شناخته‌شده
  و مستند: جدول برای بعضی سال‌های ۱۲۵۰–۱۳۳۸ شمسی یک روز جابه‌جا است.
* **نقشه‌ی بومی با کاشی‌های بدون کلید:** مسیریابی واقعی به نشان/گوگل‌مپ واگذار می‌شود؛ لانچر فقط موقعیت،
  جهت، مسیر برنامه‌ریزی‌شده و «برو» را نشان می‌دهد. کاشی‌ها کش می‌شوند و User-Agent واقعی می‌فرستند
  (آدرس تماس در `MapEngine.USER_AGENT` را قبل از انتشار عوض کنید).
* **تک‌مصرف‌کننده‌ی GPS:** همه‌چیز (سرعت‌سنج، سفر، نقطه‌ی آبی، قفل رانندگی، تعویض کارت) از `LocationHub`
  تغذیه می‌شود؛ دو `LocationListener` روی این سخت‌افزار یعنی باتری و CPU. با خاموش شدن صفحه، اشتراک GPS
  آزاد می‌شود (`LocationHub.pauseForIdle`) و با روشن شدن دوباره گرفته می‌شود.
* **RTL در اولویت:** چیدمان با `supportsRtl` و گرانیگاه‌های منطقی (`START`/`END`) است؛ `values-fa/`
  رشته‌ها و ارقام فارسی با `pref_fa_digits`، و متن‌های کوتاه برای لمس با دستکش.
* **هیچ layout XML‌ای نیست:** همه‌ی نماها در کد ساخته می‌شوند (`ui/Views.kt`). این انتخاب است نه تنبلی:
  در ۱۲۰۸×۷۲۰ با چگالی‌های نامعلومِ سازنده، `LayoutInflater` + `dp`های دست‌نویس سریع‌تر از آن خراب می‌کند
  که متوجه شوید؛ یک `Views.card(...)` که خودش metric می‌گیرد، قابل‌اطمینان‌تر است.

## وضعیت راستی‌آزمایی

پروژه **کامپایل نشده است** (محیط ساخت نداشت). هفت بررسی ایستا نوشته و اجرا شده و هر هفت صفر خطا هستند؛
`docs/limitations.md` فهرست کارهایی است که فقط با ساختن/اجرا کردن مشخص می‌شود.
