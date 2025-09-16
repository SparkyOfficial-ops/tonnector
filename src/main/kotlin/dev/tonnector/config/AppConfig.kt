package dev.tonnector.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.ton.mnemonic.Mnemonic
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class AppConfig(
    val port: Int = 8080,
    val host: String = "127.0.0.1",
    val network: String = "testnet",
    val version: String = "v4r2",
    val workchain: Int = 0,
    val mnemonic: String = "",
    val privateKey: String = "",
    val seqno: Int = 0,
    val autoApproveConnect: Boolean = false,
) {
    fun globalId(): Int = if (network.equals("mainnet", ignoreCase = true)) -239 else -3

    fun normalizedVersion(): String = when (version.lowercase().replace("_", "")) {
        "v5", "v5r1", "w5", "w5r1" -> "v5r1"
        else -> "v4r2"
    }
}

object ConfigStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(path: Path, overrides: Map<String, String>): AppConfig {
        val fileConfig = if (path.exists()) {
            json.decodeFromString<AppConfig>(path.readText())
        } else {
            AppConfig()
        }
        var config = fileConfig
        overrides["port"]?.toIntOrNull()?.let { config = config.copy(port = it) }
        overrides["host"]?.let { config = config.copy(host = it) }
        overrides["network"]?.let { config = config.copy(network = it) }
        overrides["version"]?.let { config = config.copy(version = it) }
        if (config.mnemonic.isBlank() && config.privateKey.isBlank()) {
            val generated = Mnemonic.generate()
            config = config.copy(mnemonic = generated.words.joinToString(" "))
            save(path, config)
        } else if (!path.exists()) {
            save(path, config)
        }
        return config
    }

    fun save(path: Path, config: AppConfig) {
        path.parent?.toFile()?.mkdirs()
        path.writeText(json.encodeToString(config) + "\n")
    }

    fun resolvePath(explicit: String?): Path {
        if (!explicit.isNullOrBlank()) return Path(explicit)
        return Path("tonnector.json")
    }
}
