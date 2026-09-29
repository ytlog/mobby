package com.github.ytlog.mobby.android.interaction.ui

import com.github.ytlog.mobby.android.interaction.ui.UiStrings as AppStrings

import com.github.ytlog.mobby.android.interaction.domain.ConversationId
import com.github.ytlog.mobby.android.interaction.domain.ExecutionId
import com.github.ytlog.mobby.android.interaction.domain.ExecutionPhase
import com.github.ytlog.mobby.android.interaction.domain.InteractionState

internal const val PET_BALL_DP = 56
internal const val PET_TRAY_WIDTH_DP = 252

internal data class PetTarget(
    val conversation: ConversationId,
    val title: String,
    val execution: ExecutionId,
    val phase: ExecutionPhase?,
    val action: String? = null,
)

internal data class PetFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val ballOnRight: Boolean,
    val focusable: Boolean = false,
)

internal fun petPx(dp: Int, density: Float) = (dp * density).toInt()

internal fun petTarget(state: InteractionState): PetTarget? {
    val row = state.occupied ?: return null
    val execution = row.execution ?: return null
    return PetTarget(row.conversation.id, row.conversation.title.ifBlank { AppStrings.currentTask }, execution, row.phase, row.deviceOperation?.let { com.github.ytlog.mobby.android.deviceinteraction.ui.DeviceLabels.title(it.plugin, it.action) + " · " + com.github.ytlog.mobby.android.deviceinteraction.ui.DeviceLabels.phase(it) })
}

internal fun petStatus(phase: ExecutionPhase?): String = when (phase) {
    ExecutionPhase.CANCELLING -> AppStrings.stopping2
    ExecutionPhase.AWAITING_APPROVAL -> AppStrings.awaitingConfirmation
    ExecutionPhase.SUCCEEDED -> AppStrings.taskCompleted
    ExecutionPhase.FAILED -> AppStrings.failed
    ExecutionPhase.CANCELLED -> AppStrings.stopped
    ExecutionPhase.TIMED_OUT -> AppStrings.timedOut
    ExecutionPhase.INTERRUPTED -> AppStrings.interrupted
    ExecutionPhase.OUTCOME_UNKNOWN -> AppStrings.resultUnconfirmed
    else -> AppStrings.running2
}

internal fun petTerminal(phase: ExecutionPhase?) = phase in setOf(
    ExecutionPhase.SUCCEEDED, ExecutionPhase.FAILED, ExecutionPhase.CANCELLED,
    ExecutionPhase.TIMED_OUT, ExecutionPhase.INTERRUPTED, ExecutionPhase.OUTCOME_UNKNOWN,
)

internal fun petCanStop(phase: ExecutionPhase?) = phase != ExecutionPhase.CANCELLING && !petTerminal(phase)

internal fun petShouldShow(
    foreground: Boolean,
    enabled: Boolean,
    permitted: Boolean,
    tucked: Boolean,
): Boolean = !foreground && enabled && permitted && !tucked

internal fun petFrame(
    ballX: Int,
    ballY: Int,
    expanded: Boolean,
    screenW: Int,
    screenH: Int,
    ball: Int,
    trayW: Int,
    trayH: Int,
): PetFrame {
    if (screenW <= 0 || screenH <= 0 || ball <= 0) return PetFrame(0, 0, 1, 1, true)
    val shown = ball.coerceAtMost(minOf(screenW, screenH))
    val bx = ballX.coerceIn(0, screenW - shown)
    val by = ballY.coerceIn(0, screenH - shown)
    if (!expanded) return PetFrame(bx, by, shown, shown, true)
    val height = maxOf(shown, trayH).coerceAtMost(screenH)
    val width = (shown + trayW).coerceAtMost(screenW)
    val roomLeft = bx
    val roomRight = screenW - (bx + shown)
    val onRight = when {
        trayW > 0 && roomLeft >= trayW -> true
        trayW > 0 && roomRight >= trayW -> false
        else -> roomLeft >= roomRight
    }
    val x = (if (onRight) bx - (width - shown) else bx).coerceIn(0, (screenW - width).coerceAtLeast(0))
    val y = (by - ((height - shown) / 2)).coerceIn(0, (screenH - height).coerceAtLeast(0))
    return PetFrame(x, y, width, height, onRight)
}

internal fun ballOrigin(frame: PetFrame, ball: Int): Pair<Int, Int> {
    val x = if (frame.ballOnRight) frame.x + (frame.width - ball) else frame.x
    val y = frame.y + ((frame.height - ball) / 2).coerceAtLeast(0)
    return x to y
}

internal class PetSession {
    private var tucked = false
    private var tuckedExecution: String? = null
    var expanded: Boolean = false
    var ballX: Int? = null
    var ballY: Int? = null
    var frame: PetFrame? = null
        private set

    fun visible(target: PetTarget?, foreground: Boolean, enabled: Boolean, permitted: Boolean): Boolean {
        if (tucked && (tuckedExecution != target?.execution?.value || foreground || !enabled)) tucked = false
        val show = petShouldShow(foreground, enabled, permitted, tucked)
        if (!show) {
            expanded = false
            frame = null
        }
        return show
    }

    fun place(screenW: Int, screenH: Int, ball: Int, trayW: Int, trayH: Int): PetFrame {
        val x = ballX ?: (screenW - ball - (ball / 5)).coerceAtLeast(0)
        val y = ballY ?: ((screenH - ball) * 2 / 3).coerceAtLeast(0)
        return moveBall(x, y, screenW, screenH, ball, trayW, trayH)
    }

    fun moveBall(x: Int, y: Int, screenW: Int, screenH: Int, ball: Int, trayW: Int, trayH: Int): PetFrame {
        val next = petFrame(x, y, expanded, screenW, screenH, ball, trayW, trayH)
        val (bx, by) = ballOrigin(next, ball)
        ballX = bx
        ballY = by
        frame = next
        return next
    }

    fun drag(originX: Int, originY: Int, dx: Int, dy: Int, screenW: Int, screenH: Int, ball: Int): PetFrame {
        val current = frame ?: PetFrame(originX, originY, ball, ball, true)
        val moved = current.copy(
            x = (originX + dx).coerceIn(0, (screenW - current.width).coerceAtLeast(0)),
            y = (originY + dy).coerceIn(0, (screenH - current.height).coerceAtLeast(0)),
        )
        val (bx, by) = ballOrigin(moved, ball)
        ballX = bx
        ballY = by
        frame = moved
        return moved
    }

    fun tuck(execution: String?) {
        tucked = true
        tuckedExecution = execution
        expanded = false
        frame = null
    }

    fun reveal() {
        tucked = false
        tuckedExecution = null
    }
}
