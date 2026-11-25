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
    val ignoredShapes by dao.ignoredShapes().collectAsState(emptyList())
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
    // Name / date of birth / PAN / mobile for guessing statement PDF passwords. App-private, never backed up.
    val prefs = remember { context.getSharedPreferences("statements", android.content.Context.MODE_PRIVATE) }
    var profile by remember { mutableStateOf(app.cardwallet.statement.PdfProfile.fromJson(prefs.getString("profile", null))) }
    var editProfile by remember { mutableStateOf(false) }
    var showLocked by remember { mutableStateOf(false) }
    var lockedCount by remember { mutableStateOf(app.cardwallet.statement.LockedPdfs.list(context).let { l -> l.size to l.count { it.oneOffPassword } }) } // (all, Scapia)

    // Gmail: Google sign-in gives a read-only token; Sync reads statements and alert emails with it.
    val gmailPrefs = remember { context.getSharedPreferences("gmail", android.content.Context.MODE_PRIVATE) }
    var gmail by remember { mutableStateOf(gmailPrefs.getString("email", "").orEmpty()) }
    var syncing by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf("") }
    fun runSync(token: String, email: String) {
        syncing = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { app.cardwallet.statement.GmailSync.sync(context, dao, token, profile) { syncStatus = it } } }
            syncing = false
            lockedCount = app.cardwallet.statement.LockedPdfs.list(context).let { l -> l.size to l.count { it.oneOffPassword } }
            toast(result.getOrElse { "Gmail sync failed: ${it.message}" })
            result.exceptionOrNull()?.let { dao.log("Gmail sync failed: $it"); android.util.Log.e("CardWallet", "Gmail sync failed", it) }
        }
    }
    // Every sign-in step goes to the activity log, so a failed sign-in can be traced.
    fun signInNote(text: String) { toast(text); android.util.Log.w("CardWallet", "Gmail sign-in: $text"); scope.launch { dao.log("Gmail sign-in: $text") } }
    fun gotToken(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        val token = result.accessToken ?: return signInNote("Google didn't give access to Gmail.")
        scope.launch {
            // Which account the token is for: ask Gmail itself.
            val email = runCatching { withContext(Dispatchers.IO) { app.cardwallet.statement.GmailSync.emailFor(token) } }
                .getOrElse { return@launch signInNote("couldn't read the account address: ${it.message}") }
            dao.log("Gmail sign-in: got access for $email, syncing")
            gmail = email
            gmailPrefs.edit().putString("email", email).apply()
            runSync(token, email)
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching { com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(res.data) }
            .onSuccess { gotToken(it) }
            .onFailure { signInNote("cancelled or refused (result ${res.resultCode}): ${it.message}") }
    }
    fun connectAndSync() {
        scope.launch { dao.log("Gmail sign-in: started") }
        val request = com.google.android.gms.auth.api.identity.AuthorizationRequest.builder()
            .setRequestedScopes(listOf(com.google.android.gms.common.api.Scope(app.cardwallet.statement.GmailSync.SCOPE), com.google.android.gms.common.api.Scope("email")))
            .build()
        com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { r ->
                val pending = r.pendingIntent
                if (r.hasResolution() && pending != null) {
                    scope.launch { dao.log("Gmail sign-in: asking you to choose an account and allow access") }
                    consent.launch(androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build())
                }
                else gotToken(r)
            }
            .addOnFailureListener { e ->
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                signInNote("failed${code?.let { " (code $it${if (it == 10) ": app not registered, check package and SHA-1 in Google Cloud" else ""})" }.orEmpty()}: ${e.message}")
            }
    }
    val importPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                app.cardwallet.statement.StatementImport.import(context, dao, bytes, profile)
            }
            toast(outcome.message)
            lockedCount = app.cardwallet.statement.LockedPdfs.list(context).let { l -> l.size to l.count { it.oneOffPassword } }
        }
    }
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
                    Text("Reads card spends from bank messages on this phone. Messages never leave the phone.",
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
                    PanelTitle(Icons.Default.Email, "Gmail")
                    Text("Reads statement PDFs and card alert emails from your Gmail, read-only. Mail goes straight from Gmail to this phone; nothing is sent anywhere else.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (gmail.isNotBlank()) Text("Connected: $gmail", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(enabled = !syncing, onClick = { connectAndSync() }) {
                            Text(when { syncing -> "Syncing…"; gmail.isBlank() -> "Connect Gmail"; else -> "Sync now" })
                        }
                        // Fetch every statement of the last 6 months again, e.g. after adding a card or old card digits.
                        // Spends already logged aren't added twice.
                        if (gmail.isNotBlank()) OutlinedButton(enabled = !syncing, onClick = {
                            gmailPrefs.edit().remove("seen_statement_emails").remove("synced_up_to").apply()
                            scope.launch { dao.log("Gmail: re-scanning the last 6 months") }
                            connectAndSync()
                        }) { Text("Re-scan 6 months") }
                    }
                    if (syncing && syncStatus.isNotBlank()) Text(syncStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelTitle(Icons.Default.List, "Statements")
                    Text("Import a card statement PDF: sets the card's statement day and adds purchases that SMS missed. " +
                        "Read on this phone; the PDF isn't saved.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (profile.name.isBlank() || profile.dob == null) "Add your name and date of birth so locked PDFs open."
                        else "Unlocks PDFs using ${profile.name.split(" ").first()}'s details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (profile.dob == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { importPdf.launch(arrayOf("application/pdf")) }) { Text("Import PDF") }
                        OutlinedButton(onClick = { editProfile = true }) { Text("PDF passwords") }
                    }
                    // Scapia's PDFs have a new password each time; they're optional, so they don't show as a problem.
                    val (allLocked, scapiaLocked) = lockedCount
                    if (allLocked > 0) OutlinedButton(onClick = { showLocked = true }) {
                        Text(
                            if (allLocked == scapiaLocked) "Locked PDFs ($allLocked, Scapia optional)" else "Locked PDFs (${allLocked - scapiaLocked})",
                            color = if (allLocked == scapiaLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
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
        if (ignoredShapes.isNotEmpty()) item {
            Panel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PanelTitle(Icons.Default.Close, "Ignored message types")
                    Text("Bank messages like these are skipped, because you marked one \"Not a spend\". Numbers are shown as #.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ignoredShapes.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.senderKey, style = MaterialTheme.typography.titleSmall)
                                Text(s.shape, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                            }
                            IconButton(onClick = { scope.launch { dao.delete(s); dao.log("Stopped ignoring ${s.senderKey} messages like \"${s.shape.take(80)}…\"") } }) {
                                Icon(Icons.Default.Delete, contentDescription = "Stop ignoring")
                            }
                        }
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
    if (showLocked) LockedPdfsScreen(dao, onClose = {
        showLocked = false
        lockedCount = app.cardwallet.statement.LockedPdfs.list(context).let { l -> l.size to l.count { it.oneOffPassword } }
        profile = app.cardwallet.statement.LockedPdfs.loadProfile(context)
    })
    if (editProfile) ProfileForm(profile, onDismiss = { editProfile = false }) {
        profile = it
        prefs.edit().putString("profile", it.toJson()).apply()
        editProfile = false
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

/** The details Indian banks build statement passwords from. Kept only on this phone. */
@Composable
private fun ProfileForm(profile: app.cardwallet.statement.PdfProfile, onDismiss: () -> Unit, onSave: (app.cardwallet.statement.PdfProfile) -> Unit) {
    val dobFormat = java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")
    var name by remember { mutableStateOf(profile.name) }
    var dob by remember { mutableStateOf(profile.dob?.format(dobFormat).orEmpty()) }
    var pan by remember { mutableStateOf(profile.pan) }
    var mobile by remember { mutableStateOf(profile.mobile) }
    val dobDate = runCatching { java.time.LocalDate.parse(dob.trim(), dobFormat) }.getOrNull()
    FormDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF passwords") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Banks lock statements with passwords made from these (e.g. first 4 letters of your name + birth day and month). " +
                    "The app tries the usual patterns. Stored only in this app on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Name as on the card") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dob, { dob = it }, label = { Text("Date of birth (DD-MM-YYYY)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = dob.isNotBlank() && dobDate == null)
                OutlinedTextField(pan, { pan = it.uppercase() }, label = { Text("PAN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mobile, { mobile = it.filter(Char::isDigit) }, label = { Text("Mobile (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (profile.templates.isNotEmpty()) {
                    Text("Learned from passwords you typed:", style = MaterialTheme.typography.labelMedium)
                    profile.templates.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                }
            }
        },
        confirmButton = {
            Button(enabled = dob.isBlank() || dobDate != null, modifier = Modifier.fillMaxWidth(),
                onClick = { onSave(profile.copy(name = name.trim(), dob = dobDate, pan = pan.trim(), mobile = mobile)) }) { Text("Save") }
        },
    )
}

/** Statements no known password opened. Type a password once; the app learns the pattern and opens the rest. */
@Composable
private fun LockedPdfsScreen(dao: WalletDao, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(app.cardwallet.statement.LockedPdfs.list(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    FormDialog(
        onDismissRequest = onClose,
        title = { Text("Locked PDFs") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("Type the password of any one. The app works out how it's built from your name, date of birth, PAN, mobile or " +
                        "card digits, remembers that pattern, and tries it on the rest. Passwords stay on this phone.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp))
                }
                if (items.isEmpty()) item { Text("Nothing locked.") }
                items(items, key = { it.id }) { item ->
                    var password by remember { mutableStateOf("") }
                    Panel {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.label.ifBlank { "Imported PDF" }, style = MaterialTheme.typography.titleSmall)
                            item.received?.let { Text("Received $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (item.oneOffPassword) Text("Password is in the Scapia app (each statement has its own).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(password, { password = it }, placeholder = { Text("Password") }, singleLine = true, modifier = Modifier.weight(1f))
                                Button(enabled = password.isNotBlank() && !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        message = runCatching { withContext(Dispatchers.IO) { app.cardwallet.statement.LockedPdfs.unlock(context, dao, item, password) } }
                                            .getOrElse { e -> dao.log("Unlock failed: $e"); android.util.Log.e("CardWallet", "Unlock failed", e); "Couldn't open it: ${e.message}" }
                                        items = app.cardwallet.statement.LockedPdfs.list(context)
                                        busy = false
                                    }
                                }) { Text(if (busy) "…" else "Unlock") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") } },
    )
}
