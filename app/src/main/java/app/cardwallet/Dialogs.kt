package app.cardwallet

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card as Box3
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pennywiseai.parser.core.bank.BankParserFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

private val inr = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
private val inrWhole = (NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))).apply { maximumFractionDigits = 0 }
/** ₹1,500 for whole rupees, ₹98.50 otherwise. */
fun rupees(paise: Long): String = (if (paise % 100 == 0L) inrWhole else inr).format(paise / 100.0)
fun toPaise(text: String): Long? = text.replace(",", "").trim().toBigDecimalOrNull()?.movePointRight(2)?.toLong()
/** What a card gives back: "₹125", or "250 pts · ₹125" for cards set to show points. */
fun backText(valuePaise: Long, units: Long, card: Card): String =
    if (card.showPoints && card.rupeesPerPoint != 1.0) "${reward(units, card)} · ${rupees(valuePaise)}" else rupees(valuePaise)

/** A reward in the card's own unit: "₹150" for cashback cards, "1,200 pts" for points cards. */
fun reward(units: Long, card: Card): String =
    if (card.rupeesPerPoint == 1.0) rupees(units) else NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")).format(units / 100) + " pts"

fun pct(rate: Double) = "${rate.toBigDecimal().stripTrailingZeros().toPlainString()}%"

/** Indian banks the SMS reader knows, for the card's bank picker. */
private val indianBanks by lazy {
    BankParserFactory.getAllParsers().filter { it.getCurrency() == "INR" }.map { it.getBankName() }.distinct().sorted()
}

@Composable
fun SpendDialog(
    cards: List<Card>, spend: Spend, smsBody: String?,
    onSave: (Spend) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit, deleteLabel: String = "Delete",
) {
    var cardId by remember { mutableStateOf(spend.cardId) }
    var amount by remember { mutableStateOf(if (spend.amountPaise == 0L) "" else (spend.amountPaise / 100.0).toString()) }
    var merchant by remember { mutableStateOf(spend.merchant) }
    val paise = toPaise(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (spend.id == 0L) "Add spend" else "Edit spend") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                smsBody?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Picker("Card", cards.find { it.id == cardId }?.name ?: "Pick a card", cards.map { it.name }) { cardId = cards[it].id }
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = paise != null && paise > 0 && merchant.isNotBlank() && cards.any { it.id == cardId },
                onClick = { onSave(spend.copy(cardId = cardId, amountPaise = paise!!, merchant = merchant.trim())) }) { Text("Save") }
        },
        dismissButton = { onDelete?.let { TextButton(onClick = it) { Text(deleteLabel) } } },
    )
}

