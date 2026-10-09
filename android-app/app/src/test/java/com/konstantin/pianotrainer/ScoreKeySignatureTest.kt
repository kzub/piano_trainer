package com.konstantin.pianotrainer

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreKeySignatureTest {
    @Test
    fun keepsKeySignatureContextAcrossMeasuresAndUpdatesOnChange() {
        val musicXml = """
            <score-partwise>
              <part id="P1">
                <measure number="1"><attributes><key><fifths>0</fifths></key></attributes></measure>
                <measure number="2"><attributes><key><fifths>-5</fifths></key></attributes></measure>
                <measure number="3"><note><pitch><step>C</step></pitch></note></measure>
                <measure number="4"><attributes><key><fifths>2</fifths></key></attributes></measure>
              </part>
            </score-partwise>
        """.trimIndent()

        val changes = extractKeySignaturesByMeasure(musicXml)

        assertEquals(mapOf(1 to 0, 2 to -5, 3 to -5, 4 to 2), changes)
        val timeline = PracticeTimeline(ppq = 480, measures = emptyList(), groups = emptyList(), keySignaturesByMeasure = changes)
        assertEquals(-5, timeline.keyFifthsAt(3))
        assertEquals(2, timeline.keyFifthsAt(4))
        assertEquals(0, timeline.keyFifthsAt(null))
    }

    @Test
    fun readsNamespacedMusicXmlWithSingleQuotedMeasureAttributes() {
        val musicXml = """
            <m:score-partwise xmlns:m="urn:musicxml">
              <m:part id="P1">
                <m:measure number='1'><m:attributes><m:key><m:fifths>-5</m:fifths></m:key></m:attributes></m:measure>
                <m:measure number='2'><m:note /></m:measure>
              </m:part>
            </m:score-partwise>
        """.trimIndent()

        assertEquals(mapOf(1 to -5, 2 to -5), extractKeySignaturesByMeasure(musicXml))
    }

    @Test
    fun ignoresEmptyOrMalformedMusicXml() {
        assertEquals(emptyMap<Int, Int>(), extractKeySignaturesByMeasure(""))
        assertEquals(emptyMap<Int, Int>(), extractKeySignaturesByMeasure("not MusicXML"))
    }

    @Test
    fun displayedNamesFollowTheScoresKeySignature() {
        assertEquals("Do♯4", midiSolfegeName(61, 0))
        assertEquals("Re♭4", midiSolfegeName(61, -5))
        assertEquals("Do4", midiSolfegeName(60, -5))
        assertEquals("Re♮4", midiSolfegeName(62, -5))
        assertEquals("Do♭4", midiSolfegeName(59, -6))
        assertEquals("Si♯3", midiSolfegeName(60, 7))
    }
}
