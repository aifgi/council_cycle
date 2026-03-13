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

Respond with ONLY a single JSON object. Do not include any reasoning or other text. The JSON must have a "type" field.

If you need to follow links to find the relevant meeting pages, respond with:
{
  "type": "fetch",
  "urls": ["@1", "@2"],
  "reason": "Brief explanation of why these links are likely to lead to meeting listings or meeting pages"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-5 links.

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

Return agendaUrl as the link to the meeting page itself (not any documents linked from it).
""".trimIndent()

    val user = "Committee: $committeeName\nDate range: $dateFrom to $dateTo\n\n$pageContent"
    return SplitPrompt(system, user)
}

fun buildFindAgendaPrompt(meetingUrl: String, pageContent: String): SplitPrompt {
    val system = """You are helping find the agenda document for a council committee meeting.

The page shown is a council meeting page. Your job is to find a link to the agenda document for this meeting.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object. Do not include any reasoning, explanation, or other text before or after the JSON. The JSON must have a "type" field.

If you need to follow links to find the agenda document, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation of why you want to fetch these URLs"
}

Only include URLs that appeared as links in the page content. Choose the most relevant 1-5 links.

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
    val topicsList = TOPICS.joinToString(", ")
    val excludedList = EXCLUDED_TOPICS.joinToString(", ")

    val system = """You are identifying relevant agenda items from a council committee meeting agenda.

Topics of interest: $topicsList
Excluded topics (do not include): $excludedList

Your job is to identify agenda items on this page that relate to the topics of interest.
For each relevant item, provide its title and a brief description of what it is about.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object. Do not include any reasoning or other text. The JSON must have a "type" field.

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
even if the document continues with appendices, reports, or other supporting content.""".trimIndent()

    val userParts = mutableListOf("This is the agenda of a meeting of $committeeName on $meetingDate.")

    if (fetchReason != null) {
        userParts.add("You previously requested this page because: $fetchReason")
    }

    userParts.add(pageContent)

    return SplitPrompt(system, userParts.joinToString("\n\n---\n\n"))
}

fun buildEnrichAgendaItemsPrompt(
    committeeName: String,
    meetingDate: String,
    identifiedItems: List<IdentifiedAgendaItem>,
    pageContent: String,
    fetchReason: String? = null,
    accumulatedItems: Collection<TriagedItem> = emptyList(),
): SplitPrompt {
    val system = """You are building detailed extracts for pre-identified agenda items from a council committee meeting.

The items to enrich are listed in the user message. Your job is to fetch item-specific documents
linked from the meeting page and build detailed extracts for each item.

SCOPE RULES

Only fetch documents that are specifically linked to one of the identified items.
Do NOT fetch full agenda packs or combined document packs.
Do NOT navigate to other meetings or committee pages.

For each item, build a DETAILED extract including:
- what is being proposed
- locations
- consultation outcomes
- decisions (quote verbatim)
- vote counts and conditions

OUTPUT RULES

URLs are represented as short references such as @1, @2.
Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object.
Do NOT include reasoning, explanations, or any text outside the JSON.
Do NOT add fields other than those explicitly shown below.

FETCH RESPONSE FORMAT

If you need to fetch item-specific documents to complete your analysis, respond with:
{
  "type": "agenda_item_fetch",
  "urls": ["@1", "@2"],
  "reason": "Detailed explanation of what information you expect to find and why it is needed",
  "items": [
    {"title": "Item title", "extract": "Detailed extract for this already-completed item"}
  ]
}

Rules for agenda_item_fetch:
- Only include URLs that appeared as links in the page content
- Only include item-specific documents
- NEVER include agenda packs or combined packs
- Choose the most relevant 1–5 links
- Include in "items" all items already fully analysed

FINAL RESPONSE FORMAT

Once you have gathered enough information, respond with:
{
  "type": "agenda_triaged",
  "relevant": true,
  "items": [
    {"title": "Item title", "extract": "Detailed extract with full context"}
  ]
}

Include only the items analysed in the current iteration.
Previously analysed items (if any) will be merged automatically.

NO RELEVANT CONTENT

If after reviewing the documents you determine that none of the identified items have relevant content
(e.g. the documents are empty, inaccessible, or contain no useful information), respond with:
{
  "type": "agenda_triaged",
  "relevant": false,
  "items": []
}""".trimIndent()

    val userParts = mutableListOf<String>()

    val itemsList = identifiedItems.joinToString("\n") { "- ${it.title}: ${it.description}" }
    userParts.add("Meeting of $committeeName on $meetingDate.\n\nItems to enrich:\n$itemsList")

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

fun buildAnalyzeExtractPrompt(
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

If the content includes any decision, resolution, approval, refusal, deferral, endorsement, recommendation, it MUST be explicitly included in the summary.
When NO decision is recorded:
- Explicitly state this using a sentence such as "No decision."

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
