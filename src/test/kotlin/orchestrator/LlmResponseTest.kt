package orchestrator

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmResponseTest {

    @Test
    fun `deserializes fetch response`() {
        val json = """{"type":"fetch","urls":["https://example.com/committees"],"reason":"Following committee link"}"""
        val response = Json.decodeFromString<LlmResponse>(json)
        assertEquals(
            LlmResponse.Fetch(
                urls = listOf("https://example.com/committees"),
                reason = "Following committee link",
            ),
            response,
        )
    }

    @Test
    fun `deserializes found response with all fields`() {
        val json = """
            {
              "type": "found",
              "committeeName": "Planning Committee",
              "nextMeetingDate": "2026-03-15",
              "nextMeetingTime": "14:00",
              "nextMeetingLocation": "Town Hall",
              "agendaUrl": "https://example.com/agenda",
              "summary": "Next planning meeting on March 15"
            }
        """.trimIndent()
        val response = Json.decodeFromString<LlmResponse>(json) as LlmResponse.Found
        assertEquals("Planning Committee", response.committeeName)
        assertEquals("2026-03-15", response.nextMeetingDate)
        assertEquals("14:00", response.nextMeetingTime)
        assertEquals("Town Hall", response.nextMeetingLocation)
        assertEquals("https://example.com/agenda", response.agendaUrl)
        assertEquals("Next planning meeting on March 15", response.summary)
    }

    @Test
    fun `deserializes found response with null optional fields`() {
        val json = """{"type":"found","committeeName":"Planning","summary":"No details found"}"""
        val response = Json.decodeFromString<LlmResponse>(json) as LlmResponse.Found
        assertEquals("Planning", response.committeeName)
        assertNull(response.nextMeetingDate)
        assertNull(response.nextMeetingTime)
        assertNull(response.nextMeetingLocation)
        assertNull(response.agendaUrl)
        assertEquals("No details found", response.summary)
    }
}
