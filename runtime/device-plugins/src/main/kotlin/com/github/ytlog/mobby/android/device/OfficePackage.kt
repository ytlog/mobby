package com.github.ytlog.mobby.android.device

import com.github.ytlog.mobby.android.localization.AppStrings

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Reads and writes simple xlsx, docx and pptx packages. Macros are never executed. */
object OfficePackage {
    fun inspect(file: File): String = readZip(file) { entries ->
        when (kind(file)) {
            "docx" -> AppStrings.docxParagraph(texts(entries, "word/document.xml", "w:t").size)
            "xlsx" -> AppStrings.xlsxCell(sheetText(entries).size)
            "pptx" -> AppStrings.pptxSlide(entries.keys.count { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") })
            else -> error(AppStrings.onlyXlsxDocxAndPptxAreSupported)
        }
    }
    fun read(file: File): String = readZip(file) { entries ->
        val lines = when (kind(file)) {
            "docx" -> texts(entries, "word/document.xml", "w:t")
            "xlsx" -> sheetText(entries)
            "pptx" -> entries.keys.filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.sorted()
                .flatMap { texts(entries, it, "a:t") }
            else -> error(AppStrings.onlyXlsxDocxAndPptxAreSupported)
        }
        if (lines.isEmpty()) AppStrings.documentHasNoReadableText else lines.joinToString("\n")
    }
    fun write(file: File, text: String) {
        val lines = text.replace("\r\n", "\n").split('\n').take(200)
        if (lines.sumOf { it.length } > 100_000) error(AppStrings.contentIsTooLong)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            ZipOutputStream(temporary.outputStream()).use { zip ->
                when (kind(file)) {
                    "docx" -> docx(zip, lines)
                    "xlsx" -> xlsx(zip, lines)
                    "pptx" -> pptx(zip, lines)
                    else -> error(AppStrings.onlyXlsxDocxAndPptxAreSupported)
                }
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }
    private fun kind(file: File) = file.extension.lowercase()
    private fun <T> readZip(file: File, block: (Map<String, String>) -> T): T {
        if (!file.isFile || file.length() > 32L * 1024 * 1024) error(AppStrings.documentDoesNotExistOrExceedsMb)
        ZipFile(file).use { zip ->
            val entries = linkedMapOf<String, String>()
            var total = 0L
            val items = zip.entries()
            while (items.hasMoreElements()) {
                val entry = items.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("/") || ".." in name.split('/')) error(AppStrings.invalidDocumentContent)
                if (entry.size > 2L * 1024 * 1024) error(AppStrings.documentContentIsTooLarge)
                val text = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
                total += text.length
                if (total > 8L * 1024 * 1024) error(AppStrings.documentContentIsTooLarge)
                entries[name] = text
            }
            return block(entries)
        }
    }
    private fun texts(entries: Map<String, String>, name: String, tag: String): List<String> {
        val xml = entries[name] ?: return emptyList()
        val pattern = Regex("""<${Regex.escape(tag)}(?:\s[^>]*)?>(.*?)</${Regex.escape(tag)}>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(xml).map { unescape(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
    }
    private fun sheetText(entries: Map<String, String>): List<String> {
        val inline = entries.filterKeys { it.startsWith("xl/worksheets/sheet") }.toSortedMap().values.flatMap { xml -> texts(mapOf("x" to xml), "x", "t") }
        val shared = texts(entries, "xl/sharedStrings.xml", "t")
        return (inline + shared).distinct()
    }
    private fun unescape(value: String) = value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun put(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray())
        zip.closeEntry()
    }
    private fun docx(zip: ZipOutputStream, lines: List<String>) {
        put(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
        put(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        val paragraphs = lines.joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">${escape(it)}</w:t></w:r></w:p>" }
        put(zip, "word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs</w:body></w:document>""")
    }
    private fun xlsx(zip: ZipOutputStream, lines: List<String>) {
        put(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        put(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put(zip, "xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        put(zip, "xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        val rows = lines.mapIndexed { index, line ->
            val row = index + 1
            """<row r="$row"><c r="A$row" t="inlineStr"><is><t>${escape(line)}</t></is></c></row>"""
        }.joinToString("")
        put(zip, "xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""")
    }
    private fun pptx(zip: ZipOutputStream, lines: List<String>) {
        put(zip, "[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/></Types>""")
        put(zip, "_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>""")
        put(zip, "ppt/_rels/presentation.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>""")
        put(zip, "ppt/presentation.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>""")
        val runs = lines.joinToString("") { "<a:p><a:r><a:t>${escape(it)}</a:t></a:r></a:p>" }
        put(zip, "ppt/slides/slide1.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody>$runs</p:txBody></p:sp></p:spTree></p:cSld></p:sld>""")
    }
}
