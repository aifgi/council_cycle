package llm

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClaudeLlmClient(
    private val client: AnthropicClient,
) : LlmClient<String> {

    override suspend fun generate(prompt: String, model: String): String =
        withContext(Dispatchers.IO) {
            val params = MessageCreateParams.builder()
                .maxTokens(4096L)
                .addUserMessage(prompt)
                .model(Model.of(model))
                .build()
            val message = client.messages().create(params)
            message.content().first().asText().text()
        }
}
