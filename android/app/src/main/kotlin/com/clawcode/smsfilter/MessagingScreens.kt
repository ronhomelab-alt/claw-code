package com.clawcode.smsfilter

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
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

                list.isEmpty() -> EmptyState(
                    icon = Icons.Default.Email,
                    message = when (folder) {
                        Folder.All -> "No conversations yet — tap + to start one"
                        Folder.Starred -> "No starred conversations"
                        Folder.Important -> "Nothing marked important"
                        is Folder.Labeled -> "No conversations with this label"
                    },
                )

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
        ContactAvatar(conversation.displayName, conversation.photoUri)
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
                    // Unread = full-strength + bold; read = grayer + normal.
                    color = if (unread) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = if (unread) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                // Blue + bold timestamp signals unread, like Google Messages.
                color = if (unread) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!selectionMode && onStarToggle != null) {
                    IconButton(onClick = onStarToggle, modifier = Modifier.size(28.dp)) {
                        StarIcon(meta.starred)
                    }
                }
                // Prominent blue unread dot — shown alongside the star.
                if (unread) {
                    androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                    Box(
                        Modifier
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
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

/** Friendly centered empty state: a dim icon over an explanatory line. */
@Composable
internal fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    val rules by ruleStore.rules.collectAsState()
    val logEntries by blockedLog.entries.collectAsState()
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
    var loadedCount by remember { mutableIntStateOf(0) }
    var newWhileAway by remember { mutableIntStateOf(0) }
    // Message ids whose links the user deliberately switched on. Kept only
    // in memory: leaving the thread disarms every link again.
    var linksEnabledFor by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(tick, threadId) {
        val fresh = withContext(Dispatchers.IO) {
            repository.messages(threadId).also { repository.markThreadRead(threadId) }
        }
        // Follow the bottom while the user is there; if they scrolled up to
        // read, don't yank them down — count arrivals for the "new" chip.
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val wasAtBottom = loadedCount == 0 || lastVisible >= loadedCount - 2
        messages = fresh
        if (fresh.isNotEmpty() && wasAtBottom) {
            listState.scrollToItem(fresh.lastIndex)
            newWhileAway = 0
        } else if (fresh.size > loadedCount) {
            newWhileAway += fresh.size - loadedCount
        }
        loadedCount = fresh.size
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(
                            name = repository.displayName(address),
                            photoUri = remember(address) { repository.contactPhotoUri(address) },
                            size = 36.dp,
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                        Text(
                            repository.displayName(address),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Quiet banner when this sender is currently blocked, naming the
            // rule that fired, with a one-tap way back out.
            val digits = remember(address) { PhoneNumbers.normalize(address) }
            val blockedReason = if (
                digits.isNotEmpty() &&
                (digits in rules.blockedNumbers || logEntries.any {
                    PhoneNumbers.normalize(it.sender) == digits
                })
            ) {
                logEntries.lastOrNull { PhoneNumbers.normalize(it.sender) == digits }?.reason
                    ?: "number is on your block list"
            } else {
                null
            }
            blockedReason?.let { reason ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(
                                "Blocked as spam",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers - digits) }
                            blockedLog.removeSender(digits)
                        }) { Text("Not spam") }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (settings.pinchToZoom) {
                                Modifier.pointerInput(Unit) {
                                    detectTransformGestures { _: Offset, _: Offset, zoom: Float, _: Float ->
                                        fontScale = (fontScale * zoom).coerceIn(0.8f, 2.0f)
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                ) {
                    itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                        // Show a centered date/time separator above the first
                        // message and whenever a new day starts or there's a gap
                        // over an hour — Google-Messages style.
                        val prev = messages.getOrNull(index - 1)
                        val next = messages.getOrNull(index + 1)
                        val needsSeparator =
                            prev == null || newSeparatorNeeded(prev.dateMs, message.dateMs)
                        if (needsSeparator) {
                            DateSeparator(message.dateMs)
                        }
                        // Back-to-back bubbles from the same side tighten up:
                        // squared inner corners, timestamp only on the last.
                        val groupedWithPrev =
                            !needsSeparator && prev != null && sameBubbleGroup(prev, message)
                        val groupedWithNext = next != null &&
                            !newSeparatorNeeded(message.dateMs, next.dateMs) &&
                            sameBubbleGroup(message, next)
                        MessageBubble(
                            message = message,
                            fontScale = fontScale,
                            groupedWithPrev = groupedWithPrev,
                            groupedWithNext = groupedWithNext,
                            linksOn = message.id in linksEnabledFor,
                            onToggleLinks = {
                                val turningOn = message.id !in linksEnabledFor
                                linksEnabledFor = if (turningOn) {
                                    linksEnabledFor + message.id
                                } else {
                                    linksEnabledFor - message.id
                                }
                                if (turningOn) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Links on for this message — tap the switch again to turn off"
                                        )
                                    }
                                }
                            },
                            onRetry = {
                                when (val result = repository.send(address, message.body)) {
                                    is SendResult.Sent -> scope.launch {
                                        snackbarHostState.showSnackbar("Message re-sent")
                                    }
                                    is SendResult.Failed -> scope.launch {
                                        snackbarHostState.showSnackbar(result.reason)
                                    }
                                }
                            },
                        )
                    }
                }
                // Floating jump-to-latest chip once the user scrolls up.
                val atBottom by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
                    }
                }
                LaunchedEffect(atBottom) { if (atBottom) newWhileAway = 0 }
                if (!atBottom) {
                    Surface(
                        onClick = {
                            scope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.lastIndex)
                                }
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = if (newWhileAway > 0) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (newWhileAway > 0) {
                                Text(
                                    if (newWhileAway == 1) "1 new message"
                                    else "$newWhileAway new messages",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Jump to latest",
                            )
                        }
                    }
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
            PillComposer(
                value = draft,
                onValueChange = { draft = it },
                sendEnabled = true,
                onSend = {
                    val text = draft.trim()
                    if (text.isEmpty()) return@PillComposer
                    if (!hasSendPermission(context)) {
                        sendError = SEND_PERMISSION_HELP
                        sendPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                        return@PillComposer
                    }
                    when (val result = repository.send(address, text)) {
                        is SendResult.Sent -> {
                            draft = ""
                            sendError = null
                        }
                        is SendResult.Failed -> sendError = result.reason
                    }
                },
            )
        }
    }
}

