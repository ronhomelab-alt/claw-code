package com.clawcode.smsfilter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clawcode.smsfilter.core.NumberPattern
import com.clawcode.smsfilter.core.PhoneNumbers
import com.clawcode.smsfilter.core.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersScreen(
    ruleStore: RuleStore,
    blockedLog: BlockedLog,
    settings: AppSettings,
    repository: MessagingRepository,
    isDefaultSmsApp: () -> Boolean,
    onRequestDefaultSmsRole: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenThread: (Conversation) -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Rules", "Blocked", "Setup")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spam filter") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            when (tab) {
                0 -> RulesTab(ruleStore)
                1 -> BlockedTab(blockedLog, ruleStore, repository, settings, onOpenThread)
                2 -> SetupTab(
                    settings,
                    isDefaultSmsApp,
                    onRequestDefaultSmsRole,
                    onOpenNotificationAccess,
                )
            }
        }
    }
}

@Composable
private fun RulesTab(ruleStore: RuleStore) {
    val rules by ruleStore.rules.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Number, pattern like (507) 413-####, or text") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val value = input.trim()
                if (value.isNotEmpty()) {
                    ruleStore.update { it.addSmart(value) }
                    input = ""
                }
            }) { Text("Block") }
            TextButton(onClick = {
                val value = input.trim()
                if (value.isNotEmpty()) {
                    ruleStore.update {
                        it.copy(allowedNumbers = it.allowedNumbers + PhoneNumbers.normalize(value))
                    }
                    input = ""
                }
            }) { Text("Always allow") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(rules.numberPatterns) { pattern ->
                RuleRow("Number pattern: ${pattern.raw}") {
                    ruleStore.update { it.copy(numberPatterns = it.numberPatterns - pattern) }
                }
            }
            items(rules.blockedNumbers.toList()) { number ->
                RuleRow("Blocked number: $number") {
                    ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers - number) }
                }
            }
            items(rules.textRules) { text ->
                RuleRow("Body contains: \"$text\"") {
                    ruleStore.update { it.copy(textRules = it.textRules - text) }
                }
            }
            items(rules.allowedNumbers.toList()) { number ->
                RuleRow("Always allow: $number") {
                    ruleStore.update { it.copy(allowedNumbers = it.allowedNumbers - number) }
                }
            }
        }
    }
}

/**
 * Classify free-form input: a full 10-digit number (no wildcards) becomes an
 * exact blocklist entry, anything else with digits/# becomes a pattern, and
 * everything else is treated as a body-text rule.
 */
private fun RuleSet.addSmart(value: String): RuleSet {
    val digitsAndWildcards = value.filter { it.isDigit() || it == '#' }
    val letters = value.count { it.isLetter() }
    return when {
        letters > 0 || digitsAndWildcards.isEmpty() ->
            copy(textRules = (textRules + value).distinct())
        !digitsAndWildcards.contains('#') && PhoneNumbers.normalize(value).length == 10 ->
            copy(blockedNumbers = blockedNumbers + PhoneNumbers.normalize(value))
        else ->
            NumberPattern.parse(value)
                ?.let { copy(numberPatterns = (numberPatterns + it).distinct()) }
                ?: this
    }
}

@Composable
private fun RuleRow(label: String, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.padding(top = 12.dp))
        TextButton(onClick = onDelete) { Text("Remove") }
    }
}

/**
 * Spam & blocked, Google-Messages-style: blocked senders appear as real
 * conversations — tappable, readable, replyable — hidden from the main list.
 */
