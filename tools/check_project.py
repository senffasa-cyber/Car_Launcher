#!/usr/bin/env python3
"""Static self-check for the launcher project.

There is no Android toolchain in the environment this project was authored in, so this script stands in
for the parts of `./gradlew assembleDebug` that catch problems early:

  1. **resources** — every `R.<type>.<name>` in Kotlin and every `@<type>/<name>` in XML resolves to a
     resource that exists on disk. This is by far the most common build failure in a code-only UI
     project (no layouts means no compiler help with typos).
  2. **manifest** — every component named there has a source file (aliases excepted).
  3. **kotlin syntax** — balanced brackets outside strings/comments, no merge markers.
  4. **api guarding** — framework members newer than `minSdkVersion` appear inside a function that has a
     `Build.VERSION` check or a try/catch. On an old head-unit ROM this class of bug is an instant
     crash, and the lint check for it is not enabled by default.
  5. **res xml** — every XML file parses, and `android:` attributes are only used where the namespace is
     declared.

    python3 tools/check_project.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SRC = os.path.join(ROOT, "app", "src", "main")
JAVA = os.path.join(SRC, "java")
RES = os.path.join(SRC, "res")
MANIFEST = os.path.join(SRC, "AndroidManifest.xml")

TYPE_DIRS = {
    # directory-based resources: the file name is the resource name
    "drawable": ("drawable", "mipmap"),
    "mipmap": ("mipmap", "drawable"),
    "layout": ("layout",),
    "anim": ("anim", "animator"),
    "animator": ("animator", "anim"),
    "xml": ("xml",),
    "raw": ("raw",),
    "font": ("font",),
    "menu": ("menu",),
    # value-based resources: the child tag is the type (see collect_resources)
    "string": ("string",),
    "color": ("color",),
    "dimen": ("dimen",),
    "style": ("style",),
    "integer": ("integer",),
    "bool": ("bool",),
    "array": ("array", "string-array", "integer-array"),
    "plurals": ("plurals",),
    "attr": ("attr",),
    "id": ("id",),
}


def kotlin_files():
    for dp, _, files in os.walk(JAVA):
        for f in sorted(files):
            if f.endswith(".kt"):
                yield os.path.join(dp, f)


def res_files():
    for dp, _, files in os.walk(RES):
        for f in sorted(files):
            if f.endswith(".xml"):
                yield os.path.join(dp, f)


def collect_resources():
    """type -> names, from the file names under res/<qualifier>/ and from <resources> children."""
    found = {}
    for dp, _dirs, files in os.walk(RES):
        base = os.path.basename(dp).split("-")[0]
        if base == "values":
            base = "value"
        for f in files:
            if not f.endswith(".xml"):
                continue
            if base == "value":
                continue
            found.setdefault(base, set()).add(f[:-4])
    for dp, _dirs, files in os.walk(RES):
        if os.path.basename(dp).split("-")[0] not in ("value", "values"):
            continue
        for f in files:
            path = os.path.join(dp, f)
            try:
                root = ET.parse(path).getroot()
            except Exception as exc:
                print("  ! cannot parse %s: %s" % (os.path.relpath(path, ROOT), exc))
                continue
            for child in root:
                n = child.get("name")
                if n is None:
                    continue
                if child.tag == "item":
                    t = child.get("type")
                    if t:
                        found.setdefault(t, set()).add(n)
                else:
                    found.setdefault(child.tag, set()).add(n)
    return found


# ---------------------------------------------------------------- kotlin tokenizer


def strip_code(text):
    """Blank out comments, string literals and char literals, keeping line breaks.

    Written as a scanner rather than a stack of regexes because each shortcut creates a false positive
    that costs more time than it saves: an apostrophe inside a comment unbalances a regex-based char
    rule, and a double slash inside a URL string unbalances a regex-based comment rule.
    Kotlin string templates with nested braces are followed so that `"${a ?: b}"` does not leak a
    bracket into the balance count.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * text.count("\n", i, j))
            i = j
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("\n" * text.count("\n", i, j))
            i = j
            continue
        if text[i] == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                if text[i] == "$" and text[i + 1:i + 2] == "{":
                    depth = 1
                    i += 2
                    while i < n and depth:
                        if text[i] == "{":
                            depth += 1
                        elif text[i] == "}":
                            depth -= 1
                        i += 1
                    continue
                i += 1
            continue
        if text[i] == "'":
            j = i + 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            i = min(j + 1, n)
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


