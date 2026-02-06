package llm

class MockLlmClient(
    private val handler: (String) -> String,
) : LlmClient {
    override suspend fun generate(prompt: String, model: String): String = handler(prompt)
}
