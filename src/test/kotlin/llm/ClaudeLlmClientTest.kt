package llm

import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeLlmClientTest {

    @Test
    fun `generate returns text from Claude response`() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """
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
                        """.trimIndent(),
                    )
                    .setHeader("Content-Type", "application/json"),
            )
            server.start()

            val anthropicClient = AnthropicOkHttpClientAsync.builder()
                .baseUrl(server.url("/").toString())
                .apiKey("test-key")
                .build()
            val client = ClaudeLlmClient(anthropicClient)

            val result = client.generate("Say hello", "claude-sonnet-4-5-20250929")

            assertEquals("Hello from Claude", result)
        } finally {
            server.shutdown()
        }
    }
}
