package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
    @Test
    fun intentTargetsOnlyTheBlankChatGptHomeWithoutAnyQuestionOrContext() {
        val native = chatGptIntent("com.openai.chatgpt")
        assertEquals("com.openai.chatgpt", native.`package`)
        assertBlankHomeIntent(native)
        val browser = chatGptIntent()
        assertNull(browser.`package`)
        assertBlankHomeIntent(browser)
    }

    @Test
    fun installedChatGptOpensDirectlyWithoutSharingOrChangingTheClipboard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val clipboard = seedClipboard(app)
        assertTrue(openChatGpt(app))
        val launched = Shadows.shadowOf(app).nextStartedActivity
        assertEquals("com.openai.chatgpt", launched.`package`)
        assertBlankHomeIntent(launched)
        assertNull(Shadows.shadowOf(app).nextStartedActivity)
        assertClipboardUnchanged(app, clipboard)
    }

    @Test
    fun missingChatGptAppFallsBackToTheSameBlankUrlInTheExternalBrowser() {
        val context = RecordingContext(listOf(ActivityNotFoundException("No ChatGPT app")))
        assertTrue(openChatGpt(context))
        assertEquals(2, context.attempts.size)
        assertEquals("com.openai.chatgpt", context.attempts[0].`package`)
        assertNull(context.attempts[1].`package`)
        context.attempts.forEach(::assertBlankHomeIntent)
        assertClipboardUnchanged(context, context.clipboard)
    }

    @Test
    fun deniedChatGptAppAlsoFallsBackWithoutAddingAnyText() {
        val context = RecordingContext(listOf(SecurityException("Package denied")))
        assertTrue(openChatGpt(context))
        assertEquals(2, context.attempts.size)
        context.attempts.forEach(::assertBlankHomeIntent)
        assertNull(context.attempts[1].`package`)
        assertClipboardUnchanged(context, context.clipboard)
    }

    @Test
    fun missingAppAndBrowserShowAnEnglishErrorAndKeepTheClipboardUnchanged() {
        val context = RecordingContext(listOf(
            ActivityNotFoundException("No ChatGPT app"),
            ActivityNotFoundException("No browser")
        ))
        assertFalse(openChatGpt(context))
        assertEquals(2, context.attempts.size)
        context.attempts.forEach(::assertBlankHomeIntent)
        assertEquals(
            "ChatGPT could not be opened. Please install ChatGPT or a web browser and try again.",
            ShadowToast.getTextOfLatestToast()
        )
        assertClipboardUnchanged(context, context.clipboard)
    }

    @Test
    fun bothDeniedLaunchesAreHandledWithoutSharingOrCopyingAnything() {
        val context = RecordingContext(listOf(SecurityException("App denied"), SecurityException("Browser denied")))
        assertFalse(openChatGpt(context))
        assertEquals(2, context.attempts.size)
        context.attempts.forEach(::assertBlankHomeIntent)
        assertEquals(
            "ChatGPT could not be opened. Please install ChatGPT or a web browser and try again.",
            ShadowToast.getTextOfLatestToast()
        )
        assertClipboardUnchanged(context, context.clipboard)
    }

    private fun assertBlankHomeIntent(intent: Intent) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://chatgpt.com/", intent.data.toString())
        assertNull(intent.data?.query)
        assertNull(intent.data?.fragment)
        assertNull(intent.type)
        assertNull(intent.extras)
        assertNull(intent.clipData)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
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

    private inner class RecordingContext(private val failures: List<RuntimeException>) :
        ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val attempts = mutableListOf<Intent>()
        val clipboard = seedClipboard(this)

        override fun startActivity(intent: Intent) {
            attempts.add(Intent(intent))
            failures.getOrNull(attempts.lastIndex)?.let { throw it }
        }
    }
}
