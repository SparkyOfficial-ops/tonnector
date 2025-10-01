package dev.tonnector.wallet

import dev.tonnector.config.AppConfig
import dev.tonnector.crypto.hexToBytes
import dev.tonnector.crypto.sha256
import dev.tonnector.crypto.signDataHash
import dev.tonnector.crypto.toHex
import dev.tonnector.crypto.tonProofHash
import dev.tonnector.crypto.tonProofMessage
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.bitstring.BitString
import org.ton.block.AddrNone
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.block.CurrencyCollection
import org.ton.block.Either
import org.ton.block.ExtInMsgInfo
import org.ton.block.Maybe
import org.ton.block.Message
import org.ton.block.StateInit
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import org.ton.contract.wallet.MessageData
import org.ton.contract.wallet.WalletContract
import org.ton.contract.wallet.WalletTransferBuilder
import org.ton.contract.wallet.WalletV4R2Contract
import org.ton.mnemonic.Mnemonic
import org.ton.tlb.CellRef
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.loadTlb
import org.ton.tlb.storeTlb
import java.math.BigInteger
import java.util.Base64
import java.util.zip.CRC32
data class OutMessage(
    val address: String,
    val amountNano: String,
    val payloadBoc: String? = null,
    val stateInitBoc: String? = null,
    val bounce: Boolean? = null,
)

data class SignedBoc(
    val bocBase64: String,
    val messages: List<MessageView>,
)

data class MessageView(
    val to: String,
    val bounceable: Boolean,
    val amountNano: String,
    val amountTon: String,
    val payload: PayloadView,
    val hasStateInit: Boolean,
)

data class WalletIdentity(
    val version: String,
    val workchain: Int,
    val network: String,
    val globalId: Int,
    val publicKeyHex: String,
    val addressRaw: String,
    val addressFriendly: String,
    val stateInitBoc: String,
    val walletId: Long,
)

