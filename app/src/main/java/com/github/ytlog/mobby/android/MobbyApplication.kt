package com.github.ytlog.mobby.android

import android.app.Application
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import android.widget.Toast
import com.github.ytlog.mobby.android.interaction.data.InteractionFactory
import com.github.ytlog.mobby.android.interaction.domain.ConversationId
import com.github.ytlog.mobby.android.interaction.domain.Failure
import com.github.ytlog.mobby.android.interaction.domain.InteractionState
import com.github.ytlog.mobby.android.interaction.domain.InteractionUseCases
import com.github.ytlog.mobby.android.interaction.domain.StopResult
import com.github.ytlog.mobby.android.interaction.ui.DesktopPet
import com.github.ytlog.mobby.android.runtime.android.RuntimeHost
import kotlinx.coroutines.*

class MobbyApplication : Application() {
    lateinit var runtime: RuntimeHost
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val petScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var interaction: InteractionUseCases
        private set
    private lateinit var pet: DesktopPet
    private var latest = InteractionState()
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
        runtime = RuntimeHost(this) {
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        }
        interaction = InteractionFactory.create(this, runtime.client, runtime.admin, runtime.diagnostics, applicationScope)
        permitted = Settings.canDrawOverlays(this)
        pet = DesktopPet(this, onStop = { id ->
            petScope.launch {
                val result = try {
                    interaction.stop(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    StopResult.Rejected(Failure.UNAVAILABLE)
                }
                if (result is StopResult.Rejected) Toast.makeText(this@MobbyApplication, "没能停止当前任务", Toast.LENGTH_SHORT).show()
            }
        }, onOpen = { id -> openConversation(id) })
        petScope.launch { interaction.state.collect { latest = it; syncPet() } }
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = syncPet()
            override fun onTrimMemory(level: Int) = Unit
            @Deprecated("Deprecated in Android")
            override fun onLowMemory() = Unit
        })
    }

    private fun openConversation(id: ConversationId) {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("conversationId", id.value))
    }

    private fun syncPet() {
        if (holdForUi || !::pet.isInitialized) return
        pet.update(latest, foreground, petEnabled(), permitted)
    }
}
