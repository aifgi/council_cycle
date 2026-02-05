package uk.co.councilcycle.llm

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import uk.co.councilcycle.config.AppConfig
import uk.co.councilcycle.model.*

class DefaultLlmAnalyzer(
    private val httpClient: HttpClient,
    private val config: AppConfig,
) : LlmAnalyzer {

    private val logger = LoggerFactory.getLogger(DefaultLlmAnalyzer::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun analyzePage(council: Council, pageContent: String, pageUrl: String): AnalysisResult {
        logger.info("Analyzing page for {}: {}", council.name, pageUrl)

        val prompt = buildPrompt(council, pageContent, pageUrl)
        val responseText = callLlm(prompt)
        return parseResponse(council, responseText, pageUrl)
    }

    private fun buildPrompt(council: Council, pageContent: String, pageUrl: String): String {
        return """
            You are analyzing a web page from ${council.name} (${council.region}) for decisions
            related to active travel and public realm changes.

            Page URL: $pageUrl

            Look for decisions, proposals, or discussions about:
            - Cycle lanes or cycling infrastructure
            - Traffic filters or Low Traffic Neighbourhoods (LTNs)
            - Pedestrian crossings
            - Speed limit changes
            - Road closures for active travel
            - Bus lanes
            - Pavement widening
            - School streets
            - Any other active travel or public realm improvements

            Page content:
            $pageContent

            Respond in JSON format:
            {
              "hasRelevantContent": true/false,
              "decisions": [
                {
                  "summary": "Brief summary of the decision",
                  "category": "CYCLE_LANE|TRAFFIC_FILTER|LTN|PEDESTRIAN_CROSSING|SPEED_LIMIT|ROAD_CLOSURE|BUS_LANE|PAVEMENT_WIDENING|SCHOOL_STREET|OTHER_ACTIVE_TRAVEL",
                  "relevanceScore": 1-10
                }
              ],
              "followUpUrls": ["urls to follow for more detail"]
            }
        """.trimIndent()
    }

    private suspend fun callLlm(prompt: String): String {
        // Calls the Anthropic Messages API.
        // Override config.llm.baseUrl for other providers.
        val requestBody = buildJsonObject {
            put("model", config.llm.model)
            put("max_tokens", config.llm.maxTokens)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
        }

        val response = httpClient.post(config.llm.baseUrl) {
            contentType(ContentType.Application.Json)
            header("x-api-key", config.llm.apiKey)
            header("anthropic-version", "2023-06-01")
            setBody(requestBody.toString())
        }

        val body = response.bodyAsText()
        logger.debug("LLM response: {}", body)

        val jsonResponse = json.parseToJsonElement(body).jsonObject
        return jsonResponse["content"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.content
            ?: ""
    }

    private fun parseResponse(council: Council, responseText: String, pageUrl: String): AnalysisResult {
        return try {
            val jsonResponse = json.parseToJsonElement(responseText).jsonObject
            val hasRelevant = jsonResponse["hasRelevantContent"]?.jsonPrimitive?.boolean ?: false
            val decisions = jsonResponse["decisions"]?.jsonArray?.map { element ->
                val obj = element.jsonObject
                Decision(
                    councilId = council.id,
                    councilName = council.name,
                    meetingTitle = "",
                    meetingUrl = pageUrl,
                    summary = obj["summary"]?.jsonPrimitive?.content ?: "",
                    category = try {
                        DecisionCategory.valueOf(obj["category"]?.jsonPrimitive?.content ?: "OTHER_ACTIVE_TRAVEL")
                    } catch (_: IllegalArgumentException) {
                        DecisionCategory.OTHER_ACTIVE_TRAVEL
                    },
                    relevanceScore = obj["relevanceScore"]?.jsonPrimitive?.int ?: 0,
                )
            } ?: emptyList()
            val followUpUrls = jsonResponse["followUpUrls"]?.jsonArray?.map {
                it.jsonPrimitive.content
            } ?: emptyList()

            AnalysisResult(
                decisions = decisions,
                followUpUrls = followUpUrls,
                hasRelevantContent = hasRelevant,
            )
        } catch (e: Exception) {
            logger.warn("Failed to parse LLM response: {}", e.message)
            AnalysisResult(decisions = emptyList(), followUpUrls = emptyList(), hasRelevantContent = false)
        }
    }
}
