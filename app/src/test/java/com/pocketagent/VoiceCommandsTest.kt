package com.pocketagent

import com.pocketagent.VoiceCommands.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandsTest {

    private fun parse(s: String) = VoiceCommands.parse(s)

    @Test fun opensAgents() {
        assertTrue(parse("Open Claude.") is Cmd.OpenClaude)
        assertTrue(parse("open cloud code") is Cmd.OpenClaude)
        assertTrue(parse("Please open Claude Code") is Cmd.OpenClaude)
        assertTrue(parse("Open Codex!") is Cmd.OpenCodex)
        assertTrue(parse("open code x") is Cmd.OpenCodex)
        assertTrue(parse("Open the terminal") is Cmd.OpenShell)
        assertTrue(parse("ubuntu shell") is Cmd.OpenShell)
    }

    @Test fun newFolder() {
        val a = parse("Create a new folder")
        assertTrue(a is Cmd.NewFolder); assertNull((a as Cmd.NewFolder).name)
        val b = parse("make a new folder called my cool app") as Cmd.NewFolder
        assertEquals("my cool app", b.name)
        assertEquals("my-cool-app", VoiceCommands.folderName(b.name!!))
        assertEquals("todo-list", VoiceCommands.folderName("Call it Todo List."))
        // "project" alone without a create verb is not a folder request
        assertFalse(parse("open the codex project") is Cmd.NewFolder)
    }

    @Test fun controlWords() {
        assertTrue(parse("hands free on") is Cmd.HandsFree && (parse("hands free on") as Cmd.HandsFree).on == true)
        assertTrue((parse("turn hands-free off") as Cmd.HandsFree).on == false)
        assertTrue(parse("Stop reading.") is Cmd.StopReading)
        assertTrue(parse("read that again") is Cmd.Repeat)
        assertTrue(parse("Cancel") is Cmd.Cancel)
        assertTrue(parse("never mind") is Cmd.Cancel)
        assertTrue(parse("Settings") is Cmd.Settings)
        assertTrue(parse("what can I say?") is Cmd.Help)
        assertTrue(parse("escape") is Cmd.Escape)
        assertTrue(parse("blah blah") is Cmd.Unknown)
    }

    @Test fun splitSend() {
        assertEquals("Fix the failing test" to true, VoiceCommands.splitSend("Fix the failing test, send to Claude Code."))
        assertEquals("fix the failing test" to true, VoiceCommands.splitSend("fix the failing test send to codex"))
        assertEquals("add a readme" to true, VoiceCommands.splitSend("add a readme. Sent to cloud"))
        assertEquals("yes" to true, VoiceCommands.splitSend("yes, send it"))
        assertEquals("" to true, VoiceCommands.splitSend("send to Claude"))
        assertEquals("run the tests" to false, VoiceCommands.splitSend("run the tests"))
        // "send" in the middle of a sentence is content, not a command
        assertEquals("send the email to Bob" to false, VoiceCommands.splitSend("send the email to Bob"))
    }

    @Test fun wakeWord() {
        assertTrue(VoiceCommands.matchesWake("Hey, Pat."))
        assertTrue(VoiceCommands.matchesWake("hey pad"))
        assertTrue(VoiceCommands.matchesWake("Hay Pat, open Claude"))
        assertTrue(VoiceCommands.matchesWake("Heypat"))
        assertFalse(VoiceCommands.matchesWake("hey there"))
        assertFalse(VoiceCommands.matchesWake("pattern"))
        assertFalse(VoiceCommands.matchesWake("you"))
        assertTrue(VoiceCommands.matchesWake("ok computer go", "okay computer"))
    }

    @Test fun hallucinations() {
        assertTrue(Listener.isHallucination("Thank you."))
        assertTrue(Listener.isHallucination("you"))
        assertFalse(Listener.isHallucination("open claude"))
    }
}
