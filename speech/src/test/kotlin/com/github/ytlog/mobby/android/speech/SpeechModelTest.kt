package com.github.ytlog.mobby.android.speech

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelTest {
    @Test fun `checking readiness does not download`() {
        val root = tempDir()
        var fetched = false
        val store = SpeechModelStore(root, expectedSha256 = "unused") { _, _ -> fetched = true }
        assertFalse(store.ready())
        assertFalse(fetched)
    }

    @Test fun `ensure downloads once and a ready model skips the network`() {
        val root = tempDir()
        val archive = archive()
        val hash = sha256(archive)
        var fetches = 0
        val store = SpeechModelStore(root, hash) { destination, onProgress ->
            fetches += 1
            archive.copyTo(destination, overwrite = true)
            onProgress(destination.length(), destination.length())
        }
        store.ensure()
        assertEquals(1, fetches)
        assertTrue(store.ready())
        assertTrue(File(store.modelDirectory(), "am/final.mdl").isFile)
        store.ensure()
        assertEquals(1, fetches)
        assertFalse(File(root, SpeechModel.PARTIAL).exists())
    }

    @Test fun `checksum failure deletes the partial archive and does not leave a model`() {
        val root = tempDir()
        val store = SpeechModelStore(root, expectedSha256 = "0000") { destination, _ ->
            archive().copyTo(destination, overwrite = true)
        }
        val error = assertThrows(SpeechModelException::class.java) { store.ensure() }
        assertEquals(SpeechModelException.Kind.CHECKSUM, error.kind)
        assertFalse(store.ready())
        assertFalse(File(root, SpeechModel.PARTIAL).exists())
        assertFalse(store.modelDirectory().exists())
    }

    @Test fun `zip entries that escape the destination are rejected`() {
        val root = tempDir()
        val archive = File(root, "slip.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("no".toByteArray())
            zip.closeEntry()
        }
        assertThrows(SpeechModelException::class.java) { unzipSafely(archive, File(root, "out")) }
        assertFalse(File(root, "outside.txt").exists())
        assertFalse(File(root.parentFile, "outside.txt").exists())
    }

    @Test fun `pcm level is silent at zero and full for loud samples`() {
        assertEquals(0f, pcmLevel(ByteArray(0), 0), 0.001f)
        assertEquals(0f, pcmLevel(byteArrayOf(0, 0, 0, 0), 4), 0.001f)
        val loud = ByteArray(4)
        writeSample(loud, 0, 7000)
        writeSample(loud, 2, -7000)
        assertTrue(pcmLevel(loud, 4) > 0.9f)
        assertTrue(pcmLevel(byteArrayOf(0, 4, 0, 0), 3) in 0.05f..0.2f)
    }

    @Test fun `vosk json keeps english spaces and drops spaces between chinese characters`() {
        assertEquals("你好世界", transcriptOf("""{"text":"你 好 世 界"}"""))
        assertEquals("hello world", transcriptOf("""{"partial":" hello world "}"""))
        assertEquals("打开 hello world", transcriptOf("""{"text":"打 开 hello world"}"""))
        assertEquals("", transcriptOf("""{"text":""}"""))
        assertEquals("", transcriptOf(null))
    }

    private fun archive(): File {
        val file = File.createTempFile("speech-model", ".zip")
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            writeEntry(zip, "${SpeechModel.DIRECTORY}/am/final.mdl", "model")
            writeEntry(zip, "${SpeechModel.DIRECTORY}/conf/model.conf", "conf")
        }
        return file
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray())
        zip.closeEntry()
    }

    private fun writeSample(bytes: ByteArray, offset: Int, sample: Int) {
        bytes[offset] = sample.toByte()
        bytes[offset + 1] = (sample shr 8).toByte()
    }

    private fun tempDir(): File = File.createTempFile("speech", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }
}
