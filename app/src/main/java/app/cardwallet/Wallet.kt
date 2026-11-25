package app.cardwallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours a card can wear, picked in Edit card. Order is fixed: cards store the index. */
val cardColors: List<Pair<String, List<Color>>> = listOf(
    "Midnight" to listOf(Color(0xFF141A2E), Color(0xFF3A4A7A)),
    "Royal blue" to listOf(Color(0xFF0A2A66), Color(0xFF2160D6)),
    "Maroon" to listOf(Color(0xFF4E0A28), Color(0xFFA3154F)),
    "Orange" to listOf(Color(0xFF8A3204), Color(0xFFF7791F)),
    "Gold" to listOf(Color(0xFF3B2A0A), Color(0xFFC9A24B)),
    "Purple" to listOf(Color(0xFF2A0F5C), Color(0xFF7B3FE4)),
    "Teal" to listOf(Color(0xFF053A33), Color(0xFF00A888)),
    "Red" to listOf(Color(0xFF5C0A10), Color(0xFFE03A3E)),
    "Sky" to listOf(Color(0xFF07306F), Color(0xFF1E88D8)),
    "Black" to listOf(Color(0xFF0B0B0D), Color(0xFF34363D)),
    "Green" to listOf(Color(0xFF0B3B1F), Color(0xFF1F9A55)),
    "Rose" to listOf(Color(0xFF5A1636), Color(0xFFE24D8B)),
    // Matched to real card designs.
    "Orange to violet" to listOf(Color(0xFFF2641E), Color(0xFFC7417A), Color(0xFF3A5BD9)),
    "Black & gold" to listOf(Color(0xFF15100C), Color(0xFF2A1F17), Color(0xFFB8913F)),
    "Black & brass" to listOf(Color(0xFF0B0B0D), Color(0xFF1B1A17), Color(0xFF8C6D2A)),
    "Black & magenta" to listOf(Color(0xFF0B0B0E), Color(0xFF2A0E22), Color(0xFFC2185B)),
    "Black & maroon" to listOf(Color(0xFF0B0B0E), Color(0xFF1E0B14), Color(0xFF7A1238)),
    "Navy" to listOf(Color(0xFF0B1230), Color(0xFF1C2A5A)),
    "Black & neon orange" to listOf(Color(0xFF0B0B0D), Color(0xFFFF5F00), Color(0xFFFF7A1A), Color(0xFFFF8A00)),
)

/** The card's own colour if picked, else its bank's. */
fun cardGradient(card: Card): List<Color> = card.colorIndex?.let { cardColors.getOrNull(it)?.second } ?: bankColors(card.bank)

/** Networks a card can show. */
val networks = listOf("Visa", "Mastercard", "RuPay", "Amex")

