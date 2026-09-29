package com.github.ytlog.mobby.android.runtime.android

import java.io.File
import java.nio.file.Files

/** Bounded screenshot cache. Reads refresh the last-used time used for eviction. */
internal class LruResourceStore(
    root: File,
    budgetBytes: () -> Long = { 128L * 1024 * 1024 },
) : ResourceStore(root, budgetBytes) {
    override fun evictionCandidates(entries: List<File>, needed: Long): List<File> {
        var remaining = needed
        return entries.filter { it.name.matches(Regex("[a-f0-9]{64}")) }
            .sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .takeWhile { file -> (remaining > 0).also { remaining -= Files.size(file.toPath()) } }
    }
}
