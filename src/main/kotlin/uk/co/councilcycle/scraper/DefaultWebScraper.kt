package uk.co.councilcycle.scraper

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import uk.co.councilcycle.model.Council
import uk.co.councilcycle.model.Meeting
import java.time.LocalDate

class DefaultWebScraper(
    private val httpClient: HttpClient,
) : WebScraper {

    private val logger = LoggerFactory.getLogger(DefaultWebScraper::class.java)

    override suspend fun fetchMeetingsPage(council: Council): String {
        logger.info("Fetching meetings page for {}: {}", council.name, council.meetingsUrl)
        return fetchPage(council.meetingsUrl)
    }

    override suspend fun fetchPage(url: String): String {
        val response: HttpResponse = httpClient.get(url)
        return response.bodyAsText()
    }

    override suspend fun findRecentMeetings(council: Council): List<Meeting> {
        val html = fetchMeetingsPage(council)
        return parseMeetingsFromHtml(council, html)
    }

    private fun parseMeetingsFromHtml(council: Council, html: String): List<Meeting> {
        val doc = Jsoup.parse(html)
        val meetings = mutableListOf<Meeting>()

        // Most English councils use the Modern.gov committee system.
        // This parser handles the common mgListCommittees / mgCalendarMonthView patterns.
        // Individual councils may need custom parsing logic added over time.

        val rows = doc.select("a[href*=mgAi], a[href*=ieListDocuments], a[href*=mgMeetingAttendance]")
        for (link in rows) {
            val href = link.attr("abs:href")
            val text = link.text().trim()
            if (text.isNotBlank() && href.isNotBlank()) {
                meetings.add(
                    Meeting(
                        councilId = council.id,
                        title = text,
                        url = href,
                        date = LocalDate.now(), // TODO: extract actual date from page
                        committee = "",
                    )
                )
            }
        }

        logger.info("Found {} meeting links for {}", meetings.size, council.name)
        return meetings
    }
}
