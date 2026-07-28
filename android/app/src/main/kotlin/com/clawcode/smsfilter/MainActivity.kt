package com.clawcode.smsfilter

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

sealed interface Screen {
    data object Conversations : Screen
    data class Thread(val threadId: Long, val address: String) : Screen
    data object NewMessage : Screen
    data object Filters : Screen
}

class MainActivity : ComponentActivity() {

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()

        val app = App.from(this)
        val initialScreen = screenFromIntent(intent) ?: Screen.Conversations

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf<Screen>(initialScreen) }

                BackHandler(enabled = screen != Screen.Conversations) {
                    screen = Screen.Conversations
                }

                when (val current = screen) {
                    is Screen.Conversations -> ConversationsScreen(
                        repository = app.messagingRepository,
                        onOpenThread = {
                            screen = Screen.Thread(it.threadId, it.address)
                        },
                        onNewMessage = { screen = Screen.NewMessage },
                        onOpenFilters = { screen = Screen.Filters },
                    )
                    is Screen.Thread -> ThreadScreen(
                        repository = app.messagingRepository,
                        threadId = current.threadId,
                        address = current.address,
                        onBack = { screen = Screen.Conversations },
                    )
                    is Screen.NewMessage -> NewMessageScreen(
                        repository = app.messagingRepository,
                        onSent = { threadId, address ->
                            screen = Screen.Thread(threadId, address)
                        },
                        onBack = { screen = Screen.Conversations },
                    )
                    is Screen.Filters -> FiltersScreen(
                        ruleStore = app.ruleStore,
                        blockedLog = app.blockedLog,
                        settings = app.settings,
                        isDefaultSmsApp = { isDefaultSmsApp() },
                        onRequestDefaultSmsRole = { requestDefaultSmsRole() },
                        onOpenNotificationAccess = {
                            startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        },
                        onBack = { screen = Screen.Conversations },
                    )
                }
            }
        }
    }

    /** Opens a thread directly when launched from a message notification. */
    private fun screenFromIntent(intent: Intent?): Screen? {
        val threadId = intent?.getLongExtra(Notifications.EXTRA_THREAD_ID, -1L) ?: -1L
        val address = intent?.getStringExtra(Notifications.EXTRA_ADDRESS)
        return if (threadId != -1L && address != null) Screen.Thread(threadId, address) else null
    }

    private fun requestRuntimePermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }

    private fun isDefaultSmsApp(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
    }

    private fun requestDefaultSmsRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
    }
}
