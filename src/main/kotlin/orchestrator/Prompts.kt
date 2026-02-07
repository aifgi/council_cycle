package orchestrator

data class SplitPrompt(val system: String, val user: String)

val TOPICS = listOf(
    "cycle lanes",
    "traffic filters",
    "LTN/low traffic neighbourhoods",
    "public realm improvements",
)

fun buildPhase1Prompt(
    committeeName: String,
    pageContent: String,
): SplitPrompt {
    val system = """
You are helping find a council committee's page on their website.

Below are the committee name and the contents of a web page from this council's website. Your job is to either:
1. Identify the URL of the committee's dedicated page, OR
2. Identify links that are likely to lead to the committee's page.

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with a single JSON object (no other text). The JSON must have a "type" field.

If you need to follow links to find the committee page, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation of why you want to fetch these URLs"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-5 links.

If you found the committee's page URL, respond with:
{
  "type": "committee_page_found",
  "url": "@1"
}
""".trimIndent()

    val user = "Committee: $committeeName\n\n$pageContent"
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
): SplitPrompt {
    val topicsList = TOPICS.joinToString(", ")

    val system = """
You are triaging a council committee meeting agenda to check if it contains items related to transport and planning schemes.

Topics of interest: $topicsList

URLs are represented as short references like @1, @2. Use these references when specifying URLs in your response.

Respond with a single JSON object (no other text). The JSON must have a "type" field.

If you need to follow links to read the full agenda or individual agenda items, respond with:
{
  "type": "fetch",
  "urls": ["@1"],
  "reason": "Brief explanation"
}

Only include URLs that appeared as links in the page content above. Choose the most relevant 1-5 links. Do not follow links to PDF documents if the agenda content is already available on the page.

Once you have seen enough of the agenda, determine whether it contains any items related to the topics listed above.

If the agenda contains relevant items, extract just the relevant portions verbatim and respond with:
{
  "type": "agenda_triaged",
  "relevant": true,
  "extract": "The relevant text extracted from the agenda"
}

Exception: if the page contains meeting minutes (rather than a forward-looking agenda), return a summary focusing on the question raised and the decision made, rather than verbatim text.

If no relevant items are found, respond with:
{
  "type": "agenda_triaged",
  "relevant": false
}
""".trimIndent()

    return SplitPrompt(system, pageContent)
}

fun buildPhase4Prompt(
    extract: String,
): SplitPrompt {
    val topicsList = TOPICS.joinToString(", ")

    val system = """
You are analyzing pre-extracted content from a council committee meeting agenda for transport and planning schemes.

Topics of interest: $topicsList

Respond with a single JSON object (no other text). The JSON must have a "type" field.

Analyze the content above and identify any schemes or items related to the topics listed.

{
  "type": "agenda_analyzed",
  "schemes": [
    {
      "title": "Name of the scheme or agenda item",
      "topic": "Which topic it relates to (one of: $topicsList)",
      "summary": "Brief summary of what is proposed or discussed"
    }
  ]
}

If no relevant items are found, return an empty schemes array: {"type": "agenda_analyzed", "schemes": []}
""".trimIndent()

    return SplitPrompt(system, extract)
}
