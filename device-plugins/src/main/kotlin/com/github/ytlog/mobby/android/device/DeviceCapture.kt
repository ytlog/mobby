package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.deviceinteraction.model.*
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.TimeUnit

object DeviceCapture {
    internal class Request(val id: String, val kind: String, val dest: File) {
        val events = java.util.concurrent.LinkedBlockingQueue<Any>()
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        var activity: java.lang.ref.WeakReference<DeviceCaptureActivity>? = null
    }
    private val gate = Any()
    private var active: Request? = null
    internal fun request(id: String): Request? = synchronized(gate) { active?.takeIf { it.id == id && !it.settled.get() } }
    fun await(context: Context, kind: String, dest: File, execution: DeviceExecution, checkActive: () -> Unit): String {
        val request = Request(execution.operationId, kind, dest)
        synchronized(gate) {
            check(active == null) { AppStrings.aPhotoOrAudioCaptureIsAlreadyInProgress }
            active = request
        }
        try {
            checkActive()
            execution.waiting("capture.$kind", request.id)
            context.startActivity(Intent(context, DeviceCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("operationId", request.id))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (System.nanoTime() < deadline) {
                checkActive()
                when (val event = request.events.poll(100, TimeUnit.MILLISECONDS)) {
                    is String -> execution.waiting(event, request.id)
                    is CaptureOutcome -> return event.result.getOrThrow()
                }
            }
            deviceFailure(DeviceErrorCode.TIMEOUT)
        } finally {
            synchronized(gate) { if (active === request) active = null }
            Handler(Looper.getMainLooper()).post { request.activity?.get()?.finish() }
        }
    }
    internal class CaptureOutcome(val result: Result<String>)
    internal fun stage(id: String, phase: String) { request(id)?.events?.offer(phase) }
    internal fun finish(id: String, result: Result<String>) {
        val pending = request(id) ?: return
        if (pending.settled.compareAndSet(false, true)) pending.events.offer(CaptureOutcome(result))
    }
    fun respond(operationId: String, response: String): Boolean {
        val request = request(operationId) ?: return false
        if (response != "cancel") return false
        finish(operationId, Result.failure(DeviceFailure(DeviceError(DeviceErrorCode.CANCELLED))))
        Handler(Looper.getMainLooper()).post { request.activity?.get()?.finish() }
        return true
    }
}

class DeviceCaptureActivity : Activity() {
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var playback: android.media.MediaPlayer? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var finished = false
    private lateinit var dest: File
    private var operationId = ""
    private var kind = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        operationId = intent.getStringExtra("operationId").orEmpty()
        val request = DeviceCapture.request(operationId)
        if (request == null) { finish(); return }
        request.activity = java.lang.ref.WeakReference(this)
        kind = request.kind
        dest = request.dest
        dest.parentFile?.mkdirs()
        if (kind == "photo") showCamera() else showRecorder()
    }

