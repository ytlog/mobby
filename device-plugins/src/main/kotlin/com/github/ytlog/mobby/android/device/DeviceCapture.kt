package com.github.ytlog.mobby.android.device

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DeviceCapture {
    private val gate = Any()
    private var waiter: ((Result<String>) -> Unit)? = null
    fun await(context: Context, kind: String, dest: File): String {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Result<String>>()
        synchronized(gate) {
            if (waiter != null) error("已有拍摄或录音在进行")
            waiter = { outcome.set(it); latch.countDown() }
        }
        val intent = Intent(context, DeviceCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("kind", kind)
            .putExtra("dest", dest.absolutePath)
        context.startActivity(intent)
        if (!latch.await(120, TimeUnit.SECONDS)) {
            finish(Result.failure(IllegalStateException("操作超时")))
            error("操作超时")
        }
        return outcome.get().getOrThrow()
    }
    internal fun finish(result: Result<String>) {
        val callback = synchronized(gate) { waiter.also { waiter = null } } ?: return
        callback(result)
    }
}

class DeviceCaptureActivity : Activity() {
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var finished = false
    private lateinit var dest: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind")
        val path = intent.getStringExtra("dest")
        val target = path?.let { File(it) }
        if (target == null || !insideFiles(target) || (kind != "photo" && kind != "record")) {
            complete(Result.failure(IllegalStateException("无法打开采集界面")))
            return
        }
        dest = target
        dest.parentFile?.mkdirs()
        if (kind == "photo") showCamera() else showRecorder()
    }

    private fun showCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            complete(Result.failure(IllegalStateException("相机权限已被收回")))
            return
        }
        val preview = SurfaceView(this)
        val shutter = button("拍照") { takePhoto() }
        shutter.isEnabled = false
        setContentView(column(preview, shutter, button("取消") { complete(Result.failure(IllegalStateException("已取消拍照"))) }))
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
            complete(Result.failure(IllegalStateException("没有可用的相机")))
            return
        }
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val surfaces = listOf(holder.surface, reader!!.surface)
                    device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(capture: CameraCaptureSession) {
                            session = capture
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(holder.surface) }
                            capture.setRepeatingRequest(request.build(), null, handler)
                            runOnUiThread { shutter.isEnabled = true }
                        }
                        override fun onConfigureFailed(capture: CameraCaptureSession) {
                            complete(Result.failure(IllegalStateException("相机预览失败")))
                        }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close() }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    complete(Result.failure(IllegalStateException("相机打开失败")))
                }
            }, handler)
        } catch (_: SecurityException) {
            complete(Result.failure(IllegalStateException("相机权限已被收回")))
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
                dest.writeBytes(bytes)
                complete(Result.success(dest.absolutePath))
            } catch (error: Exception) {
                complete(Result.failure(IllegalStateException(error.message ?: "保存照片失败")))
            } finally { image.close() }
        }, handler)
        capture.capture(request.build(), null, handler)
    }

    private fun showRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            complete(Result.failure(IllegalStateException("麦克风权限已被收回")))
            return
        }
        val status = TextView(this).apply { text = "正在录音"; textSize = 22f }
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
            complete(Result.failure(IllegalStateException(error.message ?: "无法开始录音")))
            return
        }
        recorder?.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finishRecording(true)
        }
        setContentView(column(status, button("完成") { finishRecording(true) }, button("取消") { finishRecording(false) }))
    }

    private fun newRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()

    private fun finishRecording(save: Boolean) {
        val active = recorder ?: return
        recorder = null
        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }
        if (!save) {
            dest.delete()
            complete(Result.failure(IllegalStateException("已取消录音")))
        } else if (!stopped || !dest.isFile || dest.length() == 0L) {
            dest.delete()
            complete(Result.failure(IllegalStateException("录音太短或没有保存")))
        } else complete(Result.success(dest.absolutePath))
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; setOnClickListener { click() } }
    private fun column(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        if (children.first() is SurfaceView) (children.first().layoutParams as LinearLayout.LayoutParams).height = 0
        if (children.first() is SurfaceView) (children.first().layoutParams as LinearLayout.LayoutParams).weight = 1f
    }

    private fun insideFiles(file: File): Boolean {
        val base = filesDir.canonicalFile
        val target = file.canonicalFile
        return target.path == base.path || target.path.startsWith(base.path + File.separator)
    }

    private fun complete(result: Result<String>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { complete(result) }
            return
        }
        if (finished) return
        finished = true
        DeviceCapture.finish(result)
        finish()
    }

    override fun onDestroy() {
        session?.close()
        camera?.close()
        reader?.close()
        recorder?.runCatching { stop(); release() }
        thread?.quitSafely()
        if (!finished) DeviceCapture.finish(Result.failure(IllegalStateException("采集界面已关闭")))
        super.onDestroy()
    }
}
