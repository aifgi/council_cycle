package llm

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory

class ClaudeLlmClient(
    private val client: AnthropicClientAsync,
    private val retryDelaysMs: List<Long> = listOf(30_000L, 60_000L, 60_000L),
) : LlmClient {

    private val logger = LoggerFactory.getLogger(ClaudeLlmClient::class.java)

    override suspend fun generate(systemPrompt: String, userPrompt: String, model: String): String {
        val params = MessageCreateParams.builder()
            .maxTokens(4096L)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
            .addUserMessage(userPrompt)
            .model(Model.of(model))
            .build()

        var lastException: RateLimitException? = null
        for (attempt in 0..retryDelaysMs.size) {
            try {
                val message = client.messages().create(params).await()
                return message.content().first().asText().text()
            } catch (e: RateLimitException) {
                lastException = e
                if (attempt < retryDelaysMs.size) {
                    val baseDelay = retryDelaysMs[attempt]
                    val jitterMs = (0..baseDelay / 10).random()
                    val delayMs = baseDelay + jitterMs
                    logger.warn(
                        "Rate limited after SDK retries exhausted. " +
                            "Application-level retry {}/{}, waiting {}s",
                        attempt + 1,
                        retryDelaysMs.size,
                        delayMs / 1000,
                    )
                    delay(delayMs)
                }
            }
        }
        throw lastException!!
    }
}
