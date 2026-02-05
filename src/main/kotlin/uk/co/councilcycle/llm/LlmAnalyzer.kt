package uk.co.councilcycle.llm

import uk.co.councilcycle.model.AnalysisResult
import uk.co.councilcycle.model.Council

interface LlmAnalyzer {
    suspend fun analyzePage(council: Council, pageContent: String, pageUrl: String): AnalysisResult
}
