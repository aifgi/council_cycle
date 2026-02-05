package uk.co.councilcycle.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DecisionTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `Decision serialization round-trips correctly`() {
        val decision = Decision(
            councilId = "oxford",
            councilName = "Oxford City Council",
            meetingTitle = "Transport Committee",
            meetingUrl = "https://example.com/meeting/123",
            summary = "Approved new cycle lane on Cowley Road",
            category = DecisionCategory.CYCLE_LANE,
            relevanceScore = 8,
        )

        val encoded = json.encodeToString(Decision.serializer(), decision)
        val decoded = json.decodeFromString(Decision.serializer(), encoded)

        assertEquals(decision, decoded)
    }

    @Test
    fun `DecisionCategory values cover expected types`() {
        val expected = setOf(
            "CYCLE_LANE", "TRAFFIC_FILTER", "LTN", "PEDESTRIAN_CROSSING",
            "SPEED_LIMIT", "ROAD_CLOSURE", "BUS_LANE", "PAVEMENT_WIDENING",
            "SCHOOL_STREET", "OTHER_ACTIVE_TRAVEL",
        )

        val actual = DecisionCategory.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }
}
