package com.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeTest {

    private val chrome = """
        ╭──────────────────────────────────────────────╮
        │ > Try "fix the failing test"                 │
        ╰──────────────────────────────────────────────╯
          ? for shortcuts
    """.trimIndent()

    @Test fun readsOnlyNewAgentText() {
        val before = "Welcome to Claude Code\n$chrome"
        val after = "Welcome to Claude Code\n> fix the failing test\n\n⏺ I found the bug in auth.ts.\n⏺ Fixed it; tests pass now.\n$chrome"
        val out = HandsFree.extractNew(before, after, "fix the failing test")
        assertEquals("I found the bug in auth.ts. Fixed it; tests pass now.", out)
    }

    @Test fun redrawnLinesAreNotRepeated() {
        val before = "line one\nline two\n$chrome"
        val after = "line one\nline two\n$chrome\n⏺ new answer\n$chrome"
        assertEquals("new answer", HandsFree.extractNew(before, after, null))
    }

    @Test fun nothingNewIsBlank() {
        val t = "hello world\n$chrome"
        assertEquals("", HandsFree.extractNew(t, t, null))
    }

    @Test fun filters() {
        assertFalse(HandsFree.keep(HandsFree.cleanLine("│ ╰──── │")))
        assertFalse(HandsFree.keep("esc to interrupt"))
        assertFalse(HandsFree.keep(HandsFree.cleanLine("✻ Thinking…")))
        assertTrue(HandsFree.keep(HandsFree.cleanLine("⏺ Done. Run npm test.")))
        assertEquals("Done. Run npm test.", HandsFree.cleanLine("⏺ Done. Run npm test."))
    }

    @Test fun speakerChunksLongText() {
        val text = (1..900).joinToString(" ") { "word$it." }
        val parts = Speaker.chunk(text)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= 3500 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }
}
