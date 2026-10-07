package com.izzyan.sgdeliveryplanner

import android.content.ClipData
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WhatsAppShareTest {
    @Test fun consumesOnlyPostalCodesAndErasesCustomerPayloadToPreventReplay() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Customer Private\nAlamat: Singapore 792452\nNo.telefon: +6592483000")
            .putExtra(Intent.EXTRA_SUBJECT, "Payment details")
        intent.clipData = ClipData.newPlainText("Customer", "Private order")
        val result = consumeWhatsAppShare(intent)
        assertEquals(listOf("792452"), result?.postalCodes)
        assertEquals(1, result?.found)
        assertEquals(0, result?.duplicate)
        assertEquals(0, result?.failed)
        assertNull(intent.extras); assertNull(intent.clipData); assertNull(intent.data)
        assertNull(consumeWhatsAppShare(intent))
    }
    @Test fun nonTextAndNonShareIntentsAreNotConsumed() {
        assertNull(consumeWhatsAppShare(Intent(Intent.ACTION_VIEW)))
        assertNull(consumeWhatsAppShare(Intent(Intent.ACTION_SEND).setType("image/png")))
    }
}