class MockWallet(
    private val privateKey: PrivateKeyEd25519,
    private val config: AppConfig,
) {
    val identity: WalletIdentity = buildIdentity()

    fun signTransfer(seqno: Int, validUntil: Int, messages: List<OutMessage>): SignedBoc {
        require(messages.isNotEmpty()) { "no messages" }
        require(messages.size <= 4) { "TONNECTOR signs up to 4 messages (got ${messages.size})" }
        val views = mutableListOf<MessageView>()
        val transfers = messages.map { msg ->
            val (dest, bounceableFlag) = parseDestination(msg.address)
            val bounceable = when {
                msg.stateInitBoc != null -> false
                msg.bounce != null -> msg.bounce
                else -> bounceableFlag
            }
            val body = msg.payloadBoc?.let { decodeBoc(it) } ?: CellBuilder.createCell { }
            val init = msg.stateInitBoc?.let { cell ->
                val parsed = decodeBoc(cell).beginParse().loadTlb(StateInit)
                CellRef(value = parsed, codec = StateInit)
            }
            views += MessageView(
                to = msg.address.trim(),
                bounceable = bounceable,
                amountNano = msg.amountNano,
                amountTon = nanoToTon(msg.amountNano),
                payload = previewPayload(if (msg.payloadBoc == null) null else body),
                hasStateInit = init != null,
            )
            WalletTransferBuilder().apply {
                destination = dest
                this.bounceable = bounceable
                coins = coins(msg.amountNano)
                sendMode = 3
                messageData = MessageData.raw(body, init)
            }.build()
        }
        val includeInit = seqno == 0
        val bodyOrMessage = if (config.normalizedVersion() == "v5r1") {
            externalBoc(
                dest = AddrStd(identity.addressRaw),
                body = v5Body(validUntil, seqno, transfers),
                stateInit = if (includeInit) v5StateInit().load() else null,
            )
        } else {
            val message = WalletV4R2Contract.transferMessage(
                AddrStd(identity.addressRaw),
                if (includeInit) v4StateInit().load() else null,
                privateKey,
                WalletContract.DEFAULT_WALLET_ID,
                validUntil,
                seqno,
                *transfers.toTypedArray(),
            )
            CellBuilder.createCell {
                storeTlb(Message.tlbCodec(AnyTlbConstructor), message)
            }
        }
        return SignedBoc(Base64.getEncoder().encodeToString(bodyOrMessage.toBoc()), views)
    }

    fun signTonProof(domain: String, timestamp: Long, payload: String): String {
        val hash = identityHash()
        val message = tonProofMessage(config.workchain, hash, domain, timestamp, payload)
        val signature = privateKey.sign(tonProofHash(message))
        return Base64.getEncoder().encodeToString(signature)
    }

    fun signDataTextOrBinary(domain: String, timestamp: Long, prefix: String, payload: ByteArray): String {
        val hash = signDataHash(config.workchain, identityHash(), domain, timestamp, prefix, payload)
        return Base64.getEncoder().encodeToString(privateKey.sign(hash))
    }

    fun signDataCell(domain: String, timestamp: Long, schema: String, cellBoc: String): String {
        val payloadCell = decodeBoc(cellBoc)
        val crc = CRC32()
        val schemaBytes = schema.encodeToByteArray()
        crc.update(schemaBytes)
        val domainCell = snake(dnsWire(domain.substringBefore(":")))
        val signing = CellBuilder.createCell {
            storeUInt(0x75569022L, 32)
            storeUInt(crc.value and 0xffffffffL, 32)
            storeUInt(timestamp, 64)
            storeAddress(config.workchain, identityHash())
            storeRef(domainCell)
            storeRef(payloadCell)
        }
        return Base64.getEncoder().encodeToString(privateKey.sign(signing.hash().toByteArray()))
    }

    private fun buildIdentity(): WalletIdentity {
        val version = config.normalizedVersion()
        val init = if (version == "v5r1") v5StateInit() else v4StateInit()
        val hash = init.hash().toByteArray()
        val raw = "${config.workchain}:${hash.toHex()}"
        val walletId = if (version == "v5r1") {
            walletIdV5(config.globalId(), config.workchain, 0).toU32()
        } else {
            WalletContract.DEFAULT_WALLET_ID.toU32()
        }
        return WalletIdentity(
            version = version,
            workchain = config.workchain,
            network = if (config.globalId() == -239) "mainnet" else "testnet",
            globalId = config.globalId(),
            publicKeyHex = privateKey.publicKey().key.toByteArray().toHex(),
            addressRaw = raw,
            addressFriendly = friendlyAddress(config.workchain, hash, bounceable = true, testnet = config.globalId() != -239),
            stateInitBoc = Base64.getEncoder().encodeToString(init.cell.toBoc()),
            walletId = walletId,
        )
    }

    private fun v4StateInit() = WalletV4R2Contract.stateInit(
        WalletV4R2Contract.Data(0, privateKey.publicKey()),
    )

    private fun v5StateInit(): CellRef<StateInit> {
        val data = CellBuilder.createCell {
            storeUInt(1, 1)
            storeUInt(0, 32)
            storeUInt(walletIdV5(config.globalId(), config.workchain, 0).toU32(), 32)
            storeBytes(privateKey.publicKey().key.toByteArray())
            storeUInt(0, 1)
        }
        return CellRef(value = StateInit(V5_CODE, data), codec = StateInit)
    }

    private fun v5Body(validUntil: Int, seqno: Int, transfers: List<org.ton.contract.wallet.WalletTransfer>): Cell {
        var outList = CellBuilder.createCell { }
        for (transfer in transfers) {
            val mode = transfer.sendMode or 2
            val messageCell = CellBuilder.createCell {
                storeTlb(
                    org.ton.block.MessageRelaxed.tlbCodec(AnyTlbConstructor),
                    transfer.toMessageRelaxed(),
                )
            }
            val prev = outList
            outList = CellBuilder.createCell {
                storeRef(prev)
                storeUInt(0x0ec3c86dL, 32)
                storeUInt(mode.toLong(), 8)
                storeRef(messageCell)
            }
        }
        val walletId = walletIdV5(config.globalId(), config.workchain, 0).toU32()
        val signing = CellBuilder.createCell {
            storeUInt(0x7369676eL, 32)
            storeUInt(walletId, 32)
            storeUInt(validUntil.toLong(), 32)
            storeUInt(seqno.toLong(), 32)
            storeUInt(1, 1)
            storeRef(outList)
            storeUInt(0, 1)
        }
        val signature = privateKey.sign(signing.hash().toByteArray())
        return CellBuilder.createCell {
            storeBits(signing.bits)
            storeBytes(signature)
            for (ref in signing.refs) storeRef(ref)
        }
    }

    private fun identityHash(): ByteArray = identity.addressRaw.substringAfter(":").hexToBytes()

    companion object {
        private val V5_CODE: Cell by lazy {
            BagOfCells(V5_CODE_HEX.hexToBytes()).first()
        }

        fun fromConfig(config: AppConfig): MockWallet {
            val seed = when {
                config.privateKey.isNotBlank() -> config.privateKey.hexToBytes().copyOf(32)
                config.mnemonic.isNotBlank() -> {
                    val words = config.mnemonic.trim().split(Regex("\\s+"))
                    Mnemonic(words).toSeed().copyOf(32)
                }
                else -> error("wallet mnemonic or privateKey is empty")
            }
            require(seed.size == 32) { "private key seed must be 32 bytes" }
            return MockWallet(PrivateKeyEd25519(seed), config)
        }

        fun walletIdV5(networkGlobalId: Int, workchain: Int, subwallet: Int): Int {
            require(subwallet in 0..0x7FFF)
            var context = 1 shl 31
            context = context or ((workchain and 0xFF) shl 23)
            context = context or (subwallet and 0x7FFF)
            return networkGlobalId xor context
        }
    }
}

