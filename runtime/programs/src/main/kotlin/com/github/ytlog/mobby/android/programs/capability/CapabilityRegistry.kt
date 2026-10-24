package com.github.ytlog.mobby.android.programs.capability

import com.github.ytlog.mobby.android.programs.call.SchemaCheck

/** Only capabilities that have a handler exist; the catalog and SDK types are generated from here. */
class CapabilityRegistry(handlers: List<CapabilityHandler>) {
    private val byId: Map<String, CapabilityHandler>

    init {
        val duplicates = handlers.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate capabilities: $duplicates" }
        handlers.forEach {
            require(it.descriptor.id.matches(ID)) { "Invalid capability id ${it.descriptor.id}" }
            require(SchemaCheck.supported(it.descriptor.inputSchema)) { "Unsupported schema keyword in ${it.descriptor.id}" }
        }
        byId = handlers.associateBy { it.descriptor.id }
    }

    operator fun get(id: String): CapabilityHandler? = byId[id]

    val descriptors: List<CapabilityDescriptor> get() = byId.values.map { it.descriptor }.sortedBy { it.id }

    companion object { val ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*") }
}
