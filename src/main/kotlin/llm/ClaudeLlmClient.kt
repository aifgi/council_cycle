package llm

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import kotlinx.coroutines.future.await

class ClaudeLlmClient(
    private val client: AnthropicClientAsync,
) : LlmClient {

    override suspend fun generate(prompt: String, model: String): String {
        val params = MessageCreateParams.builder()
            .maxTokens(4096L)
            .addUserMessage(prompt)
            .model(Model.of(model))
            .build()
        val message = client.messages().create(params).await()
        return message.content().first().asText().text()
    }
}
