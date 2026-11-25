package app.cardwallet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.PopupProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val shortDate = SimpleDateFormat("d MMM", Locale.forLanguageTag("en-IN"))
private fun dayLabel(millis: Long): String {
    val today = Calendar.getInstance()
    val day = Calendar.getInstance().apply { timeInMillis = millis }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(today, day)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(today, day)) return "Yesterday"
    return shortDate.format(Date(millis))
}

/** Value back as "5%"; for points cards the value of the points, e.g. 2.5 pts per ₹100 at ₹0.50 → "1.25%". */
private fun valuePct(rate: Double, card: Card) = pct(Cashback.valuePercent(rate, card))

/** Rule name, with the value % added only when the name doesn't already say it. */
private fun ruleTitle(r: Rule, card: Card) = valuePct(r.ratePercent, card).let { if (r.label.contains(it)) r.label else "${r.label} · $it" }

/** What a cap means, for rules whose cap follows base earnings. */
private fun capNote(r: Rule) = r.capBaseMultiple?.let { "cap = ${it.toBigDecimal().stripTrailingZeros().toPlainString()}× base earned this cycle" }

private val quickPicks = listOf("Zomato", "Swiggy", "Amazon", "Flipkart", "Blinkit", "Zepto", "Myntra", "Uber")

private val screenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 100.dp)

// ---------- Best card ----------

@Composable
fun BestCardScreen(dao: WalletDao, modifier: Modifier) {
    val cards by dao.cards().collectAsState(emptyList())
    val rules by dao.rules().collectAsState(emptyList())
    val spends by dao.spends().collectAsState(emptyList())
    val merchants by dao.merchants().collectAsState(emptyList())
    var merchant by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("1000") }
    var international by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    val paise = toPaise(amount) ?: 0

    LazyColumn(modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Where are you\npaying?", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1.5f)) {
                    OutlinedTextField(merchant, { merchant = it; searchOpen = true }, placeholder = { Text("Search merchant") }, singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) searchOpen = true })
                    val suggestions = Cashback.merchantSuggestions(merchant, merchants, rules, spends)
                        .filterNot { it.name.equals(merchant.trim(), ignoreCase = true) }
                    DropdownMenu(
                        expanded = searchOpen && suggestions.isNotEmpty(),
                        onDismissRequest = { searchOpen = false },
                        properties = PopupProperties(focusable = false),
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        suggestions.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(s.name)
                                        if (s.categories.isNotEmpty()) Text(s.categories.sorted().joinToString(", "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { merchant = s.name; searchOpen = false },
                            )
                        }
                    }
                }
                OutlinedTextField(amount, { amount = it }, prefix = { Text("₹") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = international, onClick = { international = !international }, label = { Text("International") },
                        shape = RoundedCornerShape(50))
                }
                items(quickPicks) { m ->
                    FilterChip(selected = merchant.equals(m, ignoreCase = true), onClick = { merchant = m; searchOpen = false }, label = { Text(m) },
                        shape = RoundedCornerShape(50))
                }
            }
        }
        if (cards.isEmpty()) {
            item { EmptyState("No cards yet", "Add your cards on the Cards tab, and this shows which one pays most back.") }
            return@LazyColumn
        }
        val picks = Cashback.rank(paise, merchant, cards, rules, spends, merchants, international = international)
        if (picks.isEmpty()) {
            item { EmptyState("Only debit cards", "Debit cards are tracked but never suggested. Add a credit card to see picks.") }
            return@LazyColumn
        }
        val best = picks.first()
        item {
            SectionLabel(if (merchant.isBlank()) "Best for any spend" else "Best at ${merchant.trim()}")
            Spacer(Modifier.height(10.dp))
            CardFace(best.card, badge = "★ BEST") {
                Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), horizontalAlignment = Alignment.End) {
                    if (best.card.showPoints && best.card.rupeesPerPoint != 1.0) {
                        Text(reward(best.rewardUnits, best.card), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("worth ${rupees(best.cashbackPaise)}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    } else {
                        Text(rupees(best.cashbackPaise), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text("back", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    }
                }
            }
        }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val title = best.rule?.label ?: "Base rate"
                        val rate = valuePct(best.rule?.ratePercent ?: best.card.baseRatePercent, best.card)
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (!title.contains(rate)) Pill(rate)
                    }
                    val rule = best.rule
                    if (rule != null && best.capUnits != null) {
                        CapBar(best.capUsedUnits, best.capUnits, shortDate.format(Date(Cashback.nextCycleStart(best.card.statementDay))), best.card)
                        capNote(rule)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    best.cappedOut?.let { CappedOutNote(it, best.card) }
                }
            }
        }
        // A card that would beat the best pick once its base-tied cap has room.
        picks.filter { it.unlock != null && it.unlock.valuePaise > best.cashbackPaise }.forEach { p ->
            item(key = "unlock-${p.card.id}") { UnlockNote(p) }
        }
        if (picks.size > 1) {
            item { SectionLabel("Your other cards") }
            items(picks.drop(1), key = { it.card.id }) { p ->
                Panel {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CardSwatch(p.card)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.card.name, style = MaterialTheme.typography.titleMedium)
                                Text(p.rule?.let { ruleTitle(it, p.card) } ?: "Base rate ${valuePct(p.card.baseRatePercent, p.card)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(backText(p.cashbackPaise, p.rewardUnits, p.card), style = MaterialTheme.typography.titleMedium)
                        }
                        p.cappedOut?.let { CappedOutNote(it, p.card) }
                    }
                }
            }
        }
    }
}

