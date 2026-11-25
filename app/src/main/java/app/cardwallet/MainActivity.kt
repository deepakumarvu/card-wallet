package app.cardwallet

import android.os.Bundle
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.Pick -> BestCardScreen(dao, modifier)
            Tab.Spends -> SpendsScreen(dao, modifier, spendsCard) { spendsCard = it }
            Tab.Cards -> CardsScreen(dao, modifier) { spendsCard = it; tab = Tab.Spends.ordinal }
            Tab.More -> MoreScreen(dao, modifier)
        }
    }
}
