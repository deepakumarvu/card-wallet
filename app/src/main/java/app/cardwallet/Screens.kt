package app.cardwallet

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
fun rupees(paise: Long): String = inr.format(paise / 100.0)
fun toPaise(text: String): Long? = text.replace(",", "").trim().toBigDecimalOrNull()?.movePointRight(2)?.toLong()
private fun pct(rate: Double) = "${rate.toBigDecimal().stripTrailingZeros().toPlainString()}%"

/** Indian banks the SMS reader knows, for the card's bank picker. */
private val indianBanks by lazy {
    BankParserFactory.getAllParsers().filter { it.getCurrency() == "INR" }.map { it.getBankName() }.distinct().sorted()
}

// ---------- Best card ----------

@Composable
fun BestCardScreen(dao: WalletDao, modifier: Modifier) {
    val cards by dao.cards().collectAsState(emptyList())
    val rules by dao.rules().collectAsState(emptyList())
    val spends by dao.spends().collectAsState(emptyList())
    val merchants by dao.merchants().collectAsState(emptyList())
    var merchant by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("1000") }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Where are you paying?", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant, e.g. Zomato") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            amount, { amount = it }, label = { Text("Amount (₹)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        val paise = toPaise(amount) ?: 0
        if (cards.isEmpty()) {
            Text("Add your cards on the Cards tab first.")
            return@Column
        }
        val picks = Cashback.rank(paise, merchant, cards, rules, Cashback.thisMonth(spends), merchants)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(picks, key = { it.card.id }) { p ->
                val first = p == picks.first() && p.cashbackPaise > 0
                Box3(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (first) "★ " else "") + p.card.name, Modifier.weight(1f),
                                fontWeight = if (first) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(rupees(p.cashbackPaise) + " back", fontWeight = FontWeight.Bold)
                        }
                        val why = p.rule?.let { r ->
                            "${r.label}" + (p.capLeftPaise?.let { " · ${rupees(it)} cap left this month" } ?: "")
                        } ?: "Base rate ${pct(p.card.baseRatePercent)}"
                        Text(why, style = MaterialTheme.typography.bodySmall)
                        p.cappedOut?.let {
                            Text("${it.label}: cap used up this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Spends ----------

@Composable
fun SpendsScreen(dao: WalletDao, modifier: Modifier) {
    val cards by dao.cards().collectAsState(emptyList())
    val rules by dao.rules().collectAsState(emptyList())
    val spends by dao.spends().collectAsState(emptyList())
    val merchants by dao.merchants().collectAsState(emptyList())
    val unread by dao.unread().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Spend?>(null) }
    var fromSms by remember { mutableStateOf<UnreadSms?>(null) }
    val cardName = cards.associate { it.id to it.name }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This month", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(enabled = cards.isNotEmpty(), onClick = {
                    editing = Spend(cardId = cards.first().id, amountPaise = 0, merchant = "", timeMillis = System.currentTimeMillis())
                }) { Text("Add spend") }
            }
        }
        if (unread.isNotEmpty()) item {
            Box3(Modifier.fillMaxWidth().clickable { fromSms = unread.first() }) {
                Text(
                    "${unread.size} bank message(s) couldn't be logged on their own. Tap to go through them.",
                    Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val month = Cashback.thisMonth(spends)
        items(cards, key = { "total-${it.id}" }) { card ->
            val total = Cashback.replayMonth(card, rules, month, merchants).second
            Row {
                Text(card.name, Modifier.weight(1f))
                Text("${rupees(total.spentPaise)} spent · ${rupees(total.cashbackPaise)} back")
            }
        }
        item { HorizontalDivider(); Text("All spends", style = MaterialTheme.typography.titleMedium) }
        items(spends, key = { it.id }) { s ->
            Row(Modifier.fillMaxWidth().clickable { editing = s }.padding(vertical = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(s.merchant)
                    Text("${cardName[s.cardId] ?: "?"} · ${dateFormat.format(Date(s.timeMillis))}" + if (s.smsKey != null) " · from SMS" else "",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(rupees(s.amountPaise))
            }
        }
    }

    editing?.let { s ->
        SpendDialog(cards, s, smsBody = null,
            onSave = { scope.launch { dao.save(it) }; editing = null },
            onDelete = if (s.id != 0L) ({ scope.launch { dao.delete(s) }; editing = null }) else null,
            onDismiss = { editing = null })
    }
    fromSms?.let { sms ->
        val guess = cards.firstOrNull { sms.body.contains(it.last4) } ?: cards.firstOrNull()
        SpendDialog(cards, Spend(cardId = guess?.id ?: 0, amountPaise = 0, merchant = "", timeMillis = sms.timeMillis, smsKey = sms.smsKey),
            smsBody = sms.body,
            onSave = { scope.launch { dao.save(it); dao.delete(sms) }; fromSms = null },
            onDelete = { scope.launch { dao.delete(sms) }; fromSms = null },
            deleteLabel = "Not a spend",
            onDismiss = { fromSms = null })
    }
}

@Composable
private fun SpendDialog(
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

// ---------- Cards ----------

@Composable
fun CardsScreen(dao: WalletDao, modifier: Modifier) {
    val cards by dao.cards().collectAsState(emptyList())
    val rules by dao.rules().collectAsState(emptyList())
    val merchants by dao.merchants().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var editCard by remember { mutableStateOf<Card?>(null) }
    var editRule by remember { mutableStateOf<Rule?>(null) }
    var starter by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { starter = true }) { Text("Add from list") }
                OutlinedButton(onClick = { editCard = Card(name = "", bank = "", last4 = "", baseRatePercent = 1.0) }) { Text("Add other card") }
            }
            Text("Starter rates can be out of date. Check your card's current terms.", style = MaterialTheme.typography.bodySmall)
        }
        items(cards, key = { it.id }) { card ->
            Box3(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { editCard = card }) {
                        Column(Modifier.weight(1f)) {
                            Text(card.name, fontWeight = FontWeight.Bold)
                            Text("${card.bank} •••• ${card.last4} · base ${pct(card.baseRatePercent)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    rules.filter { it.cardId == card.id }.forEach { r ->
                        Row(Modifier.fillMaxWidth().clickable { editRule = r }.padding(top = 6.dp)) {
                            Text("${r.label} · ${pct(r.ratePercent)}" + (r.monthlyCapPaise?.let { " · cap ${rupees(it)}/month" } ?: ""),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TextButton(onClick = {
                        editRule = Rule(cardId = card.id, label = "", keywords = "", category = null, ratePercent = 5.0, monthlyCapPaise = null)
                    }) { Text("Add rule") }
                }
            }
        }
    }

    if (starter) StarterDialog(onPick = { t, last4 -> scope.launch { Starter.addCard(dao, t, last4) }; starter = false }, onDismiss = { starter = false })
    editCard?.let { c ->
        CardDialog(c, onSave = { scope.launch { dao.save(it) }; editCard = null },
            onDelete = if (c.id != 0L) ({ scope.launch { dao.delete(c) }; editCard = null }) else null,
            onDismiss = { editCard = null })
    }
    editRule?.let { r ->
        RuleDialog(r, merchants.map { it.category }.distinct().sorted(), onSave = { scope.launch { dao.save(it) }; editRule = null },
            onDelete = if (r.id != 0L) ({ scope.launch { dao.delete(r) }; editRule = null }) else null,
            onDismiss = { editRule = null })
    }
}

@Composable
private fun StarterDialog(onPick: (Starter.CardTemplate, String) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf<Starter.CardTemplate?>(null) }
    var last4 by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(chosen?.name ?: "Pick your card") },
        text = {
            val c = chosen
            if (c == null) Column {
                Starter.cards.forEach { t -> Text(t.name, Modifier.fillMaxWidth().clickable { chosen = t }.padding(vertical = 10.dp)) }
            } else OutlinedTextField(last4, { if (it.length <= 4) last4 = it }, label = { Text("Last 4 digits") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        },
        confirmButton = {
            chosen?.let { t -> TextButton(enabled = last4.length == 4, onClick = { onPick(t, last4) }) { Text("Add") } }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CardDialog(card: Card, onSave: (Card) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(card.name) }
    var bank by remember { mutableStateOf(card.bank) }
    var last4 by remember { mutableStateOf(card.last4) }
    var base by remember { mutableStateOf(card.baseRatePercent.toString()) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (card.id == 0L) "Add card" else "Edit card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Swiggy HDFC") }, singleLine = true)
                Picker("Bank", bank.ifEmpty { "Pick the bank" }, indianBanks) { bank = indianBanks[it] }
                OutlinedTextField(last4, { if (it.length <= 4) last4 = it }, label = { Text("Last 4 digits") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(base, { base = it }, label = { Text("Cashback on everything else (%)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (confirmDelete) Text("Delete this card with all its rules and spends? Tap Delete again.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && bank.isNotBlank() && last4.length == 4 && base.toDoubleOrNull() != null,
                onClick = { onSave(card.copy(name = name.trim(), bank = bank, last4 = last4, baseRatePercent = base.toDouble())) }) { Text("Save") }
        },
        dismissButton = {
            onDelete?.let { TextButton(onClick = { if (confirmDelete) it() else confirmDelete = true }) { Text("Delete") } }
        },
    )
}

@Composable
private fun RuleDialog(rule: Rule, categories: List<String>, onSave: (Rule) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(rule.label) }
    var keywords by remember { mutableStateOf(rule.keywords) }
    var category by remember { mutableStateOf(rule.category) }
    var rate by remember { mutableStateOf(rule.ratePercent.toString()) }
    var cap by remember { mutableStateOf(rule.monthlyCapPaise?.let { (it / 100).toString() } ?: "") }
    val capPaise = if (cap.isBlank()) null else toPaise(cap)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "Add rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Name, e.g. Swiggy 10%") }, singleLine = true)
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Merchants, e.g. swiggy, zomato") }, singleLine = true)
                Picker("Or a category", category ?: "None", listOf("None") + categories) { category = if (it == 0) null else categories[it - 1] }
                OutlinedTextField(rate, { rate = it }, label = { Text("Cashback (%)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(cap, { cap = it }, label = { Text("Monthly cap (₹), blank = none") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && rate.toDoubleOrNull() != null && (keywords.isNotBlank() || category != null) && (cap.isBlank() || capPaise != null),
                onClick = { onSave(rule.copy(label = label.trim(), keywords = keywords.trim(), category = category, ratePercent = rate.toDouble(), monthlyCapPaise = capPaise)) },
            ) { Text("Save") }
        },
        dismissButton = { onDelete?.let { TextButton(onClick = it) { Text("Delete") } } },
    )
}

