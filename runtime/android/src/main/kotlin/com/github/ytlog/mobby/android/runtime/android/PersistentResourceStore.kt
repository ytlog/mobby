package com.github.ytlog.mobby.android.runtime.android

import java.io.File

/** Durable imported attachments. A full quota rejects new content without deleting existing data. */
internal class PersistentResourceStore(
    root: File,
    budgetBytes: () -> Long = { DEFAULT_BUDGET_BYTES },
    fallbackRoot: File? = null,
) : ResourceStore(root, budgetBytes, fallbackRoot) {
    override fun evictionCandidates(entries: List<File>, needed: Long): List<File> = emptyList()
}
