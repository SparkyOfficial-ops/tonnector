package dev.tonnector.bridge

import dev.tonnector.app.AppController
import dev.tonnector.app.Decision
import dev.tonnector.qr.qrPng
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.httpMethod
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
class BridgeServer(
    private val controller: AppController,
    private val bus: MessageBus,
) {
    private val json = Json { prettyPrint = true }
    private var engine: ApplicationEngine? = null

    fun start() {
        val host = controller.config.host
        val port = controller.config.port
        engine = embeddedServer(Netty, host = host, port = port, configure = {
            callGroupSize = 8
            responseWriteTimeoutSeconds = 120
        }) {
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, OPTIONS")
                call.response.header(HttpHeaders.AccessControlAllowHeaders, "*")
                call.response.header("Access-Control-Allow-Private-Network", "true")
                call.response.header(HttpHeaders.CacheControl, "no-store")
                if (call.request.httpMethod == HttpMethod.Options) {
                    call.respond(HttpStatusCode.NoContent)
                    finish()
                }
            }
            routing {
                get("/") { call.respondText(resource("console.html"), ContentType.Text.Html) }
                get("/icon.svg") { call.respondText(resource("icon.svg"), ContentType.Image.SVG) }
                get("/sample") { call.respondRedirect("/sample/") }
                get("/sample/") { call.respondText(resource("sample/index.html"), ContentType.Text.Html) }
                get("/sample/tonconnect-manifest.json") {
                    val base = "http://${controller.config.host}:${controller.config.port}"
                    call.respondText(
                        """{"url":"$base/sample/","name":"TONNECTOR Sample","iconUrl":"$base/icon.svg"}""",
                        ContentType.Application.Json,
                    )
                }
                get("/sample/nacl-fast.min.js") {
                    call.respondText(resource("sample/nacl-fast.min.js"), ContentType.Application.JavaScript)
                }
                get("/wallets-v2.json") { call.respondText(walletsJson(), ContentType.Application.Json) }
                get("/bridge/events") { call.streamEvents() }
                post("/bridge/message") { call.postMessage() }
                get("/ton-connect") { call.connectPage() }
                get("/api/state") { call.respondText(stateJson(), ContentType.Application.Json) }
                get("/api/requests") { call.respondText(requestsJson(), ContentType.Application.Json) }
                get("/api/log") { call.respondText(logJson(), ContentType.Application.Json) }
                get("/api/qr") {
                    val text = call.request.queryParameters["text"] ?: controller.links().universal
                    call.respondBytes(qrPng(text), ContentType.Image.PNG)
                }
                post("/api/requests/{id}/approve") { call.decide(true) }
                post("/api/requests/{id}/reject") { call.decide(false) }
                post("/api/disconnect") {
                    controller.disconnectAll()
                    call.respondText("""{"ok":true}""", ContentType.Application.Json)
                }
                post("/api/connect") {
                    val body = call.receiveText()
                    val link = Json.parseToJsonElement(body).let {
                        (it as? kotlinx.serialization.json.JsonObject)?.get("link")?.toString()?.trim('"') ?: body
                    }
                    val request = controller.openConnectLink(link)
                    call.respondText("""{"id":"${request.id}"}""", ContentType.Application.Json)
                }
                post("/api/wallet") { call.updateWallet() }
                post("/api/seqno") {
                    val body = call.receiveText()
                    val value = Regex(""""seqno"\s*:\s*(\d+)""").find(body)?.groupValues?.get(1)?.toInt()
                        ?: body.trim().toInt()
                    controller.setSeqno(value)
                    call.respondText(stateJson(), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(500, 1000)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.streamEvents() {
        val raw = request.queryParameters["client_id"] ?: return respondText("client_id required", status = HttpStatusCode.BadRequest)
        val ids = raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return respondText("client_id required", status = HttpStatusCode.BadRequest)
        val last = request.queryParameters["last_event_id"]?.toLongOrNull() ?: 0L
        val sub = bus.subscribe(ids)
        response.header(HttpHeaders.CacheControl, "no-cache")
        response.header(HttpHeaders.Connection, "keep-alive")
        response.header("X-Accel-Buffering", "no")
        respond(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.EventStream
            override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                suspend fun frame(text: String) {
                    channel.writeFully(text.encodeToByteArray())
                    channel.flush()
                }
                coroutineScope {
                    val heart = launch {
                        while (isActive) {
                            delay(5_000)
                            frame(": heartbeat\n\n")
                        }
                    }
                    try {
                        frame(": connected\n\n")
                        bus.stream(sub, last) { event ->
                            val data = buildJsonObject {
                                put("from", event.from)
                                put("message", event.message)
                            }.toString()
                            frame("id: ${event.id}\nevent: message\ndata: $data\n\n")
                        }
                    } finally {
                        heart.cancel()
                        bus.unsubscribe(sub)
                    }
                }
            }
        })
    }

    private suspend fun io.ktor.server.application.ApplicationCall.postMessage() {
        val from = request.queryParameters["client_id"]?.lowercase()
        val to = request.queryParameters["to"]?.lowercase()
        val ttl = request.queryParameters["ttl"]?.toLongOrNull() ?: 300L
        if (from.isNullOrBlank() || to.isNullOrBlank()) {
            return respondText("client_id and to are required", status = HttpStatusCode.BadRequest)
        }
        if (ttl <= 0 || ttl > 86_400) {
            return respondText("ttl out of range", status = HttpStatusCode.BadRequest)
        }
        val body = receiveText().trim().trim('"')
        if (body.isBlank()) return respondText("empty body", status = HttpStatusCode.BadRequest)
        controller.log("bridge $from → $to (${body.length} chars, ttl ${ttl}s)")
        bus.enqueue(to, from, body, ttl)
        controller.onDappMessage(from, to, body)
        respondText("OK")
    }

    private suspend fun io.ktor.server.application.ApplicationCall.connectPage() {
        val query = request.queryString()
        val link = "http://${controller.config.host}:${controller.config.port}/ton-connect?$query"
        val request = try {
            controller.openConnectLink(link)
        } catch (e: Exception) {
            return respondText(errorPage(e.message ?: "bad connect link"), ContentType.Text.Html)
        }
        respondText(connectHtml(request.id, request.summary, request.detail), ContentType.Text.Html)
    }

    private suspend fun io.ktor.server.application.ApplicationCall.decide(approve: Boolean) {
        val id = parameters["id"] ?: return respondText("missing id", status = HttpStatusCode.BadRequest)
        val redirect = request.queryParameters["redirect"] == "1"
        val code = request.queryParameters["code"]?.toIntOrNull() ?: 300
        try {
            if (approve) controller.approve(id) else controller.reject(id, decisionOf(code))
            if (redirect) respondText(donePage(approve), ContentType.Text.Html)
            else respondText("""{"ok":true}""", ContentType.Application.Json)
        } catch (e: Exception) {
            if (redirect) respondText(errorPage(e.message ?: "error"), ContentType.Text.Html)
            else respondText("""{"ok":false,"error":"${escape(e.message ?: "error")}"}""", status = HttpStatusCode.BadRequest)
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.updateWallet() {
        val body = Json.parseToJsonElement(receiveText()) as kotlinx.serialization.json.JsonObject
        fun field(name: String) = (body[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val next = controller.config.copy(
            network = field("network") ?: controller.config.network,
            version = field("version") ?: controller.config.version,
            mnemonic = field("mnemonic") ?: controller.config.mnemonic,
            privateKey = field("privateKey") ?: controller.config.privateKey,
            workchain = field("workchain")?.toIntOrNull() ?: controller.config.workchain,
            autoApproveConnect = field("autoApproveConnect")?.toBooleanStrictOrNull() ?: controller.config.autoApproveConnect,
        )
        controller.updateWallet(next)
        respondText(stateJson(), ContentType.Application.Json)
    }

    private fun stateJson(): String {
        val id = controller.wallet.identity
        val links = controller.links()
        return buildJsonObject {
            put("connected", controller.isConnected())
            put("sessions", controller.sessionCount())
            put("address", id.addressRaw)
            put("friendly", id.addressFriendly)
            put("publicKey", id.publicKeyHex)
            put("version", id.version)
            put("network", id.network)
            put("globalId", id.globalId)
            put("seqno", controller.seqno)
            put("mnemonic", controller.config.mnemonic)
            put("port", controller.config.port)
            put("bridgeUrl", links.bridge)
            put("universalUrl", links.universal)
            put("walletsUrl", links.wallets)
            put("sampleUrl", links.sample)
        }.toString()
    }

    private fun requestsJson(): String {
        val array = buildJsonArray {
            controller.requests().asReversed().forEach { (request, status) ->
                add(buildJsonObject {
                    put("id", request.id)
                    put("kind", request.kind)
                    put("title", request.title)
                    put("summary", request.summary)
                    put("detail", request.detail)
                    put("status", status)
                    put("createdAt", request.createdAt)
                })
            }
        }
        return array.toString()
    }

    private fun logJson(): String = buildJsonArray {
        controller.logLines().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }.toString()

    private fun walletsJson(): String {
        val links = controller.links()
        val wallet = buildJsonObject {
            put("name", "TONNECTOR")
            put("app_name", "tonnector")
            put("image", links.icon)
            put("about_url", links.home)
            put("universal_url", links.universal)
            put("bridge", buildJsonArray {
                add(buildJsonObject {
                    put("type", "sse")
                    put("url", links.bridge)
                })
            })
            put("platforms", buildJsonArray {
                listOf("chrome", "firefox", "safari", "windows", "macos", "linux", "android", "ios").forEach {
                    add(kotlinx.serialization.json.JsonPrimitive(it))
                }
            })
        }
        return "[$wallet]"
    }

    private fun resource(path: String): String =
        AppController::class.java.classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("missing resource $path")
}

data class Links(
    val home: String,
    val bridge: String,
    val universal: String,
    val wallets: String,
    val sample: String,
    val icon: String,
)

fun AppController.links(): Links {
    val base = "http://${config.host}:${config.port}"
    return Links(
        home = base,
        bridge = "$base/bridge",
        universal = "$base/ton-connect",
        wallets = "$base/wallets-v2.json",
        sample = "$base/sample/",
        icon = "$base/icon.svg",
    )
}

private fun decisionOf(code: Int): Decision = when (code) {
    1 -> Decision.BAD_REQUEST
    400 -> Decision.NOT_SUPPORTED
    else -> Decision.REJECT
}

private fun connectHtml(id: String, summary: String, detail: String): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<title>TONNECTOR · connect</title>
<link rel="icon" href="/icon.svg"/>
<style>
  body { margin:0; font-family:Segoe UI,sans-serif; background:#0b1220; color:#e8eef8; }
  main { max-width:640px; margin:48px auto; padding:28px; background:#121a2b; border-radius:16px; }
  h1 { margin:0 0 8px; font-size:22px; }
  .muted { color:#93a0b8; }
  pre { white-space:pre-wrap; background:#0b1220; padding:12px; border-radius:10px; }
  button { border:0; border-radius:10px; padding:12px 18px; font-weight:700; cursor:pointer; margin-right:8px; }
  .ok { background:#2f9e6b; color:white; }
  .no { background:#c44747; color:white; }
  .bad { background:#3a445c; color:white; }
</style>
</head>
<body>
<main>
  <h1>TONNECTOR</h1>
  <p class="muted">The dApp wants to connect. Approving returns a signed TonConnect event on the local bridge.</p>
  <h2>${escape(summary)}</h2>
  <pre>${escape(detail)}</pre>
  <form method="post" action="/api/requests/$id/approve?redirect=1" style="display:inline">
    <button class="ok" type="submit">Approve</button>
  </form>
  <form method="post" action="/api/requests/$id/reject?redirect=1&code=300" style="display:inline">
    <button class="no" type="submit">Reject</button>
  </form>
  <form method="post" action="/api/requests/$id/reject?redirect=1&code=1" style="display:inline">
    <button class="bad" type="submit">Bad request</button>
  </form>
  <p class="muted">You can also approve this request in the desktop window or at <a style="color:#7eb6ff" href="/">the console</a>.</p>
</main>
</body>
</html>
""".trimIndent()

private fun donePage(approved: Boolean): String = """
<!doctype html>
<html><head><meta charset="utf-8"><title>TONNECTOR</title>
<style>body{font-family:Segoe UI,sans-serif;background:#0b1220;color:#e8eef8;padding:48px}</style>
</head><body>
<h1>${if (approved) "Approved" else "Declined"}</h1>
<p>Return to the dApp. This tab can be closed.</p>
<script>setTimeout(()=>window.close(), 600)</script>
</body></html>
""".trimIndent()

private fun errorPage(message: String): String = """
<!doctype html>
<html><head><meta charset="utf-8"><title>TONNECTOR</title>
<style>body{font-family:Segoe UI,sans-serif;background:#0b1220;color:#e8eef8;padding:48px}</style>
</head><body><h1>Could not open the connect link</h1><pre>${escape(message)}</pre></body></html>
""".trimIndent()

private fun escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
