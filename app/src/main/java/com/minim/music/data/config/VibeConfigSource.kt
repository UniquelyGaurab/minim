package com.minim.music.data.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Festivals, announcements, switches and the update lock, as the screens read them.
 *
 * 5.0.0 filled this from the Firestore document `config/app`. 5.10.1 (V6) has no Firebase, so
 * [config] stays at the built-in defaults and [refresh] does nothing; nothing here touches the
 * network. Kept so the screens that read it don't change.
 */
class VibeConfigSource {
    private val _config = MutableStateFlow(VibeConfig.DEFAULT)
    val config: StateFlow<VibeConfig> = _config

    @Suppress("UNUSED_PARAMETER")
    fun refresh(force: Boolean = false) = Unit
}
