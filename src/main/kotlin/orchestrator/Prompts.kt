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

fun buildPhase3Prompt(
    committeeName: String,
    meetingDate: String,
    pageContent: String,
    fetchReason: String? = null,
    accumulatedItems: Collection<TriagedItem> = emptyList(),
): SplitPrompt {
    val topicsList = TOPICS.joinToString(", ")
    val excludedList = EXCLUDED_TOPICS.joinToString(", ")

    val system = """You are triaging a council committee meeting agenda to identify items related to transport and planning schemes
and to build detailed extracts for each relevant item.

You are analysing the agenda for a single meeting only.

IMPORTANT SCOPE RULES

Stay focused on this agenda only.
Do NOT navigate back to committee pages, meeting listings, or other meetings' agendas.
Only fetch documents that are directly linked to items on THIS agenda
(e.g. item-specific reports, minutes, or decision documents).

Topics of interest: $topicsList
Excluded topics (do not include): $excludedList

AUTHORITATIVE AGENDA SOURCE

The agenda content provided in the user message is the full and complete agenda for this meeting.
No additional agenda items exist outside of the content shown in the user message.

Do not attempt to discover, infer, or reconstruct agenda items from linked documents.
Attachments or agenda packs, if linked, duplicate this agenda and must not be used to identify items.

FORBIDDEN ACTIONS (HARD RULE)

Requesting or fetching a full agenda pack, public reports pack, agenda bundle,
or combined document pack is ALWAYS INVALID and MUST NEVER occur.

This applies EVEN IF:
- you believe agenda items might be missing
- you believe the page does not list items clearly
- you believe fetching would improve accuracy

If the only available document is a full agenda pack, you must NOT request it
and must proceed using only the agenda text provided in the user message.

WORKFLOW
Work iteratively through the agenda as follows:

1. IDENTIFY relevant items

IDENTIFY all potentially relevant agenda items explicitly listed in the agenda text
provided in the user message.

Treat this agenda text as authoritative and complete.
Do NOT assume any additional items exist.
Only include items that relate to the topics of interest above.

2. ASSESS whether more detail is required

For each relevant item, assess whether the agenda text itself provides enough context
to understand what is being proposed or decided.

Only fetch documents that are:
- specifically linked to that relevant agenda item, AND
- item-specific (e.g. a report, minutes, or decision document), AND
- NOT a full agenda pack or combined pack

If the item has its own dedicated report or document linked, fetch it and summarize,
focusing on:
- what is being proposed
- what consultation has been done
- what the results of that consultation were

If there are minutes for this item:
- summarize what question was raised and what decision was made
- if minutes are on a separate page or document, fetch them

If there is a decision or resolution:
- quote it verbatim
- do NOT paraphrase

If there is a decision document on a separate page:
- fetch it
- quote the decision verbatim
- do NOT paraphrase

3. BUILD detailed extracts

Build DETAILED extracts for each relevant item.
Include concrete specifics from documents you have reviewed:
- proposals
- locations
- consultation outcomes
- decisions
- vote counts
- conditions

Do NOT return brief or one-line summaries.

OUTPUT RULES

URLs are represented as short references such as @1, @2.
Use these references when specifying URLs in your response.

Respond with ONLY a single JSON object.
Do NOT include reasoning, explanations, or any text outside the JSON.
Do NOT add fields other than those explicitly shown below.
Do NOT omit required fields.

FETCH RESPONSE FORMAT

If (and only if) you need to fetch additional item-specific documents
(reports, minutes, or decision documents) to complete your analysis, respond with:
{
  "type": "agenda_item_fetch",
  "urls": ["@1", "@2"],
  "reason": "Detailed explanation of what information you expect to find and why it is needed",
  "items": [
    {"title": "Item title", "extract": "Detailed extract for this already-completed item"}
  ]
}

Rules for agenda_item_fetch:
- Only include URLs that appeared as links in the agenda page content
- Only include item-specific documents
- NEVER include agenda packs or combined packs
- Choose the most relevant 1–5 links
- Include in "items" all items already fully analysed

FINAL RESPONSE FORMAT

Once you have gathered enough information for all relevant items, respond with:
{
  "type": "agenda_triaged",
  "relevant": true,
  "items": [
    {"title": "Item title", "extract": "Detailed extract with full context"}
  ]
}

Include only the items analysed in the current iteration.
Previously analysed items (if any) will be merged automatically.

NO RELEVANT ITEMS

If no relevant items are found in the agenda text, respond with:
{
  "type": "agenda_triaged",
  "relevant": false,
  "summary": "For each irrelevant item, briefly explain why it is irrelevant"
}
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
