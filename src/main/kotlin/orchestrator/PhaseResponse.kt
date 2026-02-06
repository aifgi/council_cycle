package orchestrator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface PhaseResponse {

    @Serializable
    @SerialName("fetch")
    data class Fetch(
        val urls: List<String>,
        val reason: String,
    ) : PhaseResponse

    @Serializable
    @SerialName("committee_page_found")
    data class CommitteePageFound(
        val url: String,
    ) : PhaseResponse

    @Serializable
    @SerialName("meetings_found")
    data class MeetingsFound(
        val meetings: List<Meeting>,
    ) : PhaseResponse

    @Serializable
    @SerialName("agenda_analyzed")
    data class AgendaAnalyzed(
        val schemes: List<Scheme>,
    ) : PhaseResponse
}

@Serializable
data class Meeting(
    val date: String,
    val title: String,
    val agendaUrl: String? = null,
)

@Serializable
data class Scheme(
    val title: String,
    val topic: String,
    val summary: String,
    val meetingDate: String,
    val committeeName: String,
)
