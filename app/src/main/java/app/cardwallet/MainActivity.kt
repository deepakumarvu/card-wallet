package app.cardwallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
        setContent {
            LaunchedEffect(Unit) {
                if (!prefs.getBoolean("seeded", false)) {
                    Starter.merchants.forEach { dao.save(it) }
                    prefs.edit { putBoolean("seeded", true) }
                }
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(dao)
            }
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
    Scaffold(
        bottomBar = {
            NavigationBar {
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
            Tab.Spends -> SpendsScreen(dao, modifier)
            Tab.Cards -> CardsScreen(dao, modifier)
            Tab.More -> MoreScreen(dao, modifier)
        }
    }
}
