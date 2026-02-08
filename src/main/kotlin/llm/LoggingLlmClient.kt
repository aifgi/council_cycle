package llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class LoggingLlmClient(
    private val delegate: LlmClient,
    outputDir: String,
) : LlmClient {

    private val logger = LoggerFactory.getLogger(LoggingLlmClient::class.java)
    private val outputPath: Path = Paths.get(outputDir)

    override suspend fun generate(systemPrompt: String, userPrompt: String, model: String): String {
        val response = delegate.generate(systemPrompt, userPrompt, model)

        try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(outputPath)
                val file = outputPath.resolve("${System.nanoTime()}.txt")
                Files.writeString(
                    file,
                    buildString {
                        appendLine("=== MODEL ===")
                        appendLine(model)
                        appendLine()
                        appendLine("=== SYSTEM PROMPT ===")
                        appendLine(systemPrompt)
                        appendLine()
                        appendLine("=== USER PROMPT ===")
                        appendLine(userPrompt)
                        appendLine()
                        appendLine("=== RESPONSE ===")
                        appendLine(response)
                    }
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to write debug LLM log: {}", e.message)
        }

        return response
    }
}
