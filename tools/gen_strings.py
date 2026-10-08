#!/usr/bin/env python3
"""Generates `values/strings.xml` (English default) and `values-fa/strings.xml` (Persian).

Why a generator: the launcher has ~330 strings and every one of them must exist in both files with the
same format specifiers. Hand-maintained pairs drift, and a missing `%2$s` in the Persian file is a
crash at runtime, not a typo. Here the two languages live side by side, so a missing translation is
visible as a missing tuple, and the arg counts are checked before anything is written.

    python3 tools/gen_strings.py            # regenerate + verify against the Kotlin sources
"""
import os
import re
import sys
import xml.sax.saxutils as su

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# (key, english, persian)
S = [
    # ---- app / windows ----------------------------------------------------
    ("app_name", "Car Launcher", "ماشین لانچر"),
    ("title_settings", "Launcher settings", "تنظیمات لانچر"),
    ("title_onboarding", "First run", "راه‌اندازی اولیه"),
    ("title_app_picker", "Choose apps", "انتخاب برنامه‌ها"),
    ("title_place_search", "Search places", "جست‌وجوی مکان‌ها"),
    ("acc_label_foreground_tracker", "Car Launcher foreground watcher", "پایش برنامه‌ی پیش‌زمینه"),
    # ---- generic ------------------------------------------------------------
    ("ok", "OK", "تأیید"),
    ("back", "Back", "بازگشت"),
    ("next", "Next", "بعدی"),
    ("finish", "Finish", "پایان"),
    ("skip", "Skip for now", "فعلاً رد کن"),
    ("done", "Done", "انجام شد"),
    ("auto", "Auto", "خودکار"),
    ("seconds", "s", "ثانیه"),
    # ---- cards -------------------------------------------------------------
    ("card_music", "Music", "موسیقی"),
    ("card_map", "Map", "نقشه"),
    ("card_clock", "Clock", "ساعت"),
    ("card_vehicle", "Vehicle", "خودرو"),
    ("card_weather", "Weather", "هوا"),
    ("card_trip", "Trip", "سفر"),
    ("card_tiles", "Quick tiles", "کلیدهای سریع"),
    ("card_nav", "Navigation", "مسیریابی"),
    ("card_phone", "Phone", "تلفن"),
    ("card_camera", "Dashcam", "دوربین ثبت وقایع"),
    ("card_widget", "Widget", "ویجت"),
    ("card_settings", "Settings", "تنظیمات"),
    ("card_settings_sub", "Theme, cards, vehicle bus", "تم، کارت‌ها، گذرگاه خودرو"),
    ("card_unknown", "Card “%1$s” is not available on this build.", "کارت «%1$s» در این نسخه در دسترس نیست."),
    ("card_unknown_short", "Unknown card", "کارت ناشناخته"),
    ("card_fix", "Choose cards", "انتخاب کارت‌ها"),
    ("cards_title", "Cards", "کارت‌ها"),
    ("cards_summary", "Pick what shows in the centre and the side columns", "مشخص کنید وسط و ستون‌های کناری چه نشان داده شود"),
    ("theme_title", "Theme", "تم"),
    ("theme_summary", "Day, night and AMOLED", "روز، شب و AMOLED"),
    ("wallpaper_title", "Wallpaper", "پس‌زمینه"),
    ("wallpaper_summary", "Any image you can open", "هر تصویری که بتوان باز کرد"),
    # ---- clock card ---------------------------------------------------------
    ("clock_sun", "Sunrise %1$s · Sunset %2$s", "طلوع %1$s · غروب %2$s"),
    ("clock_no_weather", "Weather is off — enable it in settings", "هواشناسی خاموش است — از تنظیمات روشن کنید"),
    # ---- music card --------------------------------------------------------
    ("music_empty_title", "Nothing playing", "چیزی پخش نمی‌شود"),
    ("music_empty_hint", "Open any music app and the card fills itself", "هر برنامه‌ی موسیقی را باز کنید؛ این کارت خودکار پر می‌شود"),
    ("music_paused_hint", "Paused — tap play", "متوقف — برای پخش بزنید"),
    ("music_nothing_playing", "No media session", "نشست رسانه‌ای فعال نیست"),
    ("music_from_notification", "From notifications", "از اعلان‌ها"),
    ("media_source_auto", "Automatic", "خودکار"),
    # ---- map / nav ----------------------------------------------------------
    ("nav_go", "Go", "برو"),
    ("nav_go_to", "Go to %1$s", "رفتن به %1$s"),
    ("nav_open", "Open map", "باز کردن نقشه"),
    ("nav_no_app", "No nav app", "برنامه‌ی مسیریابی نیست"),
    ("nav_any", "Any installed map", "هر نقشه‌ی نصب‌شده"),
    ("map_free_pan", "Map is free — tap crosshair to follow", "نقشه آزاد است — برای تعقیب علامت هدف را بزنید"),
    ("map_need_gps", "Waiting for GPS", "در انتظار موقعیت"),
    ("map_offline", "Offline — cached tiles only", "آفلاین — فقط کاشی‌های ذخیره‌شده"),
    ("place_home", "Home", "خانه"),
    ("place_work", "Work", "محل کار"),
    ("place_not_set", "Not set", "تنظیم نشده"),
    ("search_hint", "Search a place…", "جست‌وجوی مکان…"),
    ("search_hint_empty", "Type at least two letters", "دو حرف کافی است"),
    ("search_working", "Searching…", "در حال جست‌وجو…"),
    ("search_none", "No result for that text", "نتیجه‌ای پیدا نشد"),
    ("search_results", "%1$d results", "%1$d نتیجه"),
    ("search_recent", "Recent destinations", "مقصدهای اخیر"),
    ("search_here", "Use current location", "موقعیت فعلی"),
    # ---- vehicle ------------------------------------------------------------
    ("veh_from_gps", "Speed from GPS", "سرعت از GPS"),
    ("veh_from_gps_short", "GPS", "GPS"),
    ("veh_from_bus", "From the car bus", "از گذرگاه خودرو"),
    ("veh_from_vhal", "From the car API", "از API خودرو"),
    ("veh_no_bus", "No vehicle data. Set the broadcast action in Settings → Vehicle.", "داده‌ای از خودرو نمی‌رسد. در تنظیمات → خودرو، نام اعلان را وارد کنید."),
    ("veh_rpm", "rpm", "دور موتور"),
    ("veh_coolant", "Coolant", "دمای آب"),
    ("veh_temp", "Temp", "دما"),
    ("veh_fuel", "Fuel", "سوخت"),
    ("veh_doors_open", "%1$d door(s) open", "%1$d در باز است"),
    ("veh_refreshed", "Vehicle data refreshed", "اطلاعات خودرو تازه شد"),
    ("toast_no_vehicle_bridge", "Vehicle bridge not configured", "پل خودرو پیکربندی نشده"),
    # ---- climate tiles ------------------------------------------------------
    ("tiles_empty", "No tiles yet — add them in Settings → Vehicle", "کاشی‌ای نیست — از تنظیمات → خودرو اضافه کنید"),
    ("tile_theme", "Theme", "تم"),
    ("tile_screen_off", "Screen off", "خاموشی صفحه"),
    ("tile_voice", "Voice", "دستور صوتی"),
    ("tile_apps", "All apps", "همه‌ی برنامه‌ها"),
    # ---- trip --------------------------------------------------------------
    ("trip_avg", "Avg km/h", "میانگین"),
    ("trip_max", "Max", "بیشینه"),
    ("trip_time", "Time", "زمان"),
    ("trip_idle", "Idle", "درجا"),
    ("trip_hint", "Long-press to reset the trip", "برای صفر شدن، انگشت را نگه دارید"),
    ("trip_reset", "Trip reset", "سفر صفر شد"),
    # ---- phone -------------------------------------------------------------
    ("phone_dial", "Dial", "شماره‌گیری"),
    ("phone_voice", "Assistant", "دستیار"),
    ("phone_bt_off", "Bluetooth is off", "بلوتوث خاموش است"),
    ("phone_bt_unavailable", "No Bluetooth adapter", "بلوتوث در دسترس نیست"),
    ("phone_bt_paired_none", "No paired device", "دستگاهی جفت نشده"),
    ("phone_no_dialer", "No dialer app found", "برنامه‌ای برای شماره‌گیری نیست"),
    ("call_incoming", "Incoming call", "تماس ورودی"),
    ("call_answer", "Answer", "پاسخ"),
    ("call_reject", "End", "قطع"),
    # ---- camera ------------------------------------------------------------
    ("camera_none", "No dashcam app found", "برنامه‌ی دوربین پیدا نشد"),
    ("camera_launch", "Open the camera app", "باز کردن دوربین"),
    ("camera_launch_failed", "That camera app cannot be opened", "این دوربین باز نمی‌شود"),
    ("camera_hint", "Recording state comes from the camera app when it broadcasts one.", "وضعیت ضبط را برنامه‌ی دوربین وقتی اعلام می‌کند نشان می‌دهیم."),
    # ---- widget ------------------------------------------------------------
    ("widget_empty", "Tap to add a widget from another app", "برای افزودن ویجت از برنامه‌ای دیگر بزنید"),
    ("widget_added", "Widget added", "ویجت افزوده شد"),
    ("widget_hold_hint", "Long-press the widget to remove it", "برای حذف، انگشت را روی ویجت نگه دارید"),
    # ---- weather -----------------------------------------------------------
    ("weather_detail", "Feels %1$s · Humidity %2$s · Wind %3$s", "احساسی %1$s · رطوبت %2$s · باد %3$s"),
    ("day_today", "Today", "امروز"),
    ("day_tomorrow", "Tomorrow", "فردا"),
    ("wx_clear", "Clear", "صاف"),
    ("wx_partly", "Partly cloudy", "نیمه ابری"),
    ("wx_cloudy", "Cloudy", "ابری"),
    ("wx_fog", "Fog", "مه"),
    ("wx_drizzle", "Drizzle", "نم‌نمک"),
    ("wx_rain", "Rain", "باران"),
    ("wx_showers", "Showers", "رگبار"),
    ("wx_snow", "Snow", "برف"),
    ("wx_storm", "Thunderstorm", "رعد و برق"),
    # ---- home chrome --------------------------------------------------------
    ("allapps_search", "Search apps", "جست‌وجوی برنامه‌ها"),
    ("allapps_recent", "Recent apps", "اخیر"),
    ("title_all_apps", "All apps", "همه‌ی برنامه‌ها"),
    ("dock_all_apps", "All apps", "همه"),
    ("dock_add_hint", "Add an app here", "اینجا برنامه بگذارید"),
    ("dock_pick_hint", "Tap an app to pin it to the dock", "برای افزودن به نوار پایین، یک برنامه را بزنید"),
    ("dock_added", "Pinned to the dock", "به نوار پایین افزوده شد"),
    ("dock_removed", "Removed from the dock", "از نوار پایین حذف شد"),
    ("top_theme", "Theme", "تم"),
    ("top_mute", "Mute media", "بی‌صدای موسیقی"),
    ("top_screen_off", "Screen off", "خاموشی صفحه"),
    ("top_settings", "Settings", "تنظیمات"),
    ("top_bt", "Bluetooth", "بلوتوث"),
    ("lock_title", "Locked while driving", "هنگام رانندگی قفل است"),
    ("lock_body", "The launcher is hiding things you should not touch while moving. This is a distraction guard, not a lock on the car.", "لانچر چیزهایی را که نباید هنگام حرکت لمس شوند پنهان کرده است. این فقط جلوگیری از حواس‌پرتی است، نه قفل خودرو."),
    ("lock_i_am_parked", "I am parked", "پارک کرده‌ام"),
    ("toast_screen_dim_instead", "Cannot switch the panel off — dimming it instead", "صفحه خاموش نمی‌شود؛ فعلاً کم‌نور می‌شود"),
    # ---- services / notifications ----------------------------------------
    ("notif_channel_service", "Launcher service", "سرویس لانچر"),
    ("notif_channel_silent", "Silent", "بی‌صدا"),
    ("notif_service_title", "Car Launcher running", "لانچر خودرو در حال اجرا"),
    ("notif_service_idle", "Keeping music, map and vehicle data alive", "برای ادامه‌ی کار موسیقی، نقشه و داده‌های خودرو"),
    ("notify_setup_title", "Set Car Launcher as your home app", "لانچر را به‌عنوان برنامه‌ی خانه انتخاب کنید"),
    ("notify_setup_text", "Tap this notification and choose Car Launcher every time.", "این اعلان را بزنید و هر بار لانچر را انتخاب کنید."),
    # ---- permissions -------------------------------------------------------
    ("perm_title", "Permissions", "دسترسی‌ها"),
    ("perm_summary", "What the launcher needs, and what is missing", "آنچه لانچر لازم دارد و کم است"),
    ("perm_granted", "Granted", "داده شده"),
    ("perm_needed", "Not granted", "داده نشده"),
    ("perm_location", "Location", "موقعیت مکانی"),
    ("perm_location_sum", "Needed for the map, speed and trip. Only used inside the launcher.", "برای نقشه، سرعت و سفر لازم است؛ فقط داخل لانچر استفاده می‌شود."),
    ("perm_notification", "Notification access", "دسترسی به اعلان‌ها"),
    ("perm_notification_sum", "Required to mirror any music app into the centre card.", "برای نمایش موسیقی هر برنامه در کارت وسط لازم است."),
    ("perm_usage", "Usage access", "دسترسی به استفاده"),
    ("perm_usage_sum", "Lets the launcher know when a music or map app is on screen.", "می‌فهماند کی برنامه‌ی موسیقی یا نقشه روی صفحه است."),
    ("perm_accessibility", "Foreground watcher (accessibility)", "پایش پیش‌زمینه (دسترسی‌پذیری)"),
    ("perm_accessibility_sum", "Optional, but it is what switches the centre card instantly.", "اختیاری است؛ ولی تعویض فوری کارت وسط با آن انجام می‌شود."),
    ("perm_accessibility_detail", "Turn on “Car Launcher foreground watcher” in the list.", "گزینه‌ی «پایش برنامه‌ی پیش‌زمینه» را در فهرست روشن کنید."),
    ("perm_overlay", "Display over other apps", "نمایش روی برنامه‌ها"),
    ("perm_overlay_sum", "Shows the call banner while another app is open.", "بنر تماس را روی برنامه‌ی دیگر نشان می‌دهد."),
    ("perm_write_settings", "Modify system settings", "تغییر تنظیمات سیستم"),
    ("perm_write_settings_sum", "Needed only for the brightness tile.", "فقط برای کلید روشنایی لازم است."),
    ("perm_battery", "Ignore battery optimisation", "نادیده‌گرفتن بهینه‌سازی باتری"),
    ("perm_battery_sum", "Keeps music and map alive when the screen is off.", "موسیقی و نقشه را پس از خاموشی صفحه زنده نگه می‌دارد."),
    ("perm_default_home", "Default home app", "برنامه‌ی خانه‌ی پیش‌فرض"),
    ("perm_default_home_sum", "Without it the home button does not open this launcher.", "بدون آن، دکمه‌ی خانه این لانچر را باز نمی‌کند."),
    # ---- settings: appearance ---------------------------------------------
    ("settings_appearance", "Appearance", "ظاهر"),
    ("appearance_note", "Colours, sizes and the wallpaper. Everything here applies to the home screen immediately.", "رنگ، اندازه و پس‌زمینه. همه بلافاصله روی صفحه‌ی خانه اعمال می‌شود."),
    ("pref_theme", "Theme", "تم"),
    ("pref_theme_sum", "Auto follows the head-unit light sensor and sunrise/sunset.", "خودکار با سنسور نور و طلوع/غروب تنظیم می‌شود."),
    ("theme_auto", "Auto", "خودکار"),
    ("theme_day", "Day", "روز"),
    ("theme_night", "Night", "شب"),
    ("theme_amoled", "AMOLED", "AMOLED"),
    ("pref_accent", "Accent colour", "رنگ تأکیدی"),
    ("pref_accent_sum", "Used for buttons, hands of the clock and highlights.", "برای دکمه‌ها، عقربه‌ها و تأکیدها به کار می‌رود."),
    ("pref_corner", "Card corner radius", "گوشه‌ی کارت‌ها"),
    ("pref_card_alpha", "Card opacity", "کدری کارت‌ها"),
    ("pref_dim", "Wallpaper dim", "کم‌نور کردن پس‌زمینه"),
    ("settings_permissions", "Permissions", "دسترسی‌ها"),
    ("permissions_note", "Only what the launcher can use is asked for. Everything here is optional except location, notification access and the home role.", "فقط آنچه لانچر به کار می‌گیرد خواسته می‌شود. همه اختیاری‌اند جز موقعیت، دسترسی اعلان و برنامه‌ی خانه."),
    ("settings_title", "Settings", "تنظیمات"),
    ("pref_dim_sum", "A dark scrim behind the cards. Forty to sixty reads well even in sunlight.", "لایه‌ی تیره پشت کارت‌ها؛ مقدار چهل تا شصت در آفتاب هم خواناست."),
    ("pref_text_scale", "Text size", "اندازه‌ی متن"),
    ("pref_text_scale_sum", "Multiplies every label, including the clock.", "همه‌ی نوشته‌ها از جمله ساعت را بزرگ می‌کند."),
    ("pref_fa_digits", "Persian digits", "ارقام فارسی"),
    ("pref_fa_digits_sum", "Show ۱۲:۳۰ instead of 12:30.", "به‌جای 12:30 بنویسد ۱۲:۳۰."),
    ("pref_wallpaper", "Wallpaper", "پس‌زمینه"),
    ("wallpaper_none", "No image chosen", "تصویری انتخاب نشده"),
    ("wallpaper_remove", "Remove wallpaper", "حذف پس‌زمینه"),
    ("wallpaper_unavailable", "No image picker on this firmware", "در این سیستم عامل انتخاب تصویر نیست"),
    # ---- settings: layout --------------------------------------------------
    ("settings_layout", "Layout", "چیدمان"),
    ("layout_note", "Which card is where. The centre carousel is the one you swipe.", "کدام کارت کجا باشد. کاروسل وسط همان است که با انگشت جابه‌جا می‌شود."),
    ("pref_preset", "Preset", "الگوی آماده"),
    ("pref_preset_sum", "Overwrites the card lists below.", "فهرست کارت‌های پایین را بازنویسی می‌کند."),
    ("pref_center_cards", "Centre cards", "کارت‌های وسط"),
    ("pref_center_cards_sum", "The first one is shown when nothing is playing.", "اولین کارت، وقتی موسیقی پخش نمی‌شود نشان داده می‌شود."),
    ("pref_left_cards", "Left column", "ستون چپ"),
    ("pref_left_cards_sum", "Mini cards. Only on wide panels.", "کارت‌های کوچک، فقط در صفحه‌های عریض."),
    ("pref_right_cards", "Right column", "ستون راست"),
    ("pref_right_cards_sum", "Mini cards. Only on wide panels.", "کارت‌های کوچک، فقط در صفحه‌های عریض."),
    ("pref_dock_size", "Dock icon size", "اندازه‌ی آیکون نوار"),
    ("pref_dock_size_sum", "Bigger is easier to hit while the car is moving.", "بزرگ‌تر، هنگام حرکت راحت‌تر فشار داده می‌شود."),
    ("pref_grid_columns", "App grid columns", "ستون‌های شبکه‌ی برنامه‌ها"),
    ("pref_dock_edit", "Dock apps", "برنامه‌های نوار پایین"),
    ("pref_dock_edit_sum", "Tap to add or remove; the order is the order on screen.", "برای افزودن یا حذف بزنید؛ همان ترتیب روی صفحه می‌ماند."),
    ("pref_hidden_apps", "Hidden apps (%1$d)", "برنامه‌های پنهان (%1$d)"),
    ("pref_hidden_apps_sum", "Chosen apps disappear from the app grid.", "برنامه‌های انتخابی از فهرست همه‌ی برنامه‌ها حذف می‌شوند."),
    # ---- settings: media --------------------------------------------------
    ("settings_media", "Music", "موسیقی"),
    ("media_note", "The launcher never plays audio itself: it mirrors the app you already use.", "لانچر خودش پخش نمی‌کند؛ برنامه‌ای را که استفاده می‌کنید نشان می‌دهد."),
    ("pref_media_source", "Preferred source", "منبع ترجیحی"),
    ("pref_media_source_sum", "Only needed when two players run at the same time.", "وقتی لازم است که هم‌زمان دو پخش‌کننده باز باشند."),
    ("pref_blur_artwork", "Blur artwork as background", "تصویر آلبوم تار به‌عنوان پس‌زمینه"),
    ("pref_lyrics_line", "Show the album line", "نمایش خط آلبوم"),
    ("pref_lyrics_line_sum", "Album name under the title.", "نام آلبوم زیر عنوان."),
    ("pref_wake_on_play", "Wake the screen when playback starts", "روشن شدن صفحه با شروع پخش"),
    ("pref_wake_on_play_sum", "Useful when you start music from a steering-wheel button.", "هنگامی که پخش را با دکمه‌ی فرمان شروع می‌کنید مفید است."),
    ("pref_pause_reverse", "Pause music in reverse gear", "توقف موسیقی در دنده عقب"),
    ("pref_pause_reverse_sum", "Resumes when you move forward again.", "با حرکت رو به جلو دوباره پخش می‌شود."),
    ("pref_wheel_play", "Treat the wheel play key as play/pause", "کلید پخش فرمان = پخش/توقف"),
    ("pref_wheel_play_sum", "Needed on units without a media button policy.", "برای دستگاه‌هایی که سیاست دکمه‌ی رسانه ندارند لازم است."),
    ("pref_notification_access", "Notification access", "دسترسی به اعلان‌ها"),
    # ---- settings: map ----------------------------------------------------
    ("settings_map", "Map and navigation", "نقشه و مسیریابی"),
    ("map_note", "The launcher draws its own map from free raster tiles; navigation itself is handed to your nav app.", "نقشه را خودِ لانچر از کاشی‌های رایگان می‌کشد؛ اما مسیریابی به برنامه‌ی نقشه سپرده می‌شود."),
    ("pref_tile_source", "Tile source", "منبع کاشی"),
    ("pref_tile_source_sum", "Auto picks a light layer by day and a dark one at night.", "خودکار روز روشن و شب تیره را انتخاب می‌کند."),
    ("tile_auto", "Auto (light by day, dark by night)", "خودکار (روز روشن، شب تیره)"),
    ("tile_custom", "Custom URL", "نشانی دلخواه"),
    ("pref_tile_url", "Custom tile URL", "نشانی کاشی"),
    ("pref_tile_url_sum", "Use {z}/{x}/{y}. Self-host a tile server for a whole fleet.", "از {z}/{x}/{y} استفاده کنید؛ برای ناوگان، سرور کاشی خودتان را بزنید."),
    ("pref_heading_up", "Heading up", "جهتِ بالا"),
    ("pref_heading_up_sum", "Rotates the map to the direction of travel.", "نقشه را با جهت حرکت می‌چرخاند."),
    ("pref_auto_zoom", "Auto zoom by speed", "بزرگ‌نمایی خودکار با سرعت"),
    ("pref_show_route", "Draw the planned route", "ترسیم مسیر"),
    ("pref_show_route_sum", "Asks a public routing server; needs the internet.", "از سرویس مسیریابی عمومی می‌پرسد؛ اینترنت لازم دارد."),
    ("pref_zoom", "Default zoom", "بزرگ‌نمایی پیش‌فرض"),
    ("pref_nav_app", "Navigation app (%1$d found)", "برنامه‌ی مسیریابی (%1$d پیدا شد)"),
    ("pref_nav_app_sum", "Neshan and Google Maps are both supported.", "نشان و نقشه‌ی گوگل هردو پشتیبانی می‌شوند."),
    ("pref_nav_template", "Directions URL for %1$s", "نشانی مسیر برای %1$s"),
    ("pref_nav_template_sum", "Placeholders: {lat}, {lon}, {label}. Leave empty for the default.", "جای‌نماها: {lat}، {lon}، {label}. خالی بگذارید تا پیش‌فرض باشد."),
    ("pref_split_nav", "Open navigation in split screen", "باز کردن مسیریابی در صفحه‌ی دوبخشی"),
    ("pref_split_nav_sum", "Only works if the ROM allows multi-window.", "فقط اگر رابط سیستم اجازه دهد کار می‌کند."),
    ("pref_home_place", "Home", "خانه"),
    ("pref_work_place", "Work", "محل کار"),
    ("pref_clear_tiles", "Delete the tile cache", "پاک کردن حافظه‌ی کاشی"),
    ("pref_clear_tiles_sum", "Currently %1$s on disk.", "اکنون %1$s روی حافظه است."),
    # ---- settings: vehicle ------------------------------------------------
    ("settings_vehicle", "Vehicle and climate", "خودرو و تهویه"),
    ("vehicle_note", "Speed can come from GPS, from a broadcast your head unit sends, or from the car API if the firmware exposes it.", "سرعت می‌تواند از GPS، از اعلان خودِ دستگاه، یا از API خودرو (اگر سیستم آن را باز کند) گرفته شود."),
    ("pref_speed_source", "Speed source", "منبع سرعت"),
    ("pref_speed_source_sum", "A tick means the launcher actually sees that source.", "علامت یعنی لانچر آن منبع را واقعاً می‌بیند."),
    ("src_gps", "GPS", "GPS"),
    ("src_broadcast", "Car broadcast", "اعلان خودرو"),
    ("src_carapi", "Car API (VHAL)", "API خودرو (VHAL)"),
    ("pref_mph", "Show speed in mph", "نمایش سرعت به مایل"),
    ("pref_bus_action", "Broadcast action", "نام اعلان"),
    ("pref_bus_action_sum", "The action your MCU/VehicleBus service sends. Restart the service after changing it.", "همان اکشنی که سرویس خودرو می‌فرستد؛ بعد از تغییر، سرویس را دوباره روشن کنید."),
    ("pref_bus_speed_extra", "Speed extra key", "کلید سرعت"),
    ("pref_bus_speed_extra_sum", "Extra holding km/h as a float or int.", "کلکی که سرعت را به km/h می‌فرستد."),
    ("pref_bus_json_extra", "JSON extra key", "کلید JSON"),
    ("pref_bus_json_extra_sum", "Optional extra with the whole snapshot (speed, rpm, fuel, doors…).", "اختیاری؛ بسته‌ی کامل شامل سرعت، دور موتور، سوخت، درها و…"),
    ("pref_climate_tiles", "Climate tiles (JSON)", "کاشی‌های تهویه (JSON)"),
    ("pref_climate_tiles_sum", "Each tile: label, icon, action, extra. The action is sent as a broadcast.", "هر کاشی: برچست، نماد، اکشن، پارامتر. اکشن به‌صورت اعلان فرستاده می‌شود."),
    ("pref_test_bus", "Test the bus now", "آزمودن همین حالا"),
    ("pref_test_bus_sum", "Last read: %1$s", "آخرین خوانش: %1$s"),
    # ---- settings: clock / weather ---------------------------------------
    ("settings_clock", "Clock and weather", "ساعت و هوا"),
    ("clock_note", "The Jalali date is computed in the launcher, not looked up in a table, so it works with no network and no Play Services.", "تاریخ جلالی در خودِ لانچر محاسبه می‌شود؛ بدون شبکه و بدون سرویس گوگل کار می‌کند."),
    ("pref_clock_24", "24-hour clock", "ساعت ۲۴گانه"),
    ("pref_seconds", "Show seconds", "نمایش ثانیه"),
    ("pref_jalali", "Show Jalali date", "نمایش تاریخ شمسی"),
    ("pref_gregorian", "Show Gregorian date", "نمایش تاریخ میلادی"),
    ("pref_analog", "Analogue clock face", "ساعت عقربه‌ای"),
    ("pref_analog_sum", "Adds the dial next to the digital time.", "صفربه‌ی عقربه‌ای را کنار عدد نشان می‌دهد."),
    ("pref_weather", "Weather (Open-Meteo)", "هواشناسی (Open-Meteo)"),
    ("pref_temp_unit", "Temperature unit", "یکای دما"),
    ("pref_manual_location", "Manual location", "موقعیت دستی"),
    ("pref_manual_location_sum", "“lat,lon” — use it when GPS is unavailable or you want another city.", "«lat,lon» — وقتی GPS نیست یا شهر دیگری را می‌خواهید."),
    ("pref_trip_reset", "Reset trip and odometer", "صفر کردن سفر و کیلومتری"),
    ("pref_trip_reset_sum", "Current trip: %1$s km.", "سفر فعلی: %1$s کیلومتر."),
    # ---- settings: behaviour ---------------------------------------------
    ("settings_behaviour", "Behaviour", "رفتار"),
    ("behaviour_note", "What the launcher does by itself while you drive.", "کاری که لانچر هنگام رانندگی خودش انجام می‌دهد."),
    ("pref_auto_center", "Switch the centre card automatically", "تعویض خودکار کارت وسط"),
    ("pref_auto_center_sum", "Music while playing, map while navigating, clock otherwise.", "موسیقی هنگام پخش، نقشه هنگام مسیریابی، وگرنه ساعت."),
    ("pref_override", "Pause auto-switch for", "توقف تعویض خودکار به مدت"),
    ("pref_override_sum", "After you swipe by hand, the launcher leaves the page alone.", "بعد از اینکه خودتان ورق زدید، صفحه دست‌نخورده می‌ماند."),
    ("pref_keep_screen_on", "Keep the screen on", "روشن نگه‌داشتن صفحه"),
    ("pref_immersive", "Hide the navigation bar", "پنهان کردن نوار ناوبری"),
    ("pref_immersive_sum", "Some head units ignore this; the panel firmware decides.", "بعضی دستگاه‌ها نادیده می‌گیرند؛ تصمیم با نرم‌افزار پنل است."),
    ("pref_status_space", "Leave room for the status bar", "جا برای نوار وضعیت"),
    ("pref_boot", "Start on boot", "شروع پس از روشن شدن دستگاه"),
    ("pref_boot_sum", "Needs the service to survive the ROM’s aggressive cleanup.", "سرویس باید از پاک‌سازی تهاجمی سیستم جان سالم برد."),
    ("pref_idle_dim", "Dim the screen after", "کم‌نور کردن صفحه پس از"),
    ("pref_idle_dim_sum", "0 disables it. The screen wakes on any touch.", "صفر یعنی خاموش. با هر لمس روشن می‌شود."),
    ("pref_drive_lock", "Simplify the screen while driving", "ساده‌سازی صفحه هنگام رانندگی"),
    ("pref_drive_lock_sum", "Hides editable content above the speed below. Not a security feature.", "بالای آن سرعت، محتوای قابل ویرایش پنهان می‌شود. امنیت نیست."),
    ("pref_drive_lock_speed", "Lock above", "قفل از سرعت"),

    ("pref_call_banner", "Call banner", "نوار تماس روی صفحه"),
    ("pref_call_banner_sum", "A strip with answer/hang-up controls while the phone is ringing.", "نوار باریک با دکمه‌های پاسخ/قطع وقتی زنگ می‌خورد."),
    ("pref_gestures", "Gestures (JSON)", "حرکت‌ها (JSON)"),
    ("pref_gestures_sum", "swipe_up / swipe_down / swipe_left / swipe_right / double_tap / long_press, each with an action.", "شش کلید با یک فرمان برای هر کدام."),
    ("pref_default_home", "Set as default home app", "انتخاب به‌عنوان خانه"),
    ("pref_default_home_sum", "The system picker decides; choose “Car Launcher” and “Always”.", "سیستم می‌پرسد؛ «ماشین لانچر» و «همیشه» را بزنید."),
    ("pref_needs_value", "Enter a value first", "اول مقداری وارد کنید"),
    ("pref_json_bad", "That text is not valid JSON — nothing changed", "متن JSON معتبر نیست؛ تغییری نکرد"),
    # ---- settings: debug --------------------------------------------------
    ("settings_debug", "Debug", "عیب‌یابی"),
    ("debug_note", "Text the launcher can actually tell you. Nothing is sent anywhere.", "هر چه لانچر می‌داند. هیچ‌چیز جایی فرستاده نمی‌شود."),
    ("pref_crash_report", "Crash report", "گزارش کرش"),
    ("pref_crash_report_sum", "What killed the last boots, read from this device", "چه چیزی بوت‌های اخیر را روی همین دستگاه کشت"),
    ("crash_none", "No crash recorded. If the launcher still will not open, the problem is not a Java exception: check that the app is allowed to be the default home.", "هیچ کرشی ثبت نشده. اگر باز هم لانچر بالا نیامد، مشکل استثنا نیست: ببینید اجازه دارد پیش‌فرض خانه باشد."),
    ("crash_seen_title", "The last start crashed before the home screen was built", "اجرای قبلی قبل از ساخت صفحه‌ی خانه کرش کرد"),
    ("crash_seen_safe", "Restart in safe mode", "بوت دوباره در حالت امن"),
    ("crash_clear", "Clear the crash log", "پاک‌کردن گزارش کرش"),
    ("crash_cleared", "Crash log cleared", "گزارش کرش پاک شد"),
    ("pref_safe_mode", "Safe mode", "حالت امن"),
    ("pref_safe_mode_sum", "Three cards, no wallpaper, no background service — for when the home screen will not come up", "سه کارت، بدون تصویر پس‌زمینه و سرویس پس‌زمینه؛ برای وقتی صفحه‌ی خانه بالا نمی‌آید"),
    ("launcher_will_not_open", "Car Launcher could not build its home screen", "ماشین لانچر نتوانست صفحه‌ی خانه را بسازد"),
    ("safe_mode_retry", "Tap anywhere to restart in safe mode. The trace below is also in filesDir/crash.log.", "جای صفحه را لمس کنید تا در حالت امن دوباره بیاید. متن زیر در filesDir/crash.log هم هست."),
    ("pref_debug_log", "Verbose logcat", "لاگ پرجمعیت"),
    ("pref_dump", "State dump", "گزارش وضعیت"),
    ("debug_dump_shown", "Copy it from the sheet", "از پنجره کپی کنید"),
    ("pref_export", "Export settings", "خروجی تنظیمات"),
    ("pref_export_sum", "A JSON file you can keep or send to another unit.", "فایل JSON برای نگه‌داشتن یا فرستادن به دستگاه دیگر."),
    ("export_failed", "Export failed: %1$s", "خروجی نگرفت شد: %1$s"),
    ("pref_restart_service", "Restart the launcher service", "خاموش/روشن کردن سرویس"),
    ("debug_service_restarted", "Service restarted", "سرویس دوباره روشن شد"),
    ("onb_dark_hint", "Easier on the eyes at night, and saves power on AMOLED panels.", "شب‌ها راحت‌تر است و روی AMOLED مصرف را کم می‌کند."),
    ("gesture_none", "Nothing", "هیچ"),
    # ---- onboarding -------------------------------------------------------
    ("onboarding_title", "First run", "راه‌اندازی اولیه"),
    ("onb_welcome_title", "Car Launcher", "ماشین لانچر"),
    ("onb_welcome_body", "Three things live in the middle of the screen, and you can change every one of them later in Settings.", "سه چیز در وسط صفحه زندگی می‌کنند و بعداً از تنظیمات قابل تغییرند."),
    ("onb_feat_music", "Music in the centre", "موسیقی در وسط"),
    ("onb_feat_music_sum", "Whatever you play appears as a card with cover art and a seek bar.", "هر چه پخش کنید با تصویر آلبوم و نوار زمان نمایش داده می‌شود."),
    ("onb_feat_map", "Map in the centre", "نقشه در وسط"),
    ("onb_feat_map_sum", "Neshan, Google Maps and Waze are all treated the same way.", "نشان، نقشه‌ی گوگل و Waze یکسان رفتار می‌شوند."),
    ("onb_feat_clock", "A real clock", "ساعت واقعی"),
    ("onb_feat_clock_sum", "Jalali date, weekday and the year’s progress ring.", "تاریخ شمسی، روز هفته و حلقه‌ی پیشرفت سال."),
    ("onb_perm_title", "Two permissions matter", "دو دسترسی مهم است"),
    ("onb_perm_body", "Location and notification access are what make the centre card and the map work. The rest is optional.", "موقعیت و دسترسی اعلان، همان‌هاست که کارت وسط و نقشه را کار می‌اندازند. بقیه اختیاری است."),
    ("onb_media_title", "Music", "موسیقی"),
    ("onb_media_body", "The launcher reads the media session of any player. If the player only posts a notification, notification access is what shows it here.", "لانچر نشست رسانه‌ی هر پخش‌کننده‌ای را می‌خواند. اگر برنامه‌ای فقط اعلان می‌فرستد، همین دسترسی کافی است."),
    ("onb_map_title", "Map and places", "نقشه و مکان‌ها"),
    ("onb_map_body", "Pick the app that will do the actual guidance; Neshan is the usual choice on Iranian units.", "برنامه‌ای که هدایت را انجام می‌دهد انتخاب کنید؛ روی دستگاه‌های ایران معمولاً نشان است."),
    ("onb_nav_none", "No nav app detected. Install Neshan or Google Maps and press Back → Next.", "برنامه‌ی مسیریابی‌ای پیدا نشد. نشان یا نقشه‌ی گوگل را نصب کنید و بعدی را بزنید."),
    ("onb_vehicle_title", "Vehicle data", "داده‌های خودرو"),
    ("onb_vehicle_body", "GPS speed works out of the box. For the car bus (RPM, fuel, doors) your head unit must send a broadcast — see Settings → Vehicle.", "سرعت با GPS کار می‌کند. برای گذرگاه خودرو (دور موتور، سوخت، درها) باید دستگاه اعلان بفرستد — تنظیمات → خودرو."),
    ("onb_vehicle_gps", "Use GPS for now", "فعلاً از GPS"),
    ("onb_vehicle_bus", "Configure the broadcast", "تنظیم اعلان"),
    ("onb_layout_title", "Layout", "چیدمان"),
    ("onb_layout_body", "A preset sets the cards; you can move them later.", "یک الگو کارت‌ها را می‌چیند؛ بعداً می‌توانید جابه‌جا کنید."),
    ("onb_preset_sum", "Centre: %1$s", "وسط: %1$s"),
    ("onb_dock_hint", "You can change the dock later in Settings → Layout.", "نوار پایین را بعداً از تنظیمات → چیدمان عوض کنید."),
    ("onb_gestures_title", "Gestures", "حرکت‌ها"),
    ("onb_gestures_body", "Swipes on the wallpaper are already mapped; these are the defaults.", "کشیدن انگشت روی پس‌زمینه از قبل تنظیم شده؛ این‌ها مقادیر پیش‌فرض‌اند."),
    ("onb_gestures_more", "Edit the mapping", "ویرایش نگاشت‌ها"),
    ("onb_done_title", "Ready", "آماده است"),
    ("onb_done_body", "Press finish. Everything can be changed later in Settings, which you reach from the gear icon.", "پایان را بزنید. همه‌چیز بعداً از تنظیمات (آیکون چرخ‌دنده) قابل تغییر است."),
    ("onb_missing", "Still missing: %1$s", "هنوز کم است: %1$s"),
    ("onb_weather_on", "Weather on", "هواشناسی روشن"),
    ("onb_weather_off", "Weather off", "هواشناسی خاموش"),
]

