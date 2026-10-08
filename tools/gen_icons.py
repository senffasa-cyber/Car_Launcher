#!/usr/bin/env python3
"""Generates the launcher's vector icons.

Hand-written Material-style path data is a copy-paste hazard (a single wrong arc flag renders as a
blob), so every icon here is built from primitives that can be reasoned about: circles, rounded
rectangles, line work with a 2dp stroke on a 24x24 grid. `tint="@android:color/white"` keeps them
usable with setColorFilter() from code, which is how the palette system drives every glyph.

Run from the repo root after changing the table below:
    python3 tools/gen_icons.py
"""
import math
import os

SIZE = 24
STROKE = 2.0
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")


def circle(cx, cy, r, dash=None):
    """Two 180-degree arcs, the standard way to describe a circle in path data."""
    if dash is not None:
        # approximate a dashed ring with `dash` segments
        parts = []
        seg = 360.0 / dash
        for i in range(dash):
            a0 = math.radians(i * seg)
            a1 = math.radians(i * seg + seg * 0.55)
            x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
            x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
            parts.append(f"M{x0:.2f},{y0:.2f}A{r:.2f},{r:.2f} 0 0 1 {x1:.2f},{y1:.2f}")
        return " ".join(parts)
    return (
        f"M{cx - r},{cy}"
        f"A{r},{r} 0 1 0 {cx + r},{cy}"
        f"A{r},{r} 0 1 0 {cx - r},{cy}Z"
    )


def ring(cx, cy, r, w=STROKE):
    """Stroke-only circle (no fill)."""
    return circle(cx, cy, r)


def rr(x, y, w, h, r):
    """Rounded rectangle path."""
    r = min(r, w / 2, h / 2)
    return (
        f"M{x + r},{y}H{x + w - r}A{r},{r} 0 0 1 {x + w},{y + r}V{y + h - r}"
        f"A{r},{r} 0 0 1 {x + w - r},{y + h}H{x + r}A{r},{r} 0 0 1 {x},{y + h - r}"
        f"V{y + r}A{r},{r} 0 0 1 {x + r},{y}Z"
    )


def tri(x1, y1, x2, y2, x3, y3):
    return f"M{x1},{y1}L{x2},{y2}L{x3},{y3}Z"


def lines(*segs):
    return " ".join(f"M{a},{b}L{c},{d}" for a, b, c, d in segs)



def cloud_path(base_y, scale=1.0):
    """Flat-bottomed cloud built from three arcs. `base_y` is the y of the bottom edge."""
    c = lambda v: round(v * scale + (1 - scale) * 12.0, 2)
    x0 = c(7.5)
    x1 = c(17.1)
    y = round(base_y, 2)
    r1 = round(c(3.6), 2)
    r2 = round(c(6.0), 2)
    return (
        f"M{x0},{y}H{x1}"
        f"A{r1},{r1} 0 0 0 {round(x1 - 0.4, 2)},{round(y - c(6.6), 2)}"
        f"A{r2},{r2} 0 0 0 {round(x0 + 0.6, 2)},{round(y - c(5.0), 2)}"
        f"A{round(c(2.8), 2)},{round(c(2.8), 2)} 0 0 0 {x0},{y}Z"
    )


def fan_petals():
    """Three curved blades around the hub, drawn as quadratic petals."""
    parts = []
    for k in range(3):
        a = math.radians(90 + k * 120)
        ax, ay = 12 + 8.6 * math.cos(a), 12 + 8.6 * math.sin(a)
        b = a + math.radians(52)
        bx, by = 12 + 5.4 * math.cos(b), 12 + 5.4 * math.sin(b)
        parts.append(f"M12,12Q{bx:.2f},{by:.2f} {ax:.2f},{ay:.2f}Q{12 + 7.2 * math.cos(a - math.radians(38)):.2f},{12 + 7.2 * math.sin(a - math.radians(38)):.2f} 12,12")
    return " ".join(parts)


def gauge_arc(r=8.4, from_deg=160, to_deg=380):
    cx, cy = 12.0, 15.0
    a0, a1 = math.radians(from_deg), math.radians(to_deg)
    x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
    x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
    large = 1 if (a1 - a0) > math.pi else 0
    return f"M{x0:.2f},{y0:.2f}A{r},{r} 0 {large} 1 {x1:.2f},{y1:.2f}"


