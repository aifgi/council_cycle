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
    @SerialName("committee_pages_found")
    data class CommitteePagesFound(
        val committees: List<CommitteeUrl>,
    ) : PhaseResponse

    @Serializable
    @SerialName("meetings_found")
    data class MeetingsFound(
        val meetings: List<Meeting>,
    ) : PhaseResponse

    @Serializable
    @SerialName("agenda_fetch")
    data class AgendaFetch(
        val urls: List<String>,
        val reason: String,
        val items: List<TriagedItem> = emptyList(),
    ) : PhaseResponse

    @Serializable
    @SerialName("agenda_triaged")
    data class AgendaTriaged(
        val relevant: Boolean,
        val items: Collection<TriagedItem> = emptyList(),
    ) : PhaseResponse

    @Serializable
    @SerialName("agenda_analyzed")
    data class AgendaAnalyzed(
        val schemes: List<Scheme>,
    ) : PhaseResponse
}

@Serializable
data class CommitteeUrl(
    val name: String,
    val url: String,
)

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
    val meetingDate: String = "",
    val committeeName: String = "",
    val agendaUrl: String = "",
)

@Serializable
data class TriagedItem(
    val title: String,
    val extract: String,
)

fun PhaseResponse.resolveUrls(resolve: (String) -> String): PhaseResponse = when (this) {
    is PhaseResponse.Fetch -> copy(urls = urls.map { resolve(it) })
    is PhaseResponse.AgendaFetch -> copy(urls = urls.map { resolve(it) })
    is PhaseResponse.CommitteePagesFound -> copy(
        committees = committees.map { it.copy(url = resolve(it.url)) }
    )
    is PhaseResponse.MeetingsFound -> copy(
        meetings = meetings.map { it.copy(agendaUrl = it.agendaUrl?.let { url -> resolve(url) }) }
    )
    is PhaseResponse.AgendaTriaged -> this
    is PhaseResponse.AgendaAnalyzed -> this
}
