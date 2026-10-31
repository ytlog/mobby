package com.github.ytlog.mobby.android.programs.permission

import com.github.ytlog.mobby.android.programs.capability.ScopeKind
import kotlinx.serialization.json.*

private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
private val HOST_LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
private val MODEL = Regex("[A-Za-z0-9._:/-]{1,128}")
private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")

/**
 * The scope of one grant. Each field is null when the capability's [ScopeKind] does not use it.
 * Domains are exact hosts or `*.suffix`; an empty set grants nothing. Absent [methods] means any method.
 */
data class Scope(
    val packages: Set<String>? = null,
    val domains: Set<String>? = null,
    val methods: Set<String>? = null,
    val insecureHttp: Boolean = false,
    val models: Set<String>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        packages?.let { put("packages", JsonArray(it.sorted().map(::JsonPrimitive))) }
        domains?.let { put("domains", JsonArray(it.sorted().map(::JsonPrimitive))) }
        methods?.let { put("methods", JsonArray(it.sorted().map(::JsonPrimitive))) }
        if (insecureHttp) put("insecureHttp", true)
        models?.let { put("models", JsonArray(it.sorted().map(::JsonPrimitive))) }
    }

    /** What both scopes allow; used when an update keeps an earlier grant. */
    fun intersect(other: Scope) = Scope(
        packages?.let { mine -> other.packages?.let { mine intersect it } ?: emptySet() },
        domains?.let { mine -> other.domains?.let { mine intersect it } ?: emptySet() },
        when { methods == null -> other.methods; other.methods == null -> methods; else -> methods intersect other.methods },
        insecureHttp && other.insecureHttp,
        models?.let { mine -> other.models?.let { mine intersect it } ?: emptySet() },
    )

    /** True when every value here is also in [requested]. */
    fun within(requested: Scope): Boolean =
        packages.orEmpty().all { it in requested.packages.orEmpty() || ANY_PACKAGE in requested.packages.orEmpty() } &&
            domains.orEmpty().all { it in requested.domains.orEmpty() } &&
            (requested.methods == null || (methods != null && requested.methods.containsAll(methods))) &&
            (!insecureHttp || requested.insecureHttp) &&
            models.orEmpty().all { it in requested.models.orEmpty() }

    /** `"*"` stands for every app; mobby itself is kept out by the capabilities that act on apps. */
    fun allowsPackage(name: String) = packages?.let { name in it || ANY_PACKAGE in it } == true

    fun allowsHost(host: String): Boolean = domains.orEmpty().any { domain ->
        if (domain.startsWith("*.")) host.endsWith(domain.substring(1)) && host.length > domain.length - 1 else host == domain
    }

    fun allowsMethod(method: String) = methods == null || method in methods

    fun allowsModel(model: String) = models?.contains(model) == true

    companion object {
        val NONE = Scope()
        /** In a package scope, every app. */
        const val ANY_PACKAGE = "*"

        /** Strict parse: keys must belong to [kind]; returns problems instead of guessing. */
        fun parse(kind: ScopeKind, json: JsonObject?): Pair<Scope?, List<String>> {
            val problems = mutableListOf<String>()
            val value = json ?: JsonObject(emptyMap())
            val allowed = when (kind) {
                ScopeKind.NONE -> emptySet()
                ScopeKind.PACKAGES -> setOf("packages")
                ScopeKind.DOMAINS -> setOf("domains", "methods", "insecureHttp")
                ScopeKind.MODELS -> setOf("models")
                ScopeKind.RECORDS, ScopeKind.DIRECTORIES -> emptySet()
            }
            (value.keys - allowed).forEach { problems += "unknown scope field $it" }
            fun strings(key: String, limit: Int, valid: (String) -> Boolean): Set<String>? {
                val array = value[key] ?: return null
                val items = (array as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (items == null || items.any { it == null }) { problems += "$key must be an array of strings"; return emptySet() }
                val set = items.filterNotNull().toSet()
                if (set.size != items.size) problems += "$key has duplicates"
                if (set.isEmpty() || set.size > limit) problems += "$key needs 1-$limit entries"
                set.filterNot(valid).forEach { problems += "invalid $key entry $it" }
                return set
            }
            val scope = when (kind) {
                ScopeKind.NONE, ScopeKind.RECORDS, ScopeKind.DIRECTORIES -> NONE
                ScopeKind.PACKAGES -> Scope(packages = strings("packages", 32) { it == ANY_PACKAGE || it.length <= 255 && it.matches(PACKAGE) }
                    ?: emptySet<String>().also { problems += "packages is required" })
                ScopeKind.DOMAINS -> Scope(
                    domains = strings("domains", 32, ::validDomain) ?: emptySet<String>().also { problems += "domains is required" },
                    methods = strings("methods", METHODS.size) { it in METHODS },
                    insecureHttp = value["insecureHttp"]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: false.also { problems += "insecureHttp must be a boolean" } } ?: false,
                )
                ScopeKind.MODELS -> Scope(models = strings("models", 16) { it.matches(MODEL) } ?: emptySet<String>().also { problems += "models is required" })
            }
            return (if (problems.isEmpty()) scope else null) to problems
        }

        fun fromJson(json: String): Scope {
            val value = Json.parseToJsonElement(json).jsonObject
            fun set(key: String) = value[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
            return Scope(set("packages"), set("domains"), set("methods"), value["insecureHttp"]?.jsonPrimitive?.boolean ?: false, set("models"))
        }

        /** Lowercase host names only; no IP literals, ports, paths or bare top-level wildcards. */
        fun validDomain(domain: String): Boolean {
            val host = domain.removePrefix("*.")
            if (host.length > 253 || host != host.lowercase()) return false
            val labels = host.split('.')
            if (labels.size < 2 || labels.any { !it.matches(HOST_LABEL) }) return false
            return !labels.last().all(Char::isDigit)
        }
    }
}
