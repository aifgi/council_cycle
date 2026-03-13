package orchestrator

data class SplitPrompt(val system: String, val user: String)

val TOPICS = listOf(
    "cycle lanes",
    "traffic/modal filters",
    "LTN/low traffic/liveable neighbourhoods",
    "public realm improvements",
    "school streets",
    "pedestrian/zebra crossings",
    "local implementation plan/LIP",
)

val EXCLUDED_TOPICS = listOf(
    "highway maintenance",
    "work programme",
)

private val TOPICS_STRING = TOPICS.joinToString(", ")
private val EXCLUDED_TOPICS_STRING = EXCLUDED_TOPICS.joinToString(", ")

fun buildPhase1Prompt(
    committeeNames: List<String>,
    pageContent: String,
): SplitPrompt {
    val system = """You are helping find the URLs of council committees' dedicated pages on their website.

Below are the committee names and the contents of a web page from this council's website. Your job is to either:
1. Identify the URLs of the listed committees' dedicated pages, OR
2. Identify links that are likely to lead to the committees' pages.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

If you need to follow links to find the committee pages, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation of why these links are likely to lead to the committee pages"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1–5 links.

If you found the committees' page URLs, respond with:
{
  "type": "committee_pages_found",
  "committees": [
    {"name": "Committee Name", "url": "@1"}
  ]
}""".trimIndent()

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
    val system = """You are helping find committee MEETING PAGES.

Below are the committee name, date range, and the contents of a web page.

Your job is to locate meeting pages within the date range and return links to the meeting pages.
Do NOT try to find agenda pages, agenda items, or agenda documents/packs in this step.

IMPORTANT NAVIGATION RULES

- Stay at the meeting-listing level until you have identified a specific meeting page.
- Only follow links that are likely to lead to:
  (a) a meeting listings page (list of meetings), or
  (b) a specific meeting page.
- Do NOT follow links to documents, agenda packs, public reports packs, minutes packs, attachments, PDFs, or any "download" links.
- Do NOT navigate into any page that is clearly a document pack / downloads page unless it is also the meeting page itself.

DATE RANGE RULES

- Only return meetings whose meeting date is within the given date range.
- If dates are shown in a different format, infer the date carefully and normalize it to YYYY-MM-DD.

OUTPUT RULES

URLs are represented as short references like @1, @2. Use these references when specifying URLs.

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

If you need to follow links to find the relevant meeting pages, respond with:
{
  "type": "fetch",
  "urls": ["@1", "@2"],
  "reason": "Brief explanation of why these links are likely to lead to meeting listings or meeting pages"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1–5 links.

If you found meeting pages within the date range, respond with:
{
  "type": "meetings_found",
  "meetings": [
    {
      "date": "YYYY-MM-DD",
      "title": "Meeting title",
      "meetingUrl": "@1"
    }
  ]
}

Return meetingUrl as the link to the meeting page itself (not any documents linked from it).
If no meetings fall within the date range, respond with meetings_found and an empty array.""".trimIndent()

    val user = "Committee: $committeeName\nDate range: $dateFrom to $dateTo\n\n$pageContent"
    return SplitPrompt(system, user)
}

fun buildFindAgendaPrompt(meetingUrl: String, pageContent: String): SplitPrompt {
    val system = """You are helping find the agenda document for a council committee meeting.

The page shown is a council meeting page. Your job is to find a link to the agenda document for this meeting.

OUTPUT RULES

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

If you need to follow links to find the agenda document, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation of why these links are likely to contain the agenda document"
}

Only include URLs that appeared as links in the page content. Choose the most relevant 1–5 links.

If you found a link to the agenda document, respond with:
{
  "type": "agenda_found",
  "agendaUrl": "@1"
}

Prefer a frontsheet or agenda cover document over a full agenda pack if both are available.
If no separate agenda document link exists on this page, return the meeting page URL itself as agendaUrl.""".trimIndent()

    return SplitPrompt(system, "Meeting page URL: $meetingUrl\n\n$pageContent")
}

