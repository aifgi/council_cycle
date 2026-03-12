package scraper

import java.util.concurrent.ConcurrentHashMap

class PdfCache {
    private val keyToBytes = ConcurrentHashMap<String, ByteArray>()
    private val urlToKey = ConcurrentHashMap<String, String>()

    fun store(key: String, originalUrl: String, bytes: ByteArray) {
        keyToBytes[key] = bytes
        urlToKey[originalUrl] = key
    }

    fun getByKey(key: String): ByteArray? = keyToBytes[key]

    fun releaseByUrl(url: String) {
        val key = urlToKey.remove(url) ?: return
        keyToBytes.remove(key)
    }
}