/** The network's mark, drawn simply in white (Mastercard as its two circles). */
@Composable
fun NetworkMark(network: String?) {
    when (network) {
        "Visa" -> Text("VISA", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
            fontStyle = FontStyle.Italic, letterSpacing = 1.sp)
        "Mastercard" -> Canvas(Modifier.size(44.dp, 28.dp)) {
            val r = size.height / 2
            drawCircle(Color(0xFFEB001B), r, Offset(r, r))
            drawCircle(Color(0xFFF79E1B).copy(alpha = 0.9f), r, Offset(size.width - r, r))
        }
        "RuPay" -> Text("RuPay", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
        "Amex" -> Surface(color = Color.Transparent, shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White)) {
            Text("AMEX", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
        else -> {}
    }
}

/** Two-stop gradient per bank, so each card looks like itself. Unknown banks get a stable pick. */
fun bankColors(bank: String): List<Color> = when (bank) {
    "HDFC Bank" -> listOf(Color(0xFF0A2A66), Color(0xFF2160D6))
    "Axis Bank" -> listOf(Color(0xFF4E0A28), Color(0xFFA3154F))
    "State Bank of India" -> listOf(Color(0xFF07306F), Color(0xFF1E88D8))
    "ICICI Bank" -> listOf(Color(0xFF5E170F), Color(0xFFD9601C))
    "Kotak Bank" -> listOf(Color(0xFF5C0A10), Color(0xFFCC2530))
    "American Express" -> listOf(Color(0xFF0B3F66), Color(0xFF3B8BC6))
    else -> listOf(
        listOf(Color(0xFF241858), Color(0xFF6A4CE2)),
        listOf(Color(0xFF053A33), Color(0xFF00A888)),
        listOf(Color(0xFF1C1F25), Color(0xFF4F5663)),
        listOf(Color(0xFF4A2A05), Color(0xFFD28E05)),
    )[Math.floorMod(bank.hashCode(), 4)]
}

/** Short bank mark for the white badge on a card: "HDFC", "AXIS", "SBI". */
fun bankShort(bank: String): String = when (bank) {
    "State Bank of India" -> "SBI"
    "American Express" -> "AMEX"
    "Federal Bank" -> "FED"
    "Indian Bank" -> "IB"
    "IDFC First Bank" -> "IDFC"
    else -> bank.split(' ').first().uppercase().take(5)
}

/**
 * A card drawn like the real thing. Top row: bank badge, name, "Bank · •• 1234", and a [status] pill.
 * Below: the masked number, then up to two [figures] (label to value) beside the network mark, and an
 * optional [progress] bar (e.g. share of caps used). [corner] draws extra content on top.
 */
@Composable
fun CardFace(
    card: Card,
    modifier: Modifier = Modifier,
    status: String? = null,
    figures: List<Pair<String, String>> = emptyList(),
    progress: Float? = null,
    corner: @Composable BoxScope.() -> Unit = {},
) {
    val colors = cardGradient(card)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1.586f)
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(colors, start = Offset.Zero, end = Offset.Infinite)),
    ) {
        // Soft light shapes for depth.
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.55f, center = Offset(size.width * 1.05f, -size.height * 0.1f))
            drawCircle(Color.White.copy(alpha = 0.05f), radius = size.width * 0.4f, center = Offset(-size.width * 0.1f, size.height * 1.1f))
        }
        Column(Modifier.matchParentSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(bankShort(card.bank), color = colors.last().copy(alpha = 1f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${card.bank} · •• ${card.last4.ifBlank { "····" }}", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1)
                }
                status?.let {
                    Surface(color = Color.White.copy(alpha = 0.18f), shape = CircleShape) {
                        Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp, 25.dp).clip(RoundedCornerShape(5.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFE9D9A6), Color(0xFFB79A55)))))
                Spacer(Modifier.width(16.dp))
                Text("••••  ••••  ••••  ${card.last4.ifBlank { "····" }}", color = Color.White.copy(alpha = 0.92f), fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                figures.take(2).forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label.uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                if (figures.isEmpty()) Spacer(Modifier.weight(1f))
                NetworkMark(card.network)
            }
            progress?.let { p ->
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                    if (p > 0f) Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape).background(Color.White))
                }
            }
        }
        corner()
    }
}

/** Tiny card swatch for list rows. */
@Composable
fun CardSwatch(card: Card, width: Dp = 36.dp) {
    Box(Modifier.size(width, width * 0.63f).clip(RoundedCornerShape(5.dp)).background(Brush.linearGradient(cardGradient(card))))
}

/** How much of a capped rule is used this cycle. */
@Composable
fun CapBar(usedUnits: Long, capUnits: Long, resetsOn: String, card: Card, color: Color = MaterialTheme.colorScheme.primary) {
    // Only a base-tied cap can be 0: nothing earned at the base rate yet this cycle, so no room yet.
    if (capUnits == 0L) {
        Text("No room yet: this cap grows with the card's base cashback this cycle",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val fraction = (usedUnits.toFloat() / capUnits).coerceIn(0f, 1f)
    val full = usedUnits >= capUnits
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape)
                .background(if (full) MaterialTheme.colorScheme.error else color))
        }
        Text(
            if (full) "Cap used up · resets $resetsOn" else "${reward(capUnits - usedUnits, card)} of ${reward(capUnits, card)} left · resets $resetsOn",
            style = MaterialTheme.typography.bodySmall,
            color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Categorical colours in fixed order (validated dark-mode palette); "Other" is always neutral grey. */
private val categoryPalette = listOf(
    Color(0xFF338CED), Color(0xFFE76624), Color(0xFF00A888), Color(0xFFD28E05),
    Color(0xFFE24D8B), Color(0xFF0AAC23), Color(0xFF9887EF), Color(0xFFEB6263),
)
val otherColor = Color(0xFF6B7280)

/** Colour follows the category, not its rank: slot = position in the sorted list of all categories. */
fun categoryColor(category: String, allCategories: List<String>): Color {
    val i = allCategories.sorted().indexOf(category)
    return if (category == "Other" || i !in categoryPalette.indices) otherColor else categoryPalette[i]
}

/** Donut with a 2dp gap between slices. */
@Composable
fun Donut(slices: List<Pair<Color, Long>>, size: Dp, thickness: Dp = 18.dp) {
    val total = slices.sumOf { it.second }.toFloat()
    Canvas(Modifier.size(size)) {
        val stroke = thickness.toPx()
        val gap = if (slices.size > 1) 2.5f else 0f
        var start = -90f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        for ((color, value) in slices) {
            val sweep = value / total * 360f
            drawArc(color, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
            start += sweep
        }
    }
}

/** One horizontal bar against a shared max, with a rounded data end. */
@Composable
fun ValueBar(fraction: Float, colors: List<Color>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape)
            .background(Brush.horizontalGradient(colors)))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = sectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primaryContainer, textColor: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    Surface(color = color, shape = CircleShape) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))