private fun Cell.toBoc(): ByteArray = BagOfCells(this).toByteArray()

private fun CellBuilder.storeAddress(workchain: Int, hash: ByteArray) {
    storeUInt(2, 2)
    storeUInt(0, 1)
    storeInt(workchain.toLong(), 8)
    storeBytes(hash)
}

private fun snake(data: ByteArray): Cell {
    val chunk = 127
    fun write(offset: Int): Cell = CellBuilder.createCell {
        val n = minOf(chunk, data.size - offset)
        storeBytes(data.copyOfRange(offset, offset + n))
        if (offset + n < data.size) storeRef(write(offset + n))
    }
    return if (data.isEmpty()) CellBuilder.createCell { } else write(0)
}

private fun dnsWire(domain: String): ByteArray {
    val labels = domain.trim('.').split('.').filter { it.isNotEmpty() }.asReversed()
    val raw = labels.joinToString("\u0000", postfix = "\u0000")
    return raw.encodeToByteArray()
}

private fun externalBoc(dest: AddrStd, body: Cell, stateInit: StateInit?): Cell {
    val info = ExtInMsgInfo(src = AddrNone, dest = dest, importFee = Coins())
    val maybeInit = Maybe.of(
        stateInit?.let {
            Either.of<StateInit, CellRef<StateInit>>(null, CellRef(value = it, codec = StateInit))
        },
    )
    val eitherBody = Either.of<Cell, CellRef<Cell>>(null, CellRef(cell = body, codec = AnyTlbConstructor))
    val message = Message(info = info, init = maybeInit, body = eitherBody)
    return CellBuilder.createCell {
        storeTlb(Message.tlbCodec(AnyTlbConstructor), message)
    }
}

private fun coins(nano: String): Coins {
    val value = BigInteger(nano.trim())
    require(value.signum() >= 0) { "negative amount" }
    require(value.bitLength() <= 63) { "amount does not fit into a signed 64-bit nanotons value" }
    return Coins.ofNano(value.toLong())
}

fun nanoToTon(nano: String): String {
    val value = BigInteger(nano.trim())
    val negative = value.signum() < 0
    val abs = value.abs()
    val whole = abs.divide(BigInteger.valueOf(1_000_000_000L))
    val frac = abs.remainder(BigInteger.valueOf(1_000_000_000L)).toString().padStart(9, '0').trimEnd('0')
    val text = if (frac.isEmpty()) whole.toString() else "$whole.$frac"
    return if (negative) "-$text" else text
}

fun decodeBoc(encoded: String): Cell {
    val cleaned = encoded.trim().replace("\\s".toRegex(), "")
    val bytes = try {
        Base64.getDecoder().decode(cleaned)
    } catch (_: IllegalArgumentException) {
        Base64.getUrlDecoder().decode(cleaned)
    }
    return BagOfCells(bytes).first()
}

