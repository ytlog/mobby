package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings

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

internal class DeviceActions(private val context: Context, private val inbox: File, private val workspace: File, private val gate: DeviceActionGate = DeviceActionGate()) {
    fun perform(plugin: String, action: String, args: Map<String, String>): String {
        gate.checkActive()
        val spec = DeviceCatalog.all.first { it.id == plugin }
        val ref = if (action in spec.grant?.actions.orEmpty()) spec.grantRef!! else spec.ref
        if (!DeviceHost.granted(context, ref)) error(DeviceHost.reason(context, ref) ?: AppStrings.permissionWasRevoked)
        return when (plugin) {
            "screen" -> screen(action, args)
            "sms" -> sms(action, args)
            "contacts" -> contacts(action, args)
            "calendar" -> calendar(action, args)
            "media" -> media(action, args)
            "storage" -> storage(action, args)
            "camera" -> capture("photo", "jpg")
            "microphone" -> capture("record", "m4a")
            "location" -> location()
            "sensors" -> sensors()
            "clipboard" -> clipboard(action, args)
            "office" -> office(action, args)
            else -> error(AppStrings.unsupportedPlugin)
        }
    }

    private fun screen(action: String, args: Map<String, String>): String {
        val service = ScreenAccessService.instance ?: error(AppStrings.accessibilityIsOffEnableMobbySScreenServiceIn)
        return service.operate(action, args, gate::checkActive)
    }

