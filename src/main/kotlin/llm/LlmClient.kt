package llm

interface LlmClient {
    suspend fun generate(systemPrompt: String, userPrompt: String, model: String): String
}
