# شبکه

## اصول

هیچ کتابخانه‌ی شبکه‌ای در پروژه نیست: نه OkHttp، نه Retrofit، نه Ktor. همه‌چیز `HttpURLConnection` است
(در `map/GeoServices.kt` → `object HttpJson` و `map/MapEngine.kt`). دلیلش حجم و RAM نیست — OkHttp فقط
~۳۵۰ کیلوبایت dex اضافه می‌کند — بلکه **تعداد نخ‌ها** است: روی این دستگاه‌ها هر thread ~۱ مگ استک
برای خودِ JVM می‌خواهد و OkHttp هم dispatcher خودش را دارد هم cache‌اش را؛ یک لانچر که باید همیشه مقیم
بماند نباید دو سیستم صف‌بندی request داشته باشد.

## انتزاع‌ها

| کلاس | مصرف‌کننده | timeout | کش |
|---|---|---|---|
| `map/HttpJson` | جست‌وجوی مکان (Nominatim)، معکوس‌یابی، مسیر (OSRM)، هوا (Open‑Meteo) | اتصال ۵ s / خواندن ۸ s | پاسخ هوا در SharedPreferences؛ بقیه نه |
| `map/MapEngine` | کاشی‌ها | اتصال ۴ s / خواندن ۶ s | ۹۶ کاشی در RAM + ۴۸ مگابایت در `cacheDir/maptiles` |
| `util/ImageCache` | کاور آهنگ، لوگوی برنامه‌ها | — (بدون شبکه؛ فقط دیسک/RAM) | LruCache با بودجه‌ی مبتنی بر heap |

هر سه **بی‌صدا شکست می‌خورند**: `runCatching` دور هر request، و نتیجه‌ی خطا فقط `Log.d` است. صفحه‌ی
نقشه‌ی خالی یا دمای `--` نباید لانچر را ببندد.

## نخ‌ها

* کاشی‌ها: `Executors.newScheduledThreadPool(3)` با نام `map-tile-N`، daemon، `NORM` priority — عمداً ۳
  تا، چون مودم این دستگاه‌ها بعد از ۴–۵ request هم‌زمان شروع به drop می‌کند.
* هوا: یک `Thread` تازه در هر بار refresh، با گارد `inFlight` (حداکثر یک request زنده) و فاصله‌ی حداقلی
  ۳۰ ثانیه بین تلاش‌ها. رفرش بعدی ۲۰ دقیقه بعد، یا ۵ دقیقه بعد اگر آخرین تلاش خطا بود.
* جست‌وجوی مکان: در coroutine‌های خودِ `PlaceSearchActivity` با `delay(550)` debounce.
* هیچ‌وقت روی نخ اصلی request نمی‌زنیم. `HttpJson.get` هیچ thread-check ندارد و `StrictMode` هم روشن
  نیست، پس اگر جایی این قانون را بشکنید هیچ ابزاری به شما نمی‌گوید — خودتان نگاه کنید.

## TLS و ترافیک رمزنگاری‌نشده

پروژه **هیچ پیکربندی امنیتی شبکه‌ای ندارد** (نه `network_security_config` در `res/xml`، نه
`usesCleartextTraffic` در مانیفست). این تصادفی نیست:

* `targetSdk` عمداً ۲۹ است، و ممنوعیت پیش‌فرض cleartext از API ۲۸ + `targetSdk >= 28` اعمال می‌شود. پس
  یک سرور کاشی/آینه‌ی محلی داخل LAN (`http://192.168.1.20/{z}/{x}/{y}.png`) بدون هیچ تنظیم اضافه‌ای
  کار می‌کند — روی هده‌یونیتی که اغلب ساعتش غلط است و فروشگاه برنامه ندارد تا ریشه‌ها را تمدید کند،
  این عمداً یک قابلیت است.
* **اگر `targetSdk` را بالا بردید،** یا باید `android:usesCleartextTraffic="true"` اضافه کنید یا یک
  فایل پیکربندی امنیت شبکه در `res/xml` با `cleartextTrafficPermitted="true""` برای LAN بنویسید،
  وگرنه نقشه از کار می‌افتد.
* روی این فریمورها هیچ pinning و هیچ `TrustManager` سفارشی‌ای نصب نمی‌شود؛ یعنی هر ریشه‌ای که کاربر یا
  سازنده‌ی دستگاه به انبار افزوده، معتبر است و MITM داخل LAN ممکن. برای کاشی و هوا عمدی است (نه داده‌ی
  حساس، نه حساب‌کاربری)؛ **فرمان‌های تهویه/خودرو هرگز از شبکه نمی‌روند** — فقط برودکست محلی.

## مصرف داده

ترتیب مصرف در یک ران: کاشی (~۵۰ کیلوبایت × ۹ × تعداد بارِ جابه‌جایی نقشه) ≫ جست‌وجوی مکان ≫ هوا.
کارت‌های موسیقی/خودرو/ساعت هیچ ترافیک شبکه‌ای تولید نمی‌کنند. اگر ناوگان دارید، برای کاشی یک mirror
محلی بگذارید؛ جزئیات سیاست مصرف هر سرور در `docs/maps-navigation.md`.

## عیب‌یابی

```bash
# وضعیت DNS/پراکسی‌ای که خود سیستم به JVM می‌دهد:
adb shell settings get global http_proxy
adb shell settings get global proxy
# آیا اصلاً اینترنت هست؟
adb shell ping -c 2 8.8.8.8
adb shell ping -c 2 tile.openstreetmap.org
logcat -s HttpJson MapEngine PlaceSearch Weather TileSources
```

اگر فقط نقشه کار نمی‌کند و بقیه بله، تقریباً همیشه rate-limit سمت سرور است (User-Agent جای‌خالی
`you@example.com` را در `MapEngine` عوض کنید) یا DNS دستگاه.
