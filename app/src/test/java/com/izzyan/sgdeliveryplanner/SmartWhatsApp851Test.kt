package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SmartWhatsApp851Test {
    private val app: Application get()=ApplicationProvider.getApplicationContext()
    @Before fun reset(){
        app.getSharedPreferences("smart_whatsapp_share",0).edit().clear().commit()
        SmartShareSession.cancel(); SmartShareSession.serviceConnected(false)
    }
    @After fun cleanup(){SmartShareSession.cancel();SmartShareSession.serviceConnected(false)}
    @Test fun defaultsAndExactGroupBindingsPersist(){
        val prefs=SmartSharePreferences(app)
        assertFalse(prefs.enabled);assertTrue(prefs.autoReturn)
        prefs.enabled=true;prefs.autoReturn=false
        prefs.saveVerified(SmartShareKind.REPORT,"Runner Test",WhatsAppChoice.BUSINESS)
        prefs.saveVerified(SmartShareKind.ROUTE,"Schedule Test",WhatsAppChoice.BUSINESS)
        val reopened=SmartSharePreferences(app)
        assertTrue(reopened.enabled);assertFalse(reopened.autoReturn)
        assertEquals("Runner Test",reopened.eligibleGroup(SmartShareKind.REPORT,WhatsAppChoice.BUSINESS)?.name)
        assertEquals("Schedule Test",reopened.eligibleGroup(SmartShareKind.ROUTE,WhatsAppChoice.BUSINESS)?.name)
        assertNull(reopened.eligibleGroup(SmartShareKind.REPORT,WhatsAppChoice.PERSONAL))
        reopened.remove(SmartShareKind.REPORT)
        assertNull(reopened.group(SmartShareKind.REPORT));assertNotNull(reopened.group(SmartShareKind.ROUTE))
    }
    @Test fun sessionRequiresPermissionMatchingPackageAndContentUriAndRejectsDuplicates(){
        val group=SmartGroup("Test",WhatsAppChoice.BUSINESS.packageName)
        val uri=Uri.parse("content://test/image.png")
        assertNull(SmartShareSession.begin(SmartShareKind.REPORT,WhatsAppChoice.BUSINESS,group,uri,true))
        SmartShareSession.serviceConnected(true)
        assertNull(SmartShareSession.begin(SmartShareKind.REPORT,WhatsAppChoice.PERSONAL,group,uri,true))
        assertNull(SmartShareSession.begin(SmartShareKind.REPORT,WhatsAppChoice.BUSINESS,group,Uri.parse("file:///tmp/test"),true))
        val request=requireNotNull(SmartShareSession.begin(SmartShareKind.REPORT,WhatsAppChoice.BUSINESS,group,uri,true))
        assertNull(SmartShareSession.begin(SmartShareKind.ROUTE,WhatsAppChoice.BUSINESS,group,uri,true))
        SmartShareSession.searchOpened(request.id)
        assertEquals(SmartShareStage.SEARCH_OPENED,SmartShareSession.stage)
        SmartShareSession.searchPrepared(request.id)
        assertFalse(SmartShareSession.active)
        assertTrue(SmartShareSession.status.contains("Verify recipient"))
        assertFalse(SmartShareSession.status.contains("Successfully"))
    }
    @Test fun onlyKnownContactPickerActivityIsAccepted(){
        assertTrue(isWhatsAppPickerClass("com.whatsapp.contact.picker.ContactPicker"))
        assertTrue(isWhatsAppPickerClass("com.whatsapp.w4b.contact.picker.ContactPicker"))
        assertFalse(isWhatsAppPickerClass("com.whatsapp.Conversation"))
        assertFalse(isWhatsAppPickerClass("com.whatsapp.HomeActivity"))
        assertFalse(isWhatsAppPickerClass(null))
    }
    @Test fun cancelAndServiceDisconnectNeverReplay(){
        SmartShareSession.serviceConnected(true)
        val group=SmartGroup("Test",WhatsAppChoice.PERSONAL.packageName)
        val uri=Uri.parse("content://test/image.png")
        val request=requireNotNull(SmartShareSession.begin(SmartShareKind.ROUTE,WhatsAppChoice.PERSONAL,group,uri,true))
        SmartShareSession.cancel()
        SmartShareSession.searchPrepared(request.id)
        assertEquals(SmartShareStage.CANCELLED,SmartShareSession.stage)
        requireNotNull(SmartShareSession.begin(SmartShareKind.ROUTE,WhatsAppChoice.PERSONAL,group,uri,true))
        SmartShareSession.serviceConnected(false)
        assertFalse(SmartShareSession.active)
        assertTrue(SmartShareSession.status.contains("manual"))
    }
}
