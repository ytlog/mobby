package com.github.ytlog.mobby.android.interaction.domain

enum class CapturePhase { CAPTURING, REVIEW, IMPORTING, ERROR, DISCARDING }
data class CameraCapture(
    val id: String, val conversation: String, val workspace: String, val captureUri: String,
    val attachmentUri: String? = null, val phase: CapturePhase = CapturePhase.CAPTURING, val error: String? = null
)