OPEN = {"(": ")", "{": "}", "[": "]"}
CLOSE = {v: k for k, v in OPEN.items()}


def balance(text):
    """-> (message, line) for the first bracket problem, else (None, 0)."""
    stack = []
    line = 1
    for ch in strip_code(text):
        if ch == "\n":
            line += 1
        if ch in OPEN:
            stack.append((ch, line))
        elif ch in CLOSE:
            if not stack:
                return ("unmatched '%s'" % ch, line)
            opener, opened = stack.pop()
            if OPEN[opener] != ch:
                return ("'%s' opened on line %d is closed by '%s'" % (opener, opened, ch), line)
    if stack:
        opener, opened = stack[-1]
        return ("unclosed '%s' from line %d" % (opener, opened), line)
    return (None, 0)


# ---------------------------------------------------------------- checks


def check_resources(res):
    problems = []
    ref = re.compile(
        r"\bR\.(string|drawable|mipmap|color|dimen|style|layout|anim|animator|xml|raw|id|array|"
        r"plurals|integer|bool|font|menu|attr)\.([A-Za-z0-9_]+)"
    )
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, ROOT)
        for m in ref.finditer(text):
            typ, name = m.group(1), m.group(2)
            buckets = TYPE_DIRS.get(typ, (typ,))
            if not any(name in res.get(b, set()) for b in buckets):
                problems.append("%s: R.%s.%s is not defined in res/" % (rel, typ, name))
    xmlref = re.compile(
        r'"@(?:\+)?(string|drawable|mipmap|color|dimen|style|layout|anim|animator|xml|raw|id|array|'
        r'plurals|integer|bool|font|menu|attr)/([A-Za-z0-9_.]+)"'
    )
    for path in [MANIFEST] + list(res_files()):
        text = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, ROOT)
        for m in xmlref.finditer(text):
            typ, name = m.group(1), m.group(2)
            if name.startswith("android:"):
                continue
            buckets = TYPE_DIRS.get(typ, (typ,))
            if not any(name in res.get(b, set()) for b in buckets):
                problems.append("%s: @%s/%s is not defined in res/" % (rel, typ, name))
    return problems


def check_manifest():
    problems = []
    text = open(MANIFEST, encoding="utf-8").read()
    try:
        ET.fromstring(text)
    except Exception as exc:
        return ["AndroidManifest.xml does not parse: %s" % exc]
    m = re.search(r'package="([^"]+)"', text)
    ns = m.group(1) if m else None
    if ns is None:
        gradle = open(os.path.join(ROOT, "app", "build.gradle"), encoding="utf-8").read()
        m = re.search(r'namespace\s*["\']([a-z0-9.]+)', gradle)
        ns = m.group(1) if m else None
        if ns is None:
            return ["cannot determine the application id / namespace"]
    pkg_dir = os.path.join(JAVA, ns.replace(".", "/"))
    # An activity-alias names no class of its own.
    aliases = set()
    for m in re.finditer(r'<activity-alias\b[^>]*android:name="\.?([A-Za-z0-9_.]+)"', text, re.S):
        aliases.add(m.group(1))
    for m in re.finditer(r'android:name="(\.[A-Za-z0-9_.]+)"', text):
        cls = m.group(1).lstrip(".")
        if cls in aliases:
            continue
        rel = cls.replace(".", "/")
        if not os.path.exists(os.path.join(pkg_dir, rel + ".kt")) and \
           not os.path.exists(os.path.join(pkg_dir, rel + ".java")):
            problems.append("manifest component %s.%s has no source file" % (ns, cls))
    # exported must be explicit on API 31+ tooling; the components that are reachable from outside
    for m in re.finditer(r"<(activity|service|receiver|provider)\b(.*?)</\1>|<(activity|service|receiver|provider)\b([^>]*)/>", text, re.S):
        pass
    return problems


def check_kotlin_syntax():
    problems = []
    for path in kotlin_files():
        raw = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, ROOT)
        if "<<<<<<<" in raw or ">>>>>>>" in raw:
            problems.append("%s: unresolved merge marker" % rel)
        msg, line = balance(raw)
        if msg:
            problems.append("%s:%d %s" % (rel, line, msg))
    return problems


