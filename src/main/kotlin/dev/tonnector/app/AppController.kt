package dev.tonnector.app

import dev.tonnector.bridge.MessageBus
import dev.tonnector.config.AppConfig
import dev.tonnector.config.ConfigStore
import dev.tonnector.crypto.SessionCrypto
import dev.tonnector.crypto.hexToBytes
import dev.tonnector.crypto.toHex
import dev.tonnector.protocol.domainOf
import dev.tonnector.protocol.parseConnectLink
import dev.tonnector.wallet.MockWallet
import dev.tonnector.wallet.OutMessage
import dev.tonnector.wallet.nanoToTon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
enum class Decision(val code: Int) {
    APPROVE(0),
    REJECT(300),
    BAD_REQUEST(1),
    NOT_SUPPORTED(400),
}

data class IncomingRequest(
    val id: String,
    val kind: String,
    val title: String,
    val summary: String,
    val detail: String,
    val createdAt: Long,
    val sessionKey: String,
    val rpcId: JsonElement?,
    val payload: JsonObject?,
)

data class Session(
    val dappClientId: String,
    val crypto: SessionCrypto,
    val manifestUrl: String,
    val appName: String,
    val domain: String,
    val proofPayload: String?,
    var connected: Boolean,
    var nextEventId: Long,
)

