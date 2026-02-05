package uk.co.councilcycle.config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val llm: LlmConfig = LlmConfig(),
    val bluesky: BlueskyConfig = BlueskyConfig(),
    val pipeline: PipelineConfig = PipelineConfig(),
)

@Serializable
data class LlmConfig(
    val baseUrl: String = "https://api.anthropic.com/v1/messages",
    val apiKey: String = "",
    val model: String = "claude-sonnet-4-20250514",
    val maxTokens: Int = 4096,
)

@Serializable
data class BlueskyConfig(
    val baseUrl: String = "https://bsky.social",
    val handle: String = "",
    val appPassword: String = "",
    val minRelevanceScore: Int = 5,
)

@Serializable
data class PipelineConfig(
    val dailyRunTime: String = "07:00",
    val maxFollowUpDepth: Int = 5,
)
