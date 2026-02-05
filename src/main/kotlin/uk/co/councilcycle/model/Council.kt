package uk.co.councilcycle.model

import kotlinx.serialization.Serializable

@Serializable
data class Council(
    val id: String,
    val name: String,
    val meetingsUrl: String,
    val region: String = "",
)