# Members that genuinely did not exist at minSdk 24. Anything here must be inside a guarded function.
API_LEVELS = {
    "NotificationChannel": 26,
    "AudioFocusRequest": 26,
    "LAYOUT_IN_DISPLAY_CUTOUT_MODE": 27,
    "TYPE_APPLICATION_OVERLAY": 26,
    "setDisplayCutout": 27,
    "isIgnoringBatteryOptimizations": 23,
    "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS": 23,
    "FLAG_IMMUTABLE": 23,
    "BluetoothManager": 18,
    "ACTION_OPEN_DOCUMENT": 19,
    "UsageStatsManager": 21,
    "FLAG_ACTIVITY_LAUNCH_ADJACENT": 24,
    "setShowBadge": 26,
    "getRawInputDevices": 16,
}


def _function_body(lines, idx):
    """Text of the function containing `lines[idx]`.

    Walking up, the enclosing header is the first line where the brace balance goes negative — that is
    the line whose `{` opened the block we are inside. Stopping at the nearest `fun|val|var` instead
    would land on a local `val` and miss a `if (SDK_INT …) return` guard one line above it.
    """
    start = 0
    depth = 0
    for i in range(idx, -1, -1):
        line = lines[i]
        depth += line.count("}") - line.count("{")
        if depth < 0 and re.search(r"\bfun\s+[A-Za-z_]", line):
            start = i
            break
    end = min(len(lines) - 1, idx + 40)
    depth = 0
    seen = False
    for i in range(start, min(len(lines), start + 200)):
        depth += lines[i].count("{") - lines[i].count("}")
        if "{" in lines[i]:
            seen = True
        if seen and depth <= 0:
            end = i
            break
    return "\n".join(lines[start:end + 1])


def check_api_guarding():
    problems = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        lines = text.split("\n")
        rel = os.path.relpath(path, ROOT)
        for needle, level in API_LEVELS.items():
            if level <= 24:
                continue
            for m in re.finditer(re.escape(needle), text):
                upto = text[:m.start()].count("\n")
                line = lines[upto] if upto < len(lines) else ""
                if line.strip().startswith("import ") or line.strip().startswith("*") or line.strip().startswith("//"):
                    continue
                body = _function_body(lines, upto)
                if "SDK_INT" in body or "VERSION_CODES" in body:
                    continue
                window = "\n".join(lines[max(0, upto - 8):upto + 3])
                if re.search(r"\btry\b|runCatching|\bcatch\b", window):
                    continue
                problems.append(
                    "%s:%d uses %s (API %d) with no SDK_INT guard or try/catch in the function"
                    % (rel, upto + 1, needle, level)
                )
    return problems


def check_res_xml():
    problems = []
    for path in res_files():
        rel = os.path.relpath(path, ROOT)
        try:
            ET.parse(path)
        except Exception as exc:
            problems.append("%s: XML does not parse (%s)" % (rel, exc))
            continue
        text = open(path, encoding="utf-8").read()
        if "xmlns:android" not in text and re.search(r"\sandroid:[A-Za-z_]+\s*=", text):
            problems.append("%s: uses android: attributes without declaring the namespace" % rel)
        name = os.path.basename(path)[:-4]
        if not re.match(r"^[a-z][a-z0-9_]*$", name):
            problems.append("%s: resource file names must be lowercase [a-z0-9_]" % rel)
    return problems


