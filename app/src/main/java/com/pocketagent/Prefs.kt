package com.pocketagent

import android.content.Context
import androidx.preference.PreferenceManager

class Prefs(c: Context) {
    private val p = PreferenceManager.getDefaultSharedPreferences(c)

    var fontSizeSp: Int
        get() = p.getInt("font_size", 12).coerceIn(8, 24)
        set(v) = p.edit().putInt("font_size", v.coerceIn(8, 24)).apply()

    val voiceAutoEnter get() = p.getBoolean("voice_auto_enter", false)
    val hapticKeys get() = p.getBoolean("haptic_keys", true)
    val prootNoSeccomp get() = p.getBoolean("proot_no_seccomp", false)

    /** true = raw key input (no IME suggestions, hides some keyboards' mic key); false = standard text field. */
    val charBasedInput get() = p.getBoolean("terminal_char_input", false)

    /** auto | system | whisper | keyboard */
    var voiceEngine: String
        get() = p.getString("voice_engine", "auto") ?: "auto"
        set(v) = p.edit().putString("voice_engine", v).apply()

    /** tiny | base | small */
    val whisperModel get() = p.getString("whisper_model", "base") ?: "base"

    /** BCP-47 tag such as en, zh, or "auto". */
    val voiceLanguage get() = p.getString("voice_language", "auto") ?: "auto"
}
