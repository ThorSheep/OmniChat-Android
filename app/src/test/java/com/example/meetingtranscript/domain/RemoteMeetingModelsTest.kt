package com.example.meetingtranscript.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RemoteMeetingModelsTest {
    private val time = Instant.parse("2026-11-20T03:00:00Z")

    @Test fun finalSegmentKeepsIdentityAndOriginalText() {
        val segment = TranscriptSegment(text = "  我們 review MVP  ", startedAt = time, endedAt = time.plusSeconds(2))
        val submission = TranscriptSubmission.from(segment)
        assertEquals(segment.id, submission.clientMsgId)
        assertEquals("我們 review MVP", submission.text)
        assertEquals("  我們 review MVP  ", segment.text)
        assertEquals(time, submission.clientTs)
        assertEquals(submission, TranscriptSubmission.from(segment))
    }

    @Test fun languageFallsBackWithoutChangingText() {
        for (language in listOf(null, "en-US", "ja", "zh-TW")) {
            assertEquals("zh-TW", TranscriptSubmission.create("id", "hello", language, time).lang)
        }
        assertEquals("en", TranscriptSubmission.create("id", "hello", "en", time).lang)
    }

    @Test fun invalidTextIsRejectedAndBoundaryIsAccepted() {
        for (text in listOf("   ", "x".repeat(2001))) {
            assertThrows(IllegalArgumentException::class.java) { TranscriptSubmission.create("id", text, null, time) }
        }
        assertEquals(2000, TranscriptSubmission.create("id", "x".repeat(2000), null, time).text.length)
    }

    @Test fun invalidIdIsRejected() {
        for (id in listOf("", "x".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { TranscriptSubmission.create(id, "text", null, time) }
        }
    }

    @Test fun timelineMergesReplayAndSortsByServerTimeThenId() {
        val timeline = MessageTimeline("server", "room")
        val later = message("c", time.plusSeconds(1))
        timeline.merge(listOf(later, message("b", time)))
        val result = timeline.merge(listOf(later, message("a", time)))
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test fun foreignRoomBatchDoesNotPartiallyUpdateTimeline() {
        val timeline = MessageTimeline("server", "room")
        assertThrows(IllegalArgumentException::class.java) {
            timeline.merge(listOf(message("a", time), message("b", time).copy(roomId = "other")))
        }
        assertTrue(timeline.merge(emptyList()).isEmpty())
    }

    private fun message(id: String, timestamp: Instant) = MeetingMessage(id, "room", MessageSenderType.USER, null, "text", "zh-TW", time, timestamp)
}
