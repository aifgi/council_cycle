package llm

class MockLlmClient<T>(
    private val handler: (String) -> T,
) : LlmClient<T> {
    override suspend fun generate(prompt: String): T = handler(prompt)
}
