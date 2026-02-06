package scraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentExtractorTest {

    private val extractor = ContentExtractor()

    @Test
    fun `removes script elements`() {
        val html = "<html><body><p>Hello</p><script>alert('x')</script></body></html>"
        val doc = extractor.extract(html)
        assertTrue(doc.select("script").isEmpty())
        assertEquals("Hello", doc.body().text())
    }

    @Test
    fun `removes style elements`() {
        val html = "<html><body><style>.x{color:red}</style><p>Content</p></body></html>"
        val doc = extractor.extract(html)
        assertTrue(doc.select("style").isEmpty())
        assertEquals("Content", doc.body().text())
    }

    @Test
    fun `removes noscript and template elements`() {
        val html = "<html><body><noscript>Enable JS</noscript><template><p>T</p></template><p>Visible</p></body></html>"
        val doc = extractor.extract(html)
        assertTrue(doc.select("noscript").isEmpty())
        assertTrue(doc.select("template").isEmpty())
        assertEquals("Visible", doc.body().text())
    }

    @Test
    fun `removes meta and link elements`() {
        val html = "<html><head><meta charset='utf-8'><link rel='stylesheet' href='s.css'></head><body><p>Text</p></body></html>"
        val doc = extractor.extract(html)
        assertTrue(doc.select("meta").isEmpty())
        assertTrue(doc.select("link").isEmpty())
    }

    @Test
    fun `removes elements with hidden attribute`() {
        val html = "<html><body><p>Visible</p><div hidden>Hidden</div></body></html>"
        val doc = extractor.extract(html)
        assertEquals("Visible", doc.body().text())
    }

    @Test
    fun `removes elements with display none`() {
        val html = """<html><body><p>Visible</p><div style="display: none">Hidden</div></body></html>"""
        val doc = extractor.extract(html)
        assertEquals("Visible", doc.body().text())
    }

    @Test
    fun `removes elements with visibility hidden`() {
        val html = """<html><body><p>Visible</p><span style="visibility: hidden">Ghost</span></body></html>"""
        val doc = extractor.extract(html)
        assertEquals("Visible", doc.body().text())
    }

    @Test
    fun `removes elements with aria-hidden true`() {
        val html = """<html><body><p>Visible</p><span aria-hidden="true">Icon</span></body></html>"""
        val doc = extractor.extract(html)
        assertEquals("Visible", doc.body().text())
    }

    @Test
    fun `preserves visible content and structure`() {
        val html = """
            <html><body>
                <h1>Title</h1>
                <p>Paragraph with <a href="/link">a link</a></p>
                <script>var x = 1;</script>
                <div hidden>secret</div>
            </body></html>
        """.trimIndent()
        val doc = extractor.extract(html)
        assertEquals("Title", doc.select("h1").text())
        assertEquals("/link", doc.select("a").attr("href"))
        assertTrue(doc.select("script").isEmpty())
        assertEquals(0, doc.select("[hidden]").size)
    }

    @Test
    fun `extracts main element content`() {
        val html = "<html><body><header>Nav</header><main><p>Main content</p></main><footer>Foot</footer></body></html>"
        val doc = extractor.extract(html)
        assertEquals("Main content", doc.body().text())
    }

    @Test
    fun `extracts role=main element content`() {
        val html = """<html><body><div>Side</div><div role="main"><p>Content</p></div></body></html>"""
        val doc = extractor.extract(html)
        assertEquals("Content", doc.body().text())
    }

    @Test
    fun `extracts #content element`() {
        val html = """<html><body><nav>Nav</nav><div id="content"><p>Body</p></div></body></html>"""
        val doc = extractor.extract(html)
        assertEquals("Body", doc.body().text())
    }

    @Test
    fun `extracts #main-content element`() {
        val html = """<html><body><nav>Nav</nav><div id="main-content"><p>Here</p></div></body></html>"""
        val doc = extractor.extract(html)
        assertEquals("Here", doc.body().text())
    }

    @Test
    fun `extracts content-area element`() {
        val html = """<html><body><aside>Side</aside><div class="content-area"><p>Area</p></div></body></html>"""
        val doc = extractor.extract(html)
        assertEquals("Area", doc.body().text())
    }

    @Test
    fun `returns full document when no main content found`() {
        val html = "<html><body><div><p>No main element</p></div></body></html>"
        val doc = extractor.extract(html)
        assertEquals("No main element", doc.body().text())
    }

    @Test
    fun `throws when multiple main content elements found`() {
        val html = """<html><body><main><p>First</p></main><div id="content"><p>Second</p></div></body></html>"""
        assertFailsWith<IllegalStateException> {
            extractor.extract(html)
        }
    }

    @Test
    fun `uses custom selectors`() {
        val html = """<html><body><div>Other</div><section class="custom"><p>Custom</p></section></body></html>"""
        val customExtractor = ContentExtractor(mainContentSelectors = listOf(".custom"))
        val doc = customExtractor.extract(html)
        assertEquals("Custom", doc.body().text())
    }
}
