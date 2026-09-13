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

    // ---- voice control

    /** Background "hey Pat" detector (WakeWordService). */
    var wakeWordEnabled: Boolean
        get() = p.getBoolean("wake_word", false)
        set(v) = p.edit().putBoolean("wake_word", v).apply()

    val wakePhrase: String
        get() = (p.getString("wake_phrase", VoiceCommands.DEFAULT_WAKE) ?: VoiceCommands.DEFAULT_WAKE).trim().ifBlank { VoiceCommands.DEFAULT_WAKE }

    /** Every new Claude Code / Codex / shell session starts in hands-free mode. */
    var handsFreeDefault: Boolean
        get() = p.getBoolean("hands_free_default", false)
        set(v) = p.edit().putBoolean("hands_free_default", v).apply()

    /** Sessions opened by voice command start hands-free even when [handsFreeDefault] is off. */
    val voiceLaunchHandsFree get() = p.getBoolean("voice_launch_hands_free", true)
}