def check_setters():
    """Flag `set(v) = put(KEY, true)` — a setter that never reads its own parameter.

    Found the hard way here: thirteen boolean preferences whose setter wrote a literal, so the switch
    looked stuck in the UI and no amount of tapping changed behaviour. The compiler cannot catch it
    (the code is valid Kotlin), and lint has no rule for it.
    """
    problems = []
    single = re.compile(r"set\s*\(\s*(\w+)\s*\)\s*(?::\s*[\w<>?]+\s*)?=\s*(.+)$")
    block_open = re.compile(r"set\s*\(\s*(\w+)\s*\)\s*\{")
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        lines = open(path, encoding="utf-8").read().split("\n")
        for idx, raw in enumerate(lines):
            line = raw.strip()
            m = single.match(line)
            if m and not line.startswith("//"):
                var, expr = m.group(1), m.group(2)
                if re.search(r"\bput\(|\bputString|\bputInt|\bputBoolean|\bputFloat", expr):
                    if var not in expr:
                        problems.append("%s:%d setter ignores its parameter: %s" % (rel, idx + 1, line))
                continue
            m2 = block_open.match(line)
            if m2:
                var = m2.group(1)
                body = []
                depth = line.count("{") - line.count("}")
                j = idx
                while j < len(lines) and (depth > 0 or j == idx):
                    j += 1
                    if j >= len(lines):
                        break
                    depth += lines[j].count("{") - lines[j].count("}")
                    body.append(lines[j])
                    if depth <= 0:
                        break
                text = "\n".join(body)
                writes = re.findall(r"\bput\w*\(([^)]*)\)", text)
                if writes and not any(var in w for w in writes):
                    if not text.strip().startswith("//") and "editor" not in text and "value" not in text:
                        problems.append("%s:%d setter block never uses %s" % (rel, idx + 1, var))
    return problems


def check_layoutparams_receiver():
    """`someLayout.addView(child, LinearLayout.LayoutParams(...).apply { Views.dp(this, 8f) })` is a trap.

    Inside that `apply`, `this` is the LayoutParams, so any call that expects a Context fails to compile
    (seven sites in PlaceSearchActivity did exactly this). The fix is to name the outer receiver
    explicitly, which is what this rule enforces.
    """
    problems = []
    open_lp = re.compile(r"\w*LayoutParams\([^)]*$|\w*LayoutParams\(.*\)\.apply\s*\{")
    same = re.compile(r"\.apply\s*\{[^}]*\bViews\.dp\(this,")
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        lines = open(path, encoding="utf-8").read().split("\n")
        for idx, line in enumerate(lines):
            if same.search(line):
                problems.append("%s:%d `this` inside a LayoutParams .apply is the LayoutParams: %s"
                                % (rel, idx + 1, line.strip()[:100]))
                continue
            if open_lp.search(line.rstrip()) and ".apply" in line:
                j = idx + 1
                while j < min(len(lines), idx + 5):
                    if "Views.dp(this," in lines[j]:
                        problems.append("%s:%d `this` inside a LayoutParams .apply is the LayoutParams"
                                        % (rel, j + 1))
                        break
                    if lines[j].strip().startswith("}"):
                        break
                    j += 1
    return problems


def check_member_shapes():
    """Two mistakes the brace balance cannot see, both from a real 104-error build.

    1. `private fun behaviour(): List<View>() {` — a return type written with call parentheses. The
       parser then reads the declaration as a bodiless function, so the error is reported at the
       *signature* ("Function without a body must be abstract") and everything below it in the file
       cascades into nonsense. Always a typo, never intentional.
    2. `const val` living directly in a class body. It is legal at top level, in a named `object`, and
       in a `companion object` — and moving members between those scopes is exactly what an edit does
       by accident, which then also un-resolves every `Foo.open(...)` style companion call site.
    """
    problems = []
    bad_fun = re.compile(r"\bfun\s+[\w`.]+\s*(?:<[^<>]*>)?\s*\([^()]*\)\s*:\s*[\w.]+(?:\s*<[^<>]*>)?\s*\(\)\s*(?:\{|=)")
    const_line = re.compile(r"^\s*(?:(?:private|internal|public|protected)\s+)*const\s+val\s")
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        raw = open(path, encoding="utf-8").read().split("\n")
        code = strip_code("\n".join(raw)).split("\n")
        stack = []
        for idx, line in enumerate(code):
            stripped = line.strip()
            if stripped.startswith("fun ") or " fun " in stripped:
                if bad_fun.search(stripped):
                    problems.append("%s:%d return type written as a call (`: Type()`): %s"
                                    % (rel, idx + 1, (raw[idx].strip())[:100]))
            depth = line.count("{") - line.count("}")
            if const_line.match(line):
                scope = stack[-1] if stack else ""
                if scope and not re.search(r"\bobject\b", scope):
                    problems.append("%s:%d `const val` outside an object/companion (enclosing: %s)"
                                    % (rel, idx + 1, scope.strip()[:70]))
            for _ in range(max(0, line.count("{"))):
                stack.append(raw[idx] if raw[idx].strip() else (stack[-1] if stack else ""))
            for _ in range(max(0, depth * 0 + line.count("}"))):
                if stack:
                    stack.pop()
    return problems


