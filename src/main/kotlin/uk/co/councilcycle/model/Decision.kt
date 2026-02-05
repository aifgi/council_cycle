package uk.co.councilcycle.model

import kotlinx.serialization.Serializable

@Serializable
data class Decision(
    val councilId: String,
    val councilName: String,
    val meetingTitle: String,
    val meetingUrl: String,
    val summary: String,
    val category: DecisionCategory,
    val relevanceScore: Int,
)

@Serializable
enum class DecisionCategory {
    CYCLE_LANE,
    TRAFFIC_FILTER,
    LTN,
    PEDESTRIAN_CROSSING,
    SPEED_LIMIT,
    ROAD_CLOSURE,
    BUS_LANE,
    PAVEMENT_WIDENING,
    SCHOOL_STREET,
    OTHER_ACTIVE_TRAVEL,
}
