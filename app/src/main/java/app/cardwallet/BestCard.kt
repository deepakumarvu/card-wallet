package app.cardwallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import java.util.Date
import kotlin.math.absoluteValue

private val presets = listOf(500L, 1000L, 2000L, 5000L, 10000L)

/**
 * "Where are you paying?": a big amount, then either the cheat sheet (best card per category,
 * before you pick anything) or, once a merchant or tile is picked, your cards as a swipeable row,
 * best first.
 */
@Composable
fun BestCardScreen(dao: WalletDao, modifier: Modifier) {
    val cards by dao.cards().collectAsState(emptyList())
    val rules by dao.rules().collectAsState(emptyList())
    val spends by dao.spends().collectAsState(emptyList())
    val merchants by dao.merchants().collectAsState(emptyList())
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var international by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("1000") }
    var searchOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val paise = toPaise(amount) ?: 0
    val picking = merchant.isNotBlank() || category != null || international

    val sheet = remember(paise, cards, rules, spends, merchants) { Cashback.cheatSheet(paise, cards, rules, spends, merchants) }
    val picks = remember(paise, merchant, category, international, cards, rules, spends, merchants) {
        Cashback.rank(paise, merchant, cards, rules, spends, merchants, international = international, category = category)
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 24.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Where are you\npaying?", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(18.dp))
                // The amount, large.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("₹", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(6.dp))
                    BasicTextField(
                        amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(9) },
                        textStyle = MaterialTheme.typography.displayMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { r ->
                        val on = paise == r * 100
                        Surface(
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = CircleShape,
                            modifier = Modifier.clip(CircleShape).clickable { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); amount = r.toString() },
                        ) {
                            Text(if (r >= 1000) "₹${r / 1000}k" else "₹$r", style = MaterialTheme.typography.labelLarge,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Box {
                    OutlinedTextField(merchant, { merchant = it; category = null; searchOpen = true }, placeholder = { Text("Search merchant") },
                        singleLine = true, shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) searchOpen = true })
                    val suggestions = remember(merchant, merchants, rules, spends) {
                        Cashback.merchantSuggestions(merchant, merchants, rules, spends).filterNot { it.name.equals(merchant.trim(), ignoreCase = true) }
                    }
                    DropdownMenu(expanded = searchOpen && suggestions.isNotEmpty(), onDismissRequest = { searchOpen = false },
                        properties = PopupProperties(focusable = false), modifier = Modifier.heightIn(max = 320.dp)) {
                        suggestions.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(s.name)
                                        if (s.categories.isNotEmpty()) Text(s.categories.sorted().joinToString(", "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { merchant = s.name; category = null; searchOpen = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (picking) item {
                        FilterChip(selected = false, onClick = { merchant = ""; category = null; international = false; searchOpen = false },
                            label = { Text("✕ Clear") }, shape = RoundedCornerShape(50))
                    }
                    items(quickPicks) { m ->
                        FilterChip(selected = merchant.equals(m, ignoreCase = true), shape = RoundedCornerShape(50),
                            onClick = { merchant = m; category = null; searchOpen = false }, label = { Text(m) })
                    }
                }
            }
        }
        if (cards.isEmpty()) {
            item { Box(Modifier.padding(horizontal = 20.dp)) { EmptyState("No cards yet", "Add your cards on the Cards tab, and this shows which one pays most back.") } }
            return@LazyColumn
        }
        if (!picking) {
            item { SectionLabel("Best card right now · for ${rupees(paise)}", Modifier.padding(horizontal = 20.dp)) }
            item { CheatSheet(sheet, paise) { tile -> haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (tile == Cashback.INTERNATIONAL) international = true else category = tile } }
            return@LazyColumn
        }
        if (picks.isEmpty()) {
            item { Box(Modifier.padding(horizontal = 20.dp)) { EmptyState("No card fits", "Debit and international-only cards aren't suggested here.") } }
            return@LazyColumn
        }
        item {
            SectionLabel(
                when {
                    merchant.isNotBlank() -> "At ${merchant.trim()}" + if (international) " · abroad" else ""
                    category != null -> "For $category"
                    else -> "Abroad"
                } + " · swipe to compare",
                Modifier.padding(horizontal = 20.dp),
            )
        }
        item { CardCarousel(picks, international) }
    }
}

/** Best card per category, as a 2-column grid of tiles. */
@Composable
private fun CheatSheet(sheet: List<Pair<String, Cashback.Pick?>>, paise: Long, onTile: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sheet.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (tile, pick) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).clickable { onTile(tile) },
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionLabel(tile)
                            if (pick == null) Text("No card", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CardSwatch(pick.card, 30.dp)
                                    Spacer(Modifier.size(10.dp))
                                    Text(pick.card.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                }
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(rupees(pick.cashbackPaise), style = MaterialTheme.typography.headlineSmall,
                                        color = if (pick.cashbackPaise >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.size(6.dp))
                                    if (paise > 0) Text(pct(pick.cashbackPaise * 100.0 / paise).let { if (it.length > 6) "%.1f%%".format(pick.cashbackPaise * 100.0 / paise) else it },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                                }
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Ranked cards as a swipeable row; the one in front tilts live and its details show below. */
@Composable
private fun CardCarousel(picks: List<Cashback.Pick>, international: Boolean) {
    val pager = rememberPagerState { picks.size }
    val haptics = LocalHapticFeedback.current
    val current = picks.getOrNull(pager.currentPage) ?: picks.first()
    androidx.compose.runtime.LaunchedEffect(pager.currentPage) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 36.dp), pageSpacing = 14.dp) { page ->
            val p = picks[page]
            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            val pointsCard = p.card.showPoints && p.card.rupeesPerPoint != 1.0
            CardFace(
                p.card,
                Modifier.graphicsLayer { val s = 1f - 0.08f * offset; scaleX = s; scaleY = s; alpha = 1f - 0.35f * offset },
                status = if (page == 0) "★ BEST" else "#${page + 1}",
                live = page == pager.currentPage,
                figures = listOfNotNull(
                    (if (international) "Net" else "Back") to rupees(p.cashbackPaise),
                    if (pointsCard) "Points" to reward(p.rewardUnits, p.card)
                    else "Rate" to pct(Cashback.valuePercent(p.rule?.ratePercent ?: p.card.baseRatePercent, p.card)),
                ),
            )
        }
        // Page dots.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            picks.indices.forEach { i ->
                Box(Modifier.padding(3.dp).size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
            }
        }
        Box(Modifier.padding(horizontal = 20.dp)) {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val title = current.rule?.label ?: "Base rate"
                        val rate = valuePct(current.rule?.ratePercent ?: current.card.baseRatePercent, current.card)
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (!title.contains(rate)) Pill(rate)
                    }
                    val rule = current.rule
                    if (rule != null && current.capUnits != null) {
                        CapBar(current.capUsedUnits, current.capUnits, shortDate.format(Date(Cashback.nextCycleStart(current.card.statementDay))), current.card)
                        capNote(rule)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    current.cappedOut?.let { CappedOutNote(it, current.card) }
                    if (international) Text(
                        "Abroad: ${pct(current.card.forexMarkupPercent)} forex fee (${rupees(current.forexFeePaise)})" +
                            if (current.card.rewardsAbroad) ", rewards ${rupees(current.cashbackPaise + current.forexFeePaise)}" else ", no rewards abroad",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // A card that would beat the best pick once its base-tied cap has room.
        picks.filter { it.unlock != null && it.unlock.valuePaise > picks.first().cashbackPaise }.forEach { p ->
            Box(Modifier.padding(horizontal = 20.dp)) { UnlockNote(p) }
        }
    }
}
