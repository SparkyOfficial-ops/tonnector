package dev.tonnector

import dev.tonnector.config.ConfigStore
import dev.tonnector.wallet.MockWallet

fun main() {
    val config = ConfigStore.load(ConfigStore.resolvePath(null), emptyMap())
    val wallet = MockWallet.fromConfig(config)
    println(wallet.identity.addressRaw)
    println(wallet.identity.publicKeyHex)
    println(wallet.identity.version + " " + wallet.identity.network)
}