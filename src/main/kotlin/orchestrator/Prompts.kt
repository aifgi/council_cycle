package orchestrator

data class SplitPrompt(val system: String, val user: String)

val TOPICS = listOf(
    "cycle lanes",
    "traffic filters",
    "LTN/low traffic neighbourhoods",
    "public realm improvements",
    "school streets",
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

Respond with a single JSON object (no other text). The JSON must have a "type" field.

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

Respond with a single JSON object (no other text). The JSON must have a "type" field.

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
    pageContent: String,
    fetchReason: String? = null,
    accumulatedItems: Collection<TriagedItem> = emptyList(),
): SplitPrompt {
    val topicsList = TOPICS.joinToString(", ")
    val excludedList = EXCLUDED_TOPICS.joinToString(", ")

    val system = """
You are triaging a council committee meeting agenda to identify items related to transport and planning schemes. Your goal is to build detailed extracts for each relevant item.

Topics of interest: $topicsList
Excluded topics (do not include): $excludedList

Work iteratively through the agenda:

1. IDENTIFY all potentially relevant agenda items. Only include items that relate to the topics above.

2. For each relevant item, assess whether the page provides enough context to understand the details:
   - Only fetch documents that are specifically linked to a relevant agenda item (e.g. an item-specific report, minutes, or decision document).
   - Do NOT fetch full agenda packs, combined document packs, or bulk PDFs that bundle all items together — these are too large and mostly irrelevant. If the only document available is a full agenda pack, work with whatever context is already on the page.
   - If the item has its own dedicated report or document linked, fetch it. Summarize focusing on: what is being proposed, what consultation has been done, and what the results were.
   - If there are minutes for this item, summarize them focusing on: what question was raised and what decision was made.
   - If the minutes are on a separate page or in a separate document, fetch them.
   - If there is a decision, include it as-is in the extract.
   - If there is a decision document on a separate page, fetch it and include its content as-is.

3. Build DETAILED extracts for each item. Include specifics from documents you have reviewed — proposals, consultation results, decisions, vote counts, conditions. Do not return brief one-line summaries.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with a single JSON object (no other text). The JSON must have a "type" field.

If you have NOT yet seen the actual agenda (e.g. the page is a listing or navigation page), respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation"
}

If you need to fetch more documents to complete your analysis (e.g. reports, minutes, decision documents), respond with:
{
  "type": "agenda_fetch",
  "urls": ["@1", "@2"],
  "reason": "Detailed explanation of what you are looking for and why",
  "items": [
    {"title": "Item title", "extract": "Detailed extract for this already-completed item"}
  ]
}
Include in "items" all items you have already fully analyzed. The "reason" must explain specifically what information you expect to find in the requested documents.

Only include URLs that appeared as links in the page content. Choose the most relevant 1-5 links.

Once you have gathered enough information for all relevant items, respond with:
{
  "type": "agenda_triaged",
  "relevant": true,
  "items": [
    {"title": "Item title", "extract": "Detailed extract with full context"}
  ]
}
Include only the items you analyzed in the current iteration. Previously analyzed items (shown below the page content if any) will be merged automatically.

If no relevant items are found, respond with:
{
  "type": "agenda_triaged",
  "relevant": false
}
""".trimIndent()

    val userParts = mutableListOf<String>()

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

Respond with a single JSON object (no other text). The JSON must have a "type" field.

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