/** Bubbles group when they're from the same side within a few minutes. */
private fun sameBubbleGroup(a: ThreadMessage, b: ThreadMessage): Boolean =
    a.isOutgoing == b.isOutgoing && (b.dateMs - a.dateMs) < 3L * 60 * 1000

/** True when a new date/time separator should precede a message. */
private fun newSeparatorNeeded(prevMs: Long, curMs: Long): Boolean {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = prevMs }
    val b = java.util.Calendar.getInstance().apply { timeInMillis = curMs }
    val sameDay = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    return !sameDay || (curMs - prevMs) > 60L * 60 * 1000
}

/** Centered "Today • 6:05 PM" / "Yesterday • 6:05 PM" / "Fri, Jul 17 • 6:05 PM" separator. */
@Composable
private fun DateSeparator(dateMs: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dayLabel = when {
        DateUtils.isToday(dateMs) -> "Today"
        DateUtils.isToday(dateMs + 24L * 60 * 60 * 1000) -> "Yesterday"
        else -> DateUtils.formatDateTime(
            context, dateMs,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                DateUtils.FORMAT_ABBREV_ALL,
        )
    }
    val time = DateUtils.formatDateTime(context, dateMs, DateUtils.FORMAT_SHOW_TIME)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            "$dayLabel • $time",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
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

/** URLs in message bodies: http(s) links and www-prefixed hosts. */
private val URL_REGEX = Regex("""(?i)\b(?:https?://|www\.)[^\s]+""")

internal fun containsUrl(body: String): Boolean = URL_REGEX.containsMatchIn(body)

/**
 * Marks each URL in [body] as a tappable, underlined link. Only called after
 * the user flips the per-message link switch — links are dead text otherwise.
 */
private fun linkifiedBody(
    body: String,
    linkColor: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in URL_REGEX.findAll(body)) {
        var url = match.value
        // Trailing punctuation belongs to the sentence, not the link.
        while (url.isNotEmpty() && url.last() in ".,;:!?)]}\"'") url = url.dropLast(1)
        if (url.isEmpty()) continue
        append(body.substring(last, match.range.first))
        val target = if (url.startsWith("www", ignoreCase = true)) "https://$url" else url
        withLink(
            LinkAnnotation.Url(
                target,
                TextLinkStyles(
                    style = SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            )
        ) { append(url) }
        last = match.range.first + url.length
    }
    append(body.substring(last))
}

/**
 * Per-message link switch, riding the bubble's corner: dim hollow circle =
 * links off (default), filled accent circle = links live. Turning links on
 * has to be a deliberate act, so a scam link can't be fat-fingered.
 */
@Composable
private fun LinkToggleBadge(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = 1.5.dp,
                color = if (on) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_link),
            contentDescription = if (on) "Turn links off" else "Turn links on",
            tint = if (on) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    message: ThreadMessage,
    fontScale: Float = 1f,
    groupedWithPrev: Boolean = false,
    groupedWithNext: Boolean = false,
    onRetry: (() -> Unit)? = null,
    linksOn: Boolean = false,
    onToggleLinks: (() -> Unit)? = null,
) {
    val outgoing = message.isOutgoing
    val context = androidx.compose.ui.platform.LocalContext.current
    val displayBody = withReactionEmoji(
        message.body,
        App.from(context).settings.showIphoneReactionsAsEmoji,
    )
    // Life-cycle label for outgoing messages: still sending, failed to send,
    // or delivered (the latter only meaningful with delivery reports on).
    val failed = outgoing && (
        message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED ||
            message.status == 64 // STATUS_FAILED
        )
    val sending = outgoing && !failed && (
        message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
            message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
        )
    val deliveryLabel = when {
        failed -> "Not sent · Tap to retry"
        sending -> "Sending…"
        outgoing && message.status == 0 -> "Delivered" // STATUS_COMPLETE
        else -> null
    }
    // Grouped bubbles: inner corners square off, tail only on the last one.
    val big = 18.dp
    val small = 6.dp
    val tail = 4.dp
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = if (groupedWithNext) 2.dp else 8.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
        Surface(
            color = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else if (outgoing) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(
                topStart = if (!outgoing && groupedWithPrev) small else big,
                topEnd = if (outgoing && groupedWithPrev) small else big,
                bottomStart = when {
                    outgoing -> big
                    groupedWithNext -> small
                    else -> tail
                },
                bottomEnd = when {
                    !outgoing -> big
                    groupedWithNext -> small
                    else -> tail
                },
            ),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(
                    if (failed && onRetry != null) Modifier.clickable { onRetry() }
                    else Modifier
                ),
        ) {
            val contentColor = when {
                failed -> MaterialTheme.colorScheme.onErrorContainer
                outgoing -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (linksOn) {
                    Text(
                        linkifiedBody(
                            displayBody,
                            if (!outgoing && !failed) MaterialTheme.colorScheme.primary
                            else contentColor,
                        ),
                        fontSize = 16.sp * fontScale,
                        lineHeight = 22.sp * fontScale,
                        color = contentColor,
                    )
                } else {
                    Text(
                        displayBody,
                        fontSize = 16.sp * fontScale,
                        lineHeight = 22.sp * fontScale,
                        color = contentColor,
                    )
                }
                // Timestamp on the last bubble of a group; always on failures.
                if (!groupedWithNext || deliveryLabel != null) {
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
                        fontWeight = if (failed) FontWeight.Bold else FontWeight.Normal,
                        color = contentColor,
                    )
                }
            }
        }
        // Link switch rides the bubble's top corner, mirrored to stay
        // toward the middle of the screen on outgoing bubbles.
        if (onToggleLinks != null && containsUrl(displayBody)) {
            LinkToggleBadge(
                on = linksOn,
                onClick = onToggleLinks,
                modifier = Modifier
                    .align(if (outgoing) Alignment.TopStart else Alignment.TopEnd)
                    .offset(x = if (outgoing) (-8).dp else 8.dp, y = (-8).dp),
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

        Column(Modifier.padding(padding).fillMaxSize()) {
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
                                avatar = { ContactAvatar(chip.name, chip.photoUri, 24.dp) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove ${chip.name}",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
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
                            ContactAvatar(match.name, match.photoUri)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(match.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    match.number,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
            // ...and the compose pill + send button stay pinned above the keyboard.
            PillComposer(
                value = body,
                onValueChange = { body = it },
                sendEnabled = recipients.isNotEmpty() || recipient.isNotBlank(),
                onSend = { doSend() },
            )
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
                q.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    message = "Type to search your messages",
                )
                results.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    message = "No matches for \"${query.trim()}\"",
                )
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
