package com.github.ytlog.mobby.android.localmodel

import com.github.ytlog.mobby.android.localization.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDownloadSourceTest {
    @Test fun languageChoosesInitialSource() {
        assertEquals(ModelDownloadSource.MODELSCOPE, ModelDownloadSource.defaultFor(AppLanguage.CHINESE))
        assertEquals(ModelDownloadSource.HUGGING_FACE, ModelDownloadSource.defaultFor(AppLanguage.ENGLISH))
    }

    @Test fun onlyKnownSourceIdsAreAccepted() {
        assertEquals(ModelDownloadSource.HF_MIRROR, ModelDownloadSource.fromId("hf-mirror"))
        assertNull(ModelDownloadSource.fromId("https://example.com"))
    }
}
