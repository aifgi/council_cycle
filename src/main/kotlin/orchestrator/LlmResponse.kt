package orchestrator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

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
    @SerialName("agenda_triaged")
    data class AgendaTriaged(
        val relevant: Boolean,
        val items: Collection<TriagedItem> = emptyList(),
        val summary: String? = null,
    ) : LlmResponse

    @Serializable
    @SerialName("agenda_items_enriched")
    data class AgendaItemsEnriched(
        val items: List<EnrichedItem>,
    ) : LlmResponse

    @Serializable
    @SerialName("agenda_analyzed")
    data class AgendaAnalyzed(
        val schemes: List<Scheme>,
    ) : LlmResponse

    @Serializable
    @SerialName("agenda_found")
    data class AgendaFound(val agendaUrl: String) : LlmResponse

    @Serializable
    @SerialName("agenda_items_identified")
    data class AgendaItemsIdentified(
        val items: List<IdentifiedAgendaItem>,
        val fetchUrls: List<String> = emptyList(),
        val fetchReason: String? = null,
    ) : LlmResponse
}

@Serializable
data class IdentifiedAgendaItem(val title: String, val description: String)

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

@Serializable
@JsonClassDiscriminator("action")
sealed interface EnrichedItem {
    val title: String

    @Serializable
    @SerialName("summary")
    data class Summary(
        override val title: String,
        val extract: String,
    ) : EnrichedItem

    @Serializable
    @SerialName("fetch")
    data class Fetch(
        override val title: String,
        val urls: List<String> = emptyList(),
        val reason: String? = null,
    ) : EnrichedItem
}

fun LlmResponse.resolveUrls(resolve: (String) -> String): LlmResponse = when (this) {
    is LlmResponse.Fetch -> copy(urls = urls.map { resolve(it) })
    is LlmResponse.CommitteePagesFound -> copy(
        committees = committees.map { it.copy(url = resolve(it.url)) }
    )
    is LlmResponse.MeetingsFound -> copy(
        meetings = meetings.map { it.copy(meetingUrl = it.meetingUrl?.let { url -> resolve(url) }) }
    )
    is LlmResponse.AgendaTriaged -> this
    is LlmResponse.AgendaAnalyzed -> this
    is LlmResponse.AgendaItemsEnriched -> copy(
        items = items.map { item ->
            when (item) {
                is EnrichedItem.Fetch -> item.copy(urls = item.urls.map { resolve(it) })
                is EnrichedItem.Summary -> item
            }
        }
    )
    is LlmResponse.AgendaFound -> copy(agendaUrl = resolve(agendaUrl))
    is LlmResponse.AgendaItemsIdentified -> copy(fetchUrls = fetchUrls.map { resolve(it) })
}
