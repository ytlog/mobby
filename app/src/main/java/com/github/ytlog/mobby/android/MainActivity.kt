package com.github.ytlog.mobby.android

import com.github.ytlog.mobby.android.localization.AppStrings

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
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
import com.github.ytlog.mobby.android.interaction.domain.ConversationId
import com.github.ytlog.mobby.android.interaction.ui.InteractionEntry
import com.github.ytlog.mobby.android.interaction.ui.InteractionHostActions
import com.github.ytlog.mobby.android.interaction.ui.mobbySystemBarColor
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var conversationNavigation by mutableStateOf<String?>(null)
    private var petEnabled by mutableStateOf(false)
    private var petPermitted by mutableStateOf(false)
    private var pendingPet = false
    private val taskNotificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    private val actions get() = (application as MobbyApplication).interaction
    private val app get() = application as MobbyApplication
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        petEnabled = app.petEnabled()
        petPermitted = Settings.canDrawOverlays(this)
        app.setPetPermitted(petPermitted)
        app.noteForeground(true)
        enableEdgeToEdge()
        if (savedInstanceState == null) openConversation(intent)
        val host = InteractionHostActions(share = { text ->
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), AppStrings.shareMessage))
        }, shortcut = { id, title ->
            val manager = getSystemService(ShortcutManager::class.java)
            if (manager.isRequestPinShortcutSupported) {
                val target = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("conversationId", id)
                manager.requestPinShortcut(ShortcutInfo.Builder(this, "conversation-$id").setShortLabel(title.take(40))
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher)).setIntent(target).build(), null)
            } else android.widget.Toast.makeText(this, AppStrings.yourLauncherDoesNotSupportShortcuts, android.widget.Toast.LENGTH_SHORT).show()
        }, appearance = { dark ->
            val background = mobbySystemBarColor(dark)
            val style = if (dark) SystemBarStyle.dark(background) else SystemBarStyle.light(background, background)
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }, localModels = { dark -> startActivity(Intent(this, com.github.ytlog.mobby.android.localmodel.LocalModelActivity::class.java).putExtra(com.github.ytlog.mobby.android.localmodel.LocalModelActivity.EXTRA_DARK, dark)) }, pet = { want ->
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
        setContent { InteractionEntry(actions, host, conversationNavigation, petEnabled, petPermitted) }
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
