package orchestrator

class UrlRegistry {
    private val urlToToken = mutableMapOf<String, String>()
    private val tokenToUrl = mutableMapOf<String, String>()
    private var nextId = 1

    fun register(url: String): String =
        urlToToken.getOrPut(url) {
            val token = "@$nextId"; nextId++
            tokenToUrl[token] = url; token
        }

    fun resolve(tokenOrUrl: String): String = tokenToUrl[tokenOrUrl] ?: tokenOrUrl
}
