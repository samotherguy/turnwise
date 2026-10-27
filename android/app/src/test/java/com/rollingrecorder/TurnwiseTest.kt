package com.rollingrecorder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnwiseTest {

    @Test
    fun noteParsesTranscribedAndPendingShapes() {
        // Transcribed: speakers is a dict, segments and action items present.
        val done = Parse.note(JSONObject("""
            {"id":"2026-10-27-220419-clip","title":"Standup","created":"2026-10-27T21:58:26","folder":"",
             "duration":287.4,"status":{"state":"done","detail":"","at":1.7e9},"warning":null,"backend":"qwen",
             "summary":"- **Ship** it","speakers":{"SPEAKER_00":"Rohan","SPEAKER_01":null},
             "segments":[{"start":1.5,"end":4,"speaker":"SPEAKER_01","text":"haan","hinglish":"haan","english":"yes"}],
             "action_items":[{"owner":"","item":"Send deck"},{"owner":"Priya","item":"Book room"}]}
        """))
        assertEquals("Rohan", done.speakerName("SPEAKER_00"))
        assertEquals("Speaker 2", done.speakerName("SPEAKER_01"))
        assertEquals(1, done.segments.size)
        assertNull(done.actionItems[0].owner)
        assertEquals("Priya", done.actionItems[1].owner)
        assertNull(done.warning)
        // Still transcribing: speakers is the summary's list, no duration, no segments.
        val busy = Parse.note(JSONObject("""
            {"id":"x","title":"Clip","created":"2026-10-27T22:15:12","folder":"","duration":null,
             "status":{"state":"denoise","detail":"0/8","started":1793110215.1},"speakers":[],"summary":""}
        """))
        assertTrue(busy.status.busy)
        assertEquals(1793110215.1, busy.status.started!!, 0.001)
        assertNull(busy.duration)
        assertTrue(busy.speakers.isEmpty() && busy.segments.isEmpty())
    }

    @Test
    fun markdownKeepsBoldBulletsAndCitations() {
        val b = Md.blocks("Intro **bold [2]** text [1]\n\n- item one\n  * item two")
        assertEquals(3, b.size)
        assertEquals(listOf(false, true, true), b.map { it.bullet })
        assertEquals(
            listOf(Md.Span("Intro "), Md.Span("bold ", true), Md.Span("[2]", true, 2), Md.Span(" text "), Md.Span("[1]", false, 1)),
            b[0].spans,
        )
        assertEquals("item two", b[2].spans.single().text)
    }

    @Test
    fun speakerHuesMatchTheWebApp() {
        // Expected values computed by the web app's own hue() in node, with palette ["Priya", "Rohan"].
        val p = listOf("Priya", "Rohan")
        val expected = mapOf("Priya" to 212, "Rohan" to 340, "Speaker 1" to 230, "Speaker 12" to 358, "Meera" to 247, "Arjun" to 171, "अमित" to 233)
        expected.forEach { (name, hue) -> assertEquals(name, hue, Voices.hue(name, p)) }
    }

    @Test
    fun turnUnderPlayheadAndTranscriptModes() {
        val segs = listOf(Segment(0.0, 2.0, "S", "a", null, null), Segment(5.0, 7.0, "S", "b", "b-h", "b-e"))
        assertEquals(-1, turnAt(segs, -1.0))
        assertEquals(0, turnAt(segs, 4.9))
        assertEquals(1, turnAt(segs, 4.995))   // 10 ms slack, as the web player
        assertEquals("b-h" to "b-e", turnLines(segs[1], "hinglish"))
        assertEquals("b-e" to null, turnLines(segs[1], "english"))
        assertEquals("a" to null, turnLines(segs[0], "english"))
        assertEquals("b" to null, turnLines(segs[1], "text"))
    }
}
