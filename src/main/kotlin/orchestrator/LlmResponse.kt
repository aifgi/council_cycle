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
    @SerialName("committee_pages_found")
    data class CommitteePagesFound(
        val committees: List<CommitteeUrl>,
    ) : LlmResponse

    @Serializable
    @SerialName("meetings_found")
    data class MeetingsFound(
        val meetings: List<Meeting>,
    ) : LlmResponse

    @Serializable
    @SerialName("agenda_item_fetch")
    data class AgendaFetch(
        val urls: List<String>,
        val reason: String,
        val items: List<TriagedItem> = emptyList(),
    ) : LlmResponse

    @Serializable
    @SerialName("agenda_triaged")
    data class AgendaTriaged(
        val relevant: Boolean,
        val items: Collection<TriagedItem> = emptyList(),
        val summary: String? = null,
    ) : LlmResponse

    @Serializable
    @SerialName("agenda_analyzed")
    data class AgendaAnalyzed(
        val schemes: List<Scheme>,
    ) : LlmResponse
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
    val meetingUrl: String? = null,
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

fun LlmResponse.resolveUrls(resolve: (String) -> String): LlmResponse = when (this) {
    is LlmResponse.Fetch -> copy(urls = urls.map { resolve(it) })
    is LlmResponse.AgendaFetch -> copy(urls = urls.map { resolve(it) })
    is LlmResponse.CommitteePagesFound -> copy(
        committees = committees.map { it.copy(url = resolve(it.url)) }
    )
    is LlmResponse.MeetingsFound -> copy(
        meetings = meetings.map { it.copy(meetingUrl = it.meetingUrl?.let { url -> resolve(url) }) }
    )
    is LlmResponse.AgendaTriaged -> this
    is LlmResponse.AgendaAnalyzed -> this
}
