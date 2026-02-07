package processor.impl

import orchestrator.Scheme
import processor.impl.FileResultProcessor.Companion.sanitizeFilename
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileResultProcessorTest {

    @Test
    fun `writes schemes to file`() {
        val dir = Files.createTempDirectory("file-processor-test")
        try {
            val processor = FileResultProcessor(dir)
            val schemes = listOf(
                Scheme(
                    title = "New Bus Route",
                    topic = "transport",
                    summary = "A new bus route proposal",
                    meetingDate = "2025-12-09",
                    committeeName = "Transport Committee",
                ),
            )

            processor.process("Test Council", "Transport Committee", schemes)

            val file = dir.resolve("Test Council - Transport Committee.txt")
            assertTrue(Files.exists(file))
            val content = file.toFile().readText()
            assertTrue(content.contains("Council: Test Council"))
            assertTrue(content.contains("Committee: Transport Committee"))
            assertTrue(content.contains("Schemes: 1"))
            assertTrue(content.contains("Title: New Bus Route"))
            assertTrue(content.contains("Topic: transport"))
            assertTrue(content.contains("Meeting date: 2025-12-09"))
            assertTrue(content.contains("Summary: A new bus route proposal"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `writes empty schemes`() {
        val dir = Files.createTempDirectory("file-processor-test")
        try {
            val processor = FileResultProcessor(dir)

            processor.process("Test Council", "Planning Committee", emptyList())

            val file = dir.resolve("Test Council - Planning Committee.txt")
            assertTrue(Files.exists(file))
            val content = file.toFile().readText()
            assertTrue(content.contains("Schemes: 0"))
            assertFalse(content.contains("---"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `creates output directory if missing`() {
        val parent = Files.createTempDirectory("file-processor-test")
        val dir = parent.resolve("nested").resolve("output")
        try {
            assertFalse(Files.exists(dir))
            val processor = FileResultProcessor(dir)

            processor.process("Test Council", "Committee", emptyList())

            assertTrue(Files.exists(dir))
            assertTrue(Files.exists(dir.resolve("Test Council - Committee.txt")))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `sanitizes special characters in filename`() {
        assertEquals("Council_Name - Committee_A_B", sanitizeFilename("Council/Name - Committee<A>B"))
        assertEquals("simple name", sanitizeFilename("simple name"))
        assertEquals("dots.and-dashes", sanitizeFilename("dots.and-dashes"))
    }
}
