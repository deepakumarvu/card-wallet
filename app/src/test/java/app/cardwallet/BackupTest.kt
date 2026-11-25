package app.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupTest {
    @Test
    fun `backup then restore gives back the same data`() {
        val content = Backup.Content(
            cards = listOf(Card(1, "Swiggy HDFC", "HDFC Bank", "1111", 1.0), Card(2, "Axis \"Ace\"", "Axis Bank", "2222", 1.5)),
            rules = listOf(
                Rule(10, 1, "Swiggy 10%", "swiggy", null, 10.0, 1500_00),
                Rule(20, 2, "Food, cabs", "swiggy, zomato, ola", "Online", 4.0, null),
            ),
            spends = listOf(
                Spend(1, 1, 450_50, "ZOMATO, Bangalore", 1_700_000_000_000, "abc123"),
                Spend(2, 2, 99_00, "Line\nbreak", 1_700_000_000_001, null),
            ),
            merchants = listOf(MerchantCategory("zomato", "Dining")),
        )
        val zip = ByteArrayOutputStream().also { Backup.write(content, it) }.toByteArray()
        assertEquals(content, Backup.read(ByteArrayInputStream(zip)))
    }
}