def gauge_ticks(r=6.6, n=5):
    cx, cy = 12.0, 15.0
    out = []
    for i in range(n):
        a = math.radians(160 + i * (220.0 / (n - 1)))
        x0, y0 = cx + (r - 1.2) * math.cos(a), cy + (r - 1.2) * math.sin(a)
        x1, y1 = cx + r * math.cos(a), cy + r * math.sin(a)
        out.append(f"M{x0:.2f},{y0:.2f}L{x1:.2f},{y1:.2f}")
    return " ".join(out)


# name -> (fill_paths, stroke_paths)
F = "fill"
S = "stroke"

ICONS = {
    # --- media -------------------------------------------------------------
    "ic_play": [(F, tri(7, 4.5, 19, 12, 7, 19.5))],
    "ic_pause": [(F, rr(6.5, 4.5, 3.6, 15, 1.6)), (F, rr(13.9, 4.5, 3.6, 15, 1.6))],
    "ic_next": [(F, tri(5, 5, 14, 12, 5, 19)), (F, rr(15.6, 5, 2.6, 14, 1.2))],
    "ic_prev": [(F, tri(19, 5, 10, 12, 19, 19)), (F, rr(5.8, 5, 2.6, 14, 1.2))],
    "ic_stop": [(F, rr(6, 6, 12, 12, 2.4))],
    "ic_volume_up": [(F, "M4,9H7.5L12,5v14L7.5,15H4Z"), (S, "M15.5,8.5a5,5 0 0 1 0,7 M18,6a8.5,8.5 0 0 1 0,12")],
    "ic_volume_down": [(F, "M4,9H7.5L12,5v14L7.5,15H4Z"), (S, "M15.5,8.5a5,5 0 0 1 0,7")],
    "ic_music": [(F, circle(8.2, 17.2, 3.0)), (F, circle(17.2, 15.2, 3.0)),
                 (S, "M11.2,17.2V6.4l9,-2v10.8")],
    "ic_open_in_app": [(S, "M13,5h6v6 M19,5l-8,8 M18,14v4a2,2 0 0 1 -2,2H7a2,2 0 0 1 -2,-2V8a2,2 0 0 1 2,-2h4")],

    # --- navigation / map ---------------------------------------------------
    "ic_map": [(S, "M9,4.5 3.5,6.5v13L9,17.5l6,2 5.5,-2v-13L15,6.5Z M9,4.5v13 M15,6.5v13")],
    "ic_navigation": [(F, tri(12, 3, 4.5, 20.5, 12, 17)), (F, tri(12, 3, 19.5, 20.5, 12, 17))],
    "ic_compass": [(S, circle(12, 12, 8.4)), (F, tri(12, 6.5, 15.5, 12, 8.5, 12))],
    "ic_north": [(S, lines((12, 20.5, 12, 9.5))), (F, "M12,2.8 17.4,10.6H6.6Z")],
    "ic_crosshair": [(S, circle(12, 12, 6.4)), (S, lines((12, 2.5, 12, 6), (12, 18, 12, 21.5), (2.5, 12, 6, 12), (18, 12, 21.5, 12))), (F, circle(12, 12, 1.8))],
    "ic_pin": [(S, circle(12, 9.6, 6.0)), (S, "M7.9,13.9 12,21.2l4.1,-7.3"), (F, circle(12, 9.6, 1.9))],
    "ic_route": [(F, circle(6.2, 18.2, 2.6)), (F, circle(17.8, 5.8, 2.6)),
                 (S, "M6.2,15.6V10a3,3 0 0 1 3,-3h6.6 M8.8,18.2h6.4a3,3 0 0 0 0,-6H8.6")],
    "ic_search": [(S, circle(10.8, 10.8, 6.2)), (S, lines((15.4, 15.4, 20.2, 20.2)))],
    "ic_home": [(S, "M4,10.5 12,4l8,6.5 M6,9.5V19a1,1 0 0 0 1,1h10a1,1 0 0 0 1,-1V9.5 M10,20v-5h4v5")],
    "ic_work": [(S, rr(3.5, 7.5, 17, 12, 2.0)), (S, "M9,7.5V6a1.5,1.5 0 0 1 1.5,-1.5h3A1.5,1.5 0 0 1 15,6v1.5 M3.5,12.5h17")],

    # --- clock / calendar / weather ----------------------------------------
    "ic_clock": [(S, circle(12, 12, 8.4)), (S, lines((12, 12, 12, 7), (12, 12, 15.6, 14)))],
    "ic_calendar": [(S, rr(3.8, 5.2, 16.4, 15, 2.2)), (S, lines((3.8, 9.4, 20.2, 9.4), (8.2, 3.4, 8.2, 6.6), (15.8, 3.4, 15.8, 6.6))), (F, circle(8.4, 13.4, 1.0)), (F, circle(12, 13.4, 1.0)), (F, circle(15.6, 13.4, 1.0)), (F, circle(8.4, 16.9, 1.0)), (F, circle(12, 16.9, 1.0))],
    "ic_weather_sun": [(S, circle(12, 12, 4.2)),
                       (S, lines((12, 2.8, 12, 5.4), (12, 18.6, 12, 21.2), (2.8, 12, 5.4, 12), (18.6, 12, 21.2, 12),
                                (5.4, 5.4, 7.2, 7.2), (16.8, 16.8, 18.6, 18.6), (18.6, 5.4, 16.8, 7.2), (7.2, 16.8, 5.4, 18.6)))],
    "ic_weather_partly": [(S, circle(8.4, 8.4, 3.2)),
                          (S, lines((8.4, 2.6, 8.4, 4.2), (2.6, 8.4, 4.2, 8.4), (4.4, 4.4, 5.6, 5.6), (12.4, 4.4, 11.2, 5.6))),
                          (S, cloud_path(19.5, 0.86))],
    "ic_weather_cloud": [(S, cloud_path(18.5))],
    "ic_weather_rain": [(S, cloud_path(15.5)),
                        (S, lines((9, 18, 8, 21), (12.5, 18, 11.5, 21), (16, 18, 15, 21)))],
    "ic_weather_snow": [(S, cloud_path(15.5)),
                        (S, lines((9, 18.4, 9, 21.2), (7.6, 19.8, 10.4, 19.8), (15, 18.4, 15, 21.2), (13.6, 19.8, 16.4, 19.8)))],
    "ic_weather_fog": [(S, cloud_path(14.5)),
                       (S, lines((4.5, 18, 19.5, 18), (6.5, 21, 17.5, 21)))],
    "ic_weather_storm": [(S, cloud_path(15.5)), (F, "M13.4,15.8 9.4,21.2L11.9,21.2 10.4,23.9 15.2,18.1 12.6,18.1 14.1,15.8Z")],
    "ic_weather_moon": [(F, "M20,14.6A8.4,8.4 0 0 1 9.4,4 8.4,8.4 0 1 0 20,14.6Z")],

    # --- vehicle ------------------------------------------------------------
    "ic_car": [(S, "M4,15.5v2.2a1,1 0 0 0 1,1h1.6a1,1 0 0 0 1,-1v-0.9h8.8v0.9a1,1 0 0 0 1,1H19a1,1 0 0 0 1,-1V15.5"),
              (S, "M3.6,15.5h16.8l-1.4,-4.6a2,2 0 0 0 -1.9,-1.4H6.9a2,2 0 0 0 -1.9,1.4Z"),
              (F, circle(7.2, 13.4, 1.0)), (F, circle(16.8, 13.4, 1.0))],
    "ic_gauge": [(S, gauge_arc()), (S, gauge_ticks()), (S, "M12,14.6 16.4,9.6"), (F, circle(12, 15.0, 1.4))],
    "ic_fuel": [(S, rr(4, 4, 8, 16, 1.8)), (S, "M12,8h3.2a1.8,1.8 0 0 1 1.8,1.8v5.4a1.8,1.8 0 0 0 1.8,1.8h0a1.6,1.6 0 0 0 1.6,-1.6V9.4L17,6"), (S, lines((5.8,7.6,10.2,7.6)))],
    "ic_thermo": [(S, "M13.8,13.6V5.4a2.2,2.2 0 1 0 -4.4,0v8.2a4.2,4.2 0 1 0 4.4,0Z"), (F, circle(11.6, 16.8, 1.7))],
    "ic_tire": [(S, circle(12, 12, 8.2)), (S, circle(12, 12, 3.4)),
                (S, lines((12, 3.8, 12, 8.6), (12, 15.4, 12, 20.2), (3.8, 12, 8.6, 12), (15.4, 12, 20.2, 12)))],
    "ic_battery": [(S, rr(2.6, 7, 16, 10, 2)), (S, "M20.6,10.5v3a1.4,1.4 0 0 0 0,-3Z"), (F, rr(4.6, 9, 6.5, 6, 1))],
    "ic_snowflake": [(S, lines((12, 3, 12, 21), (4.2, 7.5, 19.8, 16.5), (19.8, 7.5, 4.2, 16.5),
                              (9.6, 4.6, 12, 6.8, ), (14.4, 4.6, 12, 6.8),
                              (9.6, 19.4, 12, 17.2), (14.4, 19.4, 12, 17.2)))],
    "ic_fan": [(S, fan_petals()), (F, circle(12, 12, 1.7))],
    "ic_recirculate": [(S, "M5.5,10.5a6.5,6.5 0 0 1 11.2,-3.2"), (S, "M18.5,13.5a6.5,6.5 0 0 1 -11.2,3.2"),
                        (S, "M16.7,3.6v3.9h-3.9 M7.3,20.4v-3.9h3.9")],
    "ic_seat_heat": [(S, "M7.5,3.5V12a2.5,2.5 0 0 0 2.5,2.5H16 M5.5,20.5H18.5 M7.5,3.5a2,2 0 0 1 0,0 M16.5,14.5v6 M7.5,12V3.5"),
                     (S, "M9.5,7.5 12.5,7.5 M9.5,10.5 14.5,10.5")],

    # --- system / launcher chrome -------------------------------------------
    "ic_settings": [(S, circle(12, 12, 3.1)),
                    (S, "M12,2.8v2.6 M12,18.6v2.6 M2.8,12h2.6 M18.6,12h2.6 M5.5,5.5l1.9,1.9 M16.6,16.6l1.9,1.9 M18.5,5.5l-1.9,1.9 M7.4,16.6l-1.9,1.9")],
    "ic_apps": [(F, circle(6.5, 6.5, 1.9)), (F, circle(12, 6.5, 1.9)), (F, circle(17.5, 6.5, 1.9)),
                (F, circle(6.5, 12, 1.9)), (F, circle(12, 12, 1.9)), (F, circle(17.5, 12, 1.9)),
                (F, circle(6.5, 17.5, 1.9)), (F, circle(12, 17.5, 1.9)), (F, circle(17.5, 17.5, 1.9))],
    "ic_grid": [(S, rr(3.5, 3.5, 7, 7, 1.6)), (S, rr(13.5, 3.5, 7, 7, 1.6)),
                (S, rr(3.5, 13.5, 7, 7, 1.6)), (S, rr(13.5, 13.5, 7, 7, 1.6))],
    "ic_power": [(S, "M12,3.5v7.5"), (S, "M7.2,6.6a7,7 0 1 0 9.6,0")],
    "ic_screen_off": [(S, rr(3, 4.5, 18, 12, 2)), (S, lines((8.5, 20, 15.5, 20))), (S, lines((5, 4.5, 19, 16.5)))],
    "ic_bluetooth": [(S, "M8,7.5 16,16.5 12,20V4l4,3.5L8,16.5")],
    "ic_mic": [(S, "M12,3.5a2.6,2.6 0 0 1 2.6,2.6v5a2.6,2.6 0 0 1 -5.2,0v-5A2.6,2.6 0 0 1 12,3.5Z"),
               (S, "M5.5,11a6.5,6.5 0 0 0 13,0 M12,17.5V21"), (S, lines((9, 21, 15, 21)))],
    "ic_phone": [(S, "M6.6,3.8h2.6l1.6,4 -2,1.4a10.5,10.5 0 0 0 4.6,4.6l1.4,-2 4,1.6v2.6a2,2 0 0 1 -2.2,2A16,16 0 0 1 4.6,6a2,2 0 0 1 2,-2.2Z")],
    "ic_dialpad": [(F, circle(6.5, 6.5, 1.7)), (F, circle(12, 6.5, 1.7)), (F, circle(17.5, 6.5, 1.7)),
                   (F, circle(6.5, 12, 1.7)), (F, circle(12, 12, 1.7)), (F, circle(17.5, 12, 1.7)),
                   (F, circle(6.5, 17.5, 1.7)), (F, circle(12, 17.5, 1.7)), (F, circle(17.5, 17.5, 1.7))],
    "ic_camera": [(S, "M4,8.5h2.6l1.6,-2.4h6l1.6,2.4H20a1.5,1.5 0 0 1 1.5,1.5v8A1.5,1.5 0 0 1 20,19.5H4a1.5,1.5 0 0 1 -1.5,-1.5V10A1.5,1.5 0 0 1 4,8.5Z"),
                  (S, circle(12, 14, 3.4))],
    "ic_video": [(S, rr(3, 6, 12, 12, 2)), (S, "M15,11.5l6,-3.5v7l-6,-3.5Z")],
    "ic_refresh": [(S, "M19.5,12a7.5,7.5 0 1 1 -2.2,-5.3"), (S, "M19.9,3.6v4.2h-4.2")],
    "ic_reset": [(S, "M4.5,12a7.5,7.5 0 1 0 2.3,-5.3"), (S, "M4.1,3.8V8h4.2")],
    "ic_plus": [(S, lines((12, 5, 12, 19), (5, 12, 19, 12)))],
    "ic_minus": [(S, lines((5, 12, 19, 12)))],
    "ic_close": [(S, lines((6, 6, 18, 18), (18, 6, 6, 18)))],
    "ic_chevron_left": [(S, "M15,5.5 8.5,12l6.5,6.5")],
    "ic_chevron_right": [(S, "M9,5.5 15.5,12L9,18.5")],
    "ic_arrow_up": [(S, lines((12, 19, 12, 5)) + " M6.5,10.5 12,5l5.5,5.5")],
    "ic_circle": [(F, circle(12, 12, 3.6))],
    "ic_check": [(S, "M5,12.5 10,17.5 19.5,7")],
    "ic_info": [(S, circle(12, 12, 8.6)), (S, lines((12, 11, 12, 16.5))), (F, circle(12, 7.8, 1.1))],
    "ic_warning": [(S, "M12,3.8 21,19.5H3Z"), (S, lines((12, 9.5, 12, 14.5))), (F, circle(12, 17, 1.1))],
    "ic_sun": [(S, circle(12, 12, 4.0)),
               (S, lines((12, 3.2, 12, 5.8), (12, 18.2, 12, 20.8), (3.2, 12, 5.8, 12), (18.2, 12, 20.8, 12))
                + " M6.2,6.2 8,8 M16,16l1.8,1.8 M17.8,6.2 16,8 M8,16l-1.8,1.8")],
    "ic_moon": [(F, "M20.5,14.8A8.8,8.8 0 0 1 9.2,3.5 8.8,8.8 0 1 0 20.5,14.8Z")],
    "ic_auto_mode": [(S, circle(12, 12, 8.4)), (S, "M8.5,15.5 12,7.5l3.5,8 M9.8,12.8h4.4")],
    "ic_wallpaper": [(S, rr(3.5, 4.5, 17, 15, 2)), (S, "M6.5,16.5 10,12l3,3.5 2.2,-2.2 2.3,3.2"), (F, circle(9, 9, 1.4))],
    "ic_layout": [(S, rr(3.2, 4.2, 17.6, 15.6, 2)), (S, lines((3.2, 9, 20.8, 9), (13, 9, 13, 19.8)))],
    "ic_trash": [(S, "M5.5,7.5h13l-1,12.5h-11Z M9,7.5V5h6v2.5"), (S, lines((10, 11, 10, 17), (14, 11, 14, 17)))],
    "ic_trip": [(S, "M4,18.5h16"), (S, "M6,18.5V11l3,-4.5h6L18,11v7.5"), (F, circle(6.5, 16.5, 1.6)), (F, circle(17.5, 16.5, 1.6))],
    "ic_speed": [(S, gauge_arc()), (S, "M12,14.6 17.2,9.2"), (F, circle(12, 15.0, 1.3))],
    "ic_widget": [(S, rr(3.5, 4, 8, 8, 1.8)), (S, rr(13.5, 4, 7, 16, 1.8)), (S, rr(3.5, 14, 8, 6, 1.8))],
    "ic_lock": [(S, rr(5, 10.5, 14, 9.5, 2.2)), (S, "M8,10.5V8a4,4 0 0 1 8,0v2.5")],
    "ic_split": [(S, rr(3.2, 5, 8, 14, 1.8)), (S, rr(13, 5, 8, 14, 1.8))],
    "ic_launcher_body": [(S, "M4,15.5v2.2a1,1 0 0 0 1,1h1.6a1,1 0 0 0 1,-1v-0.9h8.8v0.9a1,1 0 0 0 1,1H19a1,1 0 0 0 1,-1V15.5"),
                         (S, "M3.6,15.5h16.8l-1.4,-4.6a2,2 0 0 0 -1.9,-1.4H6.9a2,2 0 0 0 -1.9,1.4Z"),
                         (F, circle(7.2, 13.4, 1.0)), (F, circle(16.8, 13.4, 1.0))],
}

