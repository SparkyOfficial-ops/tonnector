package dev.tonnector

import dev.tonnector.app.AppController
import dev.tonnector.bridge.BridgeServer
import dev.tonnector.bridge.MessageBus
import dev.tonnector.config.AppConfig
import dev.tonnector.crypto.SessionCrypto
import dev.tonnector.crypto.hexToBytes
import dev.tonnector.wallet.MockWallet
import dev.tonnector.wallet.OutMessage
import dev.tonnector.wallet.decodeBoc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ton.block.Message
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.loadTlb
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProtocolTest {
    @Test
    fun `v5 wallet id matches the published constants`() {
        assertEquals(2147483409, MockWallet.walletIdV5(-239, 0, 0))
        assertEquals(2147483645, MockWallet.walletIdV5(-3, 0, 0))
        assertEquals(8388369, MockWallet.walletIdV5(-239, -1, 0))
    }

    @Test
    fun `v4 and v5 external messages parse`() {
        for (version in listOf("v4r2", "v5r1")) {
            val wallet = wallet(version)
            val signed = wallet.signTransfer(
                seqno = 0,
                validUntil = (System.currentTimeMillis() / 1000 + 120).toInt(),
                messages = listOf(OutMessage(wallet.identity.addressRaw, "1500000")),
            )
            val cell = decodeBoc(signed.bocBase64)
            cell.beginParse().loadTlb(Message.tlbCodec(AnyTlbConstructor))
            assertTrue(signed.bocBase64.isNotBlank())
            assertTrue(wallet.identity.stateInitBoc.isNotBlank())
        }
    }

    @Test
    fun `dApp connect and sendTransaction through the local bridge`() {
        val port = ServerSocket(0).use { it.localPort }
        val config = AppConfig(
            port = port,
            privateKey = "22".repeat(32),
            mnemonic = "",
            network = "testnet",
            version = "v4r2",
        )
        val wallet = MockWallet.fromConfig(config)
        val bus = MessageBus()
        val controller = AppController(config, wallet, bus, "build/test-tonnector.json")
        val server = BridgeServer(controller, bus)
        server.start()
        try {
            Thread.sleep(250)
            val dapp = SessionCrypto.generate()
            val connectBody = AtomicReference<String>()
            val connectLatch = CountDownLatch(1)
            val txBody = AtomicReference<String>()
            val txLatch = CountDownLatch(1)
            val reader = Thread {
                val connection = http(port, "/bridge/events?client_id=${dapp.sessionId}", "GET", null, readTimeout = 0)
                connection.inputStream.bufferedReader().use { input ->
                    val data = StringBuilder()
                    while (!Thread.currentThread().isInterrupted) {
                        val line = input.readLine() ?: break
                        if (line.startsWith("data:")) data.append(line.removePrefix("data:").trim())
                        if (line.isEmpty() && data.isNotEmpty()) {
                            val event = data.toString()
                            data.clear()
                            if (!event.startsWith("{")) continue
                            val from = Regex(""""from"\s*:\s*"([0-9a-f]+)"""").find(event)?.groupValues?.get(1) ?: continue
                            val message = Regex(""""message"\s*:\s*"([^"]+)"""").find(event)?.groupValues?.get(1) ?: continue
                            val plain = dapp.decrypt(Base64.getDecoder().decode(message), from.hexToBytes()) ?: continue
                            val text = plain.toString(Charsets.UTF_8)
                            if (text.contains("\"connect\"")) {
                                connectBody.set(text)
                                connectLatch.countDown()
                            }
                            if (text.contains("\"result\"")) {
                                txBody.set(text)
                                txLatch.countDown()
                            }
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()

            val requestJson = """{"manifestUrl":"http://127.0.0.1:$port/sample/tonconnect-manifest.json","items":[{"name":"ton_addr"},{"name":"ton_proof","payload":"nonce-1"}]}"""
            val link = "/ton-connect?v=2&id=${dapp.sessionId}&r=${URLEncoder.encode(requestJson, Charsets.UTF_8)}&ret=back"
            val page = http(port, link, "GET", null, origin = "http://localhost:3000")
            assertEquals(200, page.responseCode)
            assertEquals("*", page.getHeaderField("Access-Control-Allow-Origin"))
            assertEquals("true", page.getHeaderField("Access-Control-Allow-Private-Network"))
            page.disconnect()

            val pending = waitFor {
                controller.requests().firstOrNull { it.second == "pending" && it.first.kind == "connect" }?.first
            }
            controller.approve(pending.id)
            assertTrue(connectLatch.await(8, TimeUnit.SECONDS), "connect event was not delivered")
            assertTrue(connectBody.get().contains(wallet.identity.addressRaw))
            assertTrue(connectBody.get().contains("ton_proof"))

            val rpc = """{"id":"42","method":"sendTransaction","params":["{\"valid_until\":${System.currentTimeMillis() / 1000 + 300},\"network\":\"-3\",\"from\":\"${wallet.identity.addressRaw}\",\"messages\":[{\"address\":\"${wallet.identity.addressRaw}\",\"amount\":\"1000000\"}]}"]}"""
            val walletId = controller.walletClientIdFor(dapp.sessionId)
            val encrypted = Base64.getEncoder().encodeToString(dapp.encrypt(rpc.encodeToByteArray(), walletId.hexToBytes()))
            val post = http(
                port,
                "/bridge/message?client_id=${dapp.sessionId}&to=$walletId&ttl=300",
                "POST",
                encrypted,
                origin = "http://localhost:3000",
            )
            assertEquals(200, post.responseCode)
            post.disconnect()
            val tx = waitFor {
                controller.requests().firstOrNull { it.first.kind == "sendTransaction" && it.second == "pending" }?.first
            }
            controller.approve(tx.id)
            assertTrue(txLatch.await(8, TimeUnit.SECONDS), "signed response was not delivered")
            val boc = Regex(""""result"\s*:\s*"([^"]+)"""").find(txBody.get())!!.groupValues[1]
            decodeBoc(boc).beginParse().loadTlb(Message.tlbCodec(AnyTlbConstructor))
            assertEquals(1, controller.seqno)
            reader.interrupt()
        } finally {
            server.stop()
        }
    }
}

private fun wallet(version: String) = MockWallet.fromConfig(
    AppConfig(privateKey = "33".repeat(32), mnemonic = "", version = version, network = "testnet"),
)

private fun <T> waitFor(block: () -> T?): T {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < 5000) {
        val value = block()
        if (value != null) return value
        Thread.sleep(25)
    }
    error("timed out")
}

private fun http(
    port: Int,
    path: String,
    method: String,
    body: String?,
    origin: String? = null,
    readTimeout: Int = 4000,
): HttpURLConnection {
    val connection = (URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection)
    connection.requestMethod = method
    connection.connectTimeout = 4000
    connection.readTimeout = readTimeout
    if (origin != null) connection.setRequestProperty("Origin", origin)
    if (body != null) {
        connection.doOutput = true
        connection.outputStream.use { it.write(body.encodeToByteArray()) }
    }
    return connection
}
