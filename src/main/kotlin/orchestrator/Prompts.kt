package orchestrator

data class SplitPrompt(val system: String, val user: String)

val TOPICS = listOf(
    "cycle lanes",
    "traffic/modal filters",
    "LTN/low traffic/liveable neighbourhoods",
    "public realm improvements",
    "school streets",
    "pedestrian/zebra crossings",
)

val EXCLUDED_TOPICS = listOf(
    "highway maintenance",
)

fun buildPhase1Prompt(
    committeeNames: List<String>,
    pageContent: String,
): SplitPrompt {
    val system = """
You are helping find the URLs of council committees' dedicated pages on their website.

Below are the committee names and the contents of a web page from this council's website. Your job is to either:
1. Identify the URLs of the listed committees' dedicated pages, OR
2. Identify links that are likely to lead to the committees' pages.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

If you need to follow links to find the committee pages, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation of why you want to fetch these URLs"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-5 links.

If you found the committees' page URLs, respond with:
{
  "type": "committee_pages_found",
  "committees": [
    {"name": "Committee Name", "url": "@1"}
  ]
}
""".trimIndent()

    val committeeList = committeeNames.joinToString("\n") { "- $it" }
    val user = "Committees:\n$committeeList\n\n$pageContent"
    return SplitPrompt(system, user)
}

fun buildPhase2Prompt(
    committeeName: String,
    dateFrom: String,
    dateTo: String,
    pageContent: String,
): SplitPrompt {
    val system = """
You are helping find committee meeting agendas.

Below are the committee name, date range, and the contents of a web page. Your job is to either:
1. Find meetings within the date range that have agenda documents/pages, OR
2. Identify links that are likely to lead to meeting listings or agendas.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

If you need to follow links, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-5 links.

If you found meetings, respond with:
{
  "type": "meetings_found",
  "meetings": [
    {
      "date": "YYYY-MM-DD",
      "title": "Meeting title",
      "agendaUrl": "@1 or null if no agenda link found"
    }
  ]
}

Only include meetings within the date range specified above.
""".trimIndent()

    val user = "Committee: $committeeName\nDate range: $dateFrom to $dateTo\n\n$pageContent"
    return SplitPrompt(system, user)
}

fun buildPhase3Prompt(
    committeeName: String,
    meetingDate: String,
    pageContent: String,
    fetchReason: String? = null,
    accumulatedItems: Collection<TriagedItem> = emptyList(),
): SplitPrompt {
    val topicsList = TOPICS.joinToString(", ")
    val excludedList = EXCLUDED_TOPICS.joinToString(", ")

    val system = """
You are triaging a council committee meeting agenda to identify transport/planning items and build detailed extracts for each.

IMPORTANT: Stay on THIS agenda only — do not navigate to other meetings or committee pages.

Topics of interest: $topicsList
Excluded topics (do not include): $excludedList

DOCUMENT FETCHING RULES:
- Only fetch item-specific documents (reports, minutes, decisions) linked to relevant items
- NEVER fetch full agenda packs or bundled PDFs covering all items
- If only a full pack exists, work with the page context alone

PROCESS:
1. Identify relevant agenda items
2. For each item, assess if you need more context:
   - Fetch item-specific reports → summarize: proposal, consultation, results
   - Fetch minutes/decisions → summarize: questions raised, decisions made
3. Build detailed extracts including specifics from all reviewed documents

URL REFERENCES:
URLs appear as @1, @2, etc. Use these references when requesting fetches.

OUTPUT FORMAT:
Respond with ONLY a JSON object (no explanatory text).

To fetch more documents:
{
  "type": "agenda_fetch",
  "urls": ["@1", "@2"],
  "reason": "Specific information sought",
  "items": [{"title": "...", "extract": "Detailed extract for completed item"}]
}

When finished with relevant items found:
{
  "type": "agenda_triaged",
  "relevant": true,
  "items": [{"title": "...", "extract": "Detailed extract"}]
}

When no relevant items found:
{
  "type": "agenda_triaged",
  "relevant": false
}

Include only newly analyzed items in "items"; previous items are merged automatically.
""".trimIndent()

    val userParts = mutableListOf<String>()

    userParts.add("This is the agenda of a meeting of $committeeName on $meetingDate.")

    if (fetchReason != null) {
        userParts.add("You previously requested this page because: $fetchReason")
    }

    if (accumulatedItems.isNotEmpty()) {
        val itemsText = accumulatedItems.joinToString("\n\n") { "## ${it.title}\n${it.extract}" }
        userParts.add("Items analyzed so far:\n$itemsText")
    }

    userParts.add(pageContent)

    return SplitPrompt(system, userParts.joinToString("\n\n---\n\n"))
}

fun buildPhase4Prompt(
    extract: String,
): SplitPrompt {
    val topicsList = TOPICS.joinToString(", ")
    val excludedList = EXCLUDED_TOPICS.joinToString(", ")

    val system = """
You are analyzing pre-extracted content from a council committee meeting agenda for transport and planning schemes.

Topics of interest: $topicsList
Excluded topics (do not include): $excludedList

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

Analyze the content above and identify any schemes or items related to the topics listed.

{
  "type": "agenda_analyzed",
  "schemes": [
    {
      "title": "Name of the scheme or agenda item",
      "topic": "Which topic it relates to (one of: $topicsList)",
      "summary": "Brief summary of what is proposed or discussed. If a decision was taken, it MUST be included."
    }
  ]
}

If no relevant items are found, return an empty schemes array: {"type": "agenda_analyzed", "schemes": []}
""".trimIndent()

    return SplitPrompt(system, extract)
}
