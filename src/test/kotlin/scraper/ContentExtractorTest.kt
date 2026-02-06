package scraper

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentExtractorTest {

    private val extractor = ContentExtractor()

    @Test
    fun `removes script elements`() {
        val html = "<html><body><p>Hello</p><script>alert('x')</script></body></html>"
        val result = extractor.extract(html)
        assertContains(result, "Hello")
        assertFalse(result.contains("alert"))
    }

    @Test
    fun `removes style elements`() {
        val html = "<html><body><style>.x{color:red}</style><p>Content</p></body></html>"
        val result = extractor.extract(html)
        assertContains(result, "Content")
        assertFalse(result.contains("color:red"))
    }

    @Test
    fun `removes noscript and template elements`() {
        val html = "<html><body><noscript>Enable JS</noscript><template><p>T</p></template><p>Visible</p></body></html>"
        val result = extractor.extract(html)
        assertContains(result, "Visible")
        assertFalse(result.contains("Enable JS"))
    }

    @Test
    fun `removes elements with hidden attribute`() {
        val html = "<html><body><p>Visible</p><div hidden>Hidden</div></body></html>"
        val result = extractor.extract(html)
        assertContains(result, "Visible")
        assertFalse(result.contains("Hidden"))
    }

    @Test
    fun `removes elements with display none`() {
        val html = """<html><body><p>Visible</p><div style="display: none">Hidden</div></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Visible")
        assertFalse(result.contains("Hidden"))
    }

    @Test
    fun `removes elements with visibility hidden`() {
        val html = """<html><body><p>Visible</p><span style="visibility: hidden">Ghost</span></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Visible")
        assertFalse(result.contains("Ghost"))
    }

    @Test
    fun `removes images`() {
        val html = """<html><body><p>Text</p><img alt="A photo" src="photo.jpg"></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Text")
        assertFalse(result.contains("photo"))
    }

    @Test
    fun `removes elements with aria-hidden true`() {
        val html = """<html><body><p>Visible</p><span aria-hidden="true">Icon</span></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Visible")
        assertFalse(result.contains("Icon"))
    }

    @Test
    fun `preserves visible content and links`() {
        val html = """
            <html><body>
                <h1>Title</h1>
                <p>Paragraph with <a href="/link">a link</a></p>
                <script>var x = 1;</script>
                <div hidden>secret</div>
            </body></html>
        """.trimIndent()
        val result = extractor.extract(html)
        assertContains(result, "# Title")
        assertContains(result, "[a link](/link)")
        assertFalse(result.contains("secret"))
    }

    @Test
    fun `extracts main element content`() {
        val html = "<html><body><header>Nav</header><main><p>Main content</p></main><footer>Foot</footer></body></html>"
        val result = extractor.extract(html)
        assertContains(result, "Main content")
        assertFalse(result.contains("Nav"))
        assertFalse(result.contains("Foot"))
    }

    @Test
    fun `extracts role=main element content`() {
        val html = """<html><body><div>Side</div><div role="main"><p>Content</p></div></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Content")
        assertFalse(result.contains("Side"))
    }

    @Test
    fun `extracts #content element`() {
        val html = """<html><body><nav>Nav</nav><div id="content"><p>Body</p></div></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Body")
        assertFalse(result.contains("Nav"))
    }

    @Test
    fun `extracts #main-content element`() {
        val html = """<html><body><nav>Nav</nav><div id="main-content"><p>Here</p></div></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Here")
        assertFalse(result.contains("Nav"))
    }

    @Test
    fun `extracts content-area element`() {
        val html = """<html><body><aside>Side</aside><div class="content-area"><p>Area</p></div></body></html>"""
        val result = extractor.extract(html)
        assertContains(result, "Area")
        assertFalse(result.contains("Side"))
    }

    @Test
    fun `returns full document when no main content found`() {
        val html = "<html><body><div><p>No main element</p></div></body></html>"
        val result = extractor.extract(html)
        assertContains(result, "No main element")
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
        val result = customExtractor.extract(html)
        assertContains(result, "Custom")
        assertFalse(result.contains("Other"))
    }

    @Test
    fun `outputs annotated markdown format`() {
        val html = """
            <html><body><main>
                <h2>Meeting</h2>
                <p>Details about the <a href="/meeting">meeting</a></p>
                <ul><li>Item one</li><li>Item two</li></ul>
            </main></body></html>
        """.trimIndent()
        val result = extractor.extract(html)
        assertContains(result, "## Meeting")
        assertContains(result, "[meeting](/meeting)")
        assertContains(result, "- Item one")
        assertContains(result, "- Item two")
        assertTrue(!result.contains("<"))
    }
}