class AppController(
    var config: AppConfig,
    var wallet: MockWallet,
    val bus: MessageBus,
    private val configPath: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private val requests = LinkedHashMap<String, IncomingRequest>()
    private val status = LinkedHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val logs = ArrayDeque<String>()
    var seqno: Int = config.seqno
        private set

    fun listen(listener: (String) -> Unit) {
        listeners += listener
    }

    fun logLines(): List<String> = synchronized(lock) { logs.toList() }

    fun requests(): List<Pair<IncomingRequest, String>> = synchronized(lock) {
        requests.values.map { it to (status[it.id] ?: "pending") }
    }

    fun request(id: String): Pair<IncomingRequest, String>? = synchronized(lock) {
        requests[id]?.let { it to (status[id] ?: "pending") }
    }

    fun isConnected(): Boolean = synchronized(lock) { sessions.values.any { it.connected } }

    fun sessionCount(): Int = synchronized(lock) { sessions.values.count { it.connected } }

    fun walletClientIdFor(dappClientId: String): String = synchronized(lock) {
        sessions[dappClientId.lowercase()]?.crypto?.sessionId ?: error("no session for $dappClientId")
    }

    fun log(line: String) {
        val stamped = "%tT %s".format(System.currentTimeMillis(), line)
        synchronized(lock) {
            logs.addLast(stamped)
            while (logs.size > 400) logs.removeFirst()
        }
        println(stamped)
        fire("log")
    }

    fun openConnectLink(link: String): IncomingRequest {
        val params = parseConnectLink(link)
        val clientId = params["id"] ?: params["client_id"] ?: error("connect link has no id")
        require(clientId.length == 64) { "client id must be 64 hex chars" }
        val rawRequest = params["r"] ?: error("connect link has no r")
        val requestJson = resolveConnectRequest(rawRequest)
        val root = json.parseToJsonElement(requestJson).jsonObject
        val manifestUrl = root.string("manifestUrl") ?: error("manifestUrl is missing")
        val manifest = fetchManifest(manifestUrl)
        val items = root["items"] as? JsonArray
        val proof = items.orEmpty().firstNotNullOfOrNull { element ->
            val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
            if (obj.string("name") == "ton_proof") obj.string("payload") else null
        }
        val session = Session(
            dappClientId = clientId.lowercase(),
            crypto = SessionCrypto.generate(),
            manifestUrl = manifestUrl,
            appName = manifest?.name?.ifBlank { domainOf(manifestUrl) } ?: domainOf(manifestUrl),
            domain = domainOf(manifest?.url?.ifBlank { manifestUrl } ?: manifestUrl),
            proofPayload = proof,
            connected = false,
            nextEventId = System.currentTimeMillis() / 1000,
        )
        synchronized(lock) { sessions[session.dappClientId] = session }
        val itemNames = items.orEmpty().mapNotNull { (it as? JsonObject)?.string("name") }
        val detail = buildString {
            appendLine("dApp: ${session.appName}")
            appendLine("domain: ${session.domain}")
            appendLine("manifest: $manifestUrl")
            appendLine("items: ${itemNames.joinToString().ifBlank { "ton_addr" }}")
            appendLine("dApp client: $clientId")
            appendLine("return: ${params["ret"] ?: "none"}")
            if (proof != null) appendLine("ton_proof payload: $proof")
        }
        log("connect request from ${session.appName} ($clientId)")
        return addRequest(
            IncomingRequest(
                id = UUID.randomUUID().toString(),
                kind = "connect",
                title = "Connect ${session.appName}",
                summary = "${session.appName}  ·  ${session.domain}",
                detail = detail.trimEnd(),
                createdAt = System.currentTimeMillis(),
                sessionKey = session.dappClientId,
                rpcId = null,
                payload = root,
            ),
        )
    }

    fun onDappMessage(from: String, to: String, bodyBase64: String) {
        val session = synchronized(lock) {
            sessions.values.firstOrNull { it.crypto.sessionId.equals(to, ignoreCase = true) }
        } ?: run {
            log("message for unknown wallet client $to")
            return
        }
        if (!from.equals(session.dappClientId, ignoreCase = true)) {
            log("message from unexpected client $from")
            return
        }
        val plain = try {
            val bytes = Base64.getDecoder().decode(bodyBase64.trim())
            session.crypto.decrypt(bytes, from.hexToBytes())
        } catch (e: Exception) {
            log("decrypt failed: ${e.message}")
            null
        } ?: run {
            log("discarded undecryptable message from $from")
            return
        }
        val text = plain.toString(Charsets.UTF_8)
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            log("bad json from dApp: ${e.message}")
            return
        }
        val method = root.string("method") ?: root.string("event") ?: "unknown"
        log("dApp → $method")
        when (method) {
            "disconnect" -> {
                session.connected = false
                log("dApp disconnected ${session.appName}")
                val id = root["id"]
                if (id != null) {
                    sendToDapp(session, buildJsonObject {
                        put("id", id)
                        put("result", buildJsonObject { })
                    }.toString())
                }
                fire("state")
            }
            "sendTransaction" -> enqueueRpc(session, root, "sendTransaction")
            "signData" -> enqueueRpc(session, root, "signData")
            else -> enqueueRpc(session, root, method)
        }
    }

    fun approve(id: String): String = decide(id, Decision.APPROVE)

    fun reject(id: String, decision: Decision): String {
        require(decision != Decision.APPROVE)
        return decide(id, decision)
    }

    fun disconnectAll() {
        val open = synchronized(lock) { sessions.values.filter { it.connected } }
        for (session in open) {
            session.connected = false
            val eventId = session.nextEventId++
            sendToDapp(session, buildJsonObject {
                put("event", "disconnect")
                put("id", eventId)
                put("payload", buildJsonObject { })
            }.toString())
        }
        log("wallet disconnected ${open.size} session(s)")
        fire("state")
    }

    fun updateWallet(newConfig: AppConfig) {
        val path = ConfigStore.resolvePath(configPath)
        ConfigStore.save(path, newConfig.copy(seqno = seqno))
        config = newConfig
        wallet = MockWallet.fromConfig(newConfig)
        disconnectAll()
        log("wallet reloaded ${wallet.identity.addressRaw} (${wallet.identity.version})")
        fire("state")
    }

    fun setSeqno(value: Int) {
        require(value >= 0)
        seqno = value
        persistSeqno()
        fire("state")
    }

    private fun decide(id: String, decision: Decision): String {
        val request = synchronized(lock) {
            val current = requests[id] ?: error("unknown request")
            if (status[id] != "pending") error("request already ${status[id]}")
            current
        }
        val session = synchronized(lock) { sessions[request.sessionKey] } ?: error("session expired")
        return try {
            val result = when (request.kind) {
                "connect" -> answerConnect(session, decision)
                "sendTransaction" -> answerTransaction(session, request, decision)
                "signData" -> answerSignData(session, request, decision)
                else -> answerUnsupported(session, request, decision)
            }
            synchronized(lock) { status[id] = if (decision == Decision.APPROVE) "approved" else "rejected" }
            log("${request.kind} ${if (decision == Decision.APPROVE) "approved" else decision.name} · ${session.appName}")
            fire("request")
            fire("state")
            result
        } catch (e: Exception) {
            log("failed to answer ${request.kind}: ${e.message}")
            if (request.rpcId != null) {
                sendToDapp(session, errorResponse(request.rpcId, 0, e.message ?: "Unknown error"))
            }
            synchronized(lock) { status[id] = "error" }
            fire("request")
            throw e
        }
    }

    private fun answerConnect(session: Session, decision: Decision): String {
        val eventId = session.nextEventId++
        val body = if (decision != Decision.APPROVE) {
            buildJsonObject {
                put("event", "connect_error")
                put("id", eventId)
                put("payload", buildJsonObject {
                    put("code", decision.code)
                    put("message", errorText("connect", decision))
                })
            }
        } else {
            session.connected = true
            val id = wallet.identity
            val items = buildJsonArray {
                add(buildJsonObject {
                    put("name", "ton_addr")
                    put("address", id.addressRaw)
                    put("network", id.globalId.toString())
                    put("publicKey", id.publicKeyHex)
                    put("walletStateInit", id.stateInitBoc)
                })
                if (session.proofPayload != null) {
                    val timestamp = System.currentTimeMillis() / 1000
                    add(buildJsonObject {
                        put("name", "ton_proof")
                        put("proof", buildJsonObject {
                            put("timestamp", timestamp)
                            put("domain", buildJsonObject {
                                put("lengthBytes", session.domain.encodeToByteArray().size)
                                put("value", session.domain)
                            })
                            put("signature", wallet.signTonProof(session.domain, timestamp, session.proofPayload))
                            put("payload", session.proofPayload)
                        })
                    })
                }
            }
            buildJsonObject {
                put("event", "connect")
                put("id", eventId)
                put("payload", buildJsonObject {
                    put("items", items)
                    put("device", deviceInfo())
                })
            }
        }
        val text = body.toString()
        sendToDapp(session, text)
        return text
    }

    private fun answerTransaction(session: Session, request: IncomingRequest, decision: Decision): String {
        if (decision != Decision.APPROVE) {
            val text = errorResponse(request.rpcId ?: JsonPrimitive("0"), decision.code, errorText("sendTransaction", decision))
            sendToDapp(session, text)
            return text
        }
        val tx = transactionObject(request.payload)
        val validUntil = tx.string("valid_until")?.toLongOrNull()
        if (validUntil != null && validUntil < System.currentTimeMillis() / 1000) {
            val text = errorResponse(request.rpcId ?: JsonPrimitive("0"), 1, "Transaction expired")
            sendToDapp(session, text)
            return text
        }
        val messages = readMessages(tx)
        val until = (validUntil ?: (System.currentTimeMillis() / 1000 + 300)).toInt()
        val signed = wallet.signTransfer(seqno, until, messages)
        seqno += 1
        persistSeqno()
        val text = buildJsonObject {
            put("id", request.rpcId ?: JsonPrimitive("0"))
            put("result", signed.bocBase64)
        }.toString()
        sendToDapp(session, text)
        return signed.bocBase64
    }

    private fun answerSignData(session: Session, request: IncomingRequest, decision: Decision): String {
        if (decision != Decision.APPROVE) {
            val text = errorResponse(request.rpcId ?: JsonPrimitive("0"), decision.code, errorText("signData", decision))
            sendToDapp(session, text)
            return text
        }
        val payload = signPayload(request.payload)
        val type = payload.string("type") ?: error("signData type is missing")
        val timestamp = System.currentTimeMillis() / 1000
        val signature = when (type) {
            "text" -> wallet.signDataTextOrBinary(
                session.domain,
                timestamp,
                "txt",
                (payload.string("text") ?: error("text is missing")).encodeToByteArray(),
            )
            "binary" -> wallet.signDataTextOrBinary(
                session.domain,
                timestamp,
                "bin",
                Base64.getDecoder().decode(payload.string("bytes") ?: error("bytes are missing")),
            )
            "cell" -> wallet.signDataCell(
                session.domain,
                timestamp,
                payload.string("schema") ?: error("schema is missing"),
                payload.string("cell") ?: error("cell is missing"),
            )
            else -> error("unsupported signData type $type")
        }
        val text = buildJsonObject {
            put("id", request.rpcId ?: JsonPrimitive("0"))
            put("result", buildJsonObject {
                put("signature", signature)
                put("address", wallet.identity.addressRaw)
                put("timestamp", timestamp)
                put("domain", session.domain)
                put("payload", payload)
            })
        }.toString()
        sendToDapp(session, text)
        return signature
    }

    private fun answerUnsupported(session: Session, request: IncomingRequest, decision: Decision): String {
        val code = if (decision == Decision.APPROVE) 400 else decision.code
        val text = errorResponse(request.rpcId ?: JsonPrimitive("0"), code, errorText(request.kind, Decision.NOT_SUPPORTED))
        sendToDapp(session, text)
        return text
    }

    private fun enqueueRpc(session: Session, root: JsonObject, method: String) {
        val params = root["params"] as? JsonArray
        val first = params?.firstOrNull()
        val payload = when (first) {
            is JsonObject -> first
            is JsonPrimitive -> json.parseToJsonElement(first.content).jsonObject
            else -> null
        }
        val (summary, detail) = describe(method, payload, root)
        addRequest(
            IncomingRequest(
                id = UUID.randomUUID().toString(),
                kind = method,
                title = method,
                summary = summary,
                detail = detail,
                createdAt = System.currentTimeMillis(),
                sessionKey = session.dappClientId,
                rpcId = root["id"],
                payload = root,
            ),
        )
    }

    private fun describe(method: String, payload: JsonObject?, root: JsonObject): Pair<String, String> {
        if (method == "sendTransaction" && payload != null) {
            val messages = (payload["messages"] as? JsonArray).orEmpty()
            val lines = messages.mapNotNull { element ->
                val msg = element as? JsonObject ?: return@mapNotNull null
                val to = msg.string("address") ?: "?"
                val amount = msg.string("amount") ?: "0"
                val comment = msg.string("payload")?.let { " + payload" } ?: ""
                "${nanoToTon(amount)} TON → $to$comment"
            }
            val network = payload.string("network")
            val warn = if (network != null && network != wallet.identity.globalId.toString()) {
                "\nnetwork mismatch: request $network, wallet ${wallet.identity.globalId}"
            } else ""
            val summary = lines.firstOrNull() ?: "transaction"
            val extra = if (lines.size > 1) "  +${lines.size - 1}" else ""
            return summary + extra to (lines.joinToString("\n") + warn + "\n\n" + root.toString())
        }
        if (method == "signData" && payload != null) {
            val type = payload.string("type") ?: "?"
            val preview = payload.string("text") ?: payload.string("bytes") ?: payload.string("cell") ?: ""
            return "signData $type" to "type: $type\n$preview\n\n$root"
        }
        return method to root.toString()
    }

    private fun addRequest(request: IncomingRequest): IncomingRequest {
        synchronized(lock) {
            requests[request.id] = request
            status[request.id] = "pending"
            while (requests.size > 100) {
                val oldest = requests.keys.first()
                requests.remove(oldest)
                status.remove(oldest)
            }
        }
        fire("request")
        if (request.kind == "connect" && config.autoApproveConnect) {
            try {
                approve(request.id)
            } catch (e: Exception) {
                log("auto-approve failed: ${e.message}")
            }
        }
        return request
    }

    private fun sendToDapp(session: Session, plaintext: String) {
        val encrypted = session.crypto.encrypt(plaintext.encodeToByteArray(), session.dappClientId.hexToBytes())
        val body = Base64.getEncoder().encodeToString(encrypted)
        bus.enqueue(session.dappClientId, session.crypto.sessionId, body, 300)
    }

    private fun persistSeqno() {
        val updated = config.copy(seqno = seqno)
        config = updated
        ConfigStore.save(ConfigStore.resolvePath(configPath), updated)
    }

    private fun deviceInfo() = buildJsonObject {
        put("platform", platform())
        put("appName", "tonnector")
        put("appVersion", "1.0.0")
        put("maxProtocolVersion", 2)
        put("features", buildJsonArray {
            add(JsonPrimitive("SendTransaction"))
            add(buildJsonObject {
                put("name", "SendTransaction")
                put("maxMessages", 4)
                put("extraCurrencySupported", false)
            })
            add(buildJsonObject {
                put("name", "SignData")
                put("types", buildJsonArray {
                    add(JsonPrimitive("text"))
                    add(JsonPrimitive("binary"))
                    add(JsonPrimitive("cell"))
                })
            })
        })
    }

    private fun fire(kind: String) {
        listeners.forEach { listener ->
            try {
                listener(kind)
            } catch (_: Exception) {
            }
        }
    }

    private data class Manifest(val url: String, val name: String)

    private fun fetchManifest(url: String): Manifest? = try {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000
            readTimeout = 2000
            setRequestProperty("Accept", "application/json")
        }
        if (connection.responseCode !in 200..299) null
        else {
            val text = connection.inputStream.bufferedReader().readText()
            val obj = json.parseToJsonElement(text).jsonObject
            Manifest(obj.string("url") ?: "", obj.string("name") ?: "")
        }
    } catch (_: Exception) {
        null
    }

    private fun resolveConnectRequest(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("{")) return value
        if (value.startsWith("http://") || value.startsWith("https://")) {
            val connection = (URI(value).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
            }
            return connection.inputStream.bufferedReader().readText()
        }
        val decoded = try {
            Base64.getUrlDecoder().decode(value.padBase64())
        } catch (_: IllegalArgumentException) {
            Base64.getDecoder().decode(value.padBase64())
        }
        return decoded.toString(Charsets.UTF_8)
    }
}

