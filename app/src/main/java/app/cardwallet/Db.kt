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
    /** Last 4 digits of earlier cards replaced by this one (comma-separated), so their bank SMS land here. */
    @ColumnInfo(defaultValue = "''") val oldLast4: String = "",
    /** Fee on foreign-currency spends, e.g. 3.5 (%). */
    @ColumnInfo(defaultValue = "3.5") val forexMarkupPercent: Double = 3.5,
    /** False when the card gives no reward on international spends (Scapia: 0% fee, no coins). */
    @ColumnInfo(defaultValue = "1") val rewardsAbroad: Boolean = true,
) {
    /** True when SMS digits [digits] belong to this card, now or before a replacement. */
    fun hasDigits(digits: String?): Boolean =
        !digits.isNullOrBlank() && (last4 == digits || oldLast4.split(',').any { it.trim() == digits })
}

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

/**
 * A message shape you marked "Not a spend": the bank sender (e.g. AXISBK) and the text with every
 * number masked. New messages of the same shape from that bank are ignored.
 */
@Entity(tableName = "ignored_shapes", indices = [Index(value = ["senderKey", "shape"], unique = true)])
data class IgnoredShape(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senderKey: String,
    val shape: String,
    val createdMillis: Long,
)

/** One line in the on-phone activity log, for debugging later. */
@Entity(tableName = "activity_log")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMillis: Long,
    val message: String,
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
    @Query("SELECT * FROM unread_sms") suspend fun allUnread(): List<UnreadSms>

    @Query("SELECT * FROM ignored_shapes ORDER BY createdMillis DESC") fun ignoredShapes(): Flow<List<IgnoredShape>>
    @Query("SELECT * FROM ignored_shapes") suspend fun allIgnoredShapes(): List<IgnoredShape>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnoredShape(shape: IgnoredShape): Long
    @Delete suspend fun delete(shape: IgnoredShape)

    @Insert suspend fun insertLog(entry: LogEntry)
    @Query("SELECT * FROM activity_log ORDER BY id DESC") fun logs(): Flow<List<LogEntry>>
    @Query("SELECT * FROM activity_log ORDER BY id") suspend fun allLogs(): List<LogEntry>
    @Query("DELETE FROM activity_log") suspend fun clearLogs()
    /** Keeps the newest [keep] entries. */
    @Query("DELETE FROM activity_log WHERE id <= (SELECT MAX(id) FROM activity_log) - :keep") suspend fun trimLogs(keep: Int)

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
    entities = [Card::class, Rule::class, Spend::class, MerchantCategory::class, UnreadSms::class, LogEntry::class, IgnoredShape::class],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10),
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
