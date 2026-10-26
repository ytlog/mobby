package com.github.ytlog.mobby.android.programcenter

import androidx.compose.ui.text.input.KeyboardType
import com.github.ytlog.mobby.android.programs.program.ConfigType
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigKeyboardTest {
    /** A composing IME rewrote typed secrets on the tablet; secret fields must ask for plain password input. */
    @Test fun `secret config fields ask the input method for password input`() {
        assertEquals(KeyboardType.Password, configKeyboard(ConfigType.SECRET).keyboardType)
        assertEquals(false, configKeyboard(ConfigType.SECRET).autoCorrectEnabled)
        assertEquals(KeyboardType.Number, configKeyboard(ConfigType.NUMBER).keyboardType)
        assertEquals(KeyboardType.Text, configKeyboard(ConfigType.STRING).keyboardType)
    }
}