LAUNCHER = """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0E1116"
        android:pathData="M0,0h108v108h-108z" />
    <group
        android:scaleX="2.4"
        android:scaleY="2.4"
        android:translateX="26.4"
        android:translateY="26.4">
        <path
            android:strokeColor="#3DDC97"
            android:strokeWidth="2"
            android:strokeLineCap="round"
            android:fillColor="#00000000"
            android:pathData="M4,15.5v2.2a1,1 0 0 0 1,1h1.6a1,1 0 0 0 1,-1v-0.9h8.8v0.9a1,1 0 0 0 1,1H19a1,1 0 0 0 1,-1V15.5" />
        <path
            android:strokeColor="#3DDC97"
            android:strokeWidth="2"
            android:fillColor="#00000000"
            android:pathData="M3.6,15.5h16.8l-1.4,-4.6a2,2 0 0 0 -1.9,-1.4H6.9a2,2 0 0 0 -1.9,1.4Z" />
        <path
            android:fillColor="#3DDC97"
            android:pathData="M6.2,12.4a1,1 0 1 0 2,0a1,1 0 1 0 -2,0z" />
        <path
            android:fillColor="#3DDC97"
            android:pathData="M15.8,12.4a1,1 0 1 0 2,0a1,1 0 1 0 -2,0z" />
    </group>
</vector>
"""

HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/gen_icons.py - do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w}dp"
    android:height="{h}dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}"
    android:tint="@android:color/white">
{body}
</vector>
"""


def path_xml(kind, data, stroke_w=STROKE):
    if kind == F:
        return (
            '    <path\n'
            '        android:fillColor="@android:color/white"\n'
            f'        android:pathData="{data}" />\n'
        )
    return (
        '    <path\n'
        '        android:fillColor="#00000000"\n'
        '        android:strokeColor="@android:color/white"\n'
        f'        android:strokeWidth="{stroke_w:g}"\n'
        '        android:strokeLineCap="round"\n'
        '        android:strokeLineJoin="round"\n'
        f'        android:pathData="{data}" />\n'
    )


def main():
    out = os.path.abspath(OUT)
    os.makedirs(out, exist_ok=True)
    count = 0
    for name, shapes in ICONS.items():
        body = "".join(
            path_xml(kind, data) for kind, data in shapes if isinstance(data, str)
        )
        text = HEADER.format(w=SIZE, h=SIZE, vw=SIZE, vh=SIZE, body=body)
        with open(os.path.join(out, name + ".xml"), "w", encoding="utf-8") as fh:
            fh.write(text)
        count += 1
    with open(os.path.join(out, "ic_launcher.xml"), "w", encoding="utf-8") as fh:
        fh.write(LAUNCHER)
    print(f"wrote {count} icons + launcher icon to {out}")


if __name__ == "__main__":
    main()
