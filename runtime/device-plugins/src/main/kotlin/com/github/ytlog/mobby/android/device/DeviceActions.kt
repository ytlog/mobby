package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.runtime.api.device.*
import kotlinx.serialization.json.*
import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.SmsManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class DeviceActions(private val context: Context, private val inbox: File, private val workspace: File, private val gate: DeviceActionGate, private val resources: DeviceResourceRegistrar, private val cachedResources: DeviceResourceRegistrar) {
    fun perform(plugin: String, action: String, args: Map<String, String>, execution: DeviceExecution): DeviceResult {
        gate.checkActive()
        val spec = DeviceCatalog.all.first { it.id == plugin }
        val ref = if (action in spec.grant?.actions.orEmpty()) spec.grantRef!! else spec.ref
        if (!DeviceHost.granted(context, ref)) deviceFailure(DeviceErrorCode.PERMISSION_REVOKED)
        return when (plugin) {
            "screen" -> screen(action, args)
            "sms" -> sms(action, args)
            "contacts" -> contacts(action, args)
            "calendar" -> calendar(action, args)
            "media" -> media(action, args)
            "storage" -> storage(action, args)
            "camera" -> capture("photo", "jpg", execution)
            "microphone" -> capture("record", "m4a", execution)
            "location" -> location()
            "sensors" -> sensors()
            "clipboard" -> clipboard(action, args)
            "office" -> office(action, args)
            else -> error(AppStrings.unsupportedPlugin)
        }
    }

    private fun screen(action: String, args: Map<String, String>): DeviceResult {
        val service = ScreenAccessService.instance ?: error(AppStrings.accessibilityIsOffEnableMobbySScreenServiceIn)
        val observation = service.operate(action, args, gate::checkActive)
        val screenshot = observation.screenshot
        var status = screenshot.status
        val ref = screenshot.jpeg?.let { bytes ->
            try {
                val directory = java.nio.file.Files.createTempDirectory(inbox.toPath(), "screen-").toFile()
                try {
                    val file = File(directory, "screen.jpg")
                    file.writeBytes(bytes)
                    gate.checkActive()
                    cachedResources.register(file, "image/jpeg")
                } finally { directory.deleteRecursively() }
            } catch (failure: Exception) {
                if (failure is java.util.concurrent.CancellationException) throw failure
                status = "resource_storage_failed"
                null
            }
        }
        return screenObservationResult(action, observation, ref, status)
    }

    private fun sms(action: String, args: Map<String, String>): DeviceResult = when (action) {
        "list" -> {
            val rows = mutableListOf<JsonObject>()
            (context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "date", "body"), null, null, "date DESC") ?: deviceFailure(DeviceErrorCode.UNAVAILABLE)).use { cursor ->
                val address = cursor.getColumnIndex("address")
                val date = cursor.getColumnIndex("date")
                val body = cursor.getColumnIndex("body")
                while (cursor.moveToNext() && rows.size < 20) {
                    val text = cursor.getString(body).orEmpty().replace("\n", " ").take(160)
                    rows += fields("itemId" to "sms-${rows.size}", "sender" to cursor.getString(address).orEmpty(), "time" to cursor.getLong(date), "body" to text)
                }
            }
            deviceList("message_list", rows, "sms.inbox.recent_20")
        }
        "send" -> {
            val to = args["to"]?.trim().orEmpty()
            val body = args["body"].orEmpty()
            if (!to.matches(Regex("[+0-9][0-9\\- ]{2,20}")) || body.isBlank() || body.length > 500) error(AppStrings.enterAValidNumberAndAMessageOfAt)
            SmsSendOperation(context, gate).send(to.filterNot { it.isWhitespace() || it == '-' }, body)
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun contacts(action: String, args: Map<String, String>): DeviceResult = when (action) {
        "list" -> {
            val rows = mutableListOf<JsonObject>()
            (context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC") ?: deviceFailure(DeviceErrorCode.UNAVAILABLE)).use { cursor ->
                val id = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val name = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (cursor.moveToNext() && rows.size < 50) rows += fields("itemId" to "contact-${cursor.getLong(id)}", "recordId" to cursor.getLong(id).toString(), "name" to cursor.getString(name).orEmpty().take(80))
            }
            deviceList("file_items", rows, "contacts.first_50")
        }
        "create" -> {
            val name = args["name"]?.trim().orEmpty()
            val phone = args["phone"]?.trim().orEmpty()
            if (name.isBlank() || name.length > 80) error(AppStrings.enterANameOfAtMostCharacters)
            val operations = arrayListOf(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
            if (phone.isNotBlank()) operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.take(40)).withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
            val result = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            recordChange(action, result.first().uri?.lastPathSegment ?: error(AppStrings.operationFailed), fields("name" to name, "phone" to phone))
        }
        "update" -> {
            val id = numeric(args)
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank() || name.length > 80) error(AppStrings.enterANameOfAtMostCharacters)
            val updated = context.contentResolver.update(ContactsContract.Data.CONTENT_URI, android.content.ContentValues().apply {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }, "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(id.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
            if (updated == 0) error(AppStrings.contactNotFound(id)) else recordChange(action, id.toString(), fields("name" to name))
        }
        "delete" -> {
            val id = numeric(args)
            val deleted = context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id.toString()))
            if (deleted == 0) error(AppStrings.contactNotFound(id)) else recordChange(action, id.toString())
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun calendar(action: String, args: Map<String, String>): DeviceResult = when (action) {
        "list" -> {
            val rows = mutableListOf<JsonObject>()
            val start = System.currentTimeMillis()
            (context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART), "${CalendarContract.Events.DTSTART}>=?", arrayOf(start.toString()), "${CalendarContract.Events.DTSTART} ASC") ?: deviceFailure(DeviceErrorCode.UNAVAILABLE)).use { cursor ->
                val id = cursor.getColumnIndex(CalendarContract.Events._ID)
                val title = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val whenStart = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext() && rows.size < 20) rows += fields("itemId" to "event-${cursor.getLong(id)}", "recordId" to cursor.getLong(id).toString(), "name" to cursor.getString(title).orEmpty().take(80), "time" to cursor.getLong(whenStart))
            }
            deviceList("file_items", rows, "calendar.upcoming_20")
        }
        "create" -> {
            val title = args["title"]?.trim().orEmpty()
            val start = args["start"]?.toLongOrNull()
            val end = args["end"]?.toLongOrNull()
            if (title.isBlank() || title.length > 120 || start == null || end == null || end < start) error(AppStrings.enterATitleStartTimeAndEndTime)
            val calendar = calendarId() ?: error(AppStrings.noWritableCalendar)
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendar)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }) ?: error(AppStrings.noEventWasSaved)
            recordChange(action, uri.lastPathSegment ?: error(AppStrings.noEventWasSaved), fields("title" to title, "start" to start, "end" to end, "timeZone" to java.util.TimeZone.getDefault().id))
        }
        "update" -> {
            val id = numeric(args)
            val title = args["title"]?.trim().orEmpty()
            if (title.isBlank() || title.length > 120) error(AppStrings.enterATitleOfAtMostCharacters)
            val updated = context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), android.content.ContentValues().apply { put(CalendarContract.Events.TITLE, title) }, null, null)
            if (updated == 0) error(AppStrings.eventNotFound(id)) else recordChange(action, id.toString(), fields("title" to title))
        }
        "delete" -> {
            val id = numeric(args)
            val deleted = context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            if (deleted == 0) error(AppStrings.eventNotFound(id)) else recordChange(action, id.toString())
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun calendarId(): Long? = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    }

    private fun media(action: String, args: Map<String, String>): DeviceResult = when (action) {
        "list" -> {
            val rows = mutableListOf<JsonObject>()
            mediaCollections().forEach { (kind, uri) ->
                if (rows.size >= 30) return@forEach
                (context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC") ?: deviceFailure(DeviceErrorCode.UNAVAILABLE)).use { cursor ->
                    val id = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val name = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext() && rows.size < 30) {
                        val itemUri = ContentUris.withAppendedId(uri, cursor.getLong(id))
                        val preview = if (kind in setOf("image", "video") && rows.size < 6) mediaPreview(itemUri) else null
                        rows += fields("itemId" to "$kind-${cursor.getLong(id)}", "recordId" to cursor.getLong(id).toString(), "kind" to kind,
                            "name" to cursor.getString(name).orEmpty().take(80), "sizeBytes" to cursor.getLong(size), "resourceRef" to preview)
                    }
                }
            }
            deviceList("media_items", rows, "media.authorized_30").copy(resourceRefs = rows.mapNotNull {
                (it["resourceRef"] as? JsonPrimitive)?.contentOrNull
            })
        }
        "copy" -> {
            val kind = args["kind"].orEmpty()
            val id = numeric(args)
            val uri = mediaCollections()[kind] ?: error(AppStrings.kindMustBeImageVideoOrAudio)
            val name = context.contentResolver.query(ContentUris.withAppendedId(uri, id), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: error(AppStrings.mediaNotFound(id))
            val dest = File(inbox, DevicePaths.safeName(name))
            context.contentResolver.openInputStream(ContentUris.withAppendedId(uri, id))?.use { DevicePaths.copyBounded(it, dest) } ?: error(AppStrings.cannotReadMedia(id))
            fileResult(dest, "application/octet-stream", "file_items")
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun mediaPreview(uri: Uri): String? {
        // Thumbnails are optional evidence; failure never changes the result of listing the media.
        if (Build.VERSION.SDK_INT < 29) return null
        val bitmap = try { context.contentResolver.loadThumbnail(uri, android.util.Size(240, 240), null) }
            catch (_: java.io.IOException) { return null }
        val file = File(inbox, "preview-${java.util.UUID.randomUUID()}.jpg")
        return try {
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it)) }
            cachedResources.register(file, "image/jpeg")
        } finally { bitmap.recycle(); file.delete() }
    }

    private fun mediaCollections(): Map<String, Uri> = buildMap {
        val legacy = Build.VERSION.SDK_INT < 33 && allowed(Manifest.permission.READ_EXTERNAL_STORAGE)
        val selected = Build.VERSION.SDK_INT >= 34 && allowed(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (legacy || allowed(Manifest.permission.READ_MEDIA_IMAGES) || selected) put("image", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (legacy || allowed(Manifest.permission.READ_MEDIA_VIDEO) || selected) put("video", MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        if (legacy || allowed(Manifest.permission.READ_MEDIA_AUDIO)) put("audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
    }

    private fun storage(action: String, args: Map<String, String>): DeviceResult {
        val tree = DeviceStorage.tree(context) ?: error(AppStrings.selectTheDirectoryAgain)
        val children = childDocuments(tree)
        return when (action) {
            "list" -> deviceList("file_items", children.mapIndexed { index, child -> fields("itemId" to "file-$index", "name" to child.name,
                "kind" to if (child.directory) "directory" else "file", "sizeBytes" to child.size) }, "storage.selected_directory_100")
            "copy" -> {
                val name = DevicePaths.safeName(args["name"].orEmpty())
                val child = children.firstOrNull { it.name == name && !it.directory } ?: error(AppStrings.notFound(name))
                val dest = File(inbox, name)
                context.contentResolver.openInputStream(child.uri)?.use { DevicePaths.copyBounded(it, dest) } ?: error(AppStrings.cannotRead(name))
                fileResult(dest, "application/octet-stream", "file_items")
            }
            "export" -> {
                val name = DevicePaths.safeName(args["name"].orEmpty())
                val source = DevicePaths.resolve(workspace, inbox, args["from"].orEmpty())
                if (!source.isFile) error(AppStrings.noFileFoundToExport)
                val existing = children.firstOrNull { it.name == name && !it.directory }
                val target = existing?.uri ?: DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "application/octet-stream", name) ?: error(AppStrings.cannotCreateInTheSelectedDirectory(name))
                context.contentResolver.openOutputStream(target, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error(AppStrings.cannotExport(name))
                deviceResult("file_items", fields("items" to listOf(fields("itemId" to "export", "name" to name, "sizeBytes" to source.length())), "nextCursor" to null, "scope" to "storage.selected_directory"), EffectState.CONFIRMED)
            }
            else -> error(AppStrings.unsupportedOperation)
        }
    }

    private data class StoredChild(val name: String, val uri: Uri, val directory: Boolean, val size: Long)
    private fun childDocuments(tree: Uri): List<StoredChild> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val rows = mutableListOf<StoredChild>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            val id = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val size = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext() && rows.size < 100) {
                val documentId = cursor.getString(id) ?: continue
                rows += StoredChild(
                    cursor.getString(name).orEmpty(),
                    DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
                    cursor.getString(mime) == DocumentsContract.Document.MIME_TYPE_DIR,
                    cursor.getLong(size),
                )
            }
        }
        return rows
    }

    private fun capture(kind: String, extension: String, execution: DeviceExecution): DeviceResult {
        if (!DeviceForeground.foreground()) error(AppStrings.returnToMobbyBeforeYou(if (kind == "photo") AppStrings.takePhoto else AppStrings.recordAudio))
        inbox.mkdirs()
        val dest = File(inbox, "$kind-${System.nanoTime()}.$extension")
        DeviceCapture.await(context, kind, dest, execution, gate::checkActive)
        return fileResult(dest, if (kind == "photo") "image/jpeg" else "audio/mp4", "capture_resource")
    }

    private fun location(): DeviceResult {
        if (!DeviceForeground.foreground()) error(AppStrings.returnToMobbyToGetYourLocation)
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            allowed(Manifest.permission.ACCESS_FINE_LOCATION) && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> error(AppStrings.systemLocationServicesAreOff)
        }
        val latch = CountDownLatch(1)
        val found = AtomicReference<Location>()
        if (Build.VERSION.SDK_INT >= 30) {
            manager.getCurrentLocation(provider, CancellationSignal(), context.mainExecutor) { location -> found.set(location); latch.countDown() }
        } else {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, { location -> found.set(location); latch.countDown() }, Looper.getMainLooper())
        }
        if (!latch.await(20, TimeUnit.SECONDS)) error(AppStrings.noLocationReceived)
        val location = found.get() ?: error(AppStrings.noLocationReceived)
        return deviceResult("measurement", fields("source" to provider, "samples" to listOf(
            fields("name" to "latitude", "value" to location.latitude, "unit" to "°", "accuracy" to location.accuracy, "sampledAtEpochMillis" to location.time),
            fields("name" to "longitude", "value" to location.longitude, "unit" to "°", "accuracy" to location.accuracy, "sampledAtEpochMillis" to location.time))))
    }

    private fun sensors(): DeviceResult {
        val manager = context.getSystemService(SensorManager::class.java)
        val types = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE)
        val samples = java.util.concurrent.ConcurrentHashMap<Int, List<JsonObject>>()
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val unit = when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> "m/s²"; Sensor.TYPE_GYROSCOPE -> "rad/s"; Sensor.TYPE_MAGNETIC_FIELD -> "μT"
                    Sensor.TYPE_LIGHT -> "lx"; Sensor.TYPE_PROXIMITY -> "cm"; Sensor.TYPE_PRESSURE -> "hPa"; else -> ""
                }
                samples[event.sensor.type] = event.values.mapIndexed { index, value -> fields("name" to "${event.sensor.name}[$index]", "value" to value,
                    "unit" to unit, "accuracy" to null, "sampledAtEpochMillis" to System.currentTimeMillis()) }
                if (samples.size >= types.count { manager.getDefaultSensor(it) != null }) latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = types.mapNotNull { manager.getDefaultSensor(it) }
        if (registered.isEmpty()) deviceFailure(DeviceErrorCode.UNSUPPORTED_CAPABILITY)
        registered.forEach { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        try { latch.await(3, TimeUnit.SECONDS) } finally { manager.unregisterListener(listener) }
        if (samples.isEmpty()) error(AppStrings.noSensorReadingsReceived)
        return deviceResult("measurement", fields("source" to "android.sensors", "samples" to samples.toSortedMap().values.flatten()))
    }

    private fun clipboard(action: String, args: Map<String, String>): DeviceResult {
        val manager = context.getSystemService(ClipboardManager::class.java)
        return when (action) {
            "read" -> {
                if (!DeviceForeground.foreground()) error(AppStrings.returnToMobbyToReadTheClipboard)
                val text = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                deviceText(text.take(4_000))
            }
            "write" -> {
                val text = args["text"].orEmpty()
                if (text.isEmpty() || text.length > 4_000) error(AppStrings.enterTextOfAtMostCharacters)
                manager.setPrimaryClip(ClipData.newPlainText("mobby", text))
                recordChange(action, "clipboard", fields("text" to text))
            }
            else -> error(AppStrings.unsupportedOperation)
        }
    }

    private fun office(action: String, args: Map<String, String>): DeviceResult {
        val file = DevicePaths.resolve(workspace, inbox, args["path"].orEmpty())
        return when (action) {
            "inspect" -> deviceText(OfficePackage.inspect(file))
            "read" -> deviceText(OfficePackage.read(file))
            "write" -> {
                val text = args["text"] ?: error(AppStrings.missingText)
                OfficePackage.write(file, text)
                val ref = resources.register(file, "application/octet-stream")
                deviceResult("record_change", fields("action" to "write", "after" to fields("name" to file.name, "sizeBytes" to file.length()), "previewText" to OfficePackage.read(file).take(4000)), EffectState.CONFIRMED, listOf(ref))
            }
            else -> error(AppStrings.unsupportedOperation)
        }
    }

    private fun fileResult(file: File, mediaType: String, kind: String): DeviceResult {
        gate.checkActive()
        val ref = resources.register(file, mediaType)
        val data = if (kind == "capture_resource") fields("resourceRef" to ref, "mediaType" to mediaType, "durationMillis" to null)
            else fields("items" to listOf(fields("itemId" to "resource", "name" to file.name, "sizeBytes" to file.length(), "resourceRef" to ref)), "nextCursor" to null, "scope" to "run.imported")
        return deviceResult(kind, data, EffectState.CONFIRMED, listOf(ref))
    }

    private fun numeric(args: Map<String, String>): Long {
        val id = args["id"]?.toLongOrNull()
        if (id == null || id < 0) error(AppStrings.invalidId)
        return id
    }
    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

internal fun screenObservationResult(action: String, observation: ScreenAccessService.Observation,
    screenshotRef: String?, screenshotStatus: String): DeviceResult = deviceResult("screen_observation",
    fields("packageName" to observation.packageName, "observedAtEpochMillis" to observation.observedAtEpochMillis,
        "observationRef" to screenshotRef, "screenshotStatus" to screenshotStatus,
        "actionConfirmed" to (action != "snapshot"), "text" to observation.text),
    if (action == "snapshot") EffectState.NONE else EffectState.CONFIRMED,
    listOfNotNull(screenshotRef))
