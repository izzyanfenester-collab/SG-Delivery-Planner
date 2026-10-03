package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaxDeclareTest {
    @Test
    fun intentOpensTheExactOfficialPortalWithNoRoutePayload() {
        val intent = taxDeclareIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://m.customs.gov.sg/CustomsTravellerPortal/", intent.data.toString())
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
        assertNull(intent.`package`)
        assertNull(intent.component)
        assertNull(intent.type)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun browserSelectorUsesAndroidResolutionWithoutHardCodingABrowserPackage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val selector = requireNotNull(taxDeclareIntent().selector)
        assertEquals(Intent.ACTION_MAIN, selector.action)
        assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), selector.categories)
        assertNull(selector.data)
        assertNull(selector.`package`)
        assertNull(selector.component)
        assertNull(selector.extras)
        assertNull(selector.clipData)
        val browser = ResolveInfo().apply {
            isDefault = true
            activityInfo = ActivityInfo().apply {
                packageName = "example.default.browser"
                name = "example.default.browser.BrowserActivity"
                enabled = true
                exported = true
                applicationInfo = ApplicationInfo().apply {
                    packageName = "example.default.browser"
                    enabled = true
                }
            }
        }
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(selector, browser)
        val resolved = requireNotNull(selector.resolveActivityInfo(app.packageManager, 0))
        assertEquals("example.default.browser", resolved.packageName)
        assertEquals("example.default.browser.BrowserActivity", resolved.name)
    }

    @Test
    fun successStartsAnExternalBrowserIntentAndPreservesTheClipboard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = seedClipboard(app)
        assertTrue(openTaxDeclare(app))
        val launched = requireNotNull(Shadows.shadowOf(app).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("https://m.customs.gov.sg/CustomsTravellerPortal/", launched.data.toString())
        assertEquals(Intent.ACTION_MAIN, launched.selector?.action)
        assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), launched.selector?.categories)
        assertNull(launched.`package`)
        assertNull(launched.extras)
        assertNull(launched.clipData)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertClipboardUnchanged(app, clipboard)
    }

    @Test
    fun noBrowserShowsAClearEnglishErrorAndPreservesTheClipboard() {
        val context = FailingContext(ActivityNotFoundException("No browser installed"))
        assertFalse(openTaxDeclare(context))
        assertEquals(1, context.attempts.size)
        assertEquals("https://m.customs.gov.sg/CustomsTravellerPortal/", context.attempts.single().data.toString())
        assertEquals(
            "No browser is available. Install or enable a browser to open the Singapore Customs Traveller Portal.",
            ShadowToast.getTextOfLatestToast()
        )
        assertClipboardUnchanged(context, context.clipboard)
    }

    @Test
    fun deniedBrowserLaunchShowsAnEnglishErrorWithoutChangingTheClipboard() {
        val context = FailingContext(SecurityException("Browser launch denied"))
        assertFalse(openTaxDeclare(context))
        assertEquals(1, context.attempts.size)
        assertEquals(
            "The Singapore Customs Traveller Portal could not be opened in your browser. Please try again.",
            ShadowToast.getTextOfLatestToast()
        )
        assertClipboardUnchanged(context, context.clipboard)
    }

    private fun seedClipboard(context: Context): ClipboardManager {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Personal clipboard", "Existing clipboard text"))
        return clipboard
    }

    private fun assertClipboardUnchanged(context: Context, clipboard: ClipboardManager) {
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals("Personal clipboard", clip.description.label)
        assertEquals(1, clip.itemCount)
        assertEquals("Existing clipboard text", clip.getItemAt(0).coerceToText(context).toString())
    }

    private inner class FailingContext(private val failure: RuntimeException) :
        ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val attempts = mutableListOf<Intent>()
        val clipboard = seedClipboard(this)

        override fun startActivity(intent: Intent) {
            attempts.add(Intent(intent))
            throw failure
        }
    }
}