/** "Airtel Axis could pay ₹206: Airtel recharges 25%, once you've put ₹10,300 more of other spends on it this cycle." */
@Composable
private fun UnlockNote(p: Cashback.Pick) {
    val u = p.unlock ?: return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CardSwatch(p.card)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${p.card.name} could pay ${rupees(u.valuePaise)}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${u.rule.label}, once you've put ${rupees(u.moreSpendPaise)} more of other spends on it this cycle " +
                    "(before the statement). Today it pays ${rupees(p.cashbackPaise)}.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun CappedOutNote(rule: Rule, card: Card) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text("${rule.label}: cap used up until ${shortDate.format(Date(Cashback.nextCycleStart(card.statementDay)))}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    val cardById = cards.associateBy { it.id }

    // Each card counts its own cycle.
    val cycleSpends = cards.flatMap { c -> spends.filter { it.cardId == c.id && it.timeMillis >= Cashback.cycleStart(c.statementDay) } }
    val totals = cards.associateWith { Cashback.replayCycle(it, rules, spends, merchants).second }
    val spentAll = totals.values.sumOf { it.spentPaise }
    val backAll = totals.values.sumOf { it.cashbackPaise }

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("This cycle", style = MaterialTheme.typography.headlineMedium) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Spent", rupees(spentAll), MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    StatTile("Cashback", rupees(backAll), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                }
            }
            if (unread.isNotEmpty()) item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().clickable { fromSms = unread.first() }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Email, null)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${unread.size} bank message${if (unread.size > 1) "s" else ""} to check", style = MaterialTheme.typography.titleMedium)
                            Text("Couldn't log these on their own. Tap to go through them.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (cards.isNotEmpty()) item {
                Panel {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SectionLabel("By card")
                        val max = totals.values.maxOf { it.spentPaise }.coerceAtLeast(1)
                        cards.forEach { c ->
                            val t = totals.getValue(c)
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                                        Text("since ${shortDate.format(Date(Cashback.cycleStart(c.statementDay)))}",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(rupees(t.spentPaise), style = MaterialTheme.typography.titleSmall)
                                        Text("${backText(t.cashbackPaise, t.rewardUnits, c)} back", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                ValueBar(t.spentPaise.toFloat() / max, cardGradient(c))
                            }
                        }
                    }
                }
            }
            if (cycleSpends.isNotEmpty()) item { CategoryPanel(cycleSpends, merchants) }
            item { SectionLabel("Recent spends") }
            if (spends.isEmpty()) item { EmptyState("Nothing logged yet", "Add a spend, or turn on SMS reading under More.") }
            spends.groupBy { dayLabel(it.timeMillis) }.forEach { (day, daySpends) ->
                item(key = "day-$day") {
                    Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "group-$day") {
                    Panel {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            daySpends.forEach { s ->
                                val card = cardById[s.cardId]
                                Row(Modifier.fillMaxWidth().clickable { editing = s }, verticalAlignment = Alignment.CenterVertically) {
                                    card?.let { CardSwatch(it, 32.dp) }
                                    Spacer(Modifier.size(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                        Text((card?.name ?: "?") + if (s.smsKey != null) " · from SMS" else "",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(rupees(s.amountPaise), style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cards.isNotEmpty()) ExtendedFloatingActionButton(
            onClick = { editing = Spend(cardId = cards.first().id, amountPaise = 0, merchant = "", timeMillis = System.currentTimeMillis()) },
            icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add spend") },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
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
private fun StatTile(label: String, value: String, valueColor: Color, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            SectionLabel(label)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = valueColor, maxLines = 1)
        }
    }
}

/** Donut of this cycle's spend by category, with a legend that carries names and amounts. */
@Composable
private fun CategoryPanel(cycleSpends: List<Spend>, merchants: List<MerchantCategory>) {
    val allCategories = merchants.map { it.category }.distinct()
    val byCategory = cycleSpends.groupBy { Cashback.mainCategory(it.merchant, merchants) }
        .mapValues { (_, list) -> list.sumOf { it.amountPaise } }
        .entries.sortedByDescending { it.value }
    val total = byCategory.sumOf { it.value }
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("By category")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(byCategory.map { categoryColor(it.key, allCategories) to it.value }, size = 120.dp)
                Spacer(Modifier.size(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    byCategory.forEach { (category, paise) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(categoryColor(category, allCategories))
                            Spacer(Modifier.size(8.dp))
                            Text(category, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${rupees(paise)} · ${paise * 100 / total}%", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Cards ----------

@Composable
fun CardsScreen(dao: WalletDao, modifier: Modifier) {
    val cards by dao.cards().collectAsState(emptyList())
    val rules by dao.rules().collectAsState(emptyList())
    val spends by dao.spends().collectAsState(emptyList())
    val merchants by dao.merchants().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var editCard by remember { mutableStateOf<Card?>(null) }
    var editRule by remember { mutableStateOf<Rule?>(null) }
    var starter by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Your cards", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { starter = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.size(6.dp)); Text("My cards list") }
                OutlinedButton(onClick = { editCard = Card(name = "", bank = "", last4 = "", baseRatePercent = 1.0) }) { Text("Other card") }
            }
        }
        if (cards.isEmpty()) item { EmptyState("Add your first card", "Pick a popular card to get its cashback rules, or add any card and set the rules yourself.") }
        if (cards.isNotEmpty()) item(key = "stack") {
            CardStack(cards, openId,
                onToggle = { openId = if (openId == it.id) null else it.id },
                onReorder = { reordered -> scope.launch { dao.saveCards(reordered.mapIndexed { i, c -> c.copy(sortOrder = i) }) } },
            ) { card ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = { editCard = card }) { Text("Edit card") }
                        OutlinedButton(onClick = { openId = null }) { Text("Close") }
                    }
                    CardDetails(card, rules, spends, merchants,
                        onEditRule = { editRule = it },
                        onAddRule = { editRule = Rule(cardId = card.id, label = "", keywords = "", category = null, ratePercent = 5.0, monthlyCapPaise = null) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Tap a card to open it. Long-press and drag to move it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (cards.isNotEmpty()) item {
            Text("Starter rates can be out of date. Check your card's current terms.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (starter) StarterDialog(onPick = { t, last4, day -> scope.launch { Starter.addCard(dao, t, last4, day) }; starter = false }, onDismiss = { starter = false })
    editCard?.let { c ->
        CardDialog(c,
            onSave = { scope.launch { dao.save(if (it.id == 0L) it.copy(sortOrder = dao.nextSortOrder()) else it) }; editCard = null },
            onDelete = if (c.id != 0L) ({ scope.launch { dao.delete(c) }; editCard = null; openId = null }) else null,
            onDismiss = { editCard = null })
    }
    editRule?.let { r ->
        RuleDialog(r, merchants.map { it.category }.distinct().sorted(), onSave = { scope.launch { dao.save(it) }; editRule = null },
            onDelete = if (r.id != 0L) ({ scope.launch { dao.delete(r) }; editRule = null }) else null,
            onDismiss = { editRule = null })
    }
}

private fun cardBadge(card: Card) = when {
    card.isDebit -> "DEBIT"
    card.internationalOnly -> "${valuePct(card.baseRatePercent, card)} · INTL"
    else -> "${valuePct(card.baseRatePercent, card)} BASE"
}

/**
 * Cards overlapped like a wallet: each shows its top strip, the last one shows in full.
 * Tapping a card opens it in place: it grows to full size with [details] under it and the
 * cards below slide down. Long-press then drag moves a card (while none is open);
 * [onReorder] gets the new order on release.
 */
@Composable
private fun CardStack(
    cards: List<Card>,
    openId: Long?,
    onToggle: (Card) -> Unit,
    onReorder: (List<Card>) -> Unit,
    details: @Composable (Card) -> Unit,
) {
    var order by remember(cards) { mutableStateOf(cards) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val peek = 96.dp
    val peekPx = with(LocalDensity.current) { peek.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardHeight = maxWidth / 1.586f
        Column(Modifier.fillMaxWidth()) {
            order.forEachIndexed { index, card ->
                key(card.id) {
                    val open = card.id == openId
                    val dragged = draggingId == card.id
                    // Layout height of this card's slot; the card itself always draws full size and
                    // the next card covers all but the top strip.
                    val slot by animateDpAsState(
                        when { open -> cardHeight + 12.dp; index == order.lastIndex -> cardHeight; else -> peek }, label = "slot")
                    Column(
                        Modifier
                            .zIndex(if (dragged) 100f else index.toFloat())
                            .offset { IntOffset(0, if (dragged) dragY.roundToInt() else 0) },
                    ) {
                        Box(Modifier.fillMaxWidth().height(slot)) {
                            CardFace(
                                card,
                                Modifier
                                    .wrapContentHeight(Alignment.Top, unbounded = true)
                                    .graphicsLayer { if (dragged) { scaleX = 1.03f; scaleY = 1.03f } }
                                    .shadow(if (dragged) 16.dp else 6.dp, RoundedCornerShape(22.dp))
                                    .then(if (openId != null) Modifier else Modifier.pointerInput(card.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { draggingId = card.id; dragY = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragY += amount.y
                                                val from = order.indexOfFirst { it.id == card.id }
                                                val to = (from + (dragY / peekPx).roundToInt()).coerceIn(0, order.lastIndex)
                                                if (to != from) {
                                                    order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                    dragY -= (to - from) * peekPx
                                                }
                                            },
                                            onDragEnd = { draggingId = null; dragY = 0f; if (order != cards) onReorder(order) },
                                            onDragCancel = { draggingId = null; dragY = 0f; order = cards },
                                        )
                                    })
                                    .clickable { onToggle(card) },
                                badge = cardBadge(card),
                            ) {
                                if (open && !card.isDebit) Text(card.statementDay?.let { "Statement on day $it" } ?: "Statement day not set",
                                    color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
                            }
                        }
                        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Box(Modifier.padding(bottom = 16.dp)) { details(card) }
                        }
                    }
                }
            }
        }
    }
}

/** An open card's rules with their cap bars, or a note for debit cards. */
@Composable
private fun CardDetails(
    card: Card, rules: List<Rule>, spends: List<Spend>, merchants: List<MerchantCategory>,
    onEditRule: (Rule) -> Unit, onAddRule: () -> Unit,
) {
    if (card.isDebit) {
        Text("Debit card: spends are tracked, but it's never suggested on Best card.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val earned = Cashback.replayCycle(card, rules, spends, merchants).first
    val resets = shortDate.format(Date(Cashback.nextCycleStart(card.statementDay)))
    Panel {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (card.last4.isBlank()) Text("Add the last 4 digits (Edit card) so bank SMS land on this card.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (card.rupeesPerPoint != 1.0) Text(
                "Points card: ${pct(card.baseRatePercent).removeSuffix("%")} pts per ₹100, 1 pt = ₹${card.rupeesPerPoint.toBigDecimal().stripTrailingZeros().toPlainString()}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            rules.filter { it.cardId == card.id }.forEach { r ->
                Column(Modifier.fillMaxWidth().clickable { onEditRule(r) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        val v = valuePct(r.ratePercent, card)
                        if (r.ratePercent == 0.0) Pill("Earns nothing", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (!r.label.contains(v)) Pill(v)
                    }
                    val notes = listOfNotNull(r.minAmountPaise?.let { "on spends of ${rupees(it)}+" }, capNote(r))
                    if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val cap = Cashback.capFor(r, earned.base)
                    if (r.ratePercent > 0) {
                        if (cap != null) CapBar(earned.byRule[r.id] ?: 0, cap, resets, card)
                        else Text("No cap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (card.statementDay == null) Text("Set the statement day (Edit card) so caps reset on the right day.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAddRule, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.size(4.dp)); Text("Add rule")
            }
        }
    }
}
