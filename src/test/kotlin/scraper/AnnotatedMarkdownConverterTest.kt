package scraper

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AnnotatedMarkdownConverterTest {

    private val converter = AnnotatedMarkdownConverter()

    private fun convert(html: String): String = converter.convert(Jsoup.parse(html))

    @Test
    fun `converts headings`() {
        val result = convert("<h1>Title</h1><h2>Subtitle</h2><h3>Section</h3>")
        assertContains(result, "# Title")
        assertContains(result, "## Subtitle")
        assertContains(result, "### Section")
    }

    @Test
    fun `converts paragraphs`() {
        val result = convert("<p>First paragraph</p><p>Second paragraph</p>")
        assertContains(result, "First paragraph")
        assertContains(result, "Second paragraph")
    }

    @Test
    fun `converts links with href`() {
        val result = convert("""<p>Visit <a href="https://example.com/page">this page</a></p>""")
        assertContains(result, "[this page](https://example.com/page)")
    }

    @Test
    fun `resolves relative links when base url is set`() {
        val doc = Jsoup.parse("""<p>See <a href="details.aspx?id=1">details</a></p>""", "https://example.com/list")
        val result = converter.convert(doc)
        assertContains(result, "[details](https://example.com/details.aspx?id=1)")
    }

    @Test
    fun `converts links without href as plain text`() {
        val result = convert("<p><a>no link</a></p>")
        assertContains(result, "no link")
        assertFalse(result.contains("[no link]"))
    }

    @Test
    fun `converts unordered lists`() {
        val result = convert("<ul><li>Apple</li><li>Banana</li></ul>")
        assertContains(result, "- Apple")
        assertContains(result, "- Banana")
    }

    @Test
    fun `converts ordered lists`() {
        val result = convert("<ol><li>First</li><li>Second</li><li>Third</li></ol>")
        assertContains(result, "1. First")
        assertContains(result, "2. Second")
        assertContains(result, "3. Third")
    }

    @Test
    fun `converts bold and italic`() {
        val result = convert("<p><strong>bold</strong> and <em>italic</em></p>")
        assertContains(result, "**bold**")
        assertContains(result, "*italic*")
    }

    @Test
    fun `converts inline code`() {
        val result = convert("<p>Use <code>println()</code> to print</p>")
        assertContains(result, "`println()`")
    }

    @Test
    fun `converts blockquote`() {
        val result = convert("<blockquote><p>A quote</p></blockquote>")
        assertContains(result, "> A quote")
    }

    @Test
    fun `converts preformatted text`() {
        val result = convert("<pre>line 1\nline 2</pre>")
        assertContains(result, "```")
        assertContains(result, "line 1")
        assertContains(result, "line 2")
    }

    @Test
    fun `converts horizontal rule`() {
        val result = convert("<p>Before</p><hr><p>After</p>")
        assertContains(result, "---")
    }

    @Test
    fun `converts br to newline`() {
        val result = convert("<p>Line one<br>Line two</p>")
        assertContains(result, "Line one\nLine two")
    }

    @Test
    fun `converts table with headers`() {
        val html = """
            <table>
                <thead><tr><th>Name</th><th>Date</th></tr></thead>
                <tbody>
                    <tr><td>Meeting A</td><td>2024-01-15</td></tr>
                    <tr><td>Meeting B</td><td>2024-02-20</td></tr>
                </tbody>
            </table>
        """.trimIndent()
        val result = convert(html)
        assertContains(result, "[Table]")
        assertContains(result, "Row 1:")
        assertContains(result, "[Name] Meeting A")
        assertContains(result, "[Date] 2024-01-15")
        assertContains(result, "Row 2:")
        assertContains(result, "[Name] Meeting B")
        assertContains(result, "[Date] 2024-02-20")
    }

    @Test
    fun `converts table without headers uses column numbers`() {
        val html = """
            <table>
                <tr><td>Alpha</td><td>Beta</td></tr>
                <tr><td>Gamma</td><td>Delta</td></tr>
            </table>
        """.trimIndent()
        val result = convert(html)
        assertContains(result, "[Table]")
        assertContains(result, "[Column 1] Alpha")
        assertContains(result, "[Column 2] Beta")
        assertContains(result, "[Column 1] Gamma")
        assertContains(result, "[Column 2] Delta")
    }

    @Test
    fun `converts table with th in first row but no thead`() {
        val html = """
            <table>
                <tr><th>Col A</th><th>Col B</th></tr>
                <tr><td>Val 1</td><td>Val 2</td></tr>
            </table>
        """.trimIndent()
        val result = convert(html)
        assertContains(result, "[Col A] Val 1")
        assertContains(result, "[Col B] Val 2")
    }

    @Test
    fun `strips all html tags`() {
        val html = """
            <div class="wrapper">
                <span style="color:red">Styled text</span>
                <div><p>Nested</p></div>
            </div>
        """.trimIndent()
        val result = convert(html)
        assertContains(result, "Styled text")
        assertContains(result, "Nested")
        assertFalse(result.contains("<"))
    }

    @Test
    fun `full page conversion`() {
        val html = """
            <html><body>
                <h1>Council Meeting</h1>
                <p>Held on <strong>Monday</strong> at <a href="https://example.com/venue">Town Hall</a></p>
                <h2>Agenda</h2>
                <ol><li>Opening</li><li>Minutes</li></ol>
                <h2>Attendees</h2>
                <table>
                    <thead><tr><th>Name</th><th>Role</th></tr></thead>
                    <tbody>
                        <tr><td>Alice</td><td>Chair</td></tr>
                        <tr><td>Bob</td><td>Secretary</td></tr>
                    </tbody>
                </table>
            </body></html>
        """.trimIndent()
        val result = convert(html)
        assertContains(result, "# Council Meeting")
        assertContains(result, "**Monday**")
        assertContains(result, "[Town Hall](https://example.com/venue)")
        assertContains(result, "## Agenda")
        assertContains(result, "1. Opening")
        assertContains(result, "2. Minutes")
        assertContains(result, "## Attendees")
        assertContains(result, "[Name] Alice")
        assertContains(result, "[Role] Chair")
        assertFalse(result.contains("<"))
    }
}
