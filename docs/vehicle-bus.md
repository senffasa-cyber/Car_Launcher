# گذرگاه خودرو (Vehicle Bus) — قرارداد داده‌ها

هدف: کارت خودرو، سفر، دما و تهویه‌ی مطبوع روی دستگاهی کار کنند که هیچ API رسمی‌ای ندارد. تنها چیزی که
لانچر از دستگاه می‌خواهد **برودکست** است، پس هر چیزی که بتواند برودکست بفرستد منبع داده می‌شود:
اپ OEM، یک سرویس root، ELM327 bridge، Tasker/MacroDroid، یا یک ESP32 که روی CAN گوش می‌دهد و داده را به
Wi‑Fi می‌ریزد.

## تنظیمات لازم (تنظیمات → خودرو و تهویه)

| کلید | توضیح | مقدار پیش‌فرض |
|---|---|---|
| `vehicleBroadcastAction` (تنظیمات → خودرو → «Broadcast action`) | اکشنی که لانچر هم برایش **می‌نشیند** (دریافت داده) و هم برای فرمان‌های تهویه **می‌فرستد**. خالی = غیرفعال | خالی |
| `vehicleSpeedExtra` («Speed extra key») | اگر نام extra سرعت روی دستگاه شما چیز دیگری است، همین‌جا بنویسید (`speed` همیشه پذیرفته می‌شود) | خالی |
| `speedSource` (تنظیمات → خودرو → «منبع سرعت») | `0` GPS (پیش‌فرض) · `1` برودکست (با fallback به GPS) · `2` VHAL/car API (با fallback به برودکست بعد GPS) | GPS |
| `vehicleJsonExtra` («JSON extra key») | نام extra ای که اگر برودکست ورودی داشت، محتوایش JSON کل داده است | `car_data` |

یک اکشن برای هر دو جهت کافی است؛ `VehicleHub` همان `vehicleBroadcastAction` را با `registerReceiver`
گوش می‌دهد و همان را با `sendBroadcast` برای تهویه می‌فرستد.

**هیچ OBD/ELM327 در این پروژه پیاده نشده.** «car API» یعنی `android.car.Car` (VHAL) از طریق reflection،
نه سوکت OBD-II؛ اگر فقط اداپتر ELM327 دارید، باید یک برنامه‌ی واسط بسازید که داده را به همین
برودکست ترجمه کند (حدود ۳۰ خط کار با `BluetoothSocket` + `sendBroadcast`).

`adb` برای آزمایش سریع:

```bash
# یک برودکست ورودی بفرستید تا کارت‌ها تکون بخورند:
adb shell am broadcast -a arena.car.VEHICLE --ef speed 47.5 --ef coolant 88 --ef fuel 61
adb shell am broadcast -a arena.car.VEHICLE --es car_data '{"speed":50,"rpm":1800,"tires":[2.4,2.4,2.3,2.3]}'
```

(دقت کنید `car_data` همان `vehicleJsonExtra` است — در مثال بالا عمداً نام واقعی کلید نوشته شده.)

**عیب‌یابی**: تنظیمات → خودرو → ردیف «Test the bus now» خلاصه‌ی `VehicleHub.debugDump()` را نشان می‌دهد
(آخرین مقدار خوانده‌شده از هر منبع) و لمسش `VehicleHub.refresh()` را صدا می‌زند. اگر آن‌جا خالی بود،
مشکل از فرستنده است نه لانچر — اکشن را در تنظیمات وارد کرده‌اید؟ سرویس OEM بعد از تغییر اکشن ری‌استارت
شده؟ لاگ: `logcat -s VehicleHub ClimateControl CarPropertyBridge`.

## فرمت ورودی (دستگاه → لانچر)

لانچر با `registerReceiver` روی اکشن‌های ثبت‌شده می‌نشیند و **هر extra عددی** را بر اساس نام کلید
تفسیر می‌کند (`vehicle/VehicleHub.kt` → `ingestExtras`). نام‌ها بی‌توجه به بزرگ/کوچک و بدون جداکننده
مقایسه می‌شوند، پس `door_FL`، `doorFl` و `door_fl` یکی‌اند.

| معنی | کلیدهای پذیرفته‌شده | نوع |
|---|---|---|
| سرعت | `speed`, `vehicle_speed`, `current_speed`, `gps_speed` | float (km/h؛ اگر >۲۰۰ بود لانچر آن را /100 می‌گیرد تا حالت ۰٫۱km/h هم درست شود) |
| دور موتور | `rpm`, `engine_rpm` | float |
| سوخت | `fuel`, `fuel_pct`, `fuel_lvl`, `fuel_level` | ۰..۱۰۰ |
| مسافت قابل پیمایش | `fuel_range`, `range`, `dte` | km |
| دمای مایع خنک‌کننده | `coolant`, `coolant_temp`, `water_temp`, `ect` | °C |
| اودومتر | `odometer`, `odometer_km`, `total_km` | km |
| باتری/ولتاژ | `battery`, `voltage`, `battery_pct` | % |
| دمای بیرون | `ext_temp`, `ambient_temp`, `outdoor_temp`, `temp` | °C |
| درها | `doors`, `door_open` (بیت‌ماسک) یا تک‌تک: `door_fl`, `door_fr`, `door_rl`, `door_rr`, `trunk`/`hatch`, `hood`/`bonnet` | ۰/۱ |
| دنده‌عقب | `reverse`, `gear_reverse`, `backup`, `rvc` | ۰/۱ — کارت دوربین عقب و توقف پخش از همین استفاده می‌کند |
| کولر/فن/دما | `ac`, `ac_on`, `fan`, `blower`, `fan_speed`, `target_temp`, `set_temp`, `temperature` | int/float |
| فشار باد تایر | `tire_fl`, `tpms_fl`, `tire_pressure_fl` (و `fr`, `rl`, `rr`) | bar |
| JSON یک‌جا | هر مقدارِ رشته‌ای داخل `vehicleJsonExtra` (پیش‌فرض `car_data`) به‌عنوان رشته‌ی JSON | `{"speed":50,"tires":[…]}` |

هر مقدار `NaN` یا غایب روی صفحه خط تیره می‌شود؛ لانچر هرگز عدد جعلی نشان نمی‌دهد.

### بریج VHAL (دستگاه‌هایی که `android.car` دارند)

`vehicle/CarPropertyBridge.kt` همان داده را از VHAL می‌خواند، با reflection روی
`android.car.VehiclePropertyIds` تا روی فریمورهایی که این کلاس را ندارند کلاس‌نافت نشود:
`PERF_VEHICLE_SPEED` (m/s یا km/h — خودکار تشخیص داده می‌شود)، `PERCENT_FUEL` یا `FUEL_LEVEL`،
`ENGINE_COOLANT_TEMP`. اگر `Car.createCar()` روی دستگاه شما کار کند،
هیچ برودکستی لازم نیست. دسترسی `android.car.permission.CAR_SPEED/CAR_ENGINE_*` لازم است که فقط برای
برنامه‌ی سیستمی داده می‌شود، پس عملاً یا root/سیستمی باشید یا از برودکست استفاده کنید.

## فرمت خروجی (لانچر → دستگاه)

لمس کاشی تهویه (و اکشن `climate:…`) از `vehicle/ClimateControl.kt` دو راه می‌رود:

1. **نوشتن در VHAL** (اگر `android.car` در دسترس باشد): نگاشت نام → property:
   `ac`/`ac_on` → `HVAC_POWER_ON`، `fan_up`/`fan_down` → `HVAC_FAN_SPEED`، `temp_up`/`temp_down` →
   `HVAC_DRIVER_TARGET_TEMPERATURE`، `recirc` → `HVAC_RECIRC_ON`، `defrost` → `HVAC_DEFROSTER`،
   `seat_heat` → `HVAC_SEAT_TEMPERATURE`.
2. **برودکست** روی `vehicleBroadcastAction` با دو extra: کلیدِ فرمان (مثلاً `temp_up`) و `command` که
   همان مقدار است. مثال:

```java
// چیزی که دستگاه شما باید بگیرد:
Intent i = new Intent("arena.car.VEHICLE");           // vehicleBroadcastAction
i.putExtra("fan_up", "1");                            // key = مقدار، هر دو رشته
i.putExtra("command", "fan_up");
sendBroadcast(i);
```

برای خواندن `temp=24` یا `fan:2` هر دو شکل `key=value` و `key:value` در JSON کاشی پذیرفته می‌شود
(`parseSpec`)؛ اگر فقط نام بیاید مقدار `1` در نظر گرفته می‌شود.

### مثال کاملِ JSON کاشی‌ها (کلیپ «ویرایش JSON» در تنظیمات → کاشی‌ها)

```json
[
  {"label":"AC","action":"arena.car.CLIMATE","extra":"ac","icon":"snow"},
  {"label":"+","action":"arena.car.CLIMATE","extra":"temp_up","icon":"plus"},
  {"label":"۲۴°","action":"arena.car.CLIMATE","extra":"temp=24","icon":"thermo"},
  {"label":"FAN","action":"arena.car.CLIMATE","extra":"fan=2","icon":"fan"},
  {"label":"REC","action":"arena.car.CLIMATE","extra":"recirc","icon":"recirc"},
  {"label":"DASHCAM","action":"open","extra":"com.xiaoyi.dvr","icon":"video"}
]
```

فیلدها: `label`، `icon` (نام آیکون از `res/drawable` بدون پیشوند `ic_`؛ ناشناخته = `info`)، `action`،
`extra`، `pkg` (اگر نبود از `extra` حدست می‌شود)، `value`، `confirm` (پرسیدن قبل از اجرا). اکشن می‌تواند
هر چیز مجازی در جدول اکشن‌ها باشد (`open:`، `card:`، `broadcast:`، …).

## اتصال از بیرون: اکشن لانچر

یک برنامه/ماکرو می‌تواند لانچر را با یک اکشن باز کند؛ `HomeActivity` مقدار extra را به
`ActionRouter` می‌دهد:

```bash
adb shell am start -n com.arena.carlauncher/.home.HomeActivity \
    --es arena.car.ACTION "map:home"

adb shell am start -n com.arena.carlauncher/.home.HomeActivity \
    --es arena.car.ACTION "open:com.spotify.music"
```

`HomeActivity` در `onResume`/`onNewIntent` همان extra (`arena.car.ACTION`) را به `ActionRouter` می‌دهد؛
هیچ activity جداگانه‌ای برای لینک ثبت نشده، چون روی این فریمورها `HOME` تنها نقطه‌ی ورود مطمئن است.

`SettingsActivity`، `AppPickerActivity` و `PlaceSearchActivity` با `exported="false"`ند و فقط از داخل
برنامه (یا از adb با `am start` که `START_ANY_ACTIVITY` دارد) باز می‌شوند؛ و
`LauncherService` برودکست‌های `com.arena.carlauncher.action.RESTART` / `.REFRESH` را می‌پذیرد؛
`BootReceiver` روی `BOOT_COMPLETED` است، و `CallOverlayService` از
`onResume` لانچر بالا نگه داشته می‌شود (کلیدش: تنظیمات → رفتار → «نوار تماس روی صفحه») چون برودکست
`ACTION_PHONE_STATE_CHANGED` فقط به سرویسِ زنده می‌رسد.

## سخت‌افزار/سخت‌افزار منقطع: چه انتظاری نداشته باشید

* این لانچر CAN را **مستقیم** نمی‌خواند (نه سوکت OBD، نه binding به `CarService` با permission‌های
  سیستمی). اگر هیچ برودکستی روی دستگاه نیست، یک پل بسازید: ELM327 → Android app با `sendBroadcast` یا
  ESP32-CAN → UDP → یک سرویس کوچک روی دستگاه.
* روی بعضی فریمورها برودکست‌های protected با پیشوند `android.*`/`com.android.*` رد می‌شوند؛ اکشن
  اختصاصی (`arena.car.VEHICLE`) به همین دلیل انتخاب شده است.
* `REVERSE` و `door_open` اگر از CAN نیایند، لانچر از `PhoneStateListener`/`AudioManager` و
  `Camera` کمکی چیزی در نمی‌آورد — یعنی دنده‌عقب فقط با داده‌ی شما کار می‌کند.
