package config

import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(val councils: List<CouncilConfig>)

@Serializable
data class CouncilConfig(
    val name: String,
    val siteUrl: String,
    val committees: List<String>,
)