private fun parseDestination(address: String): Pair<AddrStd, Boolean> {
    val trimmed = address.trim()
    if (':' in trimmed && !trimmed.contains('-') && trimmed.substringBefore(':').toIntOrNull() != null) {
        return AddrStd(trimmed) to true
    }
    val raw = try {
        Base64.getUrlDecoder().decode(trimmed.padBase64())
    } catch (_: IllegalArgumentException) {
        Base64.getDecoder().decode(trimmed.padBase64())
    }
    require(raw.size == 36) { "bad friendly address" }
    val tag = raw[0].toInt() and 0xFF
    val bounceable = tag and 0x40 == 0
    return AddrStd(trimmed) to bounceable
}

private fun String.padBase64(): String {
    val pad = (4 - length % 4) % 4
    return this + "=".repeat(pad)
}

fun friendlyAddress(workchain: Int, hash: ByteArray, bounceable: Boolean, testnet: Boolean): String {
    var tag = if (bounceable) 0x11 else 0x51
    if (testnet) tag = tag or 0x80
    val body = ByteArray(34)
    body[0] = tag.toByte()
    body[1] = workchain.toByte()
    hash.copyInto(body, 2)
    val crc = crc16(body)
    val full = body + byteArrayOf((crc shr 8).toByte(), (crc and 0xFF).toByte())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(full)
}

private fun crc16(data: ByteArray): Int {
    var crc = 0
    for (b in data) {
        val t = (b.toInt() xor (crc ushr 8)) and 0xFF
        crc = (CRC16[t] xor (crc shl 8)) and 0xFFFF
    }
    return crc
}

private val CRC16 = intArrayOf(
    0x0000, 0x1021, 0x2042, 0x3063, 0x4084, 0x50a5, 0x60c6, 0x70e7,
    0x8108, 0x9129, 0xa14a, 0xb16b, 0xc18c, 0xd1ad, 0xe1ce, 0xf1ef,
    0x1231, 0x0210, 0x3273, 0x2252, 0x52b5, 0x4294, 0x72f7, 0x62d6,
    0x9339, 0x8318, 0xb37b, 0xa35a, 0xd3bd, 0xc39c, 0xf3ff, 0xe3de,
    0x2462, 0x3443, 0x0420, 0x1401, 0x64e6, 0x74c7, 0x44a4, 0x5485,
    0xa56a, 0xb54b, 0x8528, 0x9509, 0xe5ee, 0xf5cf, 0xc5ac, 0xd58d,
    0x3653, 0x2672, 0x1611, 0x0630, 0x76d7, 0x66f6, 0x5695, 0x46b4,
    0xb75b, 0xa77a, 0x9719, 0x8738, 0xf7df, 0xe7fe, 0xd79d, 0xc7bc,
    0x48c4, 0x58e5, 0x6886, 0x78a7, 0x0840, 0x1861, 0x2802, 0x3823,
    0xc9cc, 0xd9ed, 0xe98e, 0xf9af, 0x8948, 0x9969, 0xa90a, 0xb92b,
    0x5af5, 0x4ad4, 0x7ab7, 0x6a96, 0x1a71, 0x0a50, 0x3a33, 0x2a12,
    0xdbfd, 0xcbdc, 0xfbbf, 0xeb9e, 0x9b79, 0x8b58, 0xbb3b, 0xab1a,
    0x6ca6, 0x7c87, 0x4ce4, 0x5cc5, 0x2c22, 0x3c03, 0x0c60, 0x1c41,
    0xedae, 0xfd8f, 0xcdec, 0xddcd, 0xad2a, 0xbd0b, 0x8d68, 0x9d49,
    0x7e97, 0x6eb6, 0x5ed5, 0x4ef4, 0x3e13, 0x2e32, 0x1e51, 0x0e70,
    0xff9f, 0xefbe, 0xdfdd, 0xcffc, 0xbf1b, 0xaf3a, 0x9f59, 0x8f78,
    0x9188, 0x81a9, 0xb1ca, 0xa1eb, 0xd10c, 0xc12d, 0xf14e, 0xe16f,
    0x1080, 0x00a1, 0x30c2, 0x20e3, 0x5004, 0x4025, 0x7046, 0x6067,
    0x83b9, 0x9398, 0xa3fb, 0xb3da, 0xc33d, 0xd31c, 0xe37f, 0xf35e,
    0x02b1, 0x1290, 0x22f3, 0x32d2, 0x4235, 0x5214, 0x6277, 0x7256,
    0xb5ea, 0xa5cb, 0x95a8, 0x8589, 0xf56e, 0xe54f, 0xd52c, 0xc50d,
    0x34e2, 0x24c3, 0x14a0, 0x0481, 0x7466, 0x6447, 0x5424, 0x4405,
    0xa7db, 0xb7fa, 0x8799, 0x97b8, 0xe75f, 0xf77e, 0xc71d, 0xd73c,
    0x26d3, 0x36f2, 0x0691, 0x16b0, 0x6657, 0x7676, 0x4615, 0x5634,
    0xd94c, 0xc96d, 0xf90e, 0xe92f, 0x99c8, 0x89e9, 0xb98a, 0xa9ab,
    0x5844, 0x4865, 0x7806, 0x6827, 0x18c0, 0x08e1, 0x3882, 0x28a3,
    0xcb7d, 0xdb5c, 0xeb3f, 0xfb1e, 0x8bf9, 0x9bd8, 0xabbb, 0xbb9a,
    0x4a75, 0x5a54, 0x6a37, 0x7a16, 0x0af1, 0x1ad0, 0x2ab3, 0x3a92,
    0xfd2e, 0xed0f, 0xdd6c, 0xcd4d, 0xbdaa, 0xad8b, 0x9de8, 0x8dc9,
    0x7c26, 0x6c07, 0x5c64, 0x4c45, 0x3ca2, 0x2c83, 0x1ce0, 0x0cc1,
    0xef1f, 0xff3e, 0xcf5d, 0xdf7c, 0xaf9b, 0xbfba, 0x8fd9, 0x9ff8,
    0x6e17, 0x7e36, 0x4e55, 0x5e74, 0x2e93, 0x3eb2, 0x0ed1, 0x1ef0,
)