@Composable
private fun BlockedTab(
    blockedLog: BlockedLog,
    ruleStore: RuleStore,
    repository: MessagingRepository,
    settings: AppSettings,
    onOpenThread: (Conversation) -> Unit,
) {
    val rules by ruleStore.rules.collectAsState()
    val logEntries by blockedLog.entries.collectAsState()
    val tick by repository.changeTick.collectAsState()
    var all by remember { mutableStateOf<List<Conversation>?>(null) }
    var autoDeleteDays by remember { mutableIntStateOf(settings.autoDeleteBlockedDays) }

    LaunchedEffect(tick) {
        all = withContext(Dispatchers.IO) { repository.conversations() }
    }

    val spam = rules.blockedNumbers + blockedLog.senders()
    val blockedConversations = all.orEmpty().filter {
        PhoneNumbers.normalize(it.address) in spam
    }
    // Log entries with no surviving conversation (e.g. auto-deleted thread).
    val logOnlySenders = logEntries
        .filter { entry ->
            val digits = PhoneNumbers.normalize(entry.sender)
            blockedConversations.none { PhoneNumbers.normalize(it.address) == digits }
        }
        .groupBy { PhoneNumbers.normalize(it.sender) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Auto-delete blocked messages", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.AUTO_DELETE_CHOICES.forEach { (label, days) ->
                FilterChip(
                    selected = autoDeleteDays == days,
                    onClick = {
                        settings.autoDeleteBlockedDays = days
                        autoDeleteDays = days
                    },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "Runs when the app opens; deletes blocked conversations' messages " +
                "older than the chosen age.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()

        if (all == null) {
            Text("Loading…")
        } else if (blockedConversations.isEmpty() && logOnlySenders.isEmpty()) {
            Text("No blocked conversations.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(blockedConversations, key = { it.threadId }) { conversation ->
                    Column {
                        ConversationRow(
                            conversation = conversation,
                            isBlocked = true,
                            onClick = { onOpenThread(conversation) },
                        )
                        Row {
                            TextButton(onClick = {
                                val digits = PhoneNumbers.normalize(conversation.address)
                                ruleStore.update {
                                    it.copy(blockedNumbers = it.blockedNumbers - digits)
                                }
                                blockedLog.removeSender(digits)
                            }) { Text("Not spam") }
                        }
                        HorizontalDivider()
                    }
                }
                items(logOnlySenders.entries.toList(), key = { it.key }) { (digits, entries) ->
                    val latest = entries.maxBy { it.timestampMs }
                    Column {
                        Text(latest.sender, style = MaterialTheme.typography.titleSmall)
                        Text(latest.body, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(latest.reason, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            ruleStore.update {
                                it.copy(blockedNumbers = it.blockedNumbers - digits)
                            }
                            blockedLog.removeSender(digits)
                        }) { Text("Not spam") }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupTab(
    settings: AppSettings,
    isDefaultSmsApp: () -> Boolean,
    onRequestDefaultSmsRole: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
) {
    var hidePreviews by remember { mutableStateOf(settings.hideNotificationPreviews) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val crashFile = remember { java.io.File(context.filesDir, App.CRASH_FILE) }
    var crashText by remember {
        mutableStateOf(if (crashFile.exists()) crashFile.readText() else null)
    }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        crashText?.let { trace ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Last crash report",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Screenshot this and share it to get the crash fixed:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(trace.take(1500), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        crashFile.delete()
                        crashText = null
                    }) { Text("Clear") }
                }
            }
        }

        if (!hasSendPermission(context)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "SMS sending is blocked",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "Android restricts SMS permissions for apps installed outside " +
                            "the Play Store. Making this the default SMS app grants them " +
                            "automatically (recommended). Or lift the restriction " +
                            "manually: App info → ⋮ menu → \"Allow restricted settings\", " +
                            "then allow SMS."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRequestDefaultSmsRole) { Text("Make default") }
                        TextButton(onClick = {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings
                                        .ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts(
                                        "package", context.packageName, null
                                    ),
                                )
                            )
                        }) { Text("Open App info") }
                    }
                }
            }
        }

        Text("Privacy & security", style = MaterialTheme.typography.titleMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Hide message previews",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Switch(
                        checked = hidePreviews,
                        onCheckedChange = {
                            settings.hideNotificationPreviews = it
                            hidePreviews = it
                        },
                    )
                }
                Text(
                    "Notifications show only who texted — never the message content — " +
                        "so scam text can't appear on your lock screen or shade. " +
                        "Open the app to read messages."
                )
                Text(
                    "Always on: message text is never rendered as clickable links, and " +
                        "MMS attachments are never downloaded — nothing in a message " +
                        "can auto-fetch content or execute.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Choose a filtering mode", style = MaterialTheme.typography.titleMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Full blocking (default SMS app)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Blocked texts are quarantined before any inbox or notification, and " +
                        "this app becomes your texting app (conversations, replies, " +
                        "notifications). Android allows only one default SMS app at a time."
                )
                Button(onClick = onRequestDefaultSmsRole, enabled = !isDefaultSmsApp()) {
                    Text(if (isDefaultSmsApp()) "Already default" else "Make default SMS app")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Companion mode (keep Google Messages)",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Google Messages stays your default app. Spam notifications are " +
                        "dismissed the instant they appear and logged here; the message " +
                        "itself still lands in Google Messages. Requires notification access."
                )
                Button(onClick = onOpenNotificationAccess) { Text("Grant notification access") }
            }
        }

        val versionName = remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (_: Exception) {
                null
            } ?: "?"
        }
        Text(
            "SMS Spam Filter v$versionName",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
