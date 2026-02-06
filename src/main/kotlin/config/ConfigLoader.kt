package config

import com.charleskorn.kaml.Yaml
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("config.ConfigLoader")

fun loadConfig(filePath: String): AppConfig? {
    val file = File(filePath)
    if (!file.exists()) {
        logger.error("Config file not found: {}", filePath)
        return null
    }

    return try {
        val text = file.readText()
        Yaml.default.decodeFromString(AppConfig.serializer(), text)
    } catch (e: Exception) {
        logger.error("Failed to parse config file: {}", e.message)
        null
    }
}
