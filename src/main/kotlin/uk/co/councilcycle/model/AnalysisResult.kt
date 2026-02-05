package uk.co.councilcycle.model

import kotlinx.serialization.Serializable

@Serializable
data class AnalysisResult(
    val decisions: List<Decision>,
    val followUpUrls: List<String>,
    val hasRelevantContent: Boolean,
)
