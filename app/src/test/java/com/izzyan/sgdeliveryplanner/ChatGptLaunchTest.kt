package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
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
class ChatGptLaunchTest {
    @Test fun installedChatGptLaunchesItsAndroidLauncherWithoutUrlPromptOrClipboardChanges() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        installChatGptLauncher(app)
        val clipboard = seedClipboard(app)
        assertTrue(openChatGpt(app))
        val launched = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, launched.action)
        assertTrue(launched.categories.contains(Intent.CATEGORY_LAUNCHER))
        assertEquals("com.openai.chatgpt", launched.component!!.packageName)
        assertNull(launched.data); assertNull(launched.type); assertNull(launched.extras); assertNull(launched.clipData)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertClipboardUnchanged(app, clipboard)
    }

    @Test fun missingChatGptShowsInstallMessageWithoutOpeningBrowserOrStore() {
        val context = RecordingContext()
        assertFalse(openChatGpt(context))
        assertTrue(context.attempts.isEmpty())
        assertEquals("ChatGPT app is not installed.", ShadowToast.getTextOfLatestToast())
        assertClipboardUnchanged(context, context.clipboard)
    }

    @Test fun installedPackageWithoutLauncherDoesNotOpenBrowser() {
        val context = RecordingContext()
        val info = android.content.pm.PackageInfo().apply { packageName = "com.openai.chatgpt" }
        Shadows.shadowOf(context.packageManager).installPackage(info)
        assertFalse(openChatGpt(context))
        assertTrue(context.attempts.isEmpty())
        assertEquals("ChatGPT app is not installed.", ShadowToast.getTextOfLatestToast())
        assertClipboardUnchanged(context, context.clipboard)
    }

    @Test fun appRemovedBetweenLookupAndLaunchIsHandledWithoutBrowserFallback() {
        assertFailedLaunchWithoutFallback(ActivityNotFoundException("App removed"))
    }

    @Test fun deniedLaunchIsHandledWithoutBrowserFallback() {
        assertFailedLaunchWithoutFallback(SecurityException("App denied"))
    }

    private fun assertFailedLaunchWithoutFallback(failure: RuntimeException) {
        val context = RecordingContext(failure)
        installChatGptLauncher(context)
        assertFalse(openChatGpt(context))
        assertEquals(1, context.attempts.size)
        assertEquals("com.openai.chatgpt", context.attempts.single().component!!.packageName)
        assertNull(context.attempts.single().data); assertNull(context.attempts.single().extras)
        assertEquals("ChatGPT app could not be opened. Please check that it is installed.", ShadowToast.getTextOfLatestToast())
        assertClipboardUnchanged(context, context.clipboard)
    }

    private fun installChatGptLauncher(context: Context) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage("com.openai.chatgpt")
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "com.openai.chatgpt"; name = "com.openai.chatgpt.MainActivity"; exported = true }
        }
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(launcher, info)
    }

    private fun seedClipboard(context: Context): ClipboardManager {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Personal clipboard", "My existing clipboard text"))
        return clipboard
    }

    private fun assertClipboardUnchanged(context: Context, clipboard: ClipboardManager) {
        val clip = requireNotNull(clipboard.primaryClip)
        assertEquals("Personal clipboard", clip.description.label)
        assertEquals(1, clip.itemCount)
        assertEquals("My existing clipboard text", clip.getItemAt(0).coerceToText(context).toString())
    }

    private inner class RecordingContext(private val failure: RuntimeException? = null) :
        ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val attempts = mutableListOf<Intent>()
        val clipboard = seedClipboard(this)
        override fun startActivity(intent: Intent) {
            attempts.add(Intent(intent))
            failure?.let { throw it }
        }
    }
}
