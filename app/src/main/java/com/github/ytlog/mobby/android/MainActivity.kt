package com.github.ytlog.mobby.android

import com.github.ytlog.mobby.android.localization.AppStrings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.github.ytlog.mobby.android.conversation.domain.ConversationId
import com.github.ytlog.mobby.android.conversation.ui.ConversationEntry
import com.github.ytlog.mobby.android.conversation.ui.ConversationHostActions
import com.github.ytlog.mobby.android.conversation.ui.mobbySystemBarColor
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var conversationNavigation by mutableStateOf<String?>(null)
    private var petEnabled by mutableStateOf(false)
    private var petPermitted by mutableStateOf(false)
    private var gatewayRefresh by mutableStateOf(0)
    private var pendingPet = false
    private val taskNotificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    private val actions get() = (application as MobbyApplication).conversations
    private val app get() = application as MobbyApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petEnabled = app.petEnabled()
        petPermitted = Settings.canDrawOverlays(this)
        app.setPetPermitted(petPermitted)
        app.noteForeground(true)
        enableEdgeToEdge()
        if (savedInstanceState == null) openConversation(intent)
        val host = ConversationHostActions(share = { text ->
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), AppStrings.shareMessage))
        }, appearance = { dark ->
            val background = mobbySystemBarColor(dark)
            val style = if (dark) SystemBarStyle.dark(background) else SystemBarStyle.light(background, background)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }, pet = { want ->
            if (!want) {
                pendingPet = false
                petEnabled = false
                app.setPetEnabled(false)
            } else if (Settings.canDrawOverlays(this)) {
                petPermitted = true
                petEnabled = true
                app.setPetPermitted(true)
                app.setPetEnabled(true)
            } else {
                pendingPet = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        setContent { ConversationEntry(actions, host, conversationNavigation, petEnabled, petPermitted, gatewayRefresh = gatewayRefresh) }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val notice = getPreferences(MODE_PRIVATE)
            if (!notice.getBoolean("task-notification-requested", false) &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notice.edit().putBoolean("task-notification-requested", true).apply()
                taskNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        app.noteForeground(true)
    }
    override fun onResume() {
        super.onResume()
        gatewayRefresh++
        val allowed = Settings.canDrawOverlays(this)
        petPermitted = allowed
        app.setPetPermitted(allowed)
        if (pendingPet) {
            pendingPet = false
            if (allowed) {
                petEnabled = true
                app.setPetEnabled(true)
            }
        }
    }
    override fun onStop() {
        if (!isChangingConfigurations) app.noteForeground(false)
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openConversation(intent) }
    private fun openConversation(intent: Intent) {
        intent.getStringExtra("conversationId")?.let { id -> lifecycleScope.launch {
            actions.select(ConversationId(id))
            // A repeated link to the same conversation is still a new navigation request.
            conversationNavigation = UUID.randomUUID().toString()
        } }
    }
}