    private fun showCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            complete(Result.failure(IllegalStateException(AppStrings.cameraPermissionWasRevoked)))
            return
        }
        val preview = SurfaceView(this)
        val shutter = button(AppStrings.takePhoto) { takePhoto() }
        shutter.isEnabled = false
        setContentView(column(preview, shutter, button(AppStrings.cancel) { complete(Result.failure(DeviceFailure(DeviceError(DeviceErrorCode.CANCELLED)))) }))
        thread = HandlerThread("device-camera").also { it.start() }
        handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = openCamera(holder, shutter)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }

    private fun openCamera(holder: SurfaceHolder, shutter: Button) {
        val manager = getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.firstOrNull()
        if (id == null) {
            complete(Result.failure(IllegalStateException(AppStrings.noCameraAvailable)))
            return
        }
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (DeviceCapture.request(operationId) == null || finished || reader == null) { device.close(); return }
                    camera = device
                    val surfaces = listOf(holder.surface, reader!!.surface)
                    device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(capture: CameraCaptureSession) {
                            if (DeviceCapture.request(operationId) == null || finished) { capture.close(); return }
                            session = capture
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(holder.surface) }
                            capture.setRepeatingRequest(request.build(), null, handler)
                            runOnUiThread { shutter.isEnabled = true }
                        }
                        override fun onConfigureFailed(capture: CameraCaptureSession) {
                            complete(Result.failure(IllegalStateException(AppStrings.cameraPreviewFailed)))
                        }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close() }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    complete(Result.failure(IllegalStateException(AppStrings.cannotOpenCamera)))
                }
            }, handler)
        } catch (_: SecurityException) {
            complete(Result.failure(IllegalStateException(AppStrings.cameraPermissionWasRevoked)))
        }
    }

    private fun takePhoto() {
        val device = camera ?: return
        val capture = session ?: return
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(reader!!.surface) }
        reader?.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                if (!finished && DeviceCapture.request(operationId) != null) {
                    dest.writeBytes(bytes)
                    runOnUiThread { showConfirmation() }
                }
            } catch (error: Exception) {
                complete(Result.failure(IllegalStateException(error.message ?: AppStrings.cannotSavePhoto)))
            } finally { image.close() }
        }, handler)
        capture.capture(request.build(), null, handler)
    }

    private fun showRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            complete(Result.failure(IllegalStateException(AppStrings.microphonePermissionWasRevoked)))
            return
        }
        val status = TextView(this).apply { text = AppStrings.recording; textSize = 22f }
        try {
            recorder = newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(dest.absolutePath)
                setMaxDuration(60_000)
                prepare()
                start()
            }
        } catch (error: Exception) {
            complete(Result.failure(IllegalStateException(error.message ?: AppStrings.cannotStartRecording)))
            return
        }
        recorder?.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording(true)
        }
        setContentView(column(status, button(AppStrings.done) { finishRecording(true) }, button(AppStrings.cancel) { finishRecording(false) }))
    }

    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

    private fun finishRecording(save: Boolean) {
        val active = recorder ?: return
        recorder = null
        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }
        if (!save) {
            dest.delete()
            complete(Result.failure(DeviceFailure(DeviceError(DeviceErrorCode.CANCELLED))))
        } else if (!stopped || !dest.isFile || dest.length() == 0L) {
            dest.delete()
            complete(Result.failure(IllegalStateException(AppStrings.recordingIsTooShortOrWasNotSaved)))
        } else showConfirmation()
    }

    private fun showConfirmation() {
        if (finished || DeviceCapture.request(operationId) == null) return
        session?.close(); session = null
        camera?.close(); camera = null
        reader?.close(); reader = null
        thread?.quitSafely(); thread = null
        DeviceCapture.stage(operationId, "capture.confirm")
        val preview: View = if (kind == "photo") android.widget.ImageView(this).apply {
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(dest.absolutePath))
            adjustViewBounds = true
            contentDescription = AppStrings.photoPreview
        } else button(com.github.ytlog.mobby.android.deviceinteraction.model.DeviceLabels.text("试听录音", "Play recording")) {
            try {
                playback?.release()
                playback = android.media.MediaPlayer().apply { setDataSource(dest.absolutePath); prepare(); start() }
            } catch (_: Exception) { complete(Result.failure(DeviceFailure(DeviceError(DeviceErrorCode.UNAVAILABLE)))) }
        }
        setContentView(column(preview,
            button(AppStrings.useCapturedMaterial) {
                if (!dest.isFile || dest.length() == 0L) complete(Result.failure(IllegalStateException(AppStrings.operationFailed)))
                else complete(Result.success(dest.absolutePath))
            },
            button(AppStrings.captureAgain) {
                playback?.release(); playback = null
                dest.delete()
                DeviceCapture.stage(operationId, "capture.$kind")
                if (kind == "photo") showCamera() else showRecorder()
            }, button(AppStrings.cancel) { complete(Result.failure(DeviceFailure(DeviceError(DeviceErrorCode.CANCELLED)))) }))
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; setOnClickListener { click() } }
    private fun column(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        if (children.first() is SurfaceView) (children.first().layoutParams as LinearLayout.LayoutParams).height = 0
        if (children.first() is SurfaceView) (children.first().layoutParams as LinearLayout.LayoutParams).weight = 1f
    }

    private fun complete(result: Result<String>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { complete(result) }
            return
        }
        if (finished) return
        playback?.release(); playback = null
        finished = true
        if (result.isFailure && ::dest.isInitialized) dest.delete()
        DeviceCapture.finish(operationId, result)
        finish()
    }

    override fun onDestroy() {
        playback?.release(); playback = null
        session?.close()
        camera?.close()
        reader?.close()
        recorder?.runCatching { stop(); release() }
        thread?.quitSafely()
        if (!finished) {
            if (::dest.isInitialized) dest.delete()
            DeviceCapture.finish(operationId, Result.failure(DeviceFailure(DeviceError(DeviceErrorCode.CANCELLED))))
        }
        super.onDestroy()
    }
}
