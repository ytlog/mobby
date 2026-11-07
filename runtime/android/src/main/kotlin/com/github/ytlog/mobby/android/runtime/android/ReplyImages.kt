package com.github.ytlog.mobby.android.runtime.android

import android.graphics.BitmapFactory
import android.webkit.MimeTypeMap
import com.github.ytlog.mobby.android.runtime.api.ReplyFile
import com.github.ytlog.mobby.android.runtime.api.ReplyImagePreview
import com.github.ytlog.mobby.android.runtime.api.ReplyImageProblem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/**
 * Images named by reply Markdown. Local paths must resolve, links included, inside the turn's workspace;
 * remote images are https only and are fetched by the caller's explicit request.
 */
internal class ReplyImages(private val home: File, private val download: (URI, Int) -> ByteArray = ::httpsDownload) {
    fun original(workspace: File, source: String): ReplyFile {
        val value = source.trim()
        val file = resolveLocal(workspace, when (SCHEME.find(value)?.groupValues?.get(1)?.lowercase()) {
            null -> decodedPath(value)
            "file" -> filePath(value)
            else -> throw Rejected(ReplyImageProblem.UNSUPPORTED_SOURCE)
        })
        val bytes = readLocal(file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mediaType = bounds.outMimeType ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?.takeUnless { it.startsWith("image/") } ?: "application/octet-stream"
        return ReplyFile(file.name, mediaType, bytes)
    }

    fun preview(workspace: File, source: String, expanded: Boolean): ReplyImagePreview {
        val bytes = try { content(workspace, source.trim()) } catch (rejected: Rejected) { return ReplyImagePreview.Rejected(rejected.problem) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ReplyImagePreview.Rejected(ReplyImageProblem.NOT_IMAGE)
        if (bounds.outWidth > MAX_SIDE || bounds.outHeight > MAX_SIDE || bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) {
            return ReplyImagePreview.Rejected(ReplyImageProblem.TOO_LARGE)
        }
        return try { ReplyImagePreview.Ready(ImagePreview.render(bytes, if (expanded) 1024 else 256, MAX_SIDE, MAX_PIXELS)) }
        catch (_: IllegalArgumentException) { ReplyImagePreview.Rejected(ReplyImageProblem.NOT_IMAGE) }
    }

    private fun content(workspace: File, source: String): ByteArray {
        if (source.startsWith("data:", ignoreCase = true)) return inline(source)
        val scheme = SCHEME.find(source)?.groupValues?.get(1)?.lowercase()
        return when (scheme) {
            null -> readLocal(resolveLocal(workspace, decodedPath(source)))
            "file" -> readLocal(resolveLocal(workspace, filePath(source)))
            "https" -> {
                val uri = uri(source)
                if (uri.host.isNullOrBlank()) throw Rejected(ReplyImageProblem.UNSUPPORTED_SOURCE)
                try { download(uri, MAX_BYTES) }
                catch (_: TooLarge) { throw Rejected(ReplyImageProblem.TOO_LARGE) }
                catch (_: IOException) { throw Rejected(ReplyImageProblem.DOWNLOAD_FAILED) }
            }
            else -> throw Rejected(ReplyImageProblem.UNSUPPORTED_SOURCE)
        }
    }

    private fun inline(source: String): ByteArray {
        val match = DATA.matchEntire(source) ?: throw Rejected(ReplyImageProblem.UNSUPPORTED_SOURCE)
        val encoded = match.groupValues[1]
        if (encoded.length.toLong() * 3 / 4 > MAX_BYTES) throw Rejected(ReplyImageProblem.TOO_LARGE)
        return try { Base64.getMimeDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw Rejected(ReplyImageProblem.NOT_IMAGE) }
    }

    private fun filePath(source: String): String {
        val uri = uri(source)
        if (uri.rawAuthority != null) throw Rejected(ReplyImageProblem.UNSUPPORTED_SOURCE)
        return uri.path ?: throw Rejected(ReplyImageProblem.MISSING)
    }

    private fun resolveLocal(workspace: File, path: String): File {
        val candidate = when {
            path == "~" -> home
            path.startsWith("~/") -> File(home, path.removePrefix("~/"))
            path.startsWith("/") -> File(path)
            else -> File(workspace, path)
        }
        val root = workspace.canonicalFile
        val file = candidate.canonicalFile
        if (file != root && !file.path.startsWith(root.path + File.separator)) throw Rejected(ReplyImageProblem.OUTSIDE_WORKSPACE)
        if (!file.isFile) throw Rejected(ReplyImageProblem.MISSING)
        return file
    }

    private fun readLocal(file: File): ByteArray {
        if (file.length() > MAX_BYTES) throw Rejected(ReplyImageProblem.TOO_LARGE)
        return try {
            java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .use { readBounded(it, MAX_BYTES) }
        } catch (_: TooLarge) { throw Rejected(ReplyImageProblem.TOO_LARGE) }
    }

    class Rejected(val problem: ReplyImageProblem) : Exception(null, null, false, false)
    class TooLarge : IOException("Reply image exceeds the download limit")

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        private const val MAX_SIDE = 16384
        private const val MAX_PIXELS = 100_000_000L
        private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
        private val DATA = Regex("data:image/[a-zA-Z0-9.+-]+(?:;[^,;]+=[^,;]*)*;base64,(.*)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private fun uri(source: String) = try { URI(source) } catch (_: URISyntaxException) { throw Rejected(ReplyImageProblem.UNSUPPORTED_SOURCE) }
        /** Markdown destinations are URLs, so `%20` names a space; raw spaces are also kept as written. */
        private fun decodedPath(source: String) = try { URI(source).path ?: source } catch (_: URISyntaxException) { source }

        private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) throw TooLarge()
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun httpsDownload(uri: URI, limit: Int): ByteArray {
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.useCaches = false
                connection.instanceFollowRedirects = true
                if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
                // Redirects never downgrade to http by default; check after following them.
                if (connection.url.protocol != "https") throw IOException("Redirected away from https")
                if (connection.contentLengthLong > limit) throw TooLarge()
                return connection.inputStream.use { readBounded(it, limit) }
            } finally { connection.disconnect() }
        }
    }
}
