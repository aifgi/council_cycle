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
    val siteUrl: String,
    val committees: List<String>,
    val dateFrom: String? = null,
    val dateTo: String? = null,
)
