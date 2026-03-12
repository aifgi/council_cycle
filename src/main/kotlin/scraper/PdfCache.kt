package scraper

import java.util.concurrent.ConcurrentHashMap

data class PdfChunk(
    val text: String,
    val startPage: Int,
    val endPage: Int,
    val totalPages: Int,
    val nextChunkKey: String?,
)

class PdfCache {
    private val chunks = ConcurrentHashMap<String, PdfChunk>()
    private val docToChunks = ConcurrentHashMap<String, List<String>>()

    fun storeChunks(originalUrl: String, chunkMap: Map<String, PdfChunk>) {
        chunks.putAll(chunkMap)
        docToChunks[originalUrl] = chunkMap.keys.toList()
    }

    fun getChunk(key: String): PdfChunk? = chunks[key]

    fun releaseByUrl(url: String) {
        val keys = docToChunks.remove(url) ?: return
        keys.forEach { chunks.remove(it) }
    }
}