package dev.tonnector

import dev.tonnector.wallet.MockWallet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProtocolTest {
    @Test
    fun `v5 wallet id matches the published constants`() {
        assertEquals(2147483409, MockWallet.walletIdV5(-239, 0, 0))
        assertEquals(2147483645, MockWallet.walletIdV5(-3, 0, 0))
        assertEquals(8388369, MockWallet.walletIdV5(-239, -1, 0))
    }
}