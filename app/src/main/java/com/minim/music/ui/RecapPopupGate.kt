package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.RecapRules
import com.minim.music.data.stats.RepriseClock

/**
 * v4.9.1: when the recap popup may slide up. Pure, so it's unit-tested.
 *
 * - Once per recap, never for a quiet day, and not when the recap was already looked at
 *   (that part is [RecapRules.shouldPopup]).
 * - Night-owl rule: never on top of Now Playing. If they're on Now Playing (or any other screen)
 *   or music is playing when it would show, it's held until they're back on Home.
 *   "Back on Home" = they left Home (Now Playing, Search, another tab...) and came back to it.
 *   Music stopping while they sit on Home also lets it through: they're done listening.
 * - [busy] (a sheet or the story is open, the app is in the background, the welcome screens)
 *   just waits; it doesn't count as leaving Home.
 */
class RecapPopupGate {
    enum class Action { NONE, HOLD, SHOW }

    /** Something to show that's waiting for Home. */
    var isHeld = false
        private set
    private var leftHome = false

    fun update(
        eligible: Boolean,
        onHome: Boolean,
        nowPlayingOpen: Boolean,
        musicPlaying: Boolean,
        busy: Boolean = false
    ): Action {
        if (!eligible) {
            isHeld = false
            leftHome = false
            return Action.NONE
        }
        if (busy) {
            isHeld = true
            return Action.HOLD
        }
        if (nowPlayingOpen || !onHome) {
            isHeld = true
            leftHome = true
            return Action.HOLD
        }
        if (musicPlaying && !leftHome) {
            isHeld = true
            return Action.HOLD
        }
        // Stays SHOW until the caller marks the recap as shown (eligible turns false)
        isHeld = false
        return Action.SHOW
    }

    companion object {
        /** 6.9.0: the daily popup is gone. The Weekly and Monthly popups replace it (part B). */
        const val DAILY_POPUP = false

        fun eligible(recap: DayStats?, popupShownDay: Int, viewedDay: Int): Boolean =
            RecapRules.shouldPopup(recap, popupShownDay, viewedDay)

        /** 6.9.0 part B: how long after its drop a Weekly or Monthly popup may still show. */
        const val REPRISE_POPUP_DAYS = 3L

        /**
         * 6.9.0 part B: the Weekly or Monthly Reprise popup that's due at [now], or null. Once each
         * ([shownWeek] / [shownMonth] hold the keys already shown or opened), only for a period that
         * met its minimum, and only in the first few days after it dropped. The month goes first.
         */
        fun dueReprise(
            now: Long,
            weekTotalMs: Long,
            monthTotalMs: Long,
            shownWeek: String,
            shownMonth: String,
            zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
        ): RepriseDue? {
            val window = REPRISE_POPUP_DAYS * 86_400_000L
            val ym = RepriseClock.latestMonth(now, zone)
            val monthKey = monthKey(ym)
            val monthAt = RepriseClock.monthDropAt(ym, zone)
            if (now >= monthAt && now - monthAt < window && monthKey != shownMonth && RepriseClock.monthUnlocked(monthTotalMs)) {
                return RepriseDue(false, ym.atDay(1), monthKey, monthTotalMs)
            }
            val week = RepriseClock.latestWeek(now, zone)
            val weekKey = weekKey(week)
            val weekAt = RepriseClock.weeklyReadyAt(week, zone)
            if (now >= weekAt && now - weekAt < window && weekKey != shownWeek && RepriseClock.weekUnlocked(weekTotalMs)) {
                return RepriseDue(true, week, weekKey, weekTotalMs)
            }
            return null
        }

        /** The same keys as RepriseScope.key. */
        fun weekKey(start: java.time.LocalDate): String = "w${com.minim.music.data.stats.Days.key(start)}"
        fun monthKey(ym: java.time.YearMonth): String = "m${ym.year * 100 + ym.monthValue}"
    }

    /** 6.9.0 part B: a Weekly ([isWeek]) or Monthly Reprise popup to show. */
    data class RepriseDue(val isWeek: Boolean, val start: java.time.LocalDate, val key: String, val totalMs: Long)
}
