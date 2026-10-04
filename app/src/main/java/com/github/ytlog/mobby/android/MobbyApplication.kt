package com.github.ytlog.mobby.android

import com.github.ytlog.mobby.android.localization.AppStrings

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.widget.Toast
import com.github.ytlog.mobby.android.graph.AppGraph
import com.github.ytlog.mobby.android.conversation.domain.ConversationId
import com.github.ytlog.mobby.android.conversation.domain.Failure
import com.github.ytlog.mobby.android.conversation.domain.ConversationState
import com.github.ytlog.mobby.android.conversation.domain.StopResult
import com.github.ytlog.mobby.android.conversation.ui.DesktopPet
import com.github.ytlog.mobby.android.conversation.ui.FloatingConversationWindow
import com.github.ytlog.mobby.android.conversation.ui.AppUpdateManager
import kotlinx.coroutines.*

class MobbyApplication : Application() {
    private lateinit var graph: AppGraph
    val runtime get() = graph.runtime
    val conversations get() = graph.conversations
    private val petScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var pet: DesktopPet
    private lateinit var floatingConversation: FloatingConversationWindow
    lateinit var updates: AppUpdateManager
        private set
    private var latest = ConversationState()
    private var foreground = false
    private var holdForUi = true
    private var permitted = false

    fun petEnabled() = getSharedPreferences(DesktopPet.PREFS, MODE_PRIVATE).getBoolean(DesktopPet.ENABLED, false)

    fun setPetEnabled(value: Boolean) {
        getSharedPreferences(DesktopPet.PREFS, MODE_PRIVATE).edit().putBoolean(DesktopPet.ENABLED, value).apply()
        syncPet()
    }

    fun setPetPermitted(value: Boolean) {
        permitted = value
        syncPet()
    }

    fun noteForeground(value: Boolean) {
        holdForUi = false
        foreground = value
        syncPet()
    }

    override fun onCreate() {
        super.onCreate()
        if (isLocalModelProcess()) return
        updates = AppUpdateManager(this)
        com.github.ytlog.mobby.android.conversation.ui.LanguagePreferences.initialize(this)
        graph = AppGraph(this) {
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        }
        permitted = Settings.canDrawOverlays(this)
        floatingConversation = FloatingConversationWindow(this, conversations, ::openConversation, ::syncPet)
        pet = DesktopPet(this, onStop = { id ->
            petScope.launch {
                val result = try {
                    conversations.stop(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    StopResult.Rejected(Failure.UNAVAILABLE)
                }
                if (result is StopResult.Rejected) Toast.makeText(this@MobbyApplication, AppStrings.couldNotStopTheCurrentTask, Toast.LENGTH_SHORT).show()
            }
        }, onOpen = { id -> openConversation(id) }, onChat = floatingConversation::show)
        com.github.ytlog.mobby.android.device.ScreenOperation.hideOverlay = {
            val ball = pet.hideForScreenOperation()
            val conversation = try { floatingConversation.hideForScreenOperation() }
                catch (error: Throwable) { ball.close(); throw error }
            AutoCloseable { try { conversation.close() } finally { ball.close() } }
        }
        petScope.launch { conversations.state.collect { latest = it; syncPet() } }
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = syncPet()
            override fun onTrimMemory(level: Int) = Unit
            @Deprecated("Deprecated in Android")
            override fun onLowMemory() = Unit
        })
    }

    private fun isLocalModelProcess(): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName().endsWith(":local_model")
        else java.io.File("/proc/self/cmdline").inputStream().use { input ->
            val bytes = ByteArray(256); val n = input.read(bytes)
            String(bytes, 0, n.coerceAtLeast(0), Charsets.UTF_8).substringBefore('\u0000').endsWith(":local_model")
        }
    }.getOrDefault(false)

    private fun openConversation(id: ConversationId?) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (id != null) intent.putExtra("conversationId", id.value)
        startActivity(intent)
    }

    private fun syncPet() {
        if (holdForUi || !::pet.isInitialized) return
        if (foreground || !petEnabled() || !permitted) floatingConversation.close()
        floatingConversation.refresh()
        pet.update(latest, foreground || floatingConversation.isOpen, petEnabled(), permitted)
    }
}
