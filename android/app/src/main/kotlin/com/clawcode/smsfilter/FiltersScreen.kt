package com.clawcode.smsfilter

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clawcode.smsfilter.core.NumberPattern
import com.clawcode.smsfilter.core.PhoneNumbers
import com.clawcode.smsfilter.core.RuleSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
                    ruleStore,
                    blockedLog,
                    repository,
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) lets a long rule wrap within the available width instead
        // of shoving "Remove" off-screen into a vertical sliver.
        Text(label, Modifier.weight(1f).padding(end = 8.dp))
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

/** Read-only Protection & Safety summary: what's active and what's guaranteed. */
@Composable
private fun ProtectionCard(
    ruleStore: RuleStore,
    blockedLog: BlockedLog,
    isDefaultSmsApp: () -> Boolean,
) {
    val rules by ruleStore.rules.collectAsState()
    val log by blockedLog.entries.collectAsState()
    val ruleCount = rules.textRules.size + rules.numberPatterns.size + rules.blockedNumbers.size
    val mode = if (isDefaultSmsApp()) "Full blocking (default SMS app)"
    else "Companion mode (needs setup below)"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Protection & safety", style = MaterialTheme.typography.titleSmall)
            Text("Mode: $mode", style = MaterialTheme.typography.bodyMedium)
            Text(
                "$ruleCount active rule(s) · ${log.size} message(s) filtered · " +
                    "${rules.allowedNumbers.size} always-allowed",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            Text("Always-on guarantees", style = MaterialTheme.typography.bodyMedium)
            listOf(
                "No internet permission — rules and messages never leave your phone.",
                "Links in messages are never auto-opened.",
                "MMS attachments are never auto-downloaded.",
                "Saved contacts are exempt from area-code and keyword rules.",
                "One-time passcodes are never blocked by keyword rules.",
            ).forEach { line ->
                Text("•  $line", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Theme, message-organization, and behavior settings (Google-Messages-style). */
@Composable
private fun AppearanceCard(settings: AppSettings) {
    val theme by settings.theme.collectAsState()
    var otp by remember { mutableStateOf(settings.autoDeleteOtp) }
    var reactions by remember { mutableStateOf(settings.showIphoneReactionsAsEmoji) }
    var swipeRight by remember { mutableStateOf(settings.swipeRightAction) }
    var swipeLeft by remember { mutableStateOf(settings.swipeLeftAction) }
    var delivery by remember { mutableStateOf(settings.deliveryReports) }
    var simpleChars by remember { mutableStateOf(settings.useSimpleCharacters) }
    var pinch by remember { mutableStateOf(settings.pinchToZoom) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Appearance & behavior", style = MaterialTheme.typography.titleSmall)

            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "System" to ThemeMode.SYSTEM,
                    "Light" to ThemeMode.LIGHT,
                    "Dark" to ThemeMode.DARK,
                ).forEach { (label, mode) ->
                    FilterChip(
                        selected = theme == mode,
                        onClick = { settings.setTheme(mode) },
                        label = { Text(label) },
                    )
                }
            }

            HorizontalDivider()
            ToggleRow(
                title = "Auto-delete OTP messages after 24 hours",
                subtitle = "One-time passcodes are removed on app open — they're useless " +
                    "after use and needn't linger.",
                checked = otp,
                onChange = { settings.autoDeleteOtp = it; otp = it },
            )
            ToggleRow(
                title = "Show iPhone reactions as emoji",
                subtitle = "Renders \"Loved …\" tapbacks from iPhones with an emoji.",
                checked = reactions,
                onChange = { settings.showIphoneReactionsAsEmoji = it; reactions = it },
            )
            ToggleRow(
                title = "Get SMS delivery reports",
                subtitle = "Show \"Delivered\" under your sent texts when the carrier confirms.",
                checked = delivery,
                onChange = { settings.deliveryReports = it; delivery = it },
            )
            ToggleRow(
                title = "Use simple characters",
                subtitle = "Convert accents and smart quotes to plain text so messages " +
                    "stay a single SMS segment.",
                checked = simpleChars,
                onChange = { settings.useSimpleCharacters = it; simpleChars = it },
            )
            ToggleRow(
                title = "Pinch to zoom conversation text",
                subtitle = "Pinch inside a conversation to resize the message text.",
                checked = pinch,
                onChange = { settings.pinchToZoom = it; pinch = it },
            )

            HorizontalDivider()
            Text("Swipe actions", style = MaterialTheme.typography.bodyMedium)
            SwipePicker("Swipe right", swipeRight) { settings.swipeRightAction = it; swipeRight = it }
            SwipePicker("Swipe left", swipeLeft) { settings.swipeLeftAction = it; swipeLeft = it }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SwipePicker(label: String, current: SwipeAction, onPick: (SwipeAction) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SwipeAction.entries.forEach { action ->
                FilterChip(
                    selected = current == action,
                    onClick = { onPick(action) },
                    label = { Text(AppSettings.SWIPE_LABELS[action] ?: action.name) },
                )
            }
        }
    }
}

/**
 * Paged retroactive clean-up: scan a chosen number of messages, report what
 * moved, then offer to continue into the next batch — a guided way to work
 * back through an old, junk-filled inbox.
 */
@Composable
private fun CleanupCard(
    ruleStore: RuleStore,
    blockedLog: BlockedLog,
    repository: MessagingRepository,
) {
    val scope = rememberCoroutineScope()
    var batchText by remember { mutableStateOf("500") }
    var scanned by remember { mutableLongStateOf(0L) }
    var totalMoved by remember { mutableLongStateOf(0L) }
    var running by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }

    fun runBatch(size: Int) {
        if (running || size <= 0) return
        running = true
        started = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SpamCleanup.run(repository, ruleStore, blockedLog, offset = scanned.toInt(), limit = size)
            }
            scanned += result.scanned
            totalMoved += result.moved
            reachedEnd = result.reachedEnd
            running = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Clean up old messages", style = MaterialTheme.typography.titleSmall)
            Text(
                "Apply your block rules to messages already in your inbox, working " +
                    "from newest to oldest. Matches move to Blocked (reversible with " +
                    "Not spam). Contacts are never touched.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = batchText,
                    onValueChange = { v -> batchText = v.filter { it.isDigit() }.take(6) },
                    modifier = Modifier.weight(1f),
                    label = { Text("How many to scan") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                )
                Button(
                    onClick = { runBatch(batchText.toIntOrNull() ?: 500) },
                    enabled = !running && !reachedEnd && batchText.isNotBlank(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(if (running) "Scanning…" else if (started) "Scan more" else "Scan") }
            }
            if (started) {
                Text(
                    "Scanned $scanned message(s) so far — moved $totalMoved to Blocked.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (reachedEnd) {
                    Text(
                        "Reached the end of your inbox.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (!running) {
                    Text("Keep going?", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { runBatch(500) }) { Text("Next 500") }
                        TextButton(onClick = { runBatch(1000) }) { Text("Next 1000") }
                    }
                }
                TextButton(onClick = {
                    scanned = 0L; totalMoved = 0L; reachedEnd = false; started = false
                }) { Text("Start over from newest") }
            }
        }
    }
}

@Composable
private fun SetupTab(
    settings: AppSettings,
    ruleStore: RuleStore,
    blockedLog: BlockedLog,
    repository: MessagingRepository,
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
        ProtectionCard(ruleStore, blockedLog, isDefaultSmsApp)
        AppearanceCard(settings)
        CleanupCard(ruleStore, blockedLog, repository)
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
