package app.cardwallet

import android.content.Context
import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
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
    /** Reward per ₹100 on spends no rule covers, in the card's unit: ₹ for cashback cards, points for points cards. */
    val baseRatePercent: Double,
    /** Day of the month the statement is made (1-31); caps reset the day after. Null = calendar month. */
    val statementDay: Int? = null,
    /** What one reward unit is worth in ₹: 1.0 for cashback, 0.5 for a point worth 50 paise. */
    @ColumnInfo(defaultValue = "1.0") val rupeesPerPoint: Double = 1.0,
    /** Debit cards are tracked for spends but never suggested. */
    @ColumnInfo(defaultValue = "0") val isDebit: Boolean = false,
    /** Position in the wallet stack; lower is higher up. */
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    /** Show rewards as points and ₹ ("250 pts · ₹125"), for points you redeem more than one way. */
    @ColumnInfo(defaultValue = "0") val showPoints: Boolean = false,
    /** Card network shown on the face: Visa, Mastercard, RuPay, Amex; null = not set. */
    val network: String? = null,
    /** Index into the card colour list; null = the bank's colour. */
    val colorIndex: Int? = null,
    /** Only suggested when Best card is set to International (e.g. a 0% forex card). */
    @ColumnInfo(defaultValue = "0") val internationalOnly: Boolean = false,
    /** Rewards only on full blocks of this many ₹ (HDFC points: 200); null = every rupee. */
    val earnBlockRupees: Int? = null,
)

/**
 * A different reward rate on some spends. It matches a spend when the merchant name contains
 * one of [keywords] (comma-separated, e.g. "swiggy, zomato") or the merchant falls in [category].
 * Amounts are in the card's unit ×100 (paise for cashback, 1/100 point for points cards).
 * - [ratePercent]: reward per ₹100; 0 means the spend earns nothing, whatever the base rate.
 * - [monthlyCapPaise]: most this rule pays per statement cycle; null = no fixed cap.
 * - [minAmountPaise]: the rule only applies to spends of at least this much.
 * - [capBaseMultiple]: cap = this × base reward earned on the card this cycle (Airtel Axis style).
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
    val minAmountPaise: Long? = null,
    val capBaseMultiple: Double? = null,
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
    @Query("SELECT * FROM cards ORDER BY sortOrder, name") fun cards(): Flow<List<Card>>
    @Query("SELECT * FROM rules ORDER BY ratePercent DESC") fun rules(): Flow<List<Rule>>
    @Query("SELECT * FROM spends ORDER BY timeMillis DESC") fun spends(): Flow<List<Spend>>
    @Query("SELECT * FROM merchant_categories ORDER BY keyword") fun merchants(): Flow<List<MerchantCategory>>
    @Query("SELECT * FROM unread_sms ORDER BY timeMillis DESC") fun unread(): Flow<List<UnreadSms>>

    @Query("SELECT * FROM cards") suspend fun allCards(): List<Card>
    @Query("SELECT * FROM rules") suspend fun allRules(): List<Rule>
    @Query("SELECT * FROM spends") suspend fun allSpends(): List<Spend>
    @Query("SELECT * FROM merchant_categories") suspend fun allMerchants(): List<MerchantCategory>

    @Upsert suspend fun save(card: Card): Long
    @Upsert suspend fun saveCards(cards: List<Card>)
    /** Where a new card goes: at the bottom of the stack. */
    @Query("SELECT COALESCE(MAX(sortOrder), 0) + 1 FROM cards") suspend fun nextSortOrder(): Int
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
    version = 6,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
    ],
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
