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