EN = {}
FA = {}
for key, en, fa in S:
    if key in EN:
        raise SystemExit("duplicate key: " + key)
    if en == "" and fa == "":
        continue
    EN[key] = en
    FA[key] = fa

FMT = re.compile(r"%(\d+\$)?[sdf]")


def arg_count(text):
    return set(m.group(0) for m in FMT.finditer(text))


def used_names():
    """Every R.string.* and @string/* referenced anywhere in the project."""
    names = set()
    for dp, _, files in os.walk(os.path.join(ROOT, "app", "src", "main")):
        for f in files:
            if not f.endswith((".kt", ".xml")):
                continue
            txt = open(os.path.join(dp, f), encoding="utf-8").read()
            for m in re.finditer(r"R\.string\.([A-Za-z0-9_]+)", txt):
                names.add(m.group(1))
            for m in re.finditer(r"@string/([A-Za-z0-9_]+)", txt):
                names.add(m.group(1))
    return names


def emit(path, table, header_note):
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- %s -->" % header_note,
        "<!-- Generated by tools/gen_strings.py; edit the table there. -->",
        "<resources>",
    ]
    for key in sorted(table):
        out.append('    <string name="%s">%s</string>' % (key, su.escape(table[key])))
    out.append("</resources>")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(out) + "\n")


def main():
    errors = []
    for key in EN:
        a, b = arg_count(EN[key]), arg_count(FA[key])
        if a != b:
            errors.append("%s: args %s (en) vs %s (fa)" % (key, sorted(a), sorted(b)))
    used = used_names()
    missing = sorted(used - set(EN))
    unused = sorted(set(EN) - used)
    if missing:
        errors.append("MISSING from the table: " + ", ".join(missing))
    if unused:
        print("note: %d defined but unreferenced (kept on purpose): %s" % (len(unused), ", ".join(unused[:12])))
    if any("%" in v and not arg_count(v) for v in EN.values()):
        errors.append("a string has % without a positional arg")
    if errors:
        print("\n".join(errors))
        sys.exit(1)
    emit(os.path.join(RES, "values", "strings.xml"), EN, "Default (English) strings.")
    emit(os.path.join(RES, "values-fa", "strings.xml"), FA, "Persian strings; the launcher is RTL-first.")
    print("wrote %d strings (en + fa)" % len(EN))


if __name__ == "__main__":
    main()
