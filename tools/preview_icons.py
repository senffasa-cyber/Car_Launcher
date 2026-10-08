#!/usr/bin/env python3
"""Renders every generated VectorDrawable into one contact sheet so the icons can be eyeballed.

VectorDrawable `pathData` is SVG path syntax, so a small parser (M L H V C Q A Z, absolute and
relative, with implicit parameter repetition) plus PIL reproduces what the device will draw. Arcs
are converted to cubic beziers with the standard endpoint -> centre parameterisation.

    python3 tools/preview_icons.py [out.png]
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw

SCALE = 4
TOKEN = re.compile(r"([MmLlHhVvCcQqAaZz])|(-?\d*\.?\d+(?:[eE][-+]?\d+)?)")
COUNTS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "Q": 4, "A": 7}


def tokenize(d):
    out = []
    for m in TOKEN.finditer(d):
        out.append(m.group(1) if m.group(1) is not None else float(m.group(2)))
    return out


def arc_to_cubics(x0, y0, rx, ry, phi_deg, fa, fs, x1, y1):
    if rx == 0 or ry == 0:
        return [(x0, y0, x1, y1, x1, y1)]
    rx, ry = abs(rx), abs(ry)
    phi = math.radians(phi_deg % 360)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    x1p = cp * dx + sp * dy
    y1p = -sp * dx + cp * dy
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num) / den) if den else 0.0
    if fa == fs:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x0 + x1) / 2.0
    cy = sp * cxp + cp * cyp + (y0 + y1) / 2.0

    def ang(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        ln = math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy)) or 1.0
        a = math.acos(max(-1.0, min(1.0, dot / ln)))
        return -a if ux * vy - uy * vx < 0 else a

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    n = max(1, int(math.ceil(abs(dt) / (math.pi / 2 - 1e-6))))
    delta = dt / n
    k = 4.0 / 3.0 * math.tan(delta / 4.0)
    out = []
    for _ in range(n):
        c1, s1 = math.cos(t1), math.sin(t1)
        c2, s2 = math.cos(t1 + delta), math.sin(t1 + delta)
        e1x = rx * c1 * cp - ry * s1 * sp + cx
        e1y = rx * c1 * sp + ry * s1 * cp + cy
        e2x = rx * c2 * cp - ry * s2 * sp + cx
        e2y = rx * c2 * sp + ry * s2 * cp + cy
        d1x = -rx * s1 * cp - ry * c1 * sp
        d1y = -rx * s1 * sp + ry * c1 * cp
        d2x = -rx * s2 * cp - ry * c2 * sp
        d2y = -rx * s2 * sp + ry * c2 * cp
        out.append((e1x + k * d1x, e1y + k * d1y, e2x - k * d2x, e2y - k * d2y, e2x, e2y))
        t1 += delta
    return out


def flatten(d):
    """-> list of (points, closed). Points are the flattened polyline of each subpath."""
    toks = tokenize(d)
    subs, cur = [], []
    x = y = sx = sy = 0.0
    i = 0
    cmd = None

    def num():
        nonlocal i
        if i < len(toks) and not isinstance(toks[i], str):
            v = toks[i]
            i += 1
            return v
        return None

    while i < len(toks):
        if isinstance(toks[i], str):
            cmd = toks[i]
            i += 1
            if cmd in ("z", "Z"):
                if cur:
                    subs.append((cur[:], True))
                    cur = []
                x, y = sx, sy
            continue
        if cmd is None:
            break
        rel = cmd.islower()
        c = cmd.upper()
        n = COUNTS.get(c)
        if n is None:
            break
        a = []
        for _ in range(n):
            v = num()
            if v is None:
                break
            a.append(v)
        if len(a) < n:
            break
        ox, oy = x, y
        if c == "H":
            x = a[0] + (ox if rel else 0)
            cur.append((x, y))
        elif c == "V":
            y = a[0] + (oy if rel else 0)
            cur.append((x, y))
        elif c in ("M", "L"):
            nx = a[0] + (ox if rel else 0)
            ny = a[1] + (oy if rel else 0)
            if c == "M":
                if cur:
                    subs.append((cur[:], False))
                    cur = []
                x, y = nx, ny
                sx, sy = nx, ny
                cur.append((x, y))
                cmd = "l" if rel else "L"  # subsequent implicit pairs are lineto
            else:
                x, y = nx, ny
                cur.append((x, y))
        elif c == "C":
            pts = [(a[k] + (ox if k % 2 == 0 else oy), a[k + 1] + (oy if k % 2 else ox)) for k in range(0, 6, 2)]
            if rel:
                pts = [(a[0] + ox, a[1] + oy), (a[2] + ox, a[3] + oy), (a[4] + ox, a[5] + oy)]
            else:
                pts = [(a[0], a[1]), (a[2], a[3]), (a[4], a[5])]
            cubic_to_poly(cur, (x, y), pts[0], pts[1], pts[2])
            x, y = pts[2]
        elif c == "Q":
            p1 = (a[0] + (ox if rel else 0), a[1] + (oy if rel else 0))
            e = (a[2] + (ox if rel else 0), a[3] + (oy if rel else 0))
            quad_to_poly(cur, (x, y), p1, e)
            x, y = e
        elif c == "A":
            rx, ry, rot, fa, fs = a[0], a[1], a[2], int(a[3]), int(a[4])
            ex = a[5] + (ox if rel else 0)
            ey = a[6] + (oy if rel else 0)
            for seg in arc_to_cubics(x, y, rx, ry, rot, fa, fs, ex, ey):
                cubic_to_poly(cur, (x, y), (seg[0], seg[1]), (seg[2], seg[3]), (seg[4], seg[5]))
                x, y = seg[4], seg[5]
    if cur:
        subs.append((cur, False))
    return subs


def cubic_to_poly(out, p0, c1, c2, p1, steps=16):
    for k in range(1, steps + 1):
        u = k / steps
        iu = 1 - u
        bx = iu ** 3 * p0[0] + 3 * iu * iu * u * c1[0] + 3 * iu * u * u * c2[0] + u ** 3 * p1[0]
        by = iu ** 3 * p0[1] + 3 * iu * iu * u * c1[1] + 3 * iu * u * u * c2[1] + u ** 3 * p1[1]
        out.append((bx, by))


def quad_to_poly(out, p0, c1, p1, steps=12):
    for k in range(1, steps + 1):
        u = k / steps
        iu = 1 - u
        bx = iu * iu * p0[0] + 2 * iu * u * c1[0] + u * u * p1[0]
        by = iu * iu * p0[1] + 2 * iu * u * c1[1] + u * u * p1[1]
        out.append((bx, by))


def render(pathdata, size, color, stroke_width=2.0, fill=True, stroke=True):
    im = Image.new("RGBA", (size * SCALE, size * SCALE), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    k = size * SCALE / 24.0
    for pts, closed in flatten(pathdata):
        if len(pts) < 2:
            continue
        poly = [(p[0] * k, p[1] * k) for p in pts]
        if fill:
            dr.polygon(poly, fill=color)
        if stroke:
            w = max(1, int(round(stroke_width * k)))
            dr.line(poly + (poly[:1] if closed else []), fill=color, width=w, joint="curve")
    return im.resize((size, size), Image.LANCZOS)


PATH_BLOCK = re.compile(r"<path\b(.*?)(?:/>|</path>)", re.S)


def parse_vector(xml):
    out = []
    for m in PATH_BLOCK.finditer(xml):
        body = m.group(1)
        pd = re.search(r'android:pathData="([^"]+)"', body)
        if not pd:
            continue
        fill = re.search(r'android:fillColor="([^"]+)"', body)
        stroke = re.search(r'android:strokeColor="([^"]+)"', body)
        sw = re.search(r'android:strokeWidth="([\d.]+)"', body)
        has_fill = bool(fill) and fill.group(1) not in ("#00000000", "@android:color/transparent")
        out.append((pd.group(1), has_fill, bool(stroke), float(sw.group(1)) if sw else 2.0))
    return out


def main():
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    d = os.path.join(root, "app", "src", "main", "res", "drawable")
    files = sorted(f for f in os.listdir(d) if f.endswith(".xml"))
    cell, cols, label_h = 92, 8, 26
    rows = (len(files) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * (cell + 8) + 8, rows * (cell + label_h + 8) + 8), (16, 19, 25))
    dr = ImageDraw.Draw(sheet)
    for idx, fn in enumerate(files):
        xml = open(os.path.join(d, fn), encoding="utf-8").read()
        vp = re.search(r'android:viewportWidth="(\d+)"', xml)
        vpw = float(vp.group(1)) if vp else 24.0
        tile = Image.new("RGBA", (cell, cell), (30, 35, 45, 255))
        for pd, hf, hs, sw in parse_vector(xml):
            art = render(pd, int(cell * 0.78), (244, 248, 252, 255), sw, fill=hf, stroke=hs or not hf)
            off = (cell - art.width) // 2
            tile.alpha_composite(art, (off, off))
        x = 8 + (idx % cols) * (cell + 8)
        y = 8 + (idx // cols) * (cell + label_h + 8)
        sheet.paste(tile, (x, y))
        dr.text((x + 2, y + cell + 6), fn[:-4], fill=(206, 214, 224))
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/icons_sheet.png"
    sheet.save(out)
    print(f"{len(files)} icons -> {out} ({sheet.width}x{sheet.height})")


if __name__ == "__main__":
    main()
