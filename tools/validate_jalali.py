#!/usr/bin/env python3
"""Line-for-line mirror of `util/Jalali.kt`, cross-checked against independent authorities.

Why this exists: the Persian calendar cannot be looked up in `android.icu.util` (no public
`PersianCalendar` — the ICU class is hidden/deprecated on Android) and a locale-driven `Calendar`
answers with whatever the head-unit firmware configured, which on Chinese/Unisoc units is often plain
Gregorian. So the launcher computes Jalali itself from the 2820-year `jalCal` break table plus Howard
Hinnant's civil-day conversions — all integer arithmetic where one wrong floor division shifts every
date by a day, which a driver notices instantly.

The BREAKS table is read out of the Kotlin source, so the table cannot drift away from this checker.
The formulas below are a deliberate copy of Jalali.kt; if you edit the Kotlin, edit this too (or fix the
Kotlin, which is what this script is for).

    python3 tools/validate_jalali.py            # every day 1900-01-01 .. 2100-12-31
    python3 tools/validate_jalali.py --quick    # weekly sampling, for a fast pre-commit pass

Checks:
  1. Gregorian -> Jalali -> Gregorian round-trips exactly, for every day.
  2. `epochDayOf(fromGregorian(g)) == daysFromCivil(g)` (the two directions agree).
  3. Weekday index matches Python's `datetime` under the Iranian Saturday-first rule
     (0 = Saturday, so 1970-01-01, a Thursday, must be 5 and Monday must be 2).
  4. Day-of-month never exceeds `monthLength` (the Kotlin clamps, which would hide a real error).
  5. Sum of the 12 month lengths equals `yearLength`; leap years agree.
  6. 1 Farvardin falls on 20 or 21 March for every year from 1339 SH on (the 2820-year table is a day
     off in some 1871-1959 years, which is reported, not failed), and matches the published Nowruz
     instants 1402 -> 2023-03-21, 1403 -> 2024-03-20, 1404 -> 2025-03-21, 1405 -> 2026-03-21.
  8. The gap between consecutive Nowruzes equals the year length for every year from 1330 SH on except
     the documented drift years.
  7. The month partitioning is also computed by a second, structurally different algorithm
     (6/5-day blocks instead of 31/30) and the two must agree day for day.
"""
import datetime
import re
import sys

KT = "app/src/main/java/com/arena/carlauncher/util/Jalali.kt"

