package com.arena.carlauncher.util

import java.util.Calendar

/**
 * Persian (Jalali / Shamsi) calendar — arithmetic 33-year leap cycles.
 *
 * Why not `android.icu.util.PersianCalendar` or a `fa_IR` [Calendar]? The ICU class is
 * `@hide`/deprecated on Android, and locale-driven `Calendar` returns whatever the OEM firmware
 * felt like configuring — on several Chinese/Unisoc head units it silently keeps reporting
 * Gregorian fields. This implementation is deterministic on every device:
 *
 *  * [jalCal] (2820-year break table) gives the Gregorian March day of 1 Farvardin.
 *  * Day counting uses Howard Hinnant's `days_from_civil` / `civil_from_days`, so Gregorian leap
 *    rules come from real calendar arithmetic instead of magic epoch constants.
 *
 * [tools/validate_jalali.py] runs the same arithmetic in Python over every day from 1900-01-01 to
 * 2100-12-31 (73,414 days): exact round-trips, month lengths that always sum to the year length,
 * weekdays that agree with [java.util.Calendar], and published Nowruz instants (1402 → 2023-03-21,
 * 1403 → 2024-03-20, 1404 → 2025-03-21, 1405 → 2026-03-21).
 *
 * Known deviation, inherited from the arithmetic 2820-year fit rather than from this port: in 51 years
 * between 1250 and 1338 SH (1871–1959 AD) the table places Nowruz one day late. From 1339 SH onward it
 * is exact for every year in the range, which is all a dashboard ever renders.
 */
