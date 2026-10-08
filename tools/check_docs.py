#!/usr/bin/env python3
"""Verifies that every file/symbol named in the documentation actually exists.

Written because the Persian docs were authored from a mental model of the tree rather than from the tree,
which produced real lies: a `util/SimpleMemoryProfile.kt` that was never written, a `CarouselView` when the
class is `CardPager`, a `service/ForegroundAppWatcher.kt` when it lives in `home/`. A reader follows one
doc reference, finds nothing, and stops trusting the rest of the file.

Applied to inline code spans (`...`) in every `*.md` under the repo:
  * `path/File.kt`, `File.kt`, `res/xml/foo.xml`, `app/build.gradle`, `dir/` -> must exist.
  * `File.kt:123` -> the file must exist (line numbers are not verified).
  * `OurType.member` -> if `OurType` is declared in this project, `member` must appear in its file.
    Android/Java types are skipped: this script cannot see inside the SDK.
  * `R.*`, `@string/...`, `snake_case`, URLs, flags and shell snippets are skipped (other tools check
    resources; gen_strings.py checks the string set).

    python3 tools/check_docs.py
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "app", "src", "main")
PKG = os.path.join(SRC, "java", "com", "arena", "carlauncher")

SPAN = re.compile(r"`([^`\n]+)`")

# Types that live in the SDK: we can resolve them, so their members are not ours to verify.
SKIP_OWNERS = {
    "Intent", "Bundle", "View", "ViewGroup", "Context", "Build", "Settings", "Uri", "File", "URI",
    "JSONObject", "JSONArray", "Arrays", "TextUtils", "MotionEvent", "KeyEvent", "WindowManager",
    "TelephonyManager", "AudioManager", "PowerManager", "LocationManager", "AlarmManager", "Notification",
    "NotificationChannel", "Handler", "Looper", "Bitmap", "Canvas", "Paint", "Color", "ColorDrawable",
    "Drawable", "GradientDrawable", "LinearLayout", "FrameLayout", "RelativeLayout", "ScrollView",
    "HorizontalScrollView", "TextView", "ImageView", "EditText", "Button", "SeekBar", "Switch",
    "RecyclerView", "ViewPager2", "AppCompatActivity", "Activity", "Service", "IntentService",
    "BroadcastReceiver", "SharedPreferences", "Editor", "String", "Float", "Int", "Long", "Double",
    "Boolean", "List", "ArrayList", "Map", "HashMap", "Set", "Thread", "System", "Math", "StrictMode",
    "BluetoothAdapter", "BluetoothSocket", "BatteryManager", "AccessibilityEvent", "ComponentName",
    "PackageManager", "ApplicationInfo", "PackageInfo", "Resources", "Configuration", "DisplayMetrics",
    "Point", "Rect", "RectF", "PointF", "Matrix", "Path", "Typeface", "Executors", "Executor",
    "FileProvider", "ContextCompat", "NotificationCompat", "ViewCompat", "WindowInsetsCompat",
    "WindowInsetsControllerCompat", "MediaMetadataCompat", "MediaMetadata", "MediaControllerCompat",
    "MediaController", "MediaSessionCompat", "MediaSession", "MediaDescriptionCompat", "AudioAttributes",
    "AudioFocusRequest", "TelecomManager", "SubscriptionManager", "UserManager", "InputMethodManager",
    "Settings.Global", "Settings.System", "Build.VERSION", "VERSION_CODES", "Locale", "TimeZone",
    "Calendar", "GregorianCalendar", "SimpleDateFormat", "Date", "StringBuilder", "InputStream",
    "OutputStream", "HttpURLConnection", "URL", "BitmapFactory", "BitmapShader", "BlurMaskFilter",
    "RenderEffect", "Vibrator", "VibratorManager", "SensorManager", "WifiManager", "ConnectivityManager",
    "AccessibilityManager", "AccessibilityService", "LayoutInflater", "MotionEvent", "GestureDetector",
    "VelocityTracker", "Scroller", "OverScroller", "LinearInterpolator", "DecelerateInterpolator",
    "ValueAnimator", "ObjectAnimator", "AnimatorSet", "StateListAnimator", "R", "AppWidgetManager",
    "AppWidgetHost", "AppWidgetProviderInfo", "Instrumentation", "Result", "Log", "BaseBundle",
}

# Members that only exist on framework types but are commonly written next to our class names.
SKIP_MEMBERS = {"class", "Companion", "INSTANCE", "notify", "notifyAll", "wait", "toString", "hashCode",
                "equals", "name", "ordinal"}


def kotlin_files():
    for dirpath, dirs, files in os.walk(PKG):
        for f in files:
            if f.endswith(".kt"):
                yield os.path.join(dirpath, f)


def md_files():
    for dirpath, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "node_modules")]
        for f in sorted(files):
            if f.endswith(".md"):
                yield os.path.join(dirpath, f)


def path_exists(token):
    """Accept a documented path relative to the repo root, to res/, or bare inside the kotlin package."""
    base = token.split(":")[0].strip().strip("./")
    if not base:
        return True
    tried = [
        os.path.join(ROOT, base),
        os.path.join(SRC, "res", base),
        os.path.join(SRC, "res", base.rstrip("/")),
        os.path.join(SRC, base),
        os.path.join(PKG, base),
        os.path.join(SRC, "java", base),
        os.path.join(SRC, "main", base),
    ]
    if any(os.path.exists(p) for p in tried):
        return True
    if "/" not in base and base.endswith(".kt"):
        for path in kotlin_files():
            if os.path.basename(path) == base:
                return True
    if "/" not in base and base.endswith(".xml"):
        for dirpath, dirs, files in os.walk(os.path.join(SRC, "res")):
            if base in files:
                return True
    return False


def owner_files(owners, owner):
    return owners.get(owner) or []


def main():
    # owner -> [files declaring it]
    owners = {}
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        for name in re.findall(
            r"^[ \t]*(?:(?:public|internal|private|abstract|open|sealed|data|final|open class|@[\w.]+)\s+)*"
            r"(?:object|class|interface|enum\s+class)\s+([A-Z]\w*)", text, re.M
        ):
            owners.setdefault(name, []).append(path)

    problems = []
    for path in md_files():
        rel = os.path.relpath(path, ROOT)
        for n, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
            for raw in SPAN.findall(line):
                token = raw.strip()
                if not token:
                    continue
                # one token may be several refs: "Foo.kt, Bar.kt" or "TileSources (MapEngine.kt)"
                for part in re.split(r"[,;()\s]+", token):
                    part = part.strip("`\"'[]{}<>=*|!?.")
                    if not part:
                        continue
                    check(rel, n, part, owners, problems)

    for p in problems:
        print(p)
    print("%d issue(s) in the documentation" % len(problems))
    return 1 if problems else 0


def check(rel, n, t, owners, problems):
    def flag(msg):
        problems.append("%s:%d %s" % (rel, n, msg))

    if t.startswith(("@", "R.", "$", "-", "/", "~")) or "://" in t or t.startswith("http"):
        pass
        return
    # Prose with slashes (`try/catch`, `read/write`, `lat/lon`), placeholders, format strings and build
    # outputs are not references.
    if any(c in t for c in "={}*…°<>|") or "…" in t:
        return
    if re.match(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)?/[a-z][a-z0-9_]*$", t):
        return
    # Android component names (`pkg/.Class`) and `context.cacheDir/...` are not file paths.
    if re.match(r"^[a-z][\w.]+/", t):
        return
    if t.startswith("app/build/") or t.endswith(".apk") or t == "local.properties":
        return
    if "/" in t and not t.endswith("/") and re.match(r"^[a-z]", t) and "." not in t:
        return
    if len(t) < 3:
        return
    # dotted reference: Type.member / Type.CONSTANT / File.kt / pkg.Class.member
    if "." in t and re.match(r"^[A-Za-z_]\w*(\.\w+)*$", t):
        parts = t.split(".")
        if t.endswith((".kt", ".xml", ".gradle", ".py", ".properties")):
            if not path_exists(t):
                flag("missing file `%s`" % t)
            return
        if len(parts) != 2:
            return
        head, member = parts
        if head in SKIP_OWNERS:
            return
        files = owner_files(owners, head)
        if files:
            if member in SKIP_MEMBERS or re.match(r"^(get|set|is)[A-Z]", member):
                return
            joined = "".join(open(f, encoding="utf-8").read() for f in files)
            if not re.search(r"\b%s\b" % re.escape(member), joined):
                flag("unknown member `%s` on %s (declared in %s)"
                     % (member, head, os.path.relpath(files[0], ROOT)))
            return
        # Owner.file.kt style
        if t.endswith(".kt") or t.endswith(".xml") or t.endswith(".gradle") or "/" in t:
            if not path_exists(t):
                flag("missing file `%s`" % t)
            return
        return
    # path-ish reference
    if t.endswith("/") or t.endswith((".kt", ".xml", ".gradle", ".properties", ".py", ".sh", ".md",
                                      ".ps1", ".png", ".json", ".bin")) or "/" in t:
        if "/" not in t and not t.endswith((".kt", ".xml", ".gradle", ".properties")):
            return
        if t.endswith("/") and not (re.match(r"^(app|docs|tools|res|gradle|scripts|values|com)([/-]|$)", t)
                                    or re.search(r"[A-Z]", t)):
            return
        if t.startswith("android.") or t.startswith("java.") or t.startswith("kotlin"):
            return
        if not path_exists(t):
            flag("missing file/dir `%s`" % t)
        return
    # CamelCase name that is neither an SDK type nor declared here
    if re.match(r"^[A-Z][A-Za-z0-9]*(View|Card|Hub|Engine|Repository|Activity|Tracker|Watcher|Coordinator|Bridge|Computer|Prefs|Pager|Pad|Bars|Store|Factory|Picker|Sources)$", t):
        if t not in owners and t not in SKIP_OWNERS:
            flag("`%s` is not declared anywhere in the sources" % t)


if __name__ == "__main__":
    sys.exit(main())
