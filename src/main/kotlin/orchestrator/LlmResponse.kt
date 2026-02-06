package orchestrator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface LlmResponse {

    @Serializable
    @SerialName("fetch")
    data class Fetch(
        val urls: List<String>,
        val reason: String,
    ) : LlmResponse

    @Serializable
    @SerialName("found")
    data class Found(
        val committeeName: String,
        val nextMeetingDate: String? = null,
        val nextMeetingTime: String? = null,
        val nextMeetingLocation: String? = null,
        val agendaUrl: String? = null,
        val summary: String,
    ) : LlmResponse
}
