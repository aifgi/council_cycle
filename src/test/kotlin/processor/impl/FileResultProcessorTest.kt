package processor.impl

import orchestrator.Scheme
import processor.impl.FileResultProcessor.Companion.sanitizeFilename
import processor.impl.FileResultProcessor.Companion.wordWrap
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

    @Test
    fun `wordWrap returns short text unchanged`() {
        assertEquals("short text", wordWrap("short text"))
    }

    @Test
    fun `wordWrap breaks after 80 chars at next space`() {
        val text = "a".repeat(80) + " break here"
        assertEquals("a".repeat(80) + "\n  break here", wordWrap(text, indent = "  "))
    }

    @Test
    fun `wordWrap breaks multiple times for long text`() {
        val words = List(30) { "word" }
        val text = words.joinToString(" ")
        val wrapped = wordWrap(text, maxWidth = 20, indent = "  ")
        for (line in wrapped.lines()) {
            // Each line (excluding indent) should reach at least maxWidth before breaking
            assertTrue(line.trimStart().length <= 30, "Line too long: '$line'")
        }
        // Content is preserved (ignoring added indents/newlines)
        assertEquals(text, wrapped.lines().joinToString(" ") { it.trimStart() })
    }

    @Test
    fun `wordWrap returns text as-is when no spaces exist`() {
        val text = "a".repeat(200)
        assertEquals(text, wordWrap(text))
    }

    @Test
    fun `wordWrap indents continuation lines`() {
        val text = "a".repeat(80) + " second line"
        val wrapped = wordWrap(text, indent = "    ")
        val lines = wrapped.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("    "))
    }
}
