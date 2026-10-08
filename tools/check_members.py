#!/usr/bin/env python3
"""Cross-file member check: does every `SomeObject.someMember` exist?

The Kotlin compiler would of course answer this, but there is no toolchain in the environment this
project was written in, and the failure mode it catches — a card calling `LocationHub.currentFix()`
when the object actually exposes `lastLocation` — is exactly the kind of bug a large hand-written
codebase accumulates. This resolves the *project's own* singletons and companions only; framework and
AndroidX classes are skipped because their members are not visible here.

    python3 tools/check_members.py
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(ROOT, "app", "src", "main", "java")

DECL = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)?(?:(?:open|override|internal|private|protected|public|abstract|final|lateinit|const|suspend|inline|operator|data|sealed|enum|companion)\s+)*(fun|val|var|object|class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
NAME_OF = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:open|internal|private|protected|public|abstract|final|sealed|data|object|companion)\s+)*(object|class|interface)\s+([A-Za-z_][A-Za-z0-9_]*)")
MEMBER_REF = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\.([a-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_(])")
MEMBER_CALL = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)\s*\(")


def files():
    for dp, _, fs in os.walk(JAVA):
        for f in sorted(fs):
            if f.endswith(".kt"):
                yield os.path.join(dp, f)


def block_end(lines, start):
    """Line index just after the brace-matched end of the declaration beginning at `start`."""
    depth = 0
    seen = False
    for i in range(start, len(lines)):
        depth += lines[i].count("{") - lines[i].count("}")
        if "{" in lines[i]:
            seen = True
        if seen and depth <= 0:
            return i
    return len(lines) - 1


def collect():
    """owner name -> (file, set of member names, start_line, end_line)."""
    # second pass: attach members by indentation-independent span test
    result = {}
    for path in files():
        lines = open(path, encoding="utf-8").read().split("\n")
        owners_here = []
        for idx, line in enumerate(lines):
            m = NAME_OF.match(line)
            if m:
                owners_here.append((m.group(2), idx, block_end(lines, idx)))
        for name, start, end in owners_here:
            members = set()
            for i in range(start + 1, end + 1):
                d = DECL.match(lines[i])
                if not d:
                    continue
                kind, member = d.group(1), d.group(2)
                if kind in ("fun", "val", "var"):
                    members.add(member)
                elif kind in ("object", "class", "interface"):
                    members.add(member)  # nested types are reachable as Owner.Type
            result.setdefault(name, (path, set(), 0))[1].update(members)
    return result



LOCAL_DECL = re.compile(r"\b(?:val|var)\s+([a-z][A-Za-z0-9_]*)\s*(?::\s*[A-Za-z0-9_.?<> ]+)?\s*=\s*([A-Z][A-Za-z0-9_]*)\s*[({]")
LOCAL_CTOR = re.compile(r"\b([a-z][A-Za-z0-9_]*)\s*:\s*([A-Z][A-Za-z0-9_]*)\s*\(")


def collect_locals(path):
    """Per-file local name -> classes.

    Per file on purpose: a global map would treat every `val d = …` in the project as a `DockView` just
    because one file declares `val d = DockView(ctx)`, and the false positives would drown the signal.
    """
    out = {}
    lines = open(path, encoding="utf-8").read().split("\n")
    for line in lines:
        if line.strip().startswith("import "):
            continue
        for m in LOCAL_DECL.finditer(line):
            out.setdefault(m.group(1), set()).add(m.group(2))
        for m in LOCAL_CTOR.finditer(line):
            out.setdefault(m.group(1), set()).add(m.group(2))
    return out


def check_locals(class_members):
    """Every `localName.member` must exist on at least one plausible class."""
    problems = []
    for path in files():
        locals_map = collect_locals(path)
        rel = os.path.relpath(path, ROOT)
        text = open(path, encoding="utf-8").read()
        own_classes = set()
        for idx, line in enumerate(text.split("\n")):
            m = NAME_OF.match(line)
            if m:
                own_classes.add(m.group(2))
        for line_no, line in enumerate(text.split("\n"), 1):
            stripped = line.strip()
            if stripped.startswith(("import ", "*", "//", "@")):
                continue
            for m in re.finditer(r"\b([a-z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])", line):
                owner, member = m.group(1), m.group(2)
                classes = locals_map.get(owner)
                if not classes:
                    continue
                candidates = [c for c in classes if c in class_members]
                if not candidates:
                    continue
                if any(member in class_members[c][1] for c in candidates):
                    continue
                # inherited members of a framework base class are unknowable here
                if member in COMMON_VIEW_MEMBERS:
                    continue
                problems.append("%s:%d  %s.%s  (%s has no such member)"
                                % (rel, line_no, owner, member, "/".join(sorted(candidates))))
    return problems


COMMON_VIEW_MEMBERS = {
    "context", "id", "tag", "visibility", "alpha", "x", "y", "left", "top", "right", "bottom",
    "width", "height", "measuredWidth", "measuredHeight", "parent", "isValid", "setText",
    "copy", "invalidate", "requestLayout", "setBounds", "addView", "removeView", "removeAllViews",
    "setOnClickListener", "setOnLongClickListener", "setPadding", "post", "postDelayed",
    "removeCallbacks", "performClick", "notifyDataSetChanged", "forEach", "forEachIndexed", "size",
    "isEmpty", "isNotEmpty", "firstOrNull", "indexOf", "indexOfFirst", "map", "mapNotNull", "filter",
    "sortedByDescending", "take", "joinToString", "ifBlank", "ifEmpty", "toString", "let", "also",
    "apply", "runCatching", "getOrDefault", "put", "putAll", "remove", "contains", "distinctBy",
    "sortedWith", "associateBy", "groupBy", "addAll", "clear", "first", "last", "none", "any",
    "count", "toList", "toSet", "minus", "plus", "substringBefore", "roundToInt", "coerceIn",
    "coerceAtLeast", "coerceAtMost", "orEmpty", "isNullOrBlank", "isBlank", "isNotBlank",
    "setBackgroundColor", "setImageResource", "setImageBitmap", "setColorFilter", "setBackground",
    "setTextColor", "setTextSize", "setSingleLine", "setContentView", "findViewById", "removeViewAt",
    "indexOfChild", "getChildAt", "childCount", "layoutParams", "isVisible", "requestFocus",
}


def main():
    owners = collect()
    known = set(owners)
    problems = []
    # A few members are inherited or provided by the framework base class; ignore those names.
    ignore = {"name", "ordinal", "values", "valueOf", "equals", "hashCode", "toString", "clone",
              "notify", "notifyAll", "getClass", "wait"}
    for path in files():
        text = open(path, encoding="utf-8").read()
        rel = os.path.relpath(path, ROOT)
        for line_no, line in enumerate(text.split("\n"), 1):
            stripped = line.strip()
            if stripped.startswith("import ") or stripped.startswith("*") or stripped.startswith("//"):
                continue
            for m in re.finditer(r"\b([A-Z][A-Za-z0-9_]*)\.([a-zA-Z_][A-Za-z0-9_]*)(\s*\()?", line):
                owner, member, called = m.group(1), m.group(2), m.group(3)
                if owner not in known or member in ignore:
                    continue
                decl_path, members, _ = owners[owner]
                if os.path.relpath(decl_path, ROOT) == rel:
                    continue  # inside its own file: `this`, private access, or a local shadow
                if member not in members:
                    kind = "call" if called else "property"
                    problems.append("%s:%d  %s.%s  (no such %s on %s, declared in %s)"
                                    % (rel, line_no, owner, member, kind, owner,
                                       os.path.relpath(decl_path, ROOT)))
    problems += check_locals(owners)
    seen = set()
    uniq = []
    for p in problems:
        key = p.split("  ")[-1]
        if key in seen:
            continue
        seen.add(key)
        uniq.append(p)
    print("%d unresolved member reference(s)" % len(uniq))
    for p in uniq:
        print("   -", p)
    return 1 if uniq else 0


if __name__ == "__main__":
    sys.exit(main())
