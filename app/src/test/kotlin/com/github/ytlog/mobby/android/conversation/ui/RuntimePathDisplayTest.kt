package com.github.ytlog.mobby.android.conversation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePathDisplayTest {
    private val app = "com.github.ytlog.mobby.android"
    private val data = "/mnt/expand/volume/user/10/$app"
    private val paths = RuntimePathDisplay(app, data)

    @Test fun `shortens the app's own paths across Android locations`() {
        assertEquals(
            "读取~/workspace/a.png，运行 /usr/bin/node，输出到 '/tmp/result.png'\n" +
                "输入：/var/lib/mobby/agent-inputs/run/image-0.png 缓存：/var/cache/mobby/state",
            paths.format(
                "读取$data/files/libtermux/home/workspace/a.png，运行 /data/data/$app/files/libtermux/usr/bin/node，" +
                    "输出到 '/data/user/10/$app/files/libtermux/usr/tmp/result.png'\n" +
                    "输入：/data/user_de/10/$app/files/agent-inputs/run/image-0.png 缓存：$data/cache/state",
            ),
        )
        assertEquals("~/workspace/a%20b.png", paths.format("file://$data/files/libtermux/home/workspace/a%20b.png"))
        assertEquals("HOME is ~.", paths.format("HOME is $data/files/libtermux/home."))
        assertEquals("/var/lib/mobby/libtermux/home-backup/a", paths.format("$data/files/libtermux/home-backup/a"))
    }

    @Test fun `leaves other apps and unrelated paths intact`() {
        val text = "/data/user/0/com.example.app/files/a /data/data/$app.debug/files/a " +
            "https://example.com/data/data/$app/files/a /backup$data/files/a ~/workspace/a"
        assertEquals(text, paths.format(text))
    }
}
