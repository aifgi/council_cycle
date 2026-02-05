package uk.co.councilcycle.scraper

import uk.co.councilcycle.model.Council
import uk.co.councilcycle.model.Meeting

interface WebScraper {
    suspend fun fetchMeetingsPage(council: Council): String
    suspend fun fetchPage(url: String): String
    suspend fun findRecentMeetings(council: Council): List<Meeting>
}
