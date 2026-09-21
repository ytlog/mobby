package com.mobby.runtime.android

import com.mobby.runtime.api.WorkspaceRef
import com.mobby.runtime.api.WorkspaceSummary
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Managed execution directories; names are display text, never caller-selected paths. */
internal class WorkspaceStore(home: File) {
    private val home = home.canonicalFile
    private val root get() = File(home, "workspaces")
    private val registry get() = File(root, ".names")
    fun resolve(ref: WorkspaceRef): File? {
        if (!validRef(ref.value)) return null
        val directory = if (ref.value == "default") File(home, "workspace") else File(root, ref.value)
        if (ref.value != "default" && root.canonicalFile != root) return null
        if (directory.canonicalFile != directory || !directory.isDirectory) return null
        if (ref.value != "default") {
            if (registry.canonicalFile != registry) return null
            val marker = File(registry, ref.value)
            if (!marker.isFile || Files.isSymbolicLink(marker.toPath()) || marker.length() > 320 || !validName(marker.readText())) return null
        }
        return directory
    }
    fun list(): List<WorkspaceSummary> = buildList {
        resolve(WorkspaceRef("default"))?.let { add(WorkspaceSummary(WorkspaceRef("default"), "默认本机工作区")) }
        if (root.canonicalFile != root) return@buildList
        root.listFiles().orEmpty().sortedBy { it.name }.forEach { directory ->
            if (directory.name == "default") return@forEach
            val ref = WorkspaceRef(directory.name)
            if (resolve(ref) == null) return@forEach
            val marker = File(registry, ref.value)
            if (!marker.isFile || Files.isSymbolicLink(marker.toPath()) || marker.length() > 320) return@forEach
            val name = marker.readText()
            if (validName(name)) add(WorkspaceSummary(ref, name))
        }
    }
    suspend fun create(name: String, initialize: suspend (File) -> Unit): WorkspaceSummary = creation.withLock {
        val display = name.trim()
        require(validName(display) && list().none { it.name == display })
        check(root.canonicalFile == root && (root.isDirectory || root.mkdirs()))
        check(registry.canonicalFile == registry && (registry.isDirectory || registry.mkdir()))
        val ref = WorkspaceRef("local-" + UUID.randomUUID())
        val directory = File(root, ref.value)
        check(directory.mkdir())
        try {
            initialize(directory)
            File(registry, ref.value).writeText(display)
            WorkspaceSummary(ref, display)
        } catch (e: Exception) {
            File(registry, ref.value).delete()
            directory.deleteRecursively()
            throw e
        }
    }
    companion object {
        private val creation = Mutex()
        fun forContext(context: android.content.Context) = WorkspaceStore(File(context.filesDir, "libtermux/home"))
        fun validRef(value: String) = value == "default" || value.matches(Regex("local-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        private fun validName(value: String) = value.isNotBlank() && value.length <= 80 && value.none { it.isISOControl() }
    }
}
