package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WhatsAppClipboardTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val clipboard get()=app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Test fun importReplacesTextboxWithCompleteClipboardTextWithoutMutatingClipboardOrHome() {
        val raw=WhatsAppImportFixture.mixed
        clipboard.setPrimaryClip(ClipData.newPlainText("Orders",raw))
        val prefs=app.getSharedPreferences("settings",0)
        prefs.edit().putString("input","650417").commit()
        val pasted=pasteWhatsAppOrders(app,"Previous editable text")
        assertEquals(raw,pasted)
        assertEquals("650417",prefs.getString("input",null))
        assertEquals(raw,clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(3,parseCustomerOrders(pasted).orders.size)
        assertTrue(parseInput(parseCustomerOrders(pasted).orders.joinToString("\n") {it.postalCode}).invalid.isEmpty())
    }
    @Test fun emptyBlankAndNonTextClipPreserveExistingTextboxWithClearMessage() {
        val existing="Name: still editable\nAddress: Singapore 650417"
        clipboard.clearPrimaryClip()
        assertEquals(existing,pasteWhatsAppOrders(app,existing))
        assertEquals("No WhatsApp order text found in clipboard.",ShadowToast.getTextOfLatestToast())
        clipboard.setPrimaryClip(ClipData.newPlainText("Blank"," \n\t"))
        assertEquals(existing,pasteWhatsAppOrders(app,existing))
        clipboard.setPrimaryClip(ClipData(ClipDescription("Image",arrayOf("image/png")),ClipData.Item(Uri.parse("content://example/photo"))))
        assertEquals(existing,pasteWhatsAppOrders(app,existing))
        assertEquals("No WhatsApp order text found in clipboard.",ShadowToast.getTextOfLatestToast())
    }
}