private fun Int.toU32(): Long = this.toLong() and 0xffffffffL

private const val V5_CODE_HEX: String =
    "b5ee9c7241021401000281000114ff00f4a413f4bcf2c80b01020120020d020148030402dcd020d749c120915b8f6320d70b1f2082106578746ebd21821073696e74bdb0925f03e082106578746eba8eb48020d72101d074d721fa4030fa44f828fa443058bd915be0ed44d0810141d721f4058307f40e6fa1319130e18040d721707fdb3ce03120d749810280b99130e070e2100f020120050c020120060902016e07080019adce76a2684020eb90eb85ffc00019af1df6a2684010eb90eb858fc00201480a0b0017b325fb51341c75c875c2c7e00011b262fb513435c280200019be5f0f6a2684080a0eb90fa02c0102f20e011e20d70b1f82107369676ebaf2e08a7f0f01e68ef0eda2edfb218308d722028308d723208020d721d31fd31fd31fed44d0d200d31f20d31fd3ffd70a000af90140ccf9109a28945f0adb31e1f2c087df02b35007b0f2d0845125baf2e0855036baf2e086f823bbf2d0882292f800de01a47fc8ca00cb1f01cf16c9ed542092f80fde70db3cd81003f6eda2edfb02f404216e926c218e4c0221d73930709421c700b38e2d01d72820761e436c20d749c008f2e09320d74ac002f2e09320d71d06c712c2005230b0f2d089d74cd7393001a4e86c128407bbf2e093d74ac000f2e093ed55e2d20001c000915be0ebd72c08142091709601d72c081c12e25210b1e30f20d74a111213009601fa4001fa44f828fa443058baf2e091ed44d0810141d718f405049d7fc8ca0040048307f453f2e08b8e14038307f45bf2e08c22d70a00216e01b3b0f2d090e2c85003cf1612f400c9ed54007230d72c08248e2d21f2e092d200ed44d0d2005113baf2d08f54503091319c01810140d721d70a00f2e08ee2c8ca0058cf16c9ed5493f2c08de20010935bdb31e1d74cd0b4d6c35e"
