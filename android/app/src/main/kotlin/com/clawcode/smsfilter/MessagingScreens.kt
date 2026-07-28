package com.clawcode.smsfilter

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawcode.smsfilter.core.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    repository: MessagingRepository,
    ruleStore: RuleStore,
    blockedLog: BlockedLog,
    onOpenThread: (Conversation) -> Unit,
    onNewMessage: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val tick by repository.changeTick.collectAsState()
    val rules by ruleStore.rules.collectAsState()
    // null = still loading (distinct from "no conversations")
    var conversations by remember { mutableStateOf<List<Conversation>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(tick) {
        conversations = withContext(Dispatchers.IO) { repository.conversations() }
    }

    fun blockConversation(conversation: Conversation) {
        val digits = PhoneNumbers.normalize(conversation.address)
        if (digits.isEmpty()) return
        ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers + digits) }
        blockedLog.append(
            BlockedMessage(
                timestampMs = System.currentTimeMillis(),
                sender = conversation.address,
                body = conversation.snippet,
                reason = "number blocked by swipe",
            )
        )
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Blocked ${conversation.displayName}",
                actionLabel = "Undo",
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                ruleStore.update { it.copy(blockedNumbers = it.blockedNumbers - digits) }
            }
        }
    }

    fun toggleRead(conversation: Conversation) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (conversation.unreadCount > 0) {
                    repository.markThreadRead(conversation.threadId)
                } else {
                    repository.markThreadUnread(conversation.threadId)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                actions = {
                    IconButton(onClick = onOpenFilters) {
                        Icon(Icons.Default.Settings, contentDescription = "Spam filter settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewMessage) {
                Icon(Icons.Default.Add, contentDescription = "Start chat")
            }
        },
    ) { padding ->
        val list = conversations
        when {
            list == null -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            list.isEmpty() -> Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("No conversations yet") }

            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.threadId }) { conversation ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.StartToEnd -> blockConversation(conversation)
                                SwipeToDismissBoxValue.EndToStart -> toggleRead(conversation)
                                else -> Unit
                            }
                            false // never remove the row; swipe acts, then snaps back
                        },
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = { SwipeActionBackground(dismissState.dismissDirection) },
                    ) {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            ConversationRow(
                                conversation = conversation,
                                isBlocked = PhoneNumbers.normalize(conversation.address) in
                                    rules.blockedNumbers,
                            ) { onOpenThread(conversation) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue) {
    val (color, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            Triple(MaterialTheme.colorScheme.errorContainer, "Block", Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            Triple(MaterialTheme.colorScheme.primaryContainer, "Read/Unread", Alignment.CenterEnd)
        else -> return
    }
    Box(
        Modifier.fillMaxSize().background(color).padding(horizontal = 24.dp),
        contentAlignment = alignment,
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    isBlocked: Boolean = false,
    onClick: () -> Unit,
) {
    val unread = conversation.unreadCount > 0
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conversation.displayName)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                DateUtils.getRelativeTimeSpanString(conversation.dateMs).toString(),
                style = MaterialTheme.typography.labelSmall,
            )
            if (unread) {
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
    threadId: Long,
    address: String,
    onBack: () -> Unit,
) {
    val tick by repository.changeTick.collectAsState()
    var messages by remember { mutableStateOf(emptyList<ThreadMessage>()) }
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var showBlockTextDialog by remember { mutableStateOf(false) }
    var blockTextInput by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Conversation options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.id }) { message -> MessageBubble(message) }
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

@Composable
private fun MessageBubble(message: ThreadMessage) {
    val outgoing = message.isOutgoing
    val context = androidx.compose.ui.platform.LocalContext.current
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
                    message.body,
                    color = if (outgoing) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    DateUtils.formatDateTime(
                        context,
                        message.dateMs,
                        DateUtils.FORMAT_SHOW_TIME,
                    ),
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
    var pickedContact by remember { mutableStateOf<ContactMatch?>(null) }
    val context = LocalContext.current
    val sendPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        sendError = if (granted) null else SEND_PERMISSION_HELP
    }
    val suggestions = remember(recipient, pickedContact) {
        if (pickedContact != null) emptyList() else repository.searchContacts(recipient)
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
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = recipient,
                onValueChange = {
                    recipient = it
                    pickedContact = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("To (name or phone number)") },
                isError = sendError != null,
                supportingText = when {
                    sendError != null -> {
                        { Text(sendError ?: "", color = MaterialTheme.colorScheme.error) }
                    }
                    pickedContact != null -> {
                        { Text("${pickedContact?.name} — ${pickedContact?.number}") }
                    }
                    else -> null
                },
            )
            suggestions.forEach { match ->
                Surface(
                    onClick = {
                        recipient = match.number
                        pickedContact = match
                    },
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
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text("Text message") },
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = {
                        val to = recipient.trim()
                        val text = body.trim()
                        if (to.isEmpty() || text.isEmpty()) return@IconButton
                        if (!hasSendPermission(context)) {
                            sendError = SEND_PERMISSION_HELP
                            sendPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                            return@IconButton
                        }
                        when (val result = repository.send(to, text)) {
                            is SendResult.Sent -> {
                                sendError = null
                                if (result.threadId >= 0) onSent(result.threadId, to) else onBack()
                            }
                            is SendResult.Failed -> sendError = result.reason
                        }
                    },
                    enabled = recipient.isNotBlank() && body.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
