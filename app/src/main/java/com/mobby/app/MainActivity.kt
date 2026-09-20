package com.mobby.app

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.mobby.interaction.domain.ConversationId
import com.mobby.interaction.ui.InteractionEntry
import com.mobby.interaction.ui.InteractionHostActions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val actions get() = (application as MobbyApplication).interaction
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openConversation(intent)
        val host = InteractionHostActions(share = { text ->
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享消息"))
        }, shortcut = { id, title ->
            val manager = getSystemService(ShortcutManager::class.java)
            if (manager.isRequestPinShortcutSupported) {
                val target = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("conversationId", id)
                manager.requestPinShortcut(ShortcutInfo.Builder(this, "conversation-$id").setShortLabel(title.take(40))
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher)).setIntent(target).build(), null)
            } else android.widget.Toast.makeText(this, "当前桌面不支持添加快捷方式", android.widget.Toast.LENGTH_SHORT).show()
        })
        setContent { InteractionEntry(actions, host) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openConversation(intent) }
    private fun openConversation(intent: Intent) {
        intent.getStringExtra("conversationId")?.let { id -> lifecycleScope.launch { actions.select(ConversationId(id)) } }
    }
}
