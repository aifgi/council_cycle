package llm

import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.errors.RateLimitException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaudeLlmClientTest {

    private val successBody = """
        {
          "id": "msg_test",
          "type": "message",
          "role": "assistant",
          "content": [{"type": "text", "text": "Hello from Claude"}],
          "model": "claude-sonnet-4-5-20250929",
          "stop_reason": "end_turn",
          "stop_sequence": null,
          "usage": {"input_tokens": 10, "output_tokens": 5}
        }
    """.trimIndent()

    private val rateLimitBody = """
        {
          "type": "error",
          "error": {"type": "rate_limit_error", "message": "Rate limit exceeded"}
        }
    """.trimIndent()

    private fun rateLimitResponse() = MockResponse()
        .setResponseCode(429)
        .setBody(rateLimitBody)
        .setHeader("Content-Type", "application/json")

    private fun successResponse() = MockResponse()
        .setBody(successBody)
        .setHeader("Content-Type", "application/json")

    private fun buildClient(server: MockWebServer, retryDelaysMs: List<Long> = emptyList()): ClaudeLlmClient {
        val anthropicClient = AnthropicOkHttpClientAsync.builder()
            .baseUrl(server.url("/").toString())
            .apiKey("test-key")
            .maxRetries(0)
            .build()
        return ClaudeLlmClient(anthropicClient, retryDelaysMs)
    }

    @Test
    fun `generate returns text from Claude response`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(successResponse())
            server.start()

            val client = buildClient(server)
            val result = client.generate("Say hello", "claude-sonnet-4-5-20250929")

            assertEquals("Hello from Claude", result)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generate retries on rate limit then succeeds`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(rateLimitResponse())
            server.enqueue(successResponse())
            server.start()

            val client = buildClient(server, retryDelaysMs = listOf(10L, 10L, 10L))
            val result = client.generate("Say hello", "claude-sonnet-4-5-20250929")

            assertEquals("Hello from Claude", result)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `generate throws after all retries exhausted`() = runBlocking {
        val server = MockWebServer()
        try {
            // 1 initial attempt + 3 retries = 4 total
            repeat(4) { server.enqueue(rateLimitResponse()) }
            server.start()

            val client = buildClient(server, retryDelaysMs = listOf(10L, 10L, 10L))

            assertFailsWith<RateLimitException> {
                client.generate("Say hello", "claude-sonnet-4-5-20250929")
            }
            assertEquals(4, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