    private fun sms(action: String, args: Map<String, String>): String = when (action) {
        "list" -> {
            val rows = mutableListOf<String>()
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("address", "date", "body"), null, null, "date DESC")?.use { cursor ->
                val address = cursor.getColumnIndex("address")
                val date = cursor.getColumnIndex("date")
                val body = cursor.getColumnIndex("body")
                while (cursor.moveToNext() && rows.size < 20) {
                    val text = cursor.getString(body).orEmpty().replace("\n", " ").take(160)
                    rows += "${cursor.getString(address).orEmpty()} ${cursor.getLong(date)} $text"
                }
            }
            if (rows.isEmpty()) AppStrings.noMessagesInTheInbox else rows.joinToString("\n")
        }
        "send" -> {
            val to = args["to"]?.trim().orEmpty()
            val body = args["body"].orEmpty()
            if (!to.matches(Regex("[+0-9][0-9\\- ]{2,20}")) || body.isBlank() || body.length > 500) error(AppStrings.enterAValidNumberAndAMessageOfAt)
            val manager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            manager.sendTextMessage(to.filterNot { it.isWhitespace() || it == '-' }, null, body, null, null)
            AppStrings.submittedForSending
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun contacts(action: String, args: Map<String, String>): String = when (action) {
        "list" -> {
            val rows = mutableListOf<String>()
            context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC")?.use { cursor ->
                val id = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val name = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (cursor.moveToNext() && rows.size < 50) rows += "${cursor.getLong(id)} ${cursor.getString(name).orEmpty().take(80)}"
            }
            if (rows.isEmpty()) AppStrings.noContacts else rows.joinToString("\n")
        }
        "create" -> {
            val name = args["name"]?.trim().orEmpty()
            val phone = args["phone"]?.trim().orEmpty()
            if (name.isBlank() || name.length > 80) error(AppStrings.enterANameOfAtMostCharacters)
            val operations = arrayListOf(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
            if (phone.isNotBlank()) operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.take(40)).withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build()
            val result = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            AppStrings.createdContact(result.first().uri?.lastPathSegment ?: "")
        }
        "update" -> {
            val id = numeric(args)
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank() || name.length > 80) error(AppStrings.enterANameOfAtMostCharacters)
            val updated = context.contentResolver.update(ContactsContract.Data.CONTENT_URI, android.content.ContentValues().apply {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }, "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(id.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
            if (updated == 0) error(AppStrings.contactNotFound(id)) else AppStrings.updatedContact(id)
        }
        "delete" -> {
            val id = numeric(args)
            val deleted = context.contentResolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id.toString()))
            if (deleted == 0) error(AppStrings.contactNotFound(id)) else AppStrings.deletedContact(id)
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun calendar(action: String, args: Map<String, String>): String = when (action) {
        "list" -> {
            val rows = mutableListOf<String>()
            val start = System.currentTimeMillis() - 86_400_000L
            context.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART), "${CalendarContract.Events.DTSTART}>=?", arrayOf(start.toString()), "${CalendarContract.Events.DTSTART} ASC")?.use { cursor ->
                val id = cursor.getColumnIndex(CalendarContract.Events._ID)
                val title = cursor.getColumnIndex(CalendarContract.Events.TITLE)
                val whenStart = cursor.getColumnIndex(CalendarContract.Events.DTSTART)
                while (cursor.moveToNext() && rows.size < 20) rows += "${cursor.getLong(id)} ${cursor.getLong(whenStart)} ${cursor.getString(title).orEmpty().take(80)}"
            }
            if (rows.isEmpty()) AppStrings.noUpcomingEvents else rows.joinToString("\n")
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
            AppStrings.createdEvent(uri.lastPathSegment)
        }
        "update" -> {
            val id = numeric(args)
            val title = args["title"]?.trim().orEmpty()
            if (title.isBlank() || title.length > 120) error(AppStrings.enterATitleOfAtMostCharacters)
            val updated = context.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), android.content.ContentValues().apply { put(CalendarContract.Events.TITLE, title) }, null, null)
            if (updated == 0) error(AppStrings.eventNotFound(id)) else AppStrings.updatedEvent(id)
        }
        "delete" -> {
            val id = numeric(args)
            val deleted = context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            if (deleted == 0) error(AppStrings.eventNotFound(id)) else AppStrings.deletedEvent(id)
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun calendarId(): Long? = context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else null
    }

    private fun media(action: String, args: Map<String, String>): String = when (action) {
        "list" -> {
            val rows = mutableListOf<String>()
            mediaCollections().forEach { (kind, uri) ->
                if (rows.size >= 30) return@forEach
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    val id = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val name = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    while (cursor.moveToNext() && rows.size < 30) rows += "$kind ${cursor.getLong(id)} ${cursor.getString(name).orEmpty().take(80)} ${cursor.getLong(size)}"
                }
            }
            if (rows.isEmpty()) AppStrings.noAccessibleMediaFiles else rows.joinToString("\n")
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
            dest.absolutePath
        }
        else -> error(AppStrings.unsupportedOperation)
    }

    private fun mediaCollections(): Map<String, Uri> = buildMap {
        val legacy = Build.VERSION.SDK_INT < 33 && allowed(Manifest.permission.READ_EXTERNAL_STORAGE)
        val selected = Build.VERSION.SDK_INT >= 34 && allowed(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (legacy || allowed(Manifest.permission.READ_MEDIA_IMAGES) || selected) put("image", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (legacy || allowed(Manifest.permission.READ_MEDIA_VIDEO) || selected) put("video", MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        if (legacy || allowed(Manifest.permission.READ_MEDIA_AUDIO)) put("audio", MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
    }

    private fun storage(action: String, args: Map<String, String>): String {
        val tree = DeviceStorage.tree(context) ?: error(AppStrings.selectTheDirectoryAgain)
        val children = childDocuments(tree)
        return when (action) {
            "list" -> children.take(100).joinToString("\n") { "${if (it.directory) "dir" else "file"} ${it.name.take(80)} ${it.size}" }.ifBlank { AppStrings.theSelectedDirectoryIsEmpty }
            "copy" -> {
                val name = DevicePaths.safeName(args["name"].orEmpty())
                val child = children.firstOrNull { it.name == name && !it.directory } ?: error(AppStrings.notFound(name))
                val dest = File(inbox, name)
                context.contentResolver.openInputStream(child.uri)?.use { DevicePaths.copyBounded(it, dest) } ?: error(AppStrings.cannotRead(name))
                dest.absolutePath
            }
            "export" -> {
                val name = DevicePaths.safeName(args["name"].orEmpty())
                val source = DevicePaths.resolve(workspace, inbox, args["from"].orEmpty())
                if (!source.isFile) error(AppStrings.noFileFoundToExport)
                val existing = children.firstOrNull { it.name == name && !it.directory }
                val target = existing?.uri ?: DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), "application/octet-stream", name) ?: error(AppStrings.cannotCreateInTheSelectedDirectory(name))
                context.contentResolver.openOutputStream(target, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error(AppStrings.cannotExport(name))
                AppStrings.exported(name)
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

    private fun capture(kind: String, extension: String): String {
        if (!DeviceForeground.foreground()) error(AppStrings.returnToMobbyBeforeYou(if (kind == "photo") AppStrings.takePhoto else AppStrings.recordAudio))
        inbox.mkdirs()
        val dest = File(inbox, "$kind-${System.nanoTime()}.$extension")
        return DeviceCapture.await(context, kind, dest)
    }

    private fun location(): String {
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
        return "latitude=${location.latitude} longitude=${location.longitude} accuracy=${location.accuracy} time=${location.time}"
    }

    private fun sensors(): String {
        val manager = context.getSystemService(SensorManager::class.java)
        val types = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_PRESSURE)
        val samples = linkedMapOf<String, String>()
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                samples[event.sensor.name] = event.values.joinToString(",") { "%.3f".format(it) }
                if (samples.size >= types.count { manager.getDefaultSensor(it) != null }) latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = types.mapNotNull { manager.getDefaultSensor(it) }
        if (registered.isEmpty()) return AppStrings.thisDeviceHasNoAvailableMotionOrEnvironmentalSensors
        registered.forEach { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        try { latch.await(3, TimeUnit.SECONDS) } finally { manager.unregisterListener(listener) }
        if (samples.isEmpty()) error(AppStrings.noSensorReadingsReceived)
        return samples.entries.joinToString("\n") { "${it.key} ${it.value}" }
    }

    private fun clipboard(action: String, args: Map<String, String>): String {
        val manager = context.getSystemService(ClipboardManager::class.java)
        return when (action) {
            "read" -> {
                if (!DeviceForeground.foreground()) error(AppStrings.returnToMobbyToReadTheClipboard)
                val text = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) AppStrings.theClipboardIsEmpty else text.take(4_000)
            }
            "write" -> {
                val text = args["text"].orEmpty()
                if (text.isEmpty() || text.length > 4_000) error(AppStrings.enterTextOfAtMostCharacters)
                manager.setPrimaryClip(ClipData.newPlainText("mobby", text))
                AppStrings.copiedToClipboard
            }
            else -> error(AppStrings.unsupportedOperation)
        }
    }

    private fun office(action: String, args: Map<String, String>): String {
        val file = DevicePaths.resolve(workspace, inbox, args["path"].orEmpty())
        return when (action) {
            "inspect" -> OfficePackage.inspect(file)
            "read" -> OfficePackage.read(file)
            "write" -> {
                val text = args["text"] ?: error(AppStrings.missingText)
                OfficePackage.write(file, text)
                file.absolutePath
            }
            else -> error(AppStrings.unsupportedOperation)
        }
    }

    private fun numeric(args: Map<String, String>): Long {
        val id = args["id"]?.toLongOrNull()
        if (id == null || id < 0) error(AppStrings.invalidId)
        return id
    }
    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
