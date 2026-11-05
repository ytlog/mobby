package com.github.ytlog.mobby.android.conversation.ui

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

private const val PreviewWidth = 184

@Composable
internal fun VirtualScreenPreviewLayer() {
    val preview = (LocalContext.current.applicationContext as? MobbyApplication)?.screenPreview ?: return
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp, end = 12.dp)) {
        preview.Preview(Modifier.align(Alignment.TopEnd).width(PreviewWidth.dp))
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
        val cardWidth = minOf(petPx(PreviewWidth, density), (region.right - region.left - margin * 2).coerceAtLeast(1))
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
        val bitmap = remember(state.jpeg, expanded) {
            state.jpeg?.takeIf { expanded }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        VirtualScreenPreviewCard(bitmap, state.error, expanded, { expanded = !expanded }, modifier)
    }

    override fun onActivityStarted(activity: Activity) { activities.add(activity); refreshWindow() }
    override fun onActivityStopped(activity: Activity) { activities.remove(activity); refreshWindow() }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

@Composable
internal fun VirtualScreenPreviewCard(
    bitmap: ImageBitmap?,
    error: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = conversationCanvas(),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 6.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(10.dp), color = colors.primary.copy(alpha = 0.10f)) {
                    AppIcon(AppIcons.Phone, null, Modifier.padding(7.dp).size(16.dp), tint = colors.primary)
                }
                Text(
                    UiStrings.virtualScreenPreview,
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FloatingAction(
                    if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    if (expanded) UiStrings.hideVirtualScreen else UiStrings.showVirtualScreen,
                    enabled = true,
                    onClick = onToggle,
                )
            }
            if (expanded) {
                error?.let {
                    Text(it, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.error,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: 9f / 16f
                BoxWithConstraints(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    val width = if (constraints.hasBoundedHeight) minOf(maxWidth, maxHeight * ratio) else maxWidth
                    if (width > 0.dp) {
                        val frame = Modifier.width(width).aspectRatio(ratio).clip(RoundedCornerShape(16.dp))
                        if (bitmap != null) {
                            Image(bitmap, UiStrings.virtualScreenPreview, frame)
                        } else {
                            Box(frame.background(colors.surfaceContainer).padding(12.dp), contentAlignment = Alignment.Center) {
                                Text(UiStrings.virtualScreenWaiting, style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
