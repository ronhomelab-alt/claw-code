package com.clawcode.smsfilter

import android.text.format.DateUtils
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    repository: MessagingRepository,
    onOpenThread: (Conversation) -> Unit,
    onNewMessage: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    val tick by repository.changeTick.collectAsState()
    var conversations by remember { mutableStateOf(emptyList<Conversation>()) }
    LaunchedEffect(tick) { conversations = repository.conversations() }

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
        floatingActionButton = {
            FloatingActionButton(onClick = onNewMessage) {
                Icon(Icons.Default.Add, contentDescription = "Start chat")
            }
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No conversations yet")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(conversations, key = { it.threadId }) { conversation ->
                    ConversationRow(conversation) { onOpenThread(conversation) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
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
            Text(
                conversation.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    repository: MessagingRepository,
    threadId: Long,
    address: String,
    onBack: () -> Unit,
) {
    val tick by repository.changeTick.collectAsState()
    var messages by remember { mutableStateOf(emptyList<ThreadMessage>()) }
    var draft by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(tick, threadId) {
        messages = repository.messages(threadId)
        repository.markThreadRead(threadId)
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
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
            )
        },
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
                        if (text.isNotEmpty()) {
                            when (val result = repository.send(address, text)) {
                                is SendResult.Sent -> {
                                    draft = ""
                                    sendError = null
                                }
                                is SendResult.Failed -> sendError = result.reason
                            }
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
                        if (to.isNotEmpty() && text.isNotEmpty()) {
                            when (val result = repository.send(to, text)) {
                                is SendResult.Sent -> {
                                    sendError = null
                                    if (result.threadId >= 0) onSent(result.threadId, to) else onBack()
                                }
                                is SendResult.Failed -> sendError = result.reason
                            }
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