def check_apply_shadowing():
    """`someDrawable.apply { colors.outline }` reads `GradientDrawable.getColors()`, not `Palette.colors`.

    An apply block puts the receiver first in the name space, so a property that happens to exist on
    both the receiver and the enclosing object resolves to the receiver — which for `colors` (a colour
    *array* on every drawable) is either a type error or, worse, silently the wrong thing. Palette
    hoists those reads out; this rule keeps new code honest about it.
    """
    problems = []
    # The receiver is on the same line as `.apply {` in every site we write; matching the type name
    # anywhere on the line is deliberate — an argument list full of `)` defeated a stricter pattern.
    starts = re.compile(r"\.apply\s*\{\s*$")
    receiver = re.compile(r"(Drawable|Paint|ColorFilter|LruCache|LayoutParams|ViewHolder)\b")
    bad = re.compile(r"(?<![\w.])colors\.")
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        lines = open(path, encoding="utf-8").read().split("\n")
        for idx, line in enumerate(lines):
            if not starts.search(line.rstrip()) or not receiver.search(line):
                continue
            depth = 0
            for j in range(idx, min(len(lines), idx + 14)):
                depth += lines[j].count("{") - lines[j].count("}")
                if j > idx and bad.search(lines[j].strip()) and "Palette.colors" not in lines[j]:
                    problems.append("%s:%d inside that apply, `colors` is the drawable's own colour array: %s"
                                    % (rel, j + 1, lines[j].strip()[:90]))
                if depth <= 0 and j > idx:
                    break
    return problems


def check_jvm_signature_clashes():
    """`var headingUp` and `fun setHeadingUp(on: Boolean)` in the same body are one JVM method twice.

    A public Kotlin property emits `setHeadingUp(Z)V` / `getHeadingUp()Z` itself, so a hand-written
    function of that name and shape is a "Platform declaration clash" — a real compile error that no
    brace or name check predicts, because both declarations are individually fine. This bit
    `TileMapView`, where the helper existed *and* the property was written directly from another place.

    Deliberately conservative, because this check runs in CI and a false positive blocks a build:
    only non-private properties, only single-parameter setters / zero-parameter getters, and only when
    the JVM-visible types agree (explicit type text, or a `true`/`1`/`1f`/`""` literal for inferred ones).
    `private var configured` + `fun setConfigured(ids: List<String>)` is therefore not reported: private
    properties do not occupy the accessor name the way public ones do.
    """
    lit = {"true": "Boolean", "false": "Boolean", "": ""}
    mod = r"(?:(?:private|internal|public|protected|open|override|final|lateinit|const)\s+)*"
    prop_re = re.compile(r"^\s*" + mod + r"(var|val)\s+(\w+)\s*(?::\s*([\w.<>, ?]+))?(?:\s*=\s*(.+?))?\s*(?:\{|$)")
    set_re = re.compile(r"^\s*(?:(?:private|internal|public|protected|open|override|final)\s+)*fun\s+set([A-Z]\w*)\s*\(\s*\w+\s*:\s*([\w.<>, ?]+)\s*\)")
    get_re = re.compile(r"^\s*(?:(?:private|internal|public|protected|open|override|final)\s+)*fun\s+get([A-Z]\w*)\s*\(\s*\)\s*:\s*([\w.<>, ?]+)")

    def norm(t):
        t = (t or "").strip()
        if t in lit and lit[t] != "":
            return lit[t]
        if re.fullmatch(r"-?\d+", t):
            return "Int"
        if re.fullmatch(r"-?\d+f", t, re.I):
            return "Float"
        if re.fullmatch(r"-?\d+(\.\d+)?", t):
            return "Double"
        if re.fullmatch(r'"[^"]*"', t):
            return "String"
        return t

    problems = []
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        raw = open(path, encoding="utf-8").read().split("\n")
        code = strip_code("\n".join(raw)).split("\n")
        depth = 0
        props = {}      # (name, depth) -> type
        for line in code:
            if "private" in line.split(" var ")[0].split(" val ")[0]:
                depth += line.count("{") - line.count("}")
                continue
            m = prop_re.match(line)
            if m and m.group(2) not in ("var", "val"):
                t = norm(m.group(3) or m.group(4))
                if t:
                    props[(m.group(2)[0].lower() + m.group(2)[1:], depth)] = t
            depth += line.count("{") - line.count("}")
        depth = 0
        for idx, line in enumerate(code):
            for rx, kind in ((set_re, "set"), (get_re, "get")):
                m = rx.match(line)
                if not m:
                    continue
                name = m.group(1)[0].lower() + m.group(1)[1:]
                ptype = props.get((name, depth))
                if ptype and norm(m.group(2)) == ptype:
                    problems.append("%s:%d `fun %s%s(%s)` is the same JVM signature as the %s of `%s` — fold it into the accessor"
                                    % (rel, idx + 1, kind, name[0].upper() + name[1:],
                                       "on: " + ptype if kind == "set" else "", kind, name))
            depth += line.count("{") - line.count("}")
    return problems


