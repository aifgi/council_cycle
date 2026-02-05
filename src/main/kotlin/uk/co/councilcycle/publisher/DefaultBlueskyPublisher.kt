package uk.co.councilcycle.publisher

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import uk.co.councilcycle.config.AppConfig
import uk.co.councilcycle.model.Decision

class DefaultBlueskyPublisher(
    private val httpClient: HttpClient,
    private val config: AppConfig,
) : BlueskyPublisher {

    private val logger = LoggerFactory.getLogger(DefaultBlueskyPublisher::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private var accessToken: String? = null

    override suspend fun publish(decision: Decision) {
        ensureAuthenticated()
        val postText = formatPost(decision)
        createPost(postText)
        logger.info("Published to Bluesky: {}", postText.take(80))
    }

    override suspend fun publishAll(decisions: List<Decision>) {
        decisions
            .filter { it.relevanceScore >= config.bluesky.minRelevanceScore }
            .forEach { decision ->
                publish(decision)
            }
    }

    private suspend fun ensureAuthenticated() {
        if (accessToken != null) return

        val body = buildJsonObject {
            put("identifier", config.bluesky.handle)
            put("password", config.bluesky.appPassword)
        }

        val response = httpClient.post("${config.bluesky.baseUrl}/xrpc/com.atproto.server.createSession") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        val responseBody = json.parseToJsonElement(response.bodyAsText()).jsonObject
        accessToken = responseBody["accessJwt"]?.jsonPrimitive?.content
        logger.info("Authenticated with Bluesky as {}", config.bluesky.handle)
    }

    private suspend fun createPost(text: String) {
        val token = accessToken ?: error("Not authenticated with Bluesky")

        val record = buildJsonObject {
            put("repo", config.bluesky.handle)
            put("collection", "app.bsky.feed.post")
            putJsonObject("record") {
                put("\$type", "app.bsky.feed.post")
                put("text", text)
                put("createdAt", java.time.Instant.now().toString())
            }
        }

        httpClient.post("${config.bluesky.baseUrl}/xrpc/com.atproto.repo.createRecord") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            setBody(record.toString())
        }
    }

    private fun formatPost(decision: Decision): String {
        val emoji = when (decision.category) {
            uk.co.councilcycle.model.DecisionCategory.CYCLE_LANE -> "\uD83D\uDEB2"
            uk.co.councilcycle.model.DecisionCategory.TRAFFIC_FILTER,
            uk.co.councilcycle.model.DecisionCategory.LTN -> "\uD83D\uDEA7"
            uk.co.councilcycle.model.DecisionCategory.PEDESTRIAN_CROSSING -> "\uD83D\uDEB6"
            uk.co.councilcycle.model.DecisionCategory.SPEED_LIMIT -> "\u26A0\uFE0F"
            uk.co.councilcycle.model.DecisionCategory.BUS_LANE -> "\uD83D\uDE8C"
            uk.co.councilcycle.model.DecisionCategory.SCHOOL_STREET -> "\uD83C\uDFEB"
            else -> "\uD83D\uDEF4"
        }

        val post = buildString {
            append("$emoji ${decision.councilName}\n\n")
            append(decision.summary)
            append("\n\n")
            append(decision.meetingUrl)
        }

        // Bluesky has a 300-character limit
        return if (post.length > 300) post.take(297) + "..." else post
    }
}
