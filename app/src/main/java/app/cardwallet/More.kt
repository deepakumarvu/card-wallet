package app.cardwallet

import android.Manifest
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.fillMaxWidth
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.InputChip
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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

// ---------- More: SMS, backup, merchant categories ----------

@Composable
private fun PanelTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

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
                dao.log("Backup saved: ${content.cards.size} cards, ${content.rules.size} rules, ${content.spends.size} spends")
            }
            toast("Backup saved")
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { restoreFrom = it }
    var showLog by remember { mutableStateOf(false) }
    val saveLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                val text = dao.allLogs().joinToString("\n") { "${logTime.format(Date(it.timeMillis))}  ${it.message}" }
                context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            }
            toast("Log saved")
        }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("More", style = MaterialTheme.typography.headlineMedium) }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelTitle(Icons.Default.Email, "Bank SMS")
                    Text("Reads card spends from bank messages on this phone. Nothing is sent anywhere: the app has no internet access.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { smsPermission.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }) {
                        Text("Read SMS from last month on")
                    }
                }
            }
        }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelTitle(Icons.Default.Lock, "Backup")
                    Text("A zip of CSV files, saved where you choose. Opens in any spreadsheet app.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { backup.launch("card-wallet-backup.zip") }) { Text("Back up") }
                        OutlinedButton(onClick = { restore.launch(arrayOf("application/zip")) }) { Text("Restore") }
                    }
                }
            }
        }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelTitle(Icons.Default.Info, "Activity log")
                    Text("Every change to cards, rules and spends, and what happened to each bank SMS. Stays on this phone.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { showLog = true }) { Text("Open log") }
                        OutlinedButton(onClick = { saveLog.launch("card-wallet-log.txt") }) { Text("Save as file") }
                    }
                }
            }
        }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PanelTitle(Icons.Default.List, "Merchant categories")
                    Text("A merchant whose name has a word starting with the keyword falls in that category. Rules can use categories.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(keyword, { keyword = it }, placeholder = { Text("Keyword") }, singleLine = true,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                        OutlinedTextField(category, { category = it }, placeholder = { Text("Category") }, singleLine = true,
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f))
                        FilledIconButton(enabled = keyword.isNotBlank() && category.isNotBlank(), onClick = {
                            scope.launch { dao.saveLogged(MerchantCategory(keyword.trim().lowercase(), category.trim())) }
                            keyword = ""
                        }) { Icon(Icons.Default.Add, contentDescription = "Add") }
                    }
                    val allCategories = merchants.map { it.category }.distinct()
                    merchants.groupBy { it.category }.toSortedMap().forEach { (cat, list) ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(categoryColor(cat, allCategories))
                                Spacer(Modifier.size(8.dp))
                                Text(cat, style = MaterialTheme.typography.titleSmall)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                list.forEach { m ->
                                    InputChip(selected = false, onClick = { scope.launch { dao.deleteLogged(m) } }, label = { Text(m.keyword) },
                                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLog) ActivityLog(dao, onClose = { showLog = false })

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
                                dao.log("Backup restored: ${content.cards.size} cards, ${content.rules.size} rules, ${content.spends.size} spends")
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


private val logTime = SimpleDateFormat("d MMM HH:mm:ss", Locale.forLanguageTag("en-IN"))

/** The activity log, newest first, full screen. */
@Composable
private fun ActivityLog(dao: WalletDao, onClose: () -> Unit) {
    val entries by dao.logs().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    FormDialog(
        onDismissRequest = onClose,
        title = { Text("Activity log") },
        text = {
            if (entries.isEmpty()) Text("Nothing logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(entries, key = { it.id }) { e ->
                    Column {
                        Text(logTime.format(Date(e.timeMillis)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(e.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { if (confirmClear) { scope.launch { dao.clearLogs() }; confirmClear = false } else confirmClear = true }) {
                Text(if (confirmClear) "Tap again to clear" else "Clear log", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