def check_return_in_expression_body():
    """`fun x(): Int = try { … return 0 … }` does not compile, and it is easy to write after a block body.

    Kotlin only allows `return` inside a `{ … }` function body, so an early bail written in the
    `?: return` style inside an expression body is an error ("Returns are not allowed for functions with
    expression body"). This bit `CrashLog.readBoot` — written correctly the first time, broken by the
    one-line refactor into `= try { … }`.
    """
    problems = []
    opener = re.compile(r"^\s*(?:(?:private|internal|public|protected|open|override|final|inline|suspend)\s+)*fun\s+[\w`.]+[^=\n]*\)=?\s*(?:\w+\s*:\s*)?\S.*=\s*(?:try|run|with|when|if)\b")
    any_expr = re.compile(r"^\s*(?:(?:private|internal|public|protected|open|override|final|inline|suspend)\s+)*fun\s+[\w`.]+[^{\n]*\)\s*(?::\s*[\w.<>, ?\[\]]+)?\s*=\s*\S")
    for path in kotlin_files():
        rel = os.path.relpath(path, ROOT)
        raw = open(path, encoding="utf-8").read().split("\n")
        code = strip_code("\n".join(raw)).split("\n")
        for idx, line in enumerate(code):
            if not any_expr.match(line) or "{" not in line:
                continue
            depth = line.count("{") - line.count("}")
            j = idx
            while depth > 0 and j + 1 < len(code):
                j += 1
                depth += code[j].count("{") - code[j].count("}")
                if re.search(r"\breturn\b", code[j]):
                    problems.append("%s:%d `return` inside an expression body (Kotlin needs a block body): %s"
                                    % (rel, j + 1, raw[j].strip()[:90]))
                    break
    return problems


def check_gradle():
    problems = []
    path = os.path.join(ROOT, "app", "build.gradle")
    if not os.path.exists(path):
        return ["app/build.gradle is missing"]
    text = open(path, encoding="utf-8").read()
    for needle in ("compileSdk", "minSdk", "applicationId", "kotlin"):
        if needle not in text:
            problems.append("app/build.gradle: no %s found" % needle)
    if "com.android.application" not in text:
        problems.append("app/build.gradle: the android application plugin is not applied")
    return problems


def main():
    res = collect_resources()
    sections = [
        ("resources", check_resources(res)),
        ("manifest", check_manifest()),
        ("kotlin syntax", check_kotlin_syntax()),
        ("api guarding", check_api_guarding()),
        ("res xml", check_res_xml()),
        ("gradle", check_gradle()),
        ("setter hygiene", check_setters()),
        ("layoutparams receiver", check_layoutparams_receiver()),
        ("member shapes", check_member_shapes()),
        ("apply shadowing", check_apply_shadowing()),
        ("jvm signature clashes", check_jvm_signature_clashes()),
        ("return in expression body", check_return_in_expression_body()),
    ]
    total = 0
    for title, problems in sections:
        print("== %s: %d issue(s)" % (title, len(problems)))
        for p in problems[:80]:
            print("   -", p)
        total += len(problems)
    print("\n%d issue(s), %d kotlin files, %d res xml files"
          % (total, len(list(kotlin_files())), len(list(res_files()))))
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
