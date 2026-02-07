package llm

class MockLlmClient(
    private val handler: (systemPrompt: String, userPrompt: String) -> String,
) : LlmClient {
    override suspend fun generate(systemPrompt: String, userPrompt: String, model: String): String =
        handler(systemPrompt, userPrompt)
}
