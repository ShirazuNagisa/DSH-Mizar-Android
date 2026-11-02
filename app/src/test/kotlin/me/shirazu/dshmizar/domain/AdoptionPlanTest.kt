package me.shirazu.dshmizar.domain

import me.shirazu.dshmizar.PairingStage
import me.shirazu.dshmizar.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What adopting a connection does, decided before anything is done.
 *
 * These five values were five `if`s scattered through a function that also dialled, read
 * and registered — each with a comment explaining why. Three of them are not obvious:
 *
 *  - A first pairing must stay on the connecting page, or the index warm it is showing
 *    progress for would never be seen.
 *  - A **LAN** socket must not go looking for the LAN. It would probe itself on every
 *    single connect, and the answer is already known.
 *  - Only a **relayed** link has a tunnel whose relay the host could be told to change.
 */
class AdoptionPlanTest {

    @Test
    fun `a first pairing stays on the connecting screen while it warms`() {
        val plan = AdoptionPlan.of(warm = true, relayed = true, localNetworkFirst = false)

        assertEquals("READY would swap in the shell and the progress would be lost", Phase.CONNECTING, plan.phase)
        assertEquals(PairingStage.SYNCING, plan.pairingStage)
        assertTrue(plan.warmsTheIndex)
    }

    @Test
    fun `a reconnect goes straight to the shell`() {
        val plan = AdoptionPlan.of(warm = false, relayed = true, localNetworkFirst = false)

        assertEquals(Phase.READY, plan.phase)
        assertNull("there is nothing to sync", plan.pairingStage)
        assertFalse("and nothing to warm", plan.warmsTheIndex)
    }

    @Test
    fun `a LAN socket never goes looking for the LAN`() {
        val plan = AdoptionPlan.of(warm = false, relayed = false, localNetworkFirst = true)

        assertFalse("it would probe itself on every connect", plan.looksForLan)
        assertFalse("and has no tunnel to move", plan.syncsRelayTier)
    }

    @Test
    fun `a relayed link looks for the LAN only when the operator asked`() {
        val asked = AdoptionPlan.of(warm = false, relayed = true, localNetworkFirst = true)
        val notAsked = AdoptionPlan.of(warm = false, relayed = true, localNetworkFirst = false)

        assertTrue(asked.looksForLan)
        assertFalse(notAsked.looksForLan)
        assertTrue("a relayed link always has a tier to settle", asked.syncsRelayTier)
        assertTrue(notAsked.syncsRelayTier)
    }

    @Test
    fun `every combination is consistent with its own inputs`() {
        for (warm in listOf(true, false)) {
            for (relayed in listOf(true, false)) {
                for (lanFirst in listOf(true, false)) {
                    val plan = AdoptionPlan.of(warm, relayed, lanFirst)
                    val label = "warm=$warm relayed=$relayed lanFirst=$lanFirst"

                    assertEquals(label, if (warm) Phase.CONNECTING else Phase.READY, plan.phase)
                    assertEquals(label, warm, plan.warmsTheIndex)
                    assertEquals(label, relayed && lanFirst, plan.looksForLan)
                    assertEquals(label, relayed, plan.syncsRelayTier)
                    assertEquals(label, warm, plan.pairingStage != null)
                }
            }
        }
    }

    @Test
    fun `the warm step and the connecting page are the same decision`() {
        // If these two ever disagree, a pairing would either show progress on a shell
        // that has already replaced the screen, or sit on "reading sessions" while
        // nothing is being read.
        for (warm in listOf(true, false)) {
            val plan = AdoptionPlan.of(warm, relayed = false, localNetworkFirst = false)
            assertEquals(warm, plan.warmsTheIndex)
            assertEquals(warm, plan.phase == Phase.CONNECTING)
        }
    }
}
