package com.clawcode.smsfilter

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clawcode.smsfilter.core.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Senders considered spam: blocklisted numbers plus anyone in the quarantine log. */
internal fun spamSenders(
    blockedNumbers: Set<String>,
    blockedLog: BlockedLog,
): Set<String> = blockedNumbers + blockedLog.senders()

/** Folder view for the conversation list. */
internal sealed interface Folder {
    data object All : Folder
    data object Starred : Folder
    data object Important : Folder
    data class Labeled(val name: String) : Folder
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    repository: MessagingRepository,
    ruleStore: RuleStore,
    blockedLog: BlockedLog,
    settings: AppSettings,
    metaStore: ConversationMetaStore,
    onOpenThread: (Conversation) -> Unit,
    onNewMessage: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val tick by repository.changeTick.collectAsState()
    val rules by ruleStore.rules.collectAsState()
    val logEntries by blockedLog.entries.collectAsState()
    val metaMap by metaStore.meta.collectAsState()
    val labelNames by metaStore.labels.collectAsState()
    var allConversations by remember { mutableStateOf<List<Conversation>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var purgedThisSession by remember { mutableStateOf(false) }
    var cleaning by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<Folder>(Folder.All) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var labelDialogFor by remember { mutableStateOf<List<Conversation>?>(null) }
    var deleteTargets by remember { mutableStateOf<List<Conversation>?>(null) }

    val spam = remember(rules.blockedNumbers, logEntries) {
        spamSenders(rules.blockedNumbers, blockedLog)
    }
    val visible = allConversations
        ?.filterNot { PhoneNumbers.normalize(it.address) in spam }
        ?.filter { c ->
            val m = metaMap[metaStore.keyFor(c.address)] ?: ThreadMeta()
            when (val f = folder) {
                Folder.All -> true
                Folder.Starred -> m.starred
                Folder.Important -> m.important
                is Folder.Labeled -> f.name in m.labels
            }
        }
    val selectionMode = selected.isNotEmpty()
    val selectedConversations = visible.orEmpty().filter { it.threadId in selected }
    val swipeRight = settings.swipeRightAction
    val swipeLeft = settings.swipeLeftAction

    LaunchedEffect(tick) {
        allConversations = withContext(Dispatchers.IO) { repository.conversations() }
        if (!purgedThisSession) {
            purgedThisSession = true
            val days = settings.autoDeleteBlockedDays
            val otp = settings.autoDeleteOtp
            withContext(Dispatchers.IO) {
                if (days > 0) {
                    val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
                    val spamThreads = allConversations.orEmpty()
                        .filter { PhoneNumbers.normalize(it.address) in spam }
                        .map { it.threadId }
                    repository.purgeSpamThreadsOlderThan(spamThreads, cutoff)
                    blockedLog.purgeOlderThan(cutoff)
                }
                if (otp) {
                    repository.deleteOldOtpMessages(System.currentTimeMillis() - 24L * 60 * 60 * 1000)
                }
            }
        }
    }

    fun blockConversations(targets: List<Conversation>) {
        val digitsList = targets.mapNotNull {
            PhoneNumbers.normalize(it.address).ifEmpty { null }
        }
        if (digitsList.isEmpty()) return
        ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers + digitsList) }
        targets.forEach {
            blockedLog.append(
                BlockedMessage(System.currentTimeMillis(), it.address, it.snippet, "blocked from list")
            )
        }
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = if (targets.size == 1) "Blocked ${targets[0].displayName}"
                else "Blocked ${targets.size} conversations",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers - digitsList.toSet()) }
                digitsList.forEach { blockedLog.removeSender(it) }
            }
        }
    }

    fun setReadState(targets: List<Conversation>, read: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach {
                    if (read) repository.markThreadRead(it.threadId)
                    else repository.markThreadUnread(it.threadId)
                }
            }
        }
    }

    fun performSwipe(action: SwipeAction, conversation: Conversation) {
        when (action) {
            SwipeAction.BLOCK -> blockConversations(listOf(conversation))
            SwipeAction.READ_UNREAD ->
                setReadState(listOf(conversation), conversation.unreadCount > 0)
            SwipeAction.STAR -> metaStore.toggleStar(conversation.address)
            SwipeAction.DELETE -> deleteTargets = listOf(conversation) // confirm before deleting
            SwipeAction.NONE -> Unit
        }
    }

    fun cleanupRecentMessages() {
        if (cleaning) return
        cleaning = true
        scope.launch {
            val movedCount = withContext(Dispatchers.IO) {
                val engine = ruleStore.engine()
                val alreadySpam = spamSenders(ruleStore.rules.value.blockedNumbers, blockedLog)
                val matched = LinkedHashMap<String, BlockedMessage>()
                for (message in repository.recentInboxMessages(500)) {
                    val digits = PhoneNumbers.normalize(message.address)
                    if (digits.isEmpty() || digits in alreadySpam || digits in matched) continue
                    val isContact = repository.isContact(message.address)
                    val verdict = engine.evaluate(message.address, message.body, isContact)
                    if (verdict is com.clawcode.smsfilter.core.Verdict.Block) {
                        matched[digits] = BlockedMessage(
                            System.currentTimeMillis(), message.address, message.body,
                            "clean-up: ${verdict.reason}",
                        )
                    }
                }
                matched.values.forEach { blockedLog.append(it) }
                matched.size
            }
            cleaning = false
            snackbarHostState.showSnackbar(
                if (movedCount > 0) "Moved $movedCount conversation(s) to Blocked"
                else "No rule matches in the last 500 messages"
            )
        }
    }

    labelDialogFor?.let { targets ->
        LabelDialog(
            metaStore = metaStore,
            targets = targets,
            onDismiss = { labelDialogFor = null; selected = emptySet() },
        )
    }

    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            title = { Text("Delete ${targets.size} conversation(s)?") },
            text = { Text("This permanently removes them from your phone.") },
            confirmButton = {
                Button(onClick = {
                    deleteTargets = null
                    selected = emptySet()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            targets.all { repository.deleteThread(it.threadId) }
                        }
                        if (!ok) snackbarHostState.showSnackbar(
                            "Couldn't delete — this app must be the default SMS app"
                        )
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                SelectionTopBar(
                    count = selected.size,
                    onClose = { selected = emptySet() },
                    onStar = {
                        selectedConversations.forEach { metaStore.setStarred(it.address, true) }
                        selected = emptySet()
                    },
                    onUnstar = {
                        selectedConversations.forEach { metaStore.setStarred(it.address, false) }
                        selected = emptySet()
                    },
                    onMarkRead = { setReadState(selectedConversations, true); selected = emptySet() },
                    onMarkUnread = { setReadState(selectedConversations, false); selected = emptySet() },
                    onMarkImportant = {
                        selectedConversations.forEach { metaStore.setImportant(it.address, true) }
                        selected = emptySet()
                    },
                    onUnmarkImportant = {
                        selectedConversations.forEach { metaStore.setImportant(it.address, false) }
                        selected = emptySet()
                    },
                    onLabel = { labelDialogFor = selectedConversations },
                    onBlock = { blockConversations(selectedConversations); selected = emptySet() },
                    onDelete = { deleteTargets = selectedConversations },
                )
            } else {
                TopAppBar(
                    title = { Text("Messages") },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        TextButton(onClick = { cleanupRecentMessages() }, enabled = !cleaning) {
                            Text(if (cleaning) "Cleaning…" else "Clean up")
                        }
                        IconButton(onClick = onOpenFilters) {
                            Icon(Icons.Default.Settings, contentDescription = "Spam filter settings")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = onNewMessage) {
                    Icon(Icons.Default.Add, contentDescription = "Start chat")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            FolderChips(folder, labelNames) { folder = it }
            val list = visible
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No conversations here")
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.threadId }) { conversation ->
                        val meta = metaMap[metaStore.keyFor(conversation.address)] ?: ThreadMeta()
                        val isSelected = conversation.threadId in selected
                        val rowContent: @Composable () -> Unit = {
                            Surface(
                                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                            ) {
                                ConversationRow(
                                    conversation = conversation,
                                    isBlocked = false,
                                    meta = meta,
                                    selected = isSelected,
                                    selectionMode = selectionMode,
                                    onStarToggle = { metaStore.toggleStar(conversation.address) },
                                    onClick = {
                                        if (selectionMode) {
                                            selected = if (isSelected) selected - conversation.threadId
                                            else selected + conversation.threadId
                                        } else onOpenThread(conversation)
                                    },
                                    onLongClick = { selected = selected + conversation.threadId },
                                )
                            }
                        }
                        if (selectionMode) {
                            rowContent()
                        } else {
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        SwipeToDismissBoxValue.StartToEnd ->
                                            performSwipe(swipeRight, conversation)
                                        SwipeToDismissBoxValue.EndToStart ->
                                            performSwipe(swipeLeft, conversation)
                                        else -> Unit
                                    }
                                    false
                                },
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    SwipeActionBackground(
                                        dismissState.dismissDirection,
                                        AppSettings.SWIPE_LABELS[swipeRight] ?: "",
                                        AppSettings.SWIPE_LABELS[swipeLeft] ?: "",
                                    )
                                },
                            ) { rowContent() }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onStar: () -> Unit,
    onUnstar: () -> Unit,
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
    onMarkImportant: () -> Unit,
    onUnmarkImportant: () -> Unit,
    onLabel: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
        },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onStar) {
                Icon(Icons.Default.Star, contentDescription = "Add star")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
            IconButton(onClick = { overflow = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
            }
            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                DropdownMenuItem(text = { Text("Remove star") },
                    onClick = { overflow = false; onUnstar() })
                DropdownMenuItem(text = { Text("Mark read") },
                    onClick = { overflow = false; onMarkRead() })
                DropdownMenuItem(text = { Text("Mark unread") },
                    onClick = { overflow = false; onMarkUnread() })
                DropdownMenuItem(text = { Text("Mark important") },
                    onClick = { overflow = false; onMarkImportant() })
                DropdownMenuItem(text = { Text("Mark not important") },
                    onClick = { overflow = false; onUnmarkImportant() })
                DropdownMenuItem(text = { Text("Label as…") },
                    onClick = { overflow = false; onLabel() })
                DropdownMenuItem(text = { Text("Block") },
                    onClick = { overflow = false; onBlock() })
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderChips(active: Folder, labels: List<String>, onSelect: (Folder) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(active == Folder.All, { onSelect(Folder.All) }, { Text("All") })
        FilterChip(active == Folder.Starred, { onSelect(Folder.Starred) }, { Text("Starred") })
        FilterChip(
            active == Folder.Important,
            { onSelect(Folder.Important) },
            { Text("Important") },
        )
        labels.forEach { name ->
            FilterChip(
                active == Folder.Labeled(name),
                { onSelect(Folder.Labeled(name)) },
                { Text(name) },
            )
        }
    }
}

/** Dialog to add/remove labels for one or more conversations, and create new labels. */
@Composable
private fun LabelDialog(
    metaStore: ConversationMetaStore,
    targets: List<Conversation>,
    onDismiss: () -> Unit,
) {
    val labels by metaStore.labels.collectAsState()
    val metaMap by metaStore.meta.collectAsState()
    var newLabel by remember { mutableStateOf("") }
    // A label is "on" if every target has it (observes metaMap so checkboxes update live).
    fun appliedToAll(label: String) = targets.isNotEmpty() && targets.all {
        label in (metaMap[metaStore.keyFor(it.address)] ?: ThreadMeta()).labels
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label as") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEach { label ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (appliedToAll(label)) {
                                    targets.forEach { metaStore.removeLabel(it.address, label) }
                                } else {
                                    targets.forEach { metaStore.addLabel(it.address, label) }
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = appliedToAll(label),
                            onCheckedChange = null,
                        )
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("New label") },
                        singleLine = true,
                    )
                    TextButton(
                        onClick = {
                            val name = newLabel.trim()
                            if (name.isNotEmpty()) {
                                targets.forEach { metaStore.addLabel(it.address, name) }
                                newLabel = ""
                            }
                        },
                        enabled = newLabel.isNotBlank(),
                    ) { Text("Add") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun SwipeActionBackground(
    direction: SwipeToDismissBoxValue,
    rightLabel: String,
    leftLabel: String,
) {
    val (color, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            Triple(MaterialTheme.colorScheme.errorContainer, rightLabel, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            Triple(MaterialTheme.colorScheme.primaryContainer, leftLabel, Alignment.CenterEnd)
        else -> return
    }
    Box(
        Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    conversation: Conversation,
    isBlocked: Boolean = false,
    meta: ThreadMeta = ThreadMeta(),
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onStarToggle: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val unread = conversation.unreadCount > 0
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = null)
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Avatar(conversation.displayName)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (meta.important) {
                    Text(
                        "» ",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    conversation.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isBlocked) {
                    Text(
                        "Blocked",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                conversation.snippet,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.labels.isNotEmpty()) {
                Text(
                    meta.labels.joinToString("  ") { "🏷 $it" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                DateUtils.getRelativeTimeSpanString(conversation.dateMs).toString(),
                style = MaterialTheme.typography.labelSmall,
            )
            if (!selectionMode && onStarToggle != null) {
                IconButton(onClick = onStarToggle, modifier = Modifier.size(28.dp)) {
                    StarIcon(meta.starred)
                }
            } else if (unread) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
    }
}

/** Star icon: a true hollow outline when unstarred, gold-filled when starred. */
@Composable
private fun StarIcon(starred: Boolean) {
    Icon(
        painter = androidx.compose.ui.res.painterResource(
            if (starred) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        ),
        contentDescription = if (starred) "Unstar" else "Star",
        tint = if (starred) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * Guidance for the SEND_SMS restricted-permission wall: Android refuses to
 * grant SMS sending to sideloaded apps unless the app is the default SMS app
 * or the user lifts the restriction manually.
 */
internal const val SEND_PERMISSION_HELP =
    "Android is blocking SMS sending for this app. Easiest fix: settings (gear icon) → " +
        "Setup → \"Make default SMS app\". Alternative: long-press the app icon → " +
        "App info → ⋮ menu → \"Allow restricted settings\", then grant SMS permission."

internal fun hasSendPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    repository: MessagingRepository,
    ruleStore: RuleStore,
    blockedLog: BlockedLog,
    metaStore: ConversationMetaStore,
    threadId: Long,
    address: String,
    onBack: () -> Unit,
) {
    val tick by repository.changeTick.collectAsState()
    val metaMap by metaStore.meta.collectAsState()
    val meta = metaMap[metaStore.keyFor(address)] ?: ThreadMeta()
    var showLabelDialog by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(emptyList<ThreadMessage>()) }
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val settings = remember { App.from(context).settings }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var showBlockTextDialog by remember { mutableStateOf(false) }
    var blockTextInput by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var fontScale by remember { mutableFloatStateOf(settings.conversationTextScale) }
    LaunchedEffect(fontScale) { settings.conversationTextScale = fontScale }
    val sendPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        sendError = if (granted) null else SEND_PERMISSION_HELP
    }
    val listState = rememberLazyListState()

    LaunchedEffect(tick, threadId) {
        messages = withContext(Dispatchers.IO) {
            repository.messages(threadId).also { repository.markThreadRead(threadId) }
        }
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    fun blockNumber() {
        val digits = PhoneNumbers.normalize(address)
        if (digits.isEmpty()) return
        ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers + digits) }
        blockedLog.append(
            BlockedMessage(
                timestampMs = System.currentTimeMillis(),
                sender = address,
                body = messages.lastOrNull { !it.isOutgoing }?.body ?: "",
                reason = "number blocked from conversation",
            )
        )
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Blocked $address",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers - digits) }
                blockedLog.removeSender(digits)
            }
        }
    }

    if (showBlockTextDialog) {
        AlertDialog(
            onDismissRequest = { showBlockTextDialog = false },
            title = { Text("Block messages containing…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Any message containing this text will be blocked, from any " +
                            "number. Case, spacing, and digit-for-letter tricks are ignored."
                    )
                    OutlinedTextField(
                        value = blockTextInput,
                        onValueChange = { blockTextInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Text to block") },
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val value = blockTextInput.trim()
                        if (value.isNotEmpty()) {
                            ruleStore.update {
                                it.copy(textRules = (it.textRules + value).distinct())
                            }
                            showBlockTextDialog = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Blocking texts containing \"$value\"")
                            }
                        }
                    },
                    enabled = blockTextInput.isNotBlank(),
                ) { Text("Block") }
            },
            dismissButton = {
                TextButton(onClick = { showBlockTextDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete conversation?") },
            text = { Text("This deletes the whole conversation from your phone. It cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) { repository.deleteThread(threadId) }
                        if (deleted) {
                            onBack()
                        } else {
                            snackbarHostState.showSnackbar(
                                "Couldn't delete — this app must be the default SMS app"
                            )
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showLabelDialog) {
        LabelDialog(
            metaStore = metaStore,
            targets = listOf(Conversation(threadId, address, address, "", 0L, 0)),
            onDismiss = { showLabelDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(repository.displayName(address)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { metaStore.toggleStar(address) }) {
                        StarIcon(meta.starred)
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Conversation options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (meta.important) "Unmark important" else "Mark important") },
                            onClick = { menuOpen = false; metaStore.toggleImportant(address) },
                        )
                        DropdownMenuItem(
                            text = { Text("Label as…") },
                            onClick = { menuOpen = false; showLabelDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Block number") },
                            onClick = {
                                menuOpen = false
                                blockNumber()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Block text…") },
                            onClick = {
                                menuOpen = false
                                blockTextInput = messages.lastOrNull { !it.isOutgoing }?.body ?: ""
                                showBlockTextDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete conversation") },
                            onClick = {
                                menuOpen = false
                                showDeleteConfirm = true
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (settings.pinchToZoom) {
                            Modifier.pointerInput(Unit) {
                                androidx.compose.foundation.gestures.detectTransformGestures {
                                    _, _, zoom, _ ->
                                    fontScale = (fontScale * zoom).coerceIn(0.8f, 2.0f)
                                }
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message, fontScale)
                }
            }
            sendError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Text message") },
                    maxLines = 4,
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty()) return@IconButton
                        if (!hasSendPermission(context)) {
                            sendError = SEND_PERMISSION_HELP
                            sendPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            return@IconButton
                        }
                        when (val result = repository.send(address, text)) {
                            is SendResult.Sent -> {
                                draft = ""
                                sendError = null
                            }
                            is SendResult.Failed -> sendError = result.reason
                        }
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/** iPhone tapbacks arrive as text like: Loved "the message". Prefix an emoji. */
private val REACTION_REGEX = Regex(
    """^(Loved|Liked|Disliked|Laughed at|Emphasized|Questioned)\s+[“"'].*[”"']$""",
)

internal fun withReactionEmoji(body: String, enabled: Boolean): String {
    if (!enabled) return body
    val match = REACTION_REGEX.find(body.trim()) ?: return body
    val emoji = when (match.groupValues[1]) {
        "Loved" -> "❤️"
        "Liked" -> "👍"
        "Disliked" -> "👎"
        "Laughed at" -> "😂"
        "Emphasized" -> "‼️"
        "Questioned" -> "❓"
        else -> ""
    }
    return "$emoji $body"
}

@Composable
private fun MessageBubble(message: ThreadMessage, fontScale: Float = 1f) {
    val outgoing = message.isOutgoing
    val context = androidx.compose.ui.platform.LocalContext.current
    val displayBody = withReactionEmoji(
        message.body,
        App.from(context).settings.showIphoneReactionsAsEmoji,
    )
    // Delivery status for outgoing messages (only meaningful with reports on).
    val deliveryLabel = if (outgoing) when (message.status) {
        0 -> "Delivered"            // STATUS_COMPLETE
        64 -> "Not delivered"       // STATUS_FAILED
        else -> null
    } else null
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (outgoing) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (outgoing) 16.dp else 4.dp,
                bottomEnd = if (outgoing) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    displayBody,
                    fontSize = 16.sp * fontScale,
                    lineHeight = 22.sp * fontScale,
                    color = if (outgoing) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    buildString {
                        append(
                            DateUtils.formatDateTime(
                                context, message.dateMs, DateUtils.FORMAT_SHOW_TIME,
                            )
                        )
                        deliveryLabel?.let { append(" · "); append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outgoing) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    repository: MessagingRepository,
    onSent: (threadId: Long, address: String) -> Unit,
    onBack: () -> Unit,
) {
    var recipient by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    var recipients by remember { mutableStateOf(listOf<ContactMatch>()) }
    val context = LocalContext.current
    val sendPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        sendError = if (granted) null else SEND_PERMISSION_HELP
    }
    val suggestions = remember(recipient) { repository.searchContacts(recipient) }

    fun addRecipient(match: ContactMatch) {
        val digits = PhoneNumbers.normalize(match.number)
        if (recipients.none { PhoneNumbers.normalize(it.number) == digits }) {
            recipients = recipients + match
        }
        recipient = ""
    }

    /** Turns the currently typed token into a recipient chip. */
    fun commitTypedToken(): Boolean {
        val token = recipient.trim().trimEnd(',').trim()
        if (token.isEmpty()) return true
        return if (token.any { it.isLetter() }) {
            val match = repository.searchContacts(token).firstOrNull()
            if (match == null) {
                sendError = "No contact found for \"$token\""
                false
            } else {
                addRecipient(match)
                true
            }
        } else {
            addRecipient(ContactMatch(name = token, number = token))
            true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New conversation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        fun doSend() {
            val text = body.trim()
            if (text.isEmpty()) return
            if (!commitTypedToken()) return
            if (recipients.isEmpty()) {
                sendError = "Add at least one recipient"
                return
            }
            if (!hasSendPermission(context)) {
                sendError = SEND_PERMISSION_HELP
                sendPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                return
            }
            // Sends one individual SMS per recipient (SMS has no true group
            // thread without MMS/RCS, which third-party apps can't originate).
            var lastThreadId = -1L
            var lastAddress = ""
            var failure: String? = null
            for (chip in recipients) {
                when (val result = repository.send(chip.number, text)) {
                    is SendResult.Sent -> {
                        lastThreadId = result.threadId
                        lastAddress = chip.number
                    }
                    is SendResult.Failed -> failure = "${chip.name}: ${result.reason}"
                }
            }
            when {
                failure != null -> sendError = failure
                recipients.size == 1 && lastThreadId >= 0 -> onSent(lastThreadId, lastAddress)
                else -> onBack()
            }
        }

        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            // Scrollable recipient-editing area takes the free space...
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recipients.isNotEmpty()) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        recipients.forEach { chip ->
                            androidx.compose.material3.InputChip(
                                selected = false,
                                onClick = { recipients = recipients - chip },
                                label = { Text(chip.name) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { value ->
                        sendError = null
                        recipient = value
                        if (value.endsWith(",")) commitTypedToken()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("To — add several with commas") },
                    isError = sendError != null,
                    supportingText = sendError?.let { err ->
                        { Text(err, color = MaterialTheme.colorScheme.error) }
                    },
                )
                suggestions.forEach { match ->
                    Surface(
                        onClick = { addRecipient(match) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(match.name)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(match.name, style = MaterialTheme.typography.titleSmall)
                                Text(match.number, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (recipients.size > 1) {
                    Text(
                        "Each person gets this as a separate text — replies come back in " +
                            "their own conversation, not a shared group thread.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // ...and the message box + send button stay pinned above the keyboard.
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Text message") },
                    minLines = 2,
                    maxLines = 6,
                )
                IconButton(
                    onClick = { doSend() },
                    enabled = body.isNotBlank() &&
                        (recipients.isNotEmpty() || recipient.isNotBlank()),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Full-text + tag search across conversations. Matches the query against
 * contact name, number, message snippet, and any labels; a query equal to a
 * label name shows that whole folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repository: MessagingRepository,
    metaStore: ConversationMetaStore,
    onOpenThread: (Conversation) -> Unit,
    onBack: () -> Unit,
) {
    val tick by repository.changeTick.collectAsState()
    val metaMap by metaStore.meta.collectAsState()
    var query by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<Conversation>?>(null) }

    LaunchedEffect(tick) { all = withContext(Dispatchers.IO) { repository.conversations() } }

    val q = query.trim().lowercase()
    val results = if (q.isEmpty()) emptyList() else all.orEmpty().filter { c ->
        val m = metaMap[metaStore.keyFor(c.address)] ?: ThreadMeta()
        c.displayName.lowercase().contains(q) ||
            c.address.lowercase().contains(q) ||
            c.snippet.lowercase().contains(q) ||
            m.labels.any { it.lowercase().contains(q) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search name, number, text, or tag") },
                        singleLine = true,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                q.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Type to search your messages")
                }
                results.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text("No matches") }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.threadId }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            meta = metaMap[metaStore.keyFor(conversation.address)] ?: ThreadMeta(),
                            onClick = { onOpenThread(conversation) },
                        )
                    }
                }
            }
        }
    }
}
