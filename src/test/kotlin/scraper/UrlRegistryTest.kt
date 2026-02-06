package scraper

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlRegistryTest {

    @Test
    fun `assigns sequential tokens`() {
        val registry = UrlRegistry()
        assertEquals("@1", registry.register("https://example.com/a"))
        assertEquals("@2", registry.register("https://example.com/b"))
        assertEquals("@3", registry.register("https://example.com/c"))
    }

    @Test
    fun `returns same token for duplicate URL`() {
        val registry = UrlRegistry()
        val first = registry.register("https://example.com/page")
        val second = registry.register("https://example.com/page")
        assertEquals(first, second)
        assertEquals("@1", first)
    }

    @Test
    fun `resolve returns full URL for known token`() {
        val registry = UrlRegistry()
        registry.register("https://example.com/page")
        assertEquals("https://example.com/page", registry.resolve("@1"))
    }

    @Test
    fun `resolve passes through unknown strings`() {
        val registry = UrlRegistry()
        assertEquals("https://example.com/other", registry.resolve("https://example.com/other"))
        assertEquals("@99", registry.resolve("@99"))
        assertEquals("plain text", registry.resolve("plain text"))
    }

    @Test
    fun `register and resolve round-trip with multiple URLs`() {
        val registry = UrlRegistry()
        val urls = listOf(
            "https://example.com/a",
            "https://example.com/b",
            "https://example.com/c",
        )
        val tokens = urls.map { registry.register(it) }
        assertEquals(listOf("@1", "@2", "@3"), tokens)

        for ((token, url) in tokens.zip(urls)) {
            assertEquals(url, registry.resolve(token))
        }
    }

}
