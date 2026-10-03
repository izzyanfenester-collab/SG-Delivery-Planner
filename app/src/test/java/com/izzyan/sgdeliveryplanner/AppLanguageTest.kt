package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AppLanguageTest {
    private fun malayNightContext(): Context {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(application.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ms-MY"))
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            fontScale = 1.3f
        }
        return application.createConfigurationContext(configuration)
    }

    @Test
    fun englishContextKeepsNightModeAndFontSizeOnAMalayDevice() {
        val base = malayNightContext()
        val original = Configuration(base.resources.configuration)

        val english = englishAppContext(base)

        assertEquals("en", english.resources.configuration.locales[0].language)
        assertEquals(View.LAYOUT_DIRECTION_LTR, english.resources.configuration.layoutDirection)
        assertEquals(original.uiMode, english.resources.configuration.uiMode)
        assertEquals(original.fontScale, english.resources.configuration.fontScale, 0.0f)
        assertEquals("ms-MY", base.resources.configuration.locales[0].toLanguageTag())
        assertEquals(original, base.resources.configuration)
    }

    @Test
    fun nativeDialogCaptionsUseEnglishWithoutChangingTheDeviceContext() {
        val base = malayNightContext()

        val english = englishAppContext(base)

        // Robolectric's framework adds invisible direction markers to some native captions.
        // Ignore only those markers so the visible text must still match English exactly.
        fun caption(id: Int) = english.getString(id).filterNot { it == '\u200e' || it == '\u200f' }
        assertEquals("Cancel", caption(android.R.string.cancel))
        assertEquals("OK", caption(android.R.string.ok))
        assertEquals("ms-MY", base.resources.configuration.locales[0].toLanguageTag())
    }
}