object Jalali {

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635,
        2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    )

    val MONTHS_FA = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    val MONTHS_EN = arrayOf(
        "Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand"
    )

    /** Iranian week: Saturday first. */
    val WEEKDAYS_FA =
        arrayOf("شنبه", "یک‌شنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه")
    val WEEKDAYS_SHORT_FA = arrayOf("ش", "ی", "د", "س", "چ", "پ", "ج")
    val WEEKDAYS_EN =
        arrayOf("Saturday", "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday")

    /** jy/jm/jd are 1-based; [weekday] is Saturday-first (0 = Saturday). */
    data class Date(val jy: Int, val jm: Int, val jd: Int, val weekday: Int) {
        fun monthName(fa: Boolean) = if (fa) MONTHS_FA[jm - 1] else MONTHS_EN[jm - 1]
        fun weekdayName(fa: Boolean) = if (fa) WEEKDAYS_FA[weekday] else WEEKDAYS_EN[weekday]
        val iso: String get() = String.format("%04d-%02d-%02d", jy, jm, jd)
        override fun toString(): String = iso
    }

    /** @return [leapIndex, gregorian year of Farvardin 1, March day of Farvardin 1] */
    private fun jalCal(jy: Int): IntArray {
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 20
        val gy = jy + 621

        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += fdiv(jump, 33) * 8 + fdiv(fmod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp
        leapJ += fdiv(n, 33) * 8 + fdiv(fmod(n, 33) + 3, 4)
        if (fmod(jump, 33) == 4 && jump - n == 4) leapJ += 1

        val leapG = fdiv(gy, 4) - fdiv(fdiv(gy, 100) + 1, 4) * 3 - 150
        val march = 20 + leapJ - leapG

        if (jump - n < 6) n = n - jump + fdiv(jump + 4, 33) * 33
        var leap = fmod(fmod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return intArrayOf(leap, gy, march)
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date. */
    fun daysFromCivil(yIn: Int, m: Int, d: Int): Int {
        var y = yIn
        if (m <= 2) y -= 1
        val era = fdiv(y, 400)
        val yoe = y - era * 400
        val mp = if (m > 2) m - 3 else m + 9
        val doy = fdiv(153 * mp + 2, 5) + d - 1
        val doe = yoe * 365 + fdiv(yoe, 4) - fdiv(yoe, 100) + doy
        return era * 146097 + doe - 719468
    }

    /** Inverse of [daysFromCivil] → `[year, month, day]`. */
    fun civilFromDays(zIn: Int): IntArray {
        val z = zIn + 719468
        val era = fdiv(z, 146097)
        val doe = z - era * 146097
        val yoe = fdiv(doe - fdiv(doe, 1460) + fdiv(doe, 36524) - fdiv(doe, 146096), 365)
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + fdiv(yoe, 4) - fdiv(yoe, 100))
        val mp = fdiv(5 * doy + 2, 153)
        val d = doy - fdiv(153 * mp + 2, 5) + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return intArrayOf(year, m, d)
    }

    /** Epoch day of 1 Farvardin of Persian year [jy]. */
    fun farvardinOneEpochDay(jy: Int): Int {
        val r = jalCal(clampYear(jy))
        return daysFromCivil(r[1], 3, r[2])
    }

    private fun clampYear(jy: Int) = jy.coerceIn(BREAKS.first(), BREAKS.last() - 1)

    fun isLeapYear(jy: Int): Boolean = jalCal(clampYear(jy))[0] == 0

    fun monthLength(jy: Int, jm: Int): Int = when {
        jm in 1..6 -> 31
        jm in 7..11 -> 30
        jm == 12 -> if (isLeapYear(jy)) 30 else 29
        else -> 31
    }

    fun yearLength(jy: Int) = if (isLeapYear(jy)) 366 else 365

    /** Saturday-first weekday index for an epoch day (1970-01-01 was a Thursday = 5). */
    fun weekdayOf(epochDay: Int): Int = fmod(epochDay + 5, 7)

    /**
     * Gregorian -> Jalali by day counting rather than the usual `k <= 185 ? k/31 : (k-186)/30`
     * closed form.
     *
     * That shortcut assumes every year is 186 + 150 days long, so in a year whose 1 Farvardin the
     * 2820-year break table places one day late, Esfand overflows into a phantom month 13 (the old
     * `coerceIn(1, 12)` then printed it as month 1 day 1 — a silently *impossible* date). Walking the
     * real month lengths and re-anchoring at each year boundary cannot produce an out-of-range result
     * at all, and it is still only 12 iterations of integer arithmetic.
     */
    fun fromGregorian(gy: Int, gm: Int, gd: Int): Date {
        val t = daysFromCivil(gy, gm, gd)
        var jy = clampYear(gy - 621)
        // Re-anchor `k` against the year's own epoch every time: the table is astronomical, so the gap
        // between two consecutive Nowruzes is not always exactly `yearLength`.
        var k = t - farvardinOneEpochDay(jy)
        var guard = 0
        while (k < 0 && guard++ < 4) {
            jy = clampYear(jy - 1)
            k = t - farvardinOneEpochDay(jy)
        }
        guard = 0
        while (k >= yearLength(jy) && guard++ < 4) {
            jy = clampYear(jy + 1)
            k = t - farvardinOneEpochDay(jy)
        }
        var jm = 1
        while (jm < 12 && k >= monthLength(jy, jm)) {
            k -= monthLength(jy, jm)
            jm++
        }
        return Date(jy, jm, k + 1, weekdayOf(t))
    }

    fun fromEpochDay(epochDay: Int): Date {
        val g = civilFromDays(epochDay)
        return fromGregorian(g[0], g[1], g[2])
    }

    /** Gregorian `[year, month, day]` for a Persian date. */
    fun toGregorian(date: Date): IntArray {
        val dayOfYear = (date.jm - 1) * 31 - fdiv(date.jm, 7) * (date.jm - 7) + date.jd - 1
        return civilFromDays(farvardinOneEpochDay(date.jy) + dayOfYear)
    }

    fun epochDayOf(date: Date): Int = farvardinOneEpochDay(date.jy) +
        (date.jm - 1) * 31 - fdiv(date.jm, 7) * (date.jm - 7) + date.jd - 1

    fun today(): Date = fromEpochDay(daysFromCivilNow())

    private fun daysFromCivilNow(): Int {
        val c = Calendar.getInstance()
        return daysFromCivil(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** "۱۶ مهر ۱۴۰۵" / "16 Mehr 1405". */
    fun format(date: Date, fa: Boolean, faDigits: Boolean, withYear: Boolean = true): String {
        val body = if (withYear) "${date.jd} ${date.monthName(fa)} ${date.jy}"
        else "${date.jd} ${date.monthName(fa)}"
        return if (faDigits) Format.toPersianDigits(body) else body
    }

    /** "پنج‌شنبه ۱۶ مهر ۱۴۰۵". */
    fun formatLong(date: Date, fa: Boolean, faDigits: Boolean): String {
        val s = "${date.weekdayName(fa)} ${date.jd} ${date.monthName(fa)} ${date.jy}"
        return if (faDigits) Format.toPersianDigits(s) else s
    }

    /** Short numeric form used in the top bar: "1405/07/16". */
    fun formatShort(date: Date, faDigits: Boolean): String =
        if (faDigits) Format.toPersianDigits(date.iso) else date.iso

    /** 0..1 through the Persian year — powers the year-progress ring on the clock card. */
    fun yearProgress(date: Date): Float {
        val start = farvardinOneEpochDay(date.jy)
        val end = farvardinOneEpochDay(date.jy + 1)
        if (end <= start) return 0f
        val now = epochDayOf(date)
        return Format.clamp((now - start).toFloat() / (end - start).toFloat(), 0f, 1f)
    }

    /** Days left in the Persian year — shown under the clock. */
    fun daysLeftInYear(date: Date): Int {
        val end = farvardinOneEpochDay(date.jy + 1)
        return (end - epochDayOf(date)).coerceAtLeast(0)
    }

    private fun fdiv(a: Int, b: Int) = Math.floorDiv(a, b)
    private fun fmod(a: Int, b: Int) = Math.floorMod(a, b)
}