// ---------- More: SMS, backup, merchant categories ----------

@Composable
fun MoreScreen(dao: WalletDao, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val merchants by dao.merchants().collectAsState(emptyList())
    var restoreFrom by remember { mutableStateOf<android.net.Uri?>(null) }
    var keyword by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.READ_SMS] == true) scope.launch {
            val added = withContext(Dispatchers.IO) { SmsSpends.importInbox(context) }
            toast("Read bank SMS: $added new")
        } else toast("SMS permission not given")
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val content = WalletDb.get(context).snapshot()
                context.contentResolver.openOutputStream(uri)!!.use { Backup.write(content, it) }
            }
            toast("Backup saved")
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreFrom = it }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Bank SMS", style = MaterialTheme.typography.titleLarge)
            Text("Reads card spends from bank messages on this phone. Nothing is sent anywhere: the app has no internet access.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }) {
                Text("Read SMS from last month on")
            }
        }
        item {
            HorizontalDivider()
            Text("Backup", style = MaterialTheme.typography.titleLarge)
            Text("A zip of CSV files, saved where you choose.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { backup.launch("card-wallet-backup.zip") }) { Text("Back up") }
                OutlinedButton(onClick = { restore.launch(arrayOf("application/zip")) }) { Text("Restore") }
            }
        }
        item {
            HorizontalDivider()
            Text("Merchant categories", style = MaterialTheme.typography.titleLarge)
            Text("Merchant names starting a word with the keyword fall in the category. Rules can use categories.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(keyword, { keyword = it }, label = { Text("Keyword") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(enabled = keyword.isNotBlank() && category.isNotBlank(), onClick = {
                    scope.launch { dao.save(MerchantCategory(keyword.trim().lowercase(), category.trim())) }
                    keyword = ""
                }) { Text("Add") }
            }
        }
        items(merchants, key = { "${it.keyword}|${it.category}" }) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${m.keyword} → ${m.category}", Modifier.weight(1f))
                IconButton(onClick = { scope.launch { dao.delete(m) } }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        }
    }

    restoreFrom?.let { uri ->
        AlertDialog(
            onDismissRequest = { restoreFrom = null },
            title = { Text("Restore backup?") },
            text = { Text("This replaces all cards, rules, spends and categories on this phone with the backup.") },
            confirmButton = {
                TextButton(onClick = {
                    restoreFrom = null
                    scope.launch {
                        val result = runCatching {
                            withContext(Dispatchers.IO) {
                                val content = context.contentResolver.openInputStream(uri)!!.use { Backup.read(it) }
                                WalletDb.get(context).replaceAll(content)
                            }
                        }
                        toast(result.fold({ "Restored" }, { "Couldn't restore: ${it.message}" }))
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { restoreFrom = null }) { Text("Cancel") } },
        )
    }
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
