package orchestrator

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhaseResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes fetch response`() {
        val raw = """{"type":"fetch","urls":["https://example.com/committees"],"reason":"Following link"}"""
        val response = json.decodeFromString<PhaseResponse>(raw)
        assertEquals(
            PhaseResponse.Fetch(
                urls = listOf("https://example.com/committees"),
                reason = "Following link",
            ),
            response,
        )
    }

    @Test
    fun `deserializes committee_pages_found response`() {
        val raw = """{"type":"committee_pages_found","committees":[{"name":"Planning","url":"https://example.com/planning"}]}"""
        val response = json.decodeFromString<PhaseResponse>(raw)
        assertEquals(
            PhaseResponse.CommitteePagesFound(
                committees = listOf(CommitteeUrl(name = "Planning", url = "https://example.com/planning")),
            ),
            response,
        )
    }

    @Test
    fun `deserializes meetings_found response`() {
        val raw = """
            {
              "type": "meetings_found",
              "meetings": [
                {"date": "2026-03-15", "title": "Planning Meeting", "agendaUrl": "https://example.com/agenda"},
                {"date": "2026-04-10", "title": "Special Meeting", "agendaUrl": null}
              ]
            }
        """.trimIndent()
        val response = json.decodeFromString<PhaseResponse>(raw) as PhaseResponse.MeetingsFound
        assertEquals(2, response.meetings.size)
        assertEquals("2026-03-15", response.meetings[0].date)
        assertEquals("Planning Meeting", response.meetings[0].title)
        assertEquals("https://example.com/agenda", response.meetings[0].agendaUrl)
        assertEquals("2026-04-10", response.meetings[1].date)
        assertNull(response.meetings[1].agendaUrl)
    }

    @Test
    fun `deserializes meetings_found with missing agendaUrl`() {
        val raw = """{"type":"meetings_found","meetings":[{"date":"2026-01-01","title":"Meeting"}]}"""
        val response = json.decodeFromString<PhaseResponse>(raw) as PhaseResponse.MeetingsFound
        assertEquals(1, response.meetings.size)
        assertNull(response.meetings[0].agendaUrl)
    }

    @Test
    fun `deserializes agenda_analyzed response with schemes`() {
        val raw = """
            {
              "type": "agenda_analyzed",
              "schemes": [
                {
                  "title": "New Cycle Lane on High Street",
                  "topic": "cycle lanes",
                  "summary": "Proposal for protected cycle lane",
                  "meetingDate": "2026-03-15",
                  "committeeName": "Planning Committee"
                }
              ]
            }
        """.trimIndent()
        val response = json.decodeFromString<PhaseResponse>(raw) as PhaseResponse.AgendaAnalyzed
        assertEquals(1, response.schemes.size)
        assertEquals("New Cycle Lane on High Street", response.schemes[0].title)
        assertEquals("cycle lanes", response.schemes[0].topic)
        assertEquals("Proposal for protected cycle lane", response.schemes[0].summary)
        assertEquals("2026-03-15", response.schemes[0].meetingDate)
        assertEquals("Planning Committee", response.schemes[0].committeeName)
    }

    @Test
    fun `deserializes agenda_analyzed with empty schemes`() {
        val raw = """{"type":"agenda_analyzed","schemes":[]}"""
        val response = json.decodeFromString<PhaseResponse>(raw) as PhaseResponse.AgendaAnalyzed
        assertEquals(0, response.schemes.size)
    }
}