@Composable
fun StarterDialog(onPick: (Starter.CardTemplate, String, Int?) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<Starter.CardTemplate?>(null) }
    var last4 by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chosen?.name ?: "Pick your card") },
        text = {
            val c = chosen
            if (c == null) Column {
                Starter.cards.forEach { t -> Text(t.name, Modifier.fillMaxWidth().clickable { chosen = t }.padding(vertical = 10.dp)) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(last4, { if (it.length <= 4) last4 = it }, label = { Text("Last 4 digits") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                StatementDayField(day) { day = it }
            }
        },
        confirmButton = {
            chosen?.let { t ->
                TextButton(enabled = last4.length == 4 && statementDayOk(day), onClick = { onPick(t, last4, day.toIntOrNull()) }) { Text("Add") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CardDialog(card: Card, onSave: (Card) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(card.name) }
    var bank by remember { mutableStateOf(card.bank) }
    var last4 by remember { mutableStateOf(card.last4) }
    var base by remember { mutableStateOf(card.baseRatePercent.toString()) }
    var day by remember { mutableStateOf(card.statementDay?.toString() ?: "") }
    var pointValue by remember { mutableStateOf(card.rupeesPerPoint.toString()) }
    var isDebit by remember { mutableStateOf(card.isDebit) }
    var showPoints by remember { mutableStateOf(card.showPoints) }
    var internationalOnly by remember { mutableStateOf(card.internationalOnly) }
    var block by remember { mutableStateOf(card.earnBlockRupees?.toString() ?: "") }
    var network by remember { mutableStateOf(card.network) }
    var colorIndex by remember { mutableStateOf(card.colorIndex) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (card.id == 0L) "Add card" else "Edit card") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Swiggy HDFC") }, singleLine = true)
                Picker("Bank", bank.ifEmpty { "Pick the bank" }, indianBanks) { bank = indianBanks[it] }
                Picker("Network", network ?: "Not set", listOf("Not set") + networks) { network = if (it == 0) null else networks[it - 1] }
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cardColors.forEachIndexed { i, (name, colors) ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(colors))
                                .border(if (colorIndex == i) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { colorIndex = i }
                                .semantics { contentDescription = name },
                        )
                    }
                }
                OutlinedTextField(last4, { if (it.length <= 4) last4 = it }, label = { Text("Last 4 digits") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { isDebit = !isDebit }) {
                    Checkbox(isDebit, { isDebit = it })
                    Text("Debit card (tracked, never suggested)")
                }
                if (!isDebit) {
                    OutlinedTextField(base, { base = it }, label = { Text("Earns per ₹100 on everything else") }, singleLine = true,
                        supportingText = { Text("₹ for cashback cards, points for points cards. E.g. 5 pts per ₹200 → 2.5") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(pointValue, { pointValue = it }, label = { Text("₹ per point") }, singleLine = true,
                        supportingText = { Text("1 for cashback cards. What a point is worth to you, e.g. 0.50") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(block, { block = it.filter(Char::isDigit) }, label = { Text("Earns only per full ₹ block") }, singleLine = true,
                        supportingText = { Text("E.g. 200: ₹1,150 earns as ₹1,000. Blank = every rupee") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (pointValue.toDoubleOrNull() != 1.0) Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showPoints = !showPoints }) {
                        Checkbox(showPoints, { showPoints = it })
                        Text("Show points as well as ₹")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { internationalOnly = !internationalOnly }) {
                        Checkbox(internationalOnly, { internationalOnly = it })
                        Text("Only for international spends")
                    }
                    StatementDayField(day) { day = it }
                }
                if (confirmDelete) Text("Delete this card with all its rules and spends? Tap Delete again.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && bank.isNotBlank() && last4.length == 4 &&
                    (isDebit || (base.toDoubleOrNull() != null && (pointValue.toDoubleOrNull() ?: 0.0) > 0 && statementDayOk(day))),
                onClick = {
                    onSave(
                        if (isDebit) card.copy(name = name.trim(), bank = bank, last4 = last4, isDebit = true, network = network, colorIndex = colorIndex, baseRatePercent = 0.0, rupeesPerPoint = 1.0, statementDay = null)
                        else card.copy(name = name.trim(), bank = bank, last4 = last4, isDebit = false, baseRatePercent = base.toDouble(),
                            rupeesPerPoint = pointValue.toDouble(), statementDay = day.toIntOrNull(), showPoints = showPoints, internationalOnly = internationalOnly,
                            earnBlockRupees = block.toIntOrNull()?.takeIf { it > 0 },
                            network = network, colorIndex = colorIndex),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            onDelete?.let { TextButton(onClick = { if (confirmDelete) it() else confirmDelete = true }) { Text("Delete") } }
        },
    )
}

@Composable
fun RuleDialog(rule: Rule, categories: List<String>, onSave: (Rule) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(rule.label) }
    var keywords by remember { mutableStateOf(rule.keywords) }
    var category by remember { mutableStateOf(rule.category) }
    var rate by remember { mutableStateOf(rule.ratePercent.toString()) }
    var cap by remember { mutableStateOf(rule.monthlyCapPaise?.let { (it / 100).toString() } ?: "") }
    var min by remember { mutableStateOf(rule.minAmountPaise?.let { (it / 100).toString() } ?: "") }
    var baseMultiple by remember { mutableStateOf(rule.capBaseMultiple?.toString() ?: "") }
    val capPaise = if (cap.isBlank()) null else toPaise(cap)
    val minPaise = if (min.isBlank()) null else toPaise(min)
    val multiple = baseMultiple.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "Add rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name, e.g. Swiggy 10%") }, singleLine = true)
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Merchants, e.g. swiggy, zomato") }, singleLine = true)
                Picker("Or a category", category ?: "None", listOf("None") + categories) { category = if (it == 0) null else categories[it - 1] }
                OutlinedTextField(rate, { rate = it }, label = { Text("Earns per ₹100 (₹ or points)") }, singleLine = true,
                    supportingText = { Text("0 = these spends earn nothing, e.g. rent or fuel") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(min, { min = it }, label = { Text("Only on spends of at least (₹)") }, singleLine = true,
                    supportingText = { Text("Blank = any amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(cap, { cap = it }, label = { Text("Cap per statement cycle (₹ or points)") }, singleLine = true,
                    supportingText = { Text("Blank = no fixed cap") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(baseMultiple, { baseMultiple = it }, label = { Text("Or cap = × base earned this cycle") }, singleLine = true,
                    supportingText = { Text("E.g. 2 → capped at twice the card's base reward this cycle. Blank = none") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && rate.toDoubleOrNull() != null && (keywords.isNotBlank() || category != null) &&
                    (cap.isBlank() || capPaise != null) && (min.isBlank() || minPaise != null) && (baseMultiple.isBlank() || multiple != null),
                onClick = {
                    onSave(rule.copy(label = label.trim(), keywords = keywords.trim(), category = category, ratePercent = rate.toDouble(),
                        monthlyCapPaise = capPaise, minAmountPaise = minPaise, capBaseMultiple = multiple))
                },
            ) { Text("Save") }
        },
        dismissButton = { onDelete?.let { TextButton(onClick = it) { Text("Delete") } } },
    )
}

private fun statementDayOk(text: String) = text.isBlank() || text.toIntOrNull() in 1..31

@Composable
private fun StatementDayField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 2) onChange(it) }, singleLine = true,
        label = { Text("Statement day (1-31)") },
        supportingText = { Text("On your card statement. Caps reset the day after. Blank = 1st of the month.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

/** A button that opens a list to pick from. */
@Composable
private fun Picker(label: String, current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); open = false }) }
            }
        }
    }
}
