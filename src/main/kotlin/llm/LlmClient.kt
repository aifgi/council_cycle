package llm

interface LlmClient<T> {
    suspend fun generate(prompt: String): T
}
