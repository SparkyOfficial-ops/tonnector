package dev.tonnector

import dev.tonnector.app.AppController
import dev.tonnector.bridge.BridgeServer
import dev.tonnector.bridge.MessageBus
import dev.tonnector.bridge.links
import dev.tonnector.config.ConfigStore
import dev.tonnector.ui.runCli
import dev.tonnector.ui.showDesktop
import dev.tonnector.wallet.MockWallet
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    val flags = parseArgs(args)
    val path = ConfigStore.resolvePath(flags["config"])
    val config = ConfigStore.load(path, flags)
    val wallet = MockWallet.fromConfig(config)
    val bus = MessageBus()
    val controller = AppController(config, wallet, bus, flags["config"] ?: "tonnector.json")
    val server = BridgeServer(controller, bus)
    server.start()
    val links = controller.links()
    controller.log("listening on ${links.home}")
    controller.log("wallet ${wallet.identity.addressRaw} ${wallet.identity.version} ${wallet.identity.network}")
    controller.log("bridge ${links.bridge}")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    if (flags.containsKey("cli")) {
        runCli(controller)
        server.stop()
        exitProcess(0)
    } else {
        showDesktop(controller)
        Thread.currentThread().join()
    }
}

private fun parseArgs(args: Array<String>): MutableMap<String, String> {
    val map = mutableMapOf<String, String>()
    for (arg in args) {
        val body = arg.removePrefix("--")
        if (body == "cli" || body == "help") {
            map[body] = "true"
            continue
        }
        val parts = body.split("=", limit = 2)
        if (parts.size == 2) map[parts[0]] = parts[1]
    }
    if (map.containsKey("help")) {
        println(
            """
            TONNECTOR — local TonConnect 2.0 bridge and mock wallet

            java -jar tonnector.jar [--port=8080] [--host=127.0.0.1] [--cli]
                                    [--network=testnet|mainnet] [--version=v4r2|v5r1]
                                    [--config=tonnector.json]
            """.trimIndent(),
        )
        exitProcess(0)
    }
    return map
}
