package config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val councils: List<CouncilConfig>,
    val outputDir: String? = null,
    val debugLlmDir: String? = null,
)

@Serializable
data class CouncilConfig(
    val name: String,
    val meetingsUrl: String? = null,
    val committees: List<String> = emptyList(),
    val mode: String = "meetings",
    val decisionsUrl: String? = null,
    val decisionMakers: List<String> = emptyList(),
    val dateFrom: String? = null,
    val dateTo: String? = null,
)