fun buildIdentifyAgendaItemsPrompt(
    committeeName: String,
    meetingDate: String,
    pageContent: String,
    fetchReason: String? = null,
): SplitPrompt {
    val topicsList = TOPICS_STRING
    val excludedList = EXCLUDED_TOPICS_STRING

    val system = """You are identifying relevant agenda items from a council committee meeting agenda.

Topics of interest: $topicsList
Excluded topics (do not include): $excludedList

Your job is to identify agenda items on this page that relate to the topics of interest.
For each relevant item, provide its title and a brief description of what it is about.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

Always respond with:
{
  "type": "agenda_items_identified",
  "items": [
    {"title": "Item title", "description": "Brief description of what this item is about"}
  ],
  "fetchUrls": ["@1"],
  "fetchReason": "Brief explanation of why more pages are needed"
}

- "items": relevant items found on this page. If none, use an empty array.
- "fetchUrls": if you have NOT yet reached the end of the agenda items section and need to read
  more pages (e.g. next chunk of a PDF), include those URLs here. Otherwise omit or use [].
- "fetchReason": required when fetchUrls is non-empty; explain why more pages are needed.

IMPORTANT: Only include fetchUrls if the agenda items list is clearly incomplete (i.e. truncated
mid-list). Do NOT fetch further pages if you have reached the end of the agenda items section,
even if the document continues with appendices, reports, or other supporting content.
Do NOT re-fetch pages you have already read. Only fetch URLs for pages not yet seen.""".trimIndent()

    val userParts = mutableListOf("This is the agenda of a meeting of $committeeName on $meetingDate.")

    if (fetchReason != null) {
        userParts.add("The page below is the continuation you requested. You requested it because: $fetchReason")
    }

    userParts.add(pageContent)

    return SplitPrompt(system, userParts.joinToString("\n\n---\n\n"))
}

fun buildEnrichAgendaItemsPrompt(
    committeeName: String,
    meetingDate: String,
    items: List<IdentifiedAgendaItem>,
    pageContent: String,
    fetchedFor: List<IdentifiedAgendaItem>? = null,
): SplitPrompt {
    val system = """You are enriching pre-identified agenda items from a council committee meeting.

The items listed below each need a detailed extract built from documents linked on the meeting page.
For EACH item you may provide a SUMMARY, request a FETCH, or both.

SCOPE RULES

Only fetch documents that are specifically linked to one of the identified items.
Do NOT fetch full agenda packs or combined document packs.
Do NOT navigate to other meetings or committee pages.
If no specific document is linked for an item, provide a summary with whatever information is available.

For each item providing a summary, build a DETAILED extract including:
- what is being proposed
- locations
- consultation outcomes
- decisions (quote verbatim)
- vote counts and conditions

OUTPUT RULES

URLs are represented as short references such as @1, @2.
Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object.
Do not include any reasoning, explanation, or any text outside the JSON.

{
  "type": "agenda_items_enriched",
  "items": [
    {
      "title": "Exact item title as given",
      "action": "summary",
      "extract": "Detailed extract with full context"
    },
    {
      "title": "Exact item title as given",
      "action": "fetch",
      "urls": ["@1"],
      "reason": "Why this specific document is needed for this item"
    }
  ]
}

Rules:
- Each item in the input list must appear at least once. An item may appear twice: once with action "summary" and once with action "fetch".
- Use "summary" when sufficient information is available from the current page
- Use "fetch" when a specific linked document would provide more detail for that item
- For "fetch": only include URLs that appeared as links in the page content; choose 1–3 most relevant links
- NEVER fetch agenda packs or combined document packs
- If an item has no linked document and insufficient information, provide a best-effort "summary"""".trimIndent()

    val itemsList = items.joinToString("\n") { "- ${it.title}: ${it.description}" }
    val userParts = mutableListOf("Meeting of $committeeName on $meetingDate.")

    if (fetchedFor != null) {
        val fetchedForList = fetchedFor.joinToString("\n") { "- ${it.title}: ${it.description}" }
        userParts.add("This document was fetched for the following agenda item(s):\n$fetchedForList")
    }

    userParts.add("Items to enrich:\n$itemsList")
    userParts.add(pageContent)

    return SplitPrompt(system, userParts.joinToString("\n\n---\n\n"))
}

fun buildAnalyzeExtractPrompt(
    extract: String,
): SplitPrompt {
    val topicsList = TOPICS_STRING
    val excludedList = EXCLUDED_TOPICS_STRING

    val system = """You are analyzing pre-extracted content from a council committee meeting agenda for transport and planning schemes.

Topics of interest: $topicsList
Excluded topics (do not include): $excludedList

Identify any schemes or items in the provided content that relate to the topics listed.

If the content includes any decision, resolution, approval, refusal, deferral, endorsement, or recommendation, it MUST be explicitly included in the summary.
When NO decision is recorded:
- Explicitly state this using a sentence such as "No decision."

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

Respond with:
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

If no relevant items are found, return an empty schemes array: {"type": "agenda_analyzed", "schemes": []}""".trimIndent()

    return SplitPrompt(system, extract)
}
