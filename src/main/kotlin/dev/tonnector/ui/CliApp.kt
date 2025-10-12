package dev.tonnector.ui

import dev.tonnector.app.AppController
import dev.tonnector.app.Decision
import dev.tonnector.bridge.links
import java.util.concurrent.LinkedBlockingQueue

fun runCli(controller: AppController) {
    val links = controller.links()
    val id = controller.wallet.identity
    println("TONNECTOR CLI")
    println("address   ${id.addressRaw}")
    println("publicKey ${id.publicKeyHex}")
    println("version   ${id.version}  network ${id.network} (${id.globalId})")
    println("bridge    ${links.bridge}")
    println("wallets   ${links.wallets}")
    println("console   ${links.home}")
    println("commands: a approve, r reject, b bad request, n not supported")
    val queue = LinkedBlockingQueue<String>()
    controller.listen { kind ->
        if (kind == "request") {
            val pending = controller.requests().lastOrNull { it.second == "pending" }?.first
            if (pending != null) queue.offer(pending.id)
        }
    }
    while (true) {
        val idPending = queue.take()
        val request = controller.request(idPending)?.first ?: continue
        if (controller.request(idPending)?.second != "pending") continue
        println()
        println("── ${request.title}")
        println(request.detail)
        print("[a/r/b/n] ")
        when (readlnOrNull()?.trim()?.lowercase()) {
            "a" -> controller.approve(request.id)
            "r" -> controller.reject(request.id, Decision.REJECT)
            "b" -> controller.reject(request.id, Decision.BAD_REQUEST)
            "n" -> controller.reject(request.id, Decision.NOT_SUPPORTED)
            "q" -> return
            else -> println("skipped")
        }
    }
}
