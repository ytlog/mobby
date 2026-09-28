package com.github.ytlog.mobby.android.interaction.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.ytlog.mobby.android.interaction.domain.ConversationId
import com.github.ytlog.mobby.android.interaction.domain.InteractionUseCases
import com.github.ytlog.mobby.android.localization.FloatingStrings

/** Owns only window/lifecycle concerns. Conversation state and execution use the app's use cases. */
class FloatingConversationWindow internal constructor(
    context: Context, private val actions: InteractionUseCases,
    private val onOpen: (ConversationId?) -> Unit, private val onVisibility: () -> Unit,
    private val window: PetWindow,
) {
    constructor(context: Context, actions: InteractionUseCases, onOpen: (ConversationId?) -> Unit,
        onVisibility: () -> Unit) : this(context, actions, onOpen, onVisibility, SystemPetWindow(context.applicationContext))
    private val context = context.applicationContext
    private val owner by lazy { FloatingOwner() }
    private var target by mutableStateOf<ConversationId?>(null)
    private val vm by lazy { ViewModelProvider(owner, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = ConversationViewModel(actions) as T
    })[ConversationViewModel::class.java] }
    private val view by lazy { ComposeView(this.context).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeViewModelStoreOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { close(); true } else false
        }
        setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) {
                FloatingConversationContent(vm, target,
                    new = { vm.openQuickConversation(null) { target = it } },
                    close = ::close, open = { id -> close(); onOpen(id) })
            }
        }
    } }
    var isOpen = false
        private set
    private var screenOperations = 0
    private var applied: PetFrame? = null

    fun show(id: ConversationId?) {
        if (isOpen) return
        isOpen = true
        owner.registry.currentState = Lifecycle.State.RESUMED
        refresh()
        if (!window.attached) {
            isOpen = false
            owner.registry.currentState = Lifecycle.State.CREATED
            Toast.makeText(context, FloatingStrings.openFailed, Toast.LENGTH_LONG).show()
        } else {
            vm.enqueue { vm.refresh() }
            vm.openQuickConversation(id ?: target) { target = it }
        }
        onVisibility()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        window.detach()
        applied = null
        owner.registry.currentState = Lifecycle.State.CREATED
        onVisibility()
    }

    fun refresh() {
        if (!isOpen || screenOperations > 0) { window.detach(); applied = null; return }
        val display = context.resources.displayMetrics
        val width = minOf(petPx(420, display.density), display.widthPixels - petPx(24, display.density)).coerceAtLeast(1)
        val height = minOf(petPx(560, display.density), display.heightPixels - petPx(96, display.density)).coerceAtLeast(1)
        val frame = PetFrame((display.widthPixels - width) / 2,
            ((display.heightPixels - height) / 3).coerceAtLeast(0), width, height, true, focusable = true)
        if (applied != frame || !window.attached) {
            window.attach(view, frame)
            applied = if (window.attached) frame else null
        }
    }

    /** Same synchronous detach contract as the bubble, so screen tools observe the underlying app. */
    fun hideForScreenOperation(): AutoCloseable {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        screenOperations++
        try { hideKeyboard(); refresh() } catch (error: Throwable) { screenOperations--; throw error }
        var released = false
        return AutoCloseable {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            if (!released) { released = true; screenOperations--; refresh() }
        }
    }

    private fun hideKeyboard() {
        if (!window.attached) return
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }
}

private class FloatingOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    private val saved = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    init {
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }
}
