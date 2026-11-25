package app.cardwallet

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.withWriteTransaction
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.flow.Flow

/** Money is kept in paise (₹1 = 100) so sums never drift. */
@Entity(tableName = "cards")
data class Card(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Bank name as the SMS parser reports it, e.g. "HDFC Bank". */
    val bank: String,
    val last4: String,
    /** Cashback on spends no rule covers, e.g. 1.0 for 1%. */
    val baseRatePercent: Double,
)

/**
 * A higher cashback rate on some spends. It matches a spend when the merchant name contains
 * one of [keywords] (comma-separated, e.g. "swiggy, zomato") or the merchant falls in [category].
 * [monthlyCapPaise] is the most cashback this rule pays in a calendar month; null means no cap.
 */
@Entity(
    tableName = "rules",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId")],
)
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val label: String,
    val keywords: String,
    val category: String?,
    val ratePercent: Double,
    val monthlyCapPaise: Long?,
)

@Entity(
    tableName = "spends",
    foreignKeys = [ForeignKey(Card::class, ["id"], ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index("smsKey", unique = true)],
)
data class Spend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val amountPaise: Long,
    val merchant: String,
    val timeMillis: Long,
    /** Set for spends read from SMS, so the same message is never logged twice. */
    val smsKey: String? = null,
)

/** "Merchant names containing [keyword] are in [category]", e.g. zomato → Dining. */
@Entity(tableName = "merchant_categories", primaryKeys = ["keyword", "category"])
data class MerchantCategory(val keyword: String, val category: String)

/** A bank SMS that looks like a card spend but couldn't be logged on its own. */
@Entity(tableName = "unread_sms", indices = [Index("smsKey", unique = true)])
data class UnreadSms(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val timeMillis: Long,
    val smsKey: String,
)

@Dao
interface WalletDao {
    @Query("SELECT * FROM cards ORDER BY name") fun cards(): Flow<List<Card>>
    @Query("SELECT * FROM rules ORDER BY ratePercent DESC") fun rules(): Flow<List<Rule>>
    @Query("SELECT * FROM spends ORDER BY timeMillis DESC") fun spends(): Flow<List<Spend>>
    @Query("SELECT * FROM merchant_categories ORDER BY keyword") fun merchants(): Flow<List<MerchantCategory>>
    @Query("SELECT * FROM unread_sms ORDER BY timeMillis DESC") fun unread(): Flow<List<UnreadSms>>

    @Query("SELECT * FROM cards") suspend fun allCards(): List<Card>
    @Query("SELECT * FROM rules") suspend fun allRules(): List<Rule>
    @Query("SELECT * FROM spends") suspend fun allSpends(): List<Spend>
    @Query("SELECT * FROM merchant_categories") suspend fun allMerchants(): List<MerchantCategory>

    @Upsert suspend fun save(card: Card): Long
    @Upsert suspend fun save(rule: Rule): Long
    @Upsert suspend fun save(spend: Spend): Long
    @Upsert suspend fun save(merchant: MerchantCategory)
    @Delete suspend fun delete(card: Card)
    @Delete suspend fun delete(rule: Rule)
    @Delete suspend fun delete(spend: Spend)
    @Delete suspend fun delete(merchant: MerchantCategory)
    @Delete suspend fun delete(sms: UnreadSms)

    /** Returns -1 when this SMS was already logged. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFromSms(spend: Spend): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertUnread(sms: UnreadSms): Long

    @Insert suspend fun insertCards(cards: List<Card>)
    @Insert suspend fun insertRules(rules: List<Rule>)
    @Insert suspend fun insertSpends(spends: List<Spend>)
    @Insert suspend fun insertMerchants(merchants: List<MerchantCategory>)
    @Query("DELETE FROM cards") suspend fun deleteAllCards()
    @Query("DELETE FROM merchant_categories") suspend fun deleteAllMerchants()
}

@Database(
    entities = [Card::class, Rule::class, Spend::class, MerchantCategory::class, UnreadSms::class],
    version = 1,
)
abstract class WalletDb : RoomDatabase() {
    abstract fun dao(): WalletDao

    suspend fun snapshot(): Backup.Content = with(dao()) {
        Backup.Content(allCards(), allRules(), allSpends(), allMerchants())
    }

    /** Replaces everything with [content]. Deleting cards also deletes their rules and spends. */
    suspend fun replaceAll(content: Backup.Content) = withWriteTransaction {
        with(dao()) {
            deleteAllCards()
            deleteAllMerchants()
            insertCards(content.cards)
            insertRules(content.rules)
            insertSpends(content.spends)
            insertMerchants(content.merchants)
        }
    }

    companion object {
        @Volatile private var instance: WalletDb? = null

        fun get(context: Context): WalletDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WalletDb::class.java, "wallet.db")
                .setDriver(AndroidSQLiteDriver())
                .build()
                .also { instance = it }
        }
    }
}