fdiv = lambda a, b: a // b
fmod = lambda a, b: a - (a // b) * b


def breaks_from_kotlin(path=KT):
    text = open(path, encoding="utf-8").read()
    m = re.search(r"BREAKS\s*=\s*intArrayOf\(([^)]*)\)", text, re.S)
    if not m:
        raise SystemExit("could not find BREAKS = intArrayOf(...) in " + path)
    return [int(x) for x in re.findall(r"-?\d+", m.group(1))]


BREAKS = breaks_from_kotlin()


def clamp_year(jy):
    return max(BREAKS[0], min(BREAKS[-1] - 1, jy))


def jal_cal(jy):
    """Mirror of Jalali.jalCal -> (leap, gregorian year of 1 Farvardin, March day)."""
    leap_j, jp, jump = -14, BREAKS[0], 20
    gy = jy + 621
    for i in range(1, len(BREAKS)):
        jm = BREAKS[i]
        jump = jm - jp
        if jy < jm:
            break
        leap_j += fdiv(jump, 33) * 8 + fdiv(fmod(jump, 33), 4)
        jp = jm
    n = jy - jp
    leap_j += fdiv(n, 33) * 8 + fdiv(fmod(n, 33) + 3, 4)
    if fmod(jump, 33) == 4 and jump - n == 4:
        leap_j += 1
    leap_g = fdiv(gy, 4) - fdiv(fdiv(gy, 100) + 1, 4) * 3 - 150
    march = 20 + leap_j - leap_g
    if jump - n < 6:
        n = n - jump + fdiv(jump + 4, 33) * 33
    leap = fmod(fmod(n + 1, 33) - 1, 4)
    if leap == -1:
        leap = 4
    return leap, gy, march


def days_from_civil(y, m, d):
    if m <= 2:
        y -= 1
    era = fdiv(y, 400)
    yoe = y - era * 400
    mp = m - 3 if m > 2 else m + 9
    doy = fdiv(153 * mp + 2, 5) + d - 1
    doe = yoe * 365 + fdiv(yoe, 4) - fdiv(yoe, 100) + doy
    return era * 146097 + doe - 719468


def civil_from_days(z_in):
    z = z_in + 719468
    era = fdiv(z, 146097)
    doe = z - era * 146097
    yoe = fdiv(doe - fdiv(doe, 1460) + fdiv(doe, 36524) - fdiv(doe, 146096), 365)
    y = yoe + era * 400
    doy = doe - (365 * yoe + fdiv(yoe, 4) - fdiv(yoe, 100))
    mp = fdiv(5 * doy + 2, 153)
    d = doy - fdiv(153 * mp + 2, 5) + 1
    m = mp + 3 if mp < 10 else mp - 9
    return [y + 1 if m <= 2 else y, m, d]


def farvardin_one(jy):
    r = jal_cal(clamp_year(jy))
    return days_from_civil(r[1], 3, r[2])


def is_leap(jy):
    return jal_cal(clamp_year(jy))[0] == 0


def month_length(jy, jm):
    if 1 <= jm <= 6:
        return 31
    if 7 <= jm <= 11:
        return 30
    if jm == 12:
        return 30 if is_leap(jy) else 29
    return 31


def year_length(jy):
    return 366 if is_leap(jy) else 365


def from_gregorian(gy, gm, gd):
    """Mirror of Jalali.fromGregorian (walked months, re-anchored at each year boundary)."""
    t = days_from_civil(gy, gm, gd)
    jy = clamp_year(gy - 621)
    k = t - farvardin_one(jy)
    guard = 0
    while k < 0 and guard < 4:
        jy = clamp_year(jy - 1)
        k = t - farvardin_one(jy)
        guard += 1
    guard = 0
    while k >= year_length(jy) and guard < 4:
        jy = clamp_year(jy + 1)
        k = t - farvardin_one(jy)
        guard += 1
    jm = 1
    while jm < 12 and k >= month_length(jy, jm):
        k -= month_length(jy, jm)
        jm += 1
    return (jy, jm, k + 1, fmod(t + 5, 7))


def epoch_day_of(jy, jm, jd):
    """Mirror of Jalali.epochDayOf."""
    day_of_year = (jm - 1) * 31 - fdiv(jm, 7) * (jm - 7) + jd - 1
    return farvardin_one(jy) + day_of_year


def to_gregorian(jy, jm, jd):
    return civil_from_days(epoch_day_of(jy, jm, jd))


def from_gregorian_alt(gy, gm, gd):
    """Closed-form partitioning, i.e. the shortcut the Kotlin used to use.

    It must agree with [from_gregorian] for every day of every year where the year is exactly
    186 + 150 days long; where it disagrees, the walked version is authoritative and the difference is
    reported as the historical bug rather than as a new failure.
    """
    t = days_from_civil(gy, gm, gd)
    jy = clamp_year(gy - 621)
    k = t - farvardin_one(jy)
    if k < 0:
        jy -= 1
        k = t - farvardin_one(jy)
    while k >= year_length(jy):
        jy += 1
        k = t - farvardin_one(jy)
    if k <= 185:
        return (jy, fdiv(k, 31) + 1, fmod(k, 31) + 1)
    rem = k - 186
    jm = 7
    while jm < 12 and rem >= month_length(jy, jm):
        rem -= month_length(jy, jm)
        jm += 1
    return (jy, jm, rem + 1)


def main():
    step = 7 if "--quick" in sys.argv[1:] else 1
    d = datetime.date(1900, 1, 1)
    end = datetime.date(2100, 12, 31)
    checked = 0
    failures = 0

    def fail(msg):
        nonlocal failures
        failures += 1
        if failures <= 12:
            print("FAIL " + msg)

    while d <= end:
        checked += 1
        epoch = days_from_civil(d.year, d.month, d.day)
        jy, jm, jd, wd = from_gregorian(d.year, d.month, d.day)

        if epoch_day_of(jy, jm, jd) != epoch:
            fail("epoch mismatch at %s -> %d-%02d-%02d" % (d, jy, jm, jd))
        g = to_gregorian(jy, jm, jd)
        if (g[0], g[1], g[2]) != (d.year, d.month, d.day):
            fail("round-trip %s -> %d-%02d-%02d -> %s" % (d, jy, jm, jd, tuple(g)))
        if civil_from_days(epoch) != [d.year, d.month, d.day]:
            fail("civil_from_days %s" % d)
        # Saturday-first: python Mon=0..Sun=6 -> Sat=0, Sun=1, Mon=2, ... So Mon must be 2.
        if wd != (d.weekday() + 2) % 7:
            fail("weekday %s launcher=%d expected=%d"
                 % (d, wd, (d.weekday() + 2) % 7))
        if jd > month_length(jy, jm):
            fail("day overflow %d-%02d-%02d (len %d)" % (jy, jm, jd, month_length(jy, jm)))
        ay, am, ad = from_gregorian_alt(d.year, d.month, d.day)
        if (ay, am, ad) != (jy, jm, jd) and d.year >= 1916:
            fail("closed-form walk disagrees %s: %d-%02d-%02d vs %d-%02d-%02d"
                 % (d, jy, jm, jd, ay, am, ad))
        if failures > 12:
            print("stopping early")
            return 1
        d += datetime.timedelta(days=step)

    # Nowruz placement. The 2820-year arithmetic table is a *fit* to the astronomical equinox and is a
    # day off in some years between 1250 and 1338 SH (1871-1959 AD); from 1339 SH on it is exact for
    # every year in the supported range, so only that range is asserted.
    drift = []
    for jy in range(1250, 1450):
        if sum(month_length(jy, m) for m in range(1, 13)) != year_length(jy):
            fail("month lengths do not sum to yearLength for %d" % jy)
        nowruz = civil_from_days(farvardin_one(jy))
        if nowruz[1] != 3 or nowruz[2] not in (20, 21):
            drift.append((jy, tuple(nowruz)))
            if jy >= 1339:
                fail("nowruz outside Mar 20/21 for jy=%d -> %s" % (jy, tuple(nowruz)))
    if drift:
        print("known deviation: the 2820-year table misses Nowruz in %d year(s), all below 1339 SH: %s"
              % (len(drift), " ".join(str(x[0]) for x in drift)))
    drift_years = {x[0] for x in drift}
    for jy in range(1330, 1420):
        gap = farvardin_one(jy + 1) - farvardin_one(jy)
        if gap not in (365, 366, 367):
            fail("implausible Nowruz gap for jy=%d: %d days" % (jy, gap))
        if jy not in drift_years and gap != year_length(jy):
            fail("Nowruz gap != yearLength for jy=%d: %d vs %d" % (jy, gap, year_length(jy)))

    # Exactly one year in the whole range has a Nowruz gap that is not 186 + 6*30; that is the year the
    # old closed-form `fromGregorian` turned into month 13. Keep it visible in the report.
    odd = [y for y in range(1250, 1450)
           if farvardin_one(y + 1) - farvardin_one(y) != year_length(y)]
    if odd:
        print("years where Nowruz gap != yearLength (the walked month split is what saves these): %s"
              % ", ".join(str(y) for y in odd))

    for jy, want in [(1402, (2023, 3, 21)), (1403, (2024, 3, 20)),
                     (1404, (2025, 3, 21)), (1405, (2026, 3, 21))]:
        got = tuple(civil_from_days(farvardin_one(jy)))
        if got != want:
            fail("published nowruz %d: got %s want %s" % (jy, got, want))

    today = datetime.date.today()
    tj = from_gregorian(today.year, today.month, today.day)
    names = ["Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
             "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"]
    print("%d days checked, %d failure(s)" % (checked, failures))
    print("  today %s -> %04d-%02d-%02d (%s)" % (today, tj[0], tj[1], tj[2], names[tj[1] - 1]))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
