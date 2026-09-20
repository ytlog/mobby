package com.libtermux.executor

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedLinesTest {
    @Test fun utf8AndIncompleteLastLineArePreserved() {
        val lines = mutableListOf<String>()
        val input = object : ByteArrayInputStream("你好\r\n世界\n最后".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        readBoundedLines(input, lines::add)
        assertEquals(listOf("你好", "世界", "最后"), lines)
    }
    @Test fun oversizedLineIsBoundedWithoutBreakingTheFollowingLine() {
        val lines = mutableListOf<String>()
        readBoundedLines(("a".repeat(300000) + "\nnext\n").byteInputStream(), lines::add)
        assertEquals(2, lines.size)
        assertTrue(lines.first().endsWith("[line truncated]"))
        assertTrue(lines.first().length < 263000)
        assertEquals("next", lines.last())
    }
}
