package com.minim.music.ui

import com.minim.music.data.stats.DayStats
import com.minim.music.data.stats.RecapRules

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
        fun eligible(recap: DayStats?, popupShownDay: Int, viewedDay: Int): Boolean =
            RecapRules.shouldPopup(recap, popupShownDay, viewedDay)
    }
}
