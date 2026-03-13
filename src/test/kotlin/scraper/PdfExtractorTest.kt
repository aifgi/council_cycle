package scraper

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfExtractorTest {

    private val extractor = PdfExtractor()

    @Test
    fun `extract returns text from PDF bytes`() {
        val bytes = createPdfWithText("Transport scheme agenda item 7")

        val result = extractor.extract(bytes, "https://example.com/agenda.pdf")

        assertNotNull(result)
        assertTrue(result.text.contains("Transport scheme agenda item 7"))
    }

    @Test
    fun `extract returns null for invalid PDF bytes`() {
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02)

        val result = extractor.extract(bytes, "https://example.com/broken.pdf")

        assertNull(result)
    }

    @Test
    fun `extract returns first 5 pages and next-page token for large PDF`() {
        val bytes = createPdfWithPages(10)

        val result = extractor.extract(bytes, "https://example.com/large.pdf")

        assertNotNull(result)
        assertTrue(result.text.contains("Page 1"))
        assertTrue(result.text.contains("Page 5"))
        assertFalse(result.text.contains("Page 6"))
        val nextPageUrl = result.urlRegistry.resolve("@1")
        assertTrue(nextPageUrl.startsWith("https://pdf-page.internal/"))
        val pathParts = nextPageUrl.removePrefix("https://pdf-page.internal/").split("/")
        assertEquals(2, pathParts.size)
        assertEquals(6, pathParts[1].toInt())
    }

    @Test
    fun `getPage returns remaining pages from cache`() {
        val bytes = createPdfWithPages(10)
        val firstResult = extractor.extract(bytes, "https://example.com/large.pdf")
        assertNotNull(firstResult)
        val nextPageUrl = firstResult.urlRegistry.resolve("@1")

        val secondResult = extractor.getPage(nextPageUrl)

        assertNotNull(secondResult)
        assertTrue(secondResult.text.contains("Page 6"))
        assertTrue(secondResult.text.contains("Page 10"))
        assertFalse(secondResult.text.contains("Page 5"))
    }

    @Test
    fun `getPage returns null after release`() {
        val bytes = createPdfWithPages(30)
        val firstResult = extractor.extract(bytes, "https://example.com/large.pdf")
        assertNotNull(firstResult)
        val nextPageUrl = firstResult.urlRegistry.resolve("@1")

        extractor.release("https://example.com/large.pdf")

        assertNull(extractor.getPage(nextPageUrl))
    }

    @Test
    fun `extract does not append next-page token for PDF within page limit`() {
        val bytes = createPdfWithPages(5)

        val result = extractor.extract(bytes, "https://example.com/small.pdf")

        assertNotNull(result)
        assertTrue(result.text.contains("Page 1"))
        assertTrue(result.text.contains("Page 5"))
        assertFalse(result.text.contains("pdf-page.internal"))
        assertEquals("@1", result.urlRegistry.resolve("@1"))
    }

    private fun createPdfWithText(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText(text)
                cs.endText()
            }
            doc.save(output)
        }
        return output.toByteArray()
    }

    private fun createPdfWithPages(pageCount: Int): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { doc ->
            repeat(pageCount) { i ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Page ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(output)
        }
        return output.toByteArray()
    }
}
