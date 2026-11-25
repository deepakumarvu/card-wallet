package app.cardwallet

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupTest {
    @Test
    fun `backup then restore gives back the same data`() {
        val content = Backup.Content(
            cards = listOf(Card(1, "Swiggy HDFC", "HDFC Bank", "1111", 1.0, statementDay = 14, sortOrder = 2, showPoints = true, network = "Visa", colorIndex = 3, oldLast4 = "5125"), Card(2, "Axis \"Ace\"", "Axis Bank", "2222", 1.5, rupeesPerPoint = 0.2), Card(3, "Debit", "HDFC Bank", "3333", 0.0, isDebit = true)),
            rules = listOf(
                Rule(10, 1, "Swiggy 10%", "swiggy", null, 10.0, 1500_00),
                Rule(20, 2, "Food, cabs", "swiggy, zomato, ola", "Online", 4.0, null, minAmountPaise = 249_00, capBaseMultiple = 2.0),
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

    @Test
    fun `old backups without the newer columns still restore`() {
        val old = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(old).use { zip ->
            mapOf(
                "cards.csv" to "id,name,bank,last4,base_rate_percent,statement_day\n1,Swiggy HDFC,HDFC Bank,1111,1.0,14\n",
                "rules.csv" to "id,card_id,label,keywords,category,rate_percent,monthly_cap_paise\n10,1,Swiggy 10%,swiggy,,10.0,150000\n",
                "spends.csv" to "id,card_id,amount_paise,merchant,time_millis,sms_key\n",
                "merchant_categories.csv" to "keyword,category\n",
            ).forEach { (name, text) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        val content = Backup.read(ByteArrayInputStream(old.toByteArray()))
        assertEquals(Card(1, "Swiggy HDFC", "HDFC Bank", "1111", 1.0, 14), content.cards.single())
        assertEquals(Rule(10, 1, "Swiggy 10%", "swiggy", null, 10.0, 1500_00), content.rules.single())
    }
}
