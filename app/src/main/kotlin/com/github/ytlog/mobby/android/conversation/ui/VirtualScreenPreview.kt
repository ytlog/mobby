package com.github.ytlog.mobby.android.conversation.ui

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.ytlog.mobby.android.MobbyApplication
import com.github.ytlog.mobby.android.conversation.domain.Appearance
import com.github.ytlog.mobby.android.runtime.api.device.ScreenControlPort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun VirtualScreenPreviewLayer() {
    val preview = (LocalContext.current.applicationContext as? MobbyApplication)?.screenPreview ?: return
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp, end = 12.dp)) {
        preview.Preview(Modifier.align(Alignment.TopEnd).width(200.dp))
    }
}

internal class VirtualScreenPreview(
    private val app: MobbyApplication,
    private val controller: ScreenControlPort,
    scope: CoroutineScope,
) : Application.ActivityLifecycleCallbacks {
    private val activities = mutableSetOf<Activity>()
    private val window by lazy { SystemPetWindow(app).also { it.onFoldChanged(::refreshWindow) } }
    private val owner by lazy { FloatingOwner() }
    private var overlay: ComposeView? = null
    private var appliedFrame: PetFrame? = null
    private var expanded by mutableStateOf(true)

    init {
        app.registerActivityLifecycleCallbacks(this)
        app.getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.packageName,
        ) { _, _ -> scope.launch { refreshWindow() } }
        scope.launch {
            controller.state.map { it.active }.distinctUntilChanged().collect { active ->
                if (!active) expanded = true
                refreshWindow()
            }
        }
    }

    fun refreshWindow() {
        if (activities.isNotEmpty() || !controller.state.value.active || !Settings.canDrawOverlays(app)) {
            if (overlay != null) {
                window.detach()
                owner.registry.currentState = Lifecycle.State.CREATED
                overlay = null
                appliedFrame = null
            }
            return
        }
        val (width, height) = overlayDisplaySize(app)
        val density = app.resources.displayMetrics.density
        val margin = petPx(12, density)
        val top = petPx(56, density)
        val region = safeOverlayRegion(width, height, window.foldRegion(), width - margin, top, margin)
            ?: OverlayRegion(0, 0, width, height)
        val cardWidth = minOf(petPx(200, density), (region.right - region.left - margin * 2).coerceAtLeast(1))
        val frame = PetFrame((region.right - margin - cardWidth).coerceAtLeast(region.left),
            (region.top + top).coerceAtMost(region.bottom - 1), cardWidth, WRAP_CONTENT, true)
        val view = overlay ?: ComposeView(overlayWindowContext(app)).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val appearance by app.conversations.appearance.collectAsStateWithLifecycle()
                val dark = appearance == Appearance.DARK || appearance == Appearance.SYSTEM && isSystemInDarkTheme()
                MaterialTheme(colorScheme = if (dark) MobbyDarkScheme else MobbyLightScheme) {
                    Preview(Modifier.fillMaxWidth())
                }
            }
        }
        if (frame == appliedFrame && window.attached) return
        owner.registry.currentState = Lifecycle.State.RESUMED
        window.attach(view, frame)
        if (window.attached) {
            overlay = view
            appliedFrame = frame
        } else {
            owner.registry.currentState = Lifecycle.State.CREATED
            overlay = null
            appliedFrame = null
        }
    }

    @Composable
    fun Preview(modifier: Modifier) {
        val state by controller.state.collectAsStateWithLifecycle()
        if (!state.active) return
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(view, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val previous = view.keepScreenOn
                view.keepScreenOn = true
                try { awaitCancellation() } finally { view.keepScreenOn = previous }
            }
        }
        LaunchedEffect(state.displayId, expanded, lifecycle) {
            if (expanded) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    try { controller.refreshPreview() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { break }
                    delay(700)
                }
            }
        }
        Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(UiStrings.virtualScreenPreview, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) UiStrings.hideVirtualScreen else UiStrings.showVirtualScreen)
                    }
                }
                if (expanded) {
                    val bitmap = remember(state.jpeg) {
                        state.jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    }
                    if (bitmap != null) Image(bitmap, UiStrings.virtualScreenPreview, Modifier.fillMaxWidth().heightIn(max = 220.dp))
                    else Text(UiStrings.virtualScreenWaiting, style = MaterialTheme.typography.bodySmall)
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    override fun onActivityStarted(activity: Activity) { activities.add(activity); refreshWindow() }
    override fun onActivityStopped(activity: Activity) { activities.remove(activity); refreshWindow() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
