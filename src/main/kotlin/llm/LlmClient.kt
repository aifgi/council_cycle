package llm

interface LlmClient {
    suspend fun generate(prompt: String, model: String): String
}
