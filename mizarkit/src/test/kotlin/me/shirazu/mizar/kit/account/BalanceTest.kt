package me.shirazu.mizar.kit.account

import me.shirazu.mizar.kit.WIRE_JSON
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The balance answer, as the host sends it.
 *
 * The figure is a decimal string on purpose: parsing it into a `Double` on the way
 * to the screen would round somebody's money, and the host already validated the
 * shape before it sent it.
 */
class BalanceTest {

    @Test
    fun `a ready answer carries both kinds of wallet`() {
        val answer = WIRE_JSON.decodeFromString(
            BalanceResult.serializer(),
            """
            {"status":"ready",
             "wallets":[{"currency":"CNY","balance":"48.20"},{"currency":"USD","balance":"6.75"}],
             "bonusWallets":[{"currency":"CNY","balance":"5.00"}],
             "topUpURL":"https://platform.deepseek.com/top_up"}
            """.trimIndent(),
        )

        assertTrue(answer.isReady)
        assertEquals(listOf("CNY", "USD"), answer.wallets.map { it.currency })
        assertEquals("48.20", answer.wallets.first().balance)
        assertEquals(1, answer.bonusWallets.size)
        assertEquals("https://platform.deepseek.com/top_up", answer.topUpURL)
    }

    @Test
    fun `the three ways of having no balance are told apart`() {
        for (status in listOf("signed-out", "unsupported", "unavailable")) {
            val answer = WIRE_JSON.decodeFromString(BalanceResult.serializer(), """{"status":"$status"}""")
            assertFalse(answer.isReady, "$status must not read as a figure")
            assertTrue(answer.wallets.isEmpty())
            assertEquals(status, answer.status)
        }
    }

    @Test
    fun `an unknown status is treated as unavailable, not as ready`() {
        // The status is a merge-extensible union: a host that grows a new one must
        // read as "no figure", never as a balance of zero.
        val answer = WIRE_JSON.decodeFromString(BalanceResult.serializer(), """{"status":"throttled"}""")
        assertFalse(answer.isReady)
    }
}
