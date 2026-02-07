package processor.impl

import orchestrator.Scheme
import org.slf4j.LoggerFactory
import processor.ResultProcessor
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class FileResultProcessor(private val outputDir: Path) : ResultProcessor {

    private val logger = LoggerFactory.getLogger(FileResultProcessor::class.java)

    override fun process(councilName: String, committeeName: String, schemes: List<Scheme>) {
        outputDir.createDirectories()
        val fileName = sanitizeFilename("$councilName - $committeeName") + ".txt"
        val file = outputDir.resolve(fileName)

        val content = buildString {
            appendLine("Council: $councilName")
            appendLine("Committee: $committeeName")
            appendLine("Schemes: ${schemes.size}")

            for (scheme in schemes) {
                appendLine()
                appendLine("---")
                appendLine("Title: ${scheme.title}")
                appendLine("Topic: ${scheme.topic}")
                appendLine("Meeting date: ${scheme.meetingDate}")
                appendLine("Summary: ${scheme.summary}")
            }
        }

        file.writeText(content)
        logger.info("Wrote results to {}", file)
    }

    companion object {
        internal fun sanitizeFilename(name: String): String =
            name.replace(Regex("[^a-zA-Z0-9 ._-]"), "_")
    }
}
