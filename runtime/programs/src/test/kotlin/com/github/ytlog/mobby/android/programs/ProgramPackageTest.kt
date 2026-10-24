package com.github.ytlog.mobby.android.programs

import com.github.ytlog.mobby.android.programs.capability.ScopeKind
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.program.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProgramPackageTest {
    private val root = Files.createTempDirectory("packages").toFile()
    private val reader = ProgramPackageReader(fixtureRegistry())
    private fun builder() = PackageBuilder(File(root, "pkg"))
    private fun problems(dir: File) = (runCatching { reader.read(dir) }.exceptionOrNull() as? PackageRejected)?.problems?.map { "${it.path}: ${it.message}" }
        ?: error("expected rejection")

    @After fun clean() { root.deleteRecursively() }

    @Test fun `scopes parse strictly and compare by kind`() {
        val (domains, ok) = Scope.parse(ScopeKind.DOMAINS, Json.parseToJsonElement("""{"domains":["api.example.com","*.cdn.example.com"],"methods":["GET"]}""").jsonObject)
        assertTrue(ok.isEmpty())
        domains!!
        assertTrue(domains.allowsHost("api.example.com") && domains.allowsHost("a.cdn.example.com"))
        assertFalse(domains.allowsHost("cdn.example.com") || domains.allowsHost("evil-api.example.com") || domains.allowsHost("example.com"))
        assertTrue(domains.allowsMethod("GET") && !domains.allowsMethod("POST"))
        listOf("10.0.0.1", "Example.com", "localhost", "*.com", "a..b.com", "a.example.com:8080").forEach { assertFalse(it, Scope.validDomain(it)) }
        assertTrue(Scope.parse(ScopeKind.DOMAINS, Json.parseToJsonElement("""{"domains":["a.com"],"paths":["/"]}""").jsonObject).second.isNotEmpty())
        assertTrue(Scope.parse(ScopeKind.NONE, Json.parseToJsonElement("""{"packages":["a.b"]}""").jsonObject).second.isNotEmpty())
        val any = Scope(domains = setOf("a.com"))
        val get = Scope(domains = setOf("a.com"), methods = setOf("GET"))
        assertEquals(setOf("GET"), any.intersect(get).methods)
        assertTrue(get.within(any))
        assertFalse(any.within(get))
        assertEquals(Scope.fromJson(get.toJson().toString()), get)
    }

    @Test fun `a complete package validates and has a stable checksum`() {
        val first = reader.read(builder().write())
        assertEquals("price-watch", first.manifest.id)
        assertEquals(listOf("main.js", "manifest.json", "ui/app.js", "ui/index.html"), first.files.keys.toList())
        assertEquals(setOf("GET"), first.permissions.single { it.capability == "net.http" }.scope.methods)
        assertEquals(first.checksum, reader.read(builder().write()).checksum)
        assertNotEquals(first.checksum, reader.read(builder().apply { files["main.js"] = "changed" }.write()).checksum)
    }

    @Test fun `manifest errors are all reported and unknown fields are rejected`() {
        assertTrue(problems(builder().apply { extra = "\"entry\":\"main.js\"" }.write()).single().startsWith("manifest.json"))
        val many = problems(builder().apply {
            id = "Bad_Id"; background = "missing.js"
            permissions = """[{"capability":"camera.photo","required":true},{"capability":"net.http","required":true,"scope":{"domains":["10.0.0.1"]}},
                {"capability":"time.schedule","required":true}]"""
            config = """[{"key":"mode","type":"enum","required":true,"label":"模式"},{"key":"token","type":"secret","required":true,"label":"令牌","domains":["other.com"]}]"""
        }.write())
        listOf("id:", "background:", "permissions[0].capability:", "permissions[1].scope: invalid domains entry 10.0.0.1",
            "config[0].options:", "config[1].domains:").forEach { expected -> assertTrue(expected, many.any { it.startsWith(expected) }) }
        assertTrue(problems(builder().apply { background = null; ui = null; subscriptions = "[]" }.write()).any { it.contains("background or ui is required") })
    }

    @Test fun `subscriptions need background logic, a requested event capability and a valid filter`() {
        assertTrue(problems(builder().apply { background = null }.write()).any { it.contains("subscriptions need background logic") })
        assertTrue(problems(builder().apply { subscriptions = """[{"id":"x","event":"net.http","handler":"h"}]""" }.write()).any { it.contains("not an event capability") })
        assertTrue(problems(builder().apply {
            permissions = """[{"capability":"storage.sql","required":true}]"""; config = "[]"
        }.write()).any { it.contains("must also be requested") })
        assertTrue(problems(builder().apply { subscriptions = """[{"id":"x","event":"time.schedule","filter":{"at":"9"},"handler":"h"}]""" }.write())
            .any { it.contains("filter needs every") })
    }

    @Test fun `ui pages may not run inline code or load external resources`() {
        listOf(
            "<script>alert(1)</script>" to "inline scripts",
            """<button onclick="go()">x</button>""" to "inline event handler",
            """<img src="https://tracker.example.com/p.png">""" to "external reference",
            """<script src="//cdn.example.com/x.js"></script>""" to "external reference",
            """<a href="javascript:go()">x</a>""" to "javascript:",
        ).forEach { (html, expected) -> assertTrue(html, problems(builder().apply { this.html = html }.write()).any { it.contains(expected) }) }
        assertTrue(problems(builder().apply { files["ui/style.css"] = "body{background:url(https://x.example.com/a.png)}" }.write()).any { it.startsWith("ui/style.css") })
        reader.read(builder().write())
    }

    @Test fun `structure limits and links are enforced`() {
        val dir = builder().write()
        Files.createSymbolicLink(File(dir, "link.js").toPath(), File(dir, "main.js").toPath())
        assertTrue(problems(dir).single().startsWith("link.js: symbolic"))
        val small = ProgramPackageReader(fixtureRegistry(), PackageLimits(maxFiles = 2))
        assertTrue((runCatching { small.read(builder().write()) }.exceptionOrNull() as PackageRejected).problems.any { it.message.contains("more than 2 files") })
    }
}
