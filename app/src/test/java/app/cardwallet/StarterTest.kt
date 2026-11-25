package app.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Test

class StarterTest {
    @Test
    fun `existing cards get their template's looks, without overriding choices`() {
        val regalia = Card(1, "HDFC Regalia Gold", "HDFC Bank", "7126", 2.5, rupeesPerPoint = 0.5)
        val filled = Starter.withLooks(regalia)
        assertEquals(13, filled.colorIndex)
        assertEquals("Mastercard", filled.network)
        assertEquals(true, filled.showPoints)

        // A colour or network the user picked stays.
        val picked = Starter.withLooks(regalia.copy(colorIndex = 4, network = "Visa"))
        assertEquals(4, picked.colorIndex)
        assertEquals("Visa", picked.network)

        // A card not in the list is left alone.
        val other = Card(2, "My own card", "HDFC Bank", "1111", 1.0)
        assertEquals(other, Starter.withLooks(other))
    }

    @Test
    fun `Scapia moves from the old teal to neon orange, but a colour you picked stays`() {
        val scapia = Card(3, "Scapia", "Federal Bank", "4984", 10.0, rupeesPerPoint = 0.2, colorIndex = 6)
        assertEquals(18, Starter.recolour(scapia).colorIndex)
        assertEquals(5, Starter.recolour(scapia.copy(colorIndex = 5)).colorIndex)
        assertEquals(18, Starter.withLooks(scapia.copy(colorIndex = null)).colorIndex)
        assertEquals(true, Starter.withLooks(scapia).internationalOnly)
    }
}
