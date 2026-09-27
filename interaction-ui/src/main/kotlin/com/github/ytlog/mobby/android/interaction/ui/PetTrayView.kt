package com.github.ytlog.mobby.android.interaction.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.ytlog.mobby.android.interaction.domain.ExecutionPhase
import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

/** Presentation only: execution and overlay lifecycle stay in DesktopPet. */
internal class PetTrayView(
    context: Context,
    target: PetTarget?,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onTuck: () -> Unit,
) : FrameLayout(context) {
    private val blue = context.getColor(R.color.mobby_brand_fold)
    private val blueSurface = context.getColor(R.color.mobby_brand_surface)
    private val ink = 0xFF203149.toInt()
    private val secondary = 0xFF62738A.toInt()
    private val red = 0xFFBA1A1A.toInt()

    init {
        // Reserve space inside the overlay window so its shadow is not clipped.
        setPadding(dp(6), dp(6), dp(6), dp(6))
        clipChildren = false
        clipToPadding = false
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            background = shape(0xFFF8FBFF.toInt(), 22, 0xFFDCE7F5.toInt())
            elevation = dp(3).toFloat()
            clipToOutline = true
        }
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(target?.title ?: AppStrings.appName, ink, 16f, true).apply {
                maxLines = 2
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply {
                contentDescription = AppStrings.collapseBubble
                setImageResource(R.drawable.pet_close)
                imageTintList = ColorStateList.valueOf(secondary)
                setPadding(dp(15), dp(15), dp(15), dp(15))
                background = ripple(Color.TRANSPARENT, secondary, 24)
                isFocusable = true
                setOnClickListener { onTuck() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        val stopping = target?.phase == ExecutionPhase.CANCELLING
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), 0, dp(14))
            addView(View(context).apply {
                background = shape(if (stopping) red else if (target == null) secondary else blue, 3)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(8) })
            addView(label(
                if (target == null) AppStrings.noRunningTasks
                else if (stopping) petStatus(target.phase)
                else target.action ?: petStatus(target.phase),
                if (stopping) red else secondary, 12f,
            ).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(action(
                if (target == null) AppStrings.returnToApp else AppStrings.open,
                if (target == null) AppStrings.returnToApp else AppStrings.openConversation,
                R.drawable.pet_open, blue, blueSurface, true, onOpen,
            ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (target != null) {
                addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
                addView(action(
                    if (stopping) AppStrings.stopping2 else AppStrings.stop,
                    AppStrings.stopCurrentTask, R.drawable.pet_stop,
                    red, 0xFFFFEFED.toInt(), petCanStop(target.phase), onStop,
                ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        // Natural height for normal fonts; scroll only when the screen cannot fit it.
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(card, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun label(value: String, color: Int, size: Float, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextColor(color)
        textSize = size
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun action(
        title: String, description: String, icon: Int, color: Int, fill: Int,
        enabled: Boolean, onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(10), dp(12), dp(10), dp(12))
        contentDescription = description
        isEnabled = enabled
        isFocusable = enabled
        val foreground = if (enabled) color else secondary
        background = if (enabled) ripple(fill, color, 14) else shape(0xFFEDF1F6.toInt(), 14)
        if (enabled) setOnClickListener { onClick() }
        accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(foreground)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) })
        addView(label(title, foreground, 13f, true).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun shape(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun ripple(fill: Int, color: Int, radius: Int) = RippleDrawable(
        ColorStateList.valueOf((color and 0x00FFFFFF) or 0x18000000),
        shape(fill, radius), shape(Color.WHITE, radius),
    )

    private fun dp(value: Int) = petPx(value, resources.displayMetrics.density)
}
