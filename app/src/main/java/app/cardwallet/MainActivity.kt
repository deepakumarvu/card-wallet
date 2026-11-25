package app.cardwallet

import android.os.Bundle
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
    /** Leaving the app: refresh the home-screen cheat sheet with what changed. */
    override fun onStop() {
        super.onStop()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { app.cardwallet.widget.CheatSheetWidget().updateAll(applicationContext) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = WalletDb.get(this).dao()
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        // Keep the daily Gmail sync scheduled once Gmail is connected (an existing schedule is left as is).
        if (!getSharedPreferences("gmail", MODE_PRIVATE).getString("email", null).isNullOrBlank()) {
            app.cardwallet.statement.GmailWorker.schedule(this)
        }
        setContent {
            LaunchedEffect(Unit) {
                if (!prefs.getBoolean("seeded_v2", false)) {
                    Starter.merchants.forEach { dao.save(it) }
                    dao.log("First run: added ${Starter.merchants.size} merchant keywords")
                    prefs.edit { putBoolean("seeded_v2", true) }
                }
                if (!prefs.getBoolean("seeded_jewellery", false)) {
                    Starter.jewellery.forEach { dao.save(it) }
                    dao.log("Added ${Starter.jewellery.size} jewellery keywords")
                    prefs.edit { putBoolean("seeded_jewellery", true) }
                }
                // Bump the key when templates gain new settings, so existing cards pick them up once.
                if (!prefs.getBoolean("looks_filled_v4", false)) {
                    Starter.fillLooks(dao)
                    prefs.edit { putBoolean("looks_filled_v4", true) }
                }
                if (!prefs.getBoolean("recolours_v1", false)) {
                    Starter.applyRecolours(dao)
                    prefs.edit { putBoolean("recolours_v1", true) }
                }
            }
            WalletTheme { App(dao) }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Pick("Best card", Icons.Default.Star),
    Spends("Spends", Icons.Default.List),
    Cards("Cards", Icons.Default.AccountBox),
    More("More", Icons.Default.Settings),
}

@Composable
private fun App(dao: WalletDao) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Card the Spends list is narrowed to; null = all cards.
    var spendsCard by rememberSaveable { mutableStateOf<Long?>(null) }
    val haptics = LocalHapticFeedback.current
    // Surface sets the default text colour (onBackground); without it, plain titles draw black on black.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) {
        // Screens fade into each other.
        AnimatedContent(tab, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "tab") { t ->
            val modifier = Modifier.statusBarsPadding()
            when (Tab.entries[t]) {
                Tab.Pick -> BestCardScreen(dao, modifier)
                Tab.Spends -> SpendsScreen(dao, modifier, spendsCard) { spendsCard = it }
                Tab.Cards -> CardsScreen(dao, modifier) { spendsCard = it; tab = Tab.Spends.ordinal }
                Tab.More -> MoreScreen(dao, modifier)
            }
        }
        // Floating tab bar: a dark glass pill; the open tab shows its name.
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            shape = CircleShape,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f)))),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
        ) {
            Row(Modifier.padding(6.dp).animateContentSize(spring(stiffness = 500f)), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tab.entries.forEachIndexed { i, t ->
                    val selected = tab == i
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable {
                                if (tab != i) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                tab = i
                            }
                            .padding(horizontal = if (selected) 18.dp else 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(t.icon, contentDescription = t.label, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Text(t.label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    } }
}
