package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings

import java.io.File
import java.io.InputStream

object DevicePaths {
    fun resolve(workspace: File, inbox: File, raw: String): File {
        if (raw.isBlank() || '\u0000' in raw || '\\' in raw || raw.split('/').any { it == ".." }) error(AppStrings.invalidPath)
        val file = if (raw.startsWith("/")) File(raw) else File(workspace, raw.trimStart('/'))
        return contained(listOf(workspace, inbox), file)
    }
    fun contained(roots: List<File>, candidate: File): File {
        val target = candidate.canonicalFile
        val bases = roots.map { it.canonicalFile }
        if (bases.none { base -> target.path == base.path || target.path.startsWith(base.path + File.separator) }) error(AppStrings.pathIsOutsideTheAllowedDirectory)
        return target
    }
    fun copyBounded(input: InputStream, dest: File, maxBytes: Long = 32L * 1024 * 1024) {
        dest.parentFile?.mkdirs()
        try {
            dest.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) error(AppStrings.fileExceedsMb)
                    out.write(buffer, 0, read)
                }
            }
        } catch (error: Throwable) {
            dest.delete()
            throw error
        }
    }
    fun safeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.isBlank() || base == "." || base == ".." || base.length > 120 || '\u0000' in base) error(AppStrings.invalidFileName)
        return base
    }
}