private fun errorResponse(id: JsonElement, code: Int, message: String): String = buildJsonObject {
    put("id", id)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
    })
}.toString()

private fun errorText(method: String, decision: Decision): String = when (decision) {
    Decision.REJECT -> when (method) {
        "connect" -> "User declined the connection"
        "signData" -> "User declined the request"
        else -> "User declined the transaction"
    }
    Decision.BAD_REQUEST -> "Bad request"
    Decision.NOT_SUPPORTED -> "Method not supported"
    Decision.APPROVE -> "OK"
}

private fun transactionObject(root: JsonObject?): JsonObject {
    val params = root?.get("params") as? JsonArray ?: error("params are missing")
    val first = params.firstOrNull() ?: error("empty params")
    return when (first) {
        is JsonObject -> first
        is JsonPrimitive -> Json.parseToJsonElement(first.content).jsonObject
        else -> error("bad transaction params")
    }
}

private fun signPayload(root: JsonObject?): JsonObject = transactionObject(root)

private fun readMessages(tx: JsonObject): List<OutMessage> {
    val messages = tx["messages"] as? JsonArray ?: error("messages are missing")
    return messages.map { element ->
        val msg = element.jsonObject
        OutMessage(
            address = msg.string("address") ?: error("message address is missing"),
            amountNano = msg.string("amount") ?: error("message amount is missing"),
            payloadBoc = msg.string("payload"),
            stateInitBoc = msg.string("stateInit"),
        )
    }
}

private fun JsonObject.string(name: String): String? {
    val value = this[name] as? JsonPrimitive ?: return null
    return value.contentOrNull
}

private fun platform(): String {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "win" in os -> "windows"
        "mac" in os -> "macos"
        else -> "linux"
    }
}

private fun String.padBase64(): String = this + "=".repeat((4 - length % 4) % 4)
