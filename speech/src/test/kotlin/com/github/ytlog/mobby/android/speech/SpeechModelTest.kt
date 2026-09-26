package com.github.ytlog.mobby.android.speech

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelTest {
    @Test fun `readiness never downloads`() {
        val root = tempDir()
        var fetched = false
        val store = SpeechModelStore(root, "unused") { _, _ -> fetched = true }
        assertFalse(store.ready())
        assertFalse(fetched)
    }

    @Test fun `only required files are installed and archive is removed`() {
        val root = tempDir()
        val oldModel = File(root, "vosk-model-small-cn-0.22").apply { mkdirs() }
        val oldChinese = File(root, "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23").apply { mkdirs() }
        val archive = archive()
        var fetches = 0
        val store = SpeechModelStore(root, sha256(archive)) { destination, _ ->
            fetches++
            archive.copyTo(destination, overwrite = true)
        }
        store.ensure()
        assertTrue(store.ready())
        assertEquals(SpeechModel.REQUIRED, store.modelDirectory().list()?.toSet())
        assertFalse(File(root, SpeechModel.PARTIAL).exists())
        assertFalse(oldModel.exists())
        assertFalse(oldChinese.exists())
        store.ensure()
        assertEquals(1, fetches)
    }

    @Test fun `bad checksum removes partial and does not install`() {
        val root = tempDir()
        val oldChinese = File(root, "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23").apply { mkdirs() }
        val archive = archive()
        val store = SpeechModelStore(root, "0000", mirrorDownload = { _, _, _, _ ->
            throw SpeechModelException(SpeechModelException.Kind.NETWORK)
        }) { destination, _ -> archive.copyTo(destination, overwrite = true) }
        assertEquals(SpeechModelException.Kind.CHECKSUM,
            assertThrows(SpeechModelException::class.java) { store.ensure() }.kind)
        assertFalse(store.ready())
        assertTrue(oldChinese.exists())
        assertFalse(File(root, SpeechModel.PARTIAL).exists())
    }

    @Test fun `domestic source installs verified files without fetching large archive`() {
        val root = tempDir()
        val files = testMirrorFiles()
        var archiveFetched = false
        val progress = mutableListOf<Pair<Long, Long>>()
        val store = SpeechModelStore(root, preferDomestic = true, mirrorFiles = files,
            mirrorDownload = { url, destination, _, callback ->
                val name = url.substringAfterLast('=')
                val bytes = name.toByteArray()
                destination.writeBytes(bytes)
                callback(bytes.size.toLong(), bytes.size.toLong())
            }) { _, _ -> archiveFetched = true }
        store.ensure { read, total -> progress += read to total }
        assertTrue(store.ready())
        assertFalse(archiveFetched)
        assertEquals(SpeechModel.REQUIRED, store.modelDirectory().list()?.toSet())
        assertEquals(files.values.sumOf { it.bytes } to files.values.sumOf { it.bytes }, progress.last())
    }

    @Test fun `domestic download failure falls back to verified archive`() {
        val root = tempDir()
        val archive = archive()
        var archiveFetched = false
        val store = SpeechModelStore(root, sha256(archive), preferDomestic = true,
            mirrorDownload = { _, _, _, _ -> throw SpeechModelException(SpeechModelException.Kind.NETWORK) }) { destination, _ ->
            archiveFetched = true
            archive.copyTo(destination, overwrite = true)
        }
        store.ensure()
        assertTrue(archiveFetched)
        assertTrue(store.ready())
    }

    @Test fun `normalization preserves english spaces`() {
        assertEquals("你好世界", normalizeTranscript(" 你 好 世 界 "))
        assertEquals("hello world", normalizeTranscript(" hello world "))
    }

    @Test fun `pcm level responds to quiet voice`() {
        assertEquals(0f, pcmLevel(ByteArray(0), 0), 0.001f)
        assertTrue(pcmLevel(byteArrayOf(0, 4, 0, 0), 4) > 0f)
    }

    private fun archive(): File {
        val file = File.createTempFile("speech-model", ".tar.bz2").apply { deleteOnExit() }
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            (SpeechModel.REQUIRED + "unused-large-model.onnx").forEach { name ->
                val bytes = name.toByteArray()
                val entry = TarArchiveEntry("${SpeechModel.DIRECTORY}/$name").apply { size = bytes.size.toLong() }
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private fun testMirrorFiles(): Map<String, SpeechFile> = SpeechModel.REQUIRED.associateWith { name ->
        val bytes = name.toByteArray()
        val file = File.createTempFile("speech-file", ".bin").apply { writeBytes(bytes); deleteOnExit() }
        SpeechFile(bytes.size.toLong(), sha256(file))
    }

    private fun tempDir(): File = File.createTempFile("speech", "").apply {
        delete(); mkdirs(); deleteOnExit()
    }
}
