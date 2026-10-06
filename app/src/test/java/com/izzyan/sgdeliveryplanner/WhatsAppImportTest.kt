package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class WhatsAppImportTest {
    @Test fun extractsBulkAlamatAddressesWithoutPhoneNumbers() {
        val text = """Alamat:
            Blk 452B Sengkang West Way #12-407 Singapore 792452
            No.telefon:
            https://wa.me/+6592483000

            Alamat:
            Blk 625A Woodland Drive 52
            #07-51 S 731625
            No.telefon:
            +6596430908
        """.trimIndent()
        assertEquals(listOf("792452", "731625"), extractWhatsAppPostalCodes(text))
    }
    @Test fun handlesAtLeastOneHundredOrdersWithLeadingZerosAndStableDeduplication() {
        val text = (1..150).joinToString("\n\n") { "Alamat:\nSingapore ${it.toString().padStart(6, '0')}\nNo.telefon:\n+6592483000" }
        assertEquals((1..150).map { it.toString().padStart(6, '0') }, extractWhatsAppPostalCodes(text + "\n" + text))
    }
    @Test fun standaloneBoundariesExcludeLongNumbersAndPhoneUrlsAndLabels() {
        val text = "792452, (731625) 650202 1234567 6592483000 +6596430908\nPhone: 123456\nhttps://wa.me/654321\n+65 123456 78"
        assertEquals(listOf("792452", "731625", "650202"), extractWhatsAppPostalCodes(text))
    }
    @Test fun prefersAddressSectionsAndHandlesMixedCaseWindowsLineBreaks() {
        val text = "Customer 123456\r\nALAMAT: S 012345\r\nNO.TELEFON: 654321\r\nPayment 999999\r\nAlamat: Singapore 012345\r\nNo telefon: +6592483000"
        assertEquals(listOf("012345"), extractWhatsAppPostalCodes(text))
    }
    @Test fun phoneLabelOnPreviousLineExcludesSeparatedPhoneDigits() {
        assertEquals(listOf("792452"), extractWhatsAppPostalCodes("No.telefon:\n659248 3000\n792452"))
    }
    @Test fun doesNotMistakeLabeledPaymentOrCustomerNumbersForPostalCodes() {
        val text = "Payment: 100000\nCustomer: 123456\nFacebook: https://example.com/650202\nSingapore 792452"
        assertEquals(listOf("792452"), extractWhatsAppPostalCodes(text))
    }
    @Test fun reportsFoundDuplicatesAndFailedAddressSections() {
        val text = """Alamat:
            Singapore 792452
            No.telefon:
            +6592483000

            Alamat:
            Singapore 792452
            No.telefon:
            +6596430908

            Alamat:
            Blk 999 Missing Postal
            No.telefon:
            +6591112222
        """.trimIndent()
        val result = analyzeWhatsAppImport(text)
        assertEquals(listOf("792452"), result.postalCodes)
        assertEquals(2, result.found)
        assertEquals(1, result.duplicate)
        assertEquals(1, result.failed)
    }
    @Test fun detectsMalayAlamatTemplate() {
        val text = """TT.Co 💍 INVOICE: 28/9/26

            Total: $55  (COD)

            Nama: Komala
            FB: Komala Sobari

            Alamat:
            18 Eunos Crescent #02-2889 Singapura 400018

            No.telefon:
            https://wa.me/+6583915054
        """.trimIndent()
        assertEquals(listOf("400018"), extractWhatsAppPostalCodes(text))
    }

    @Test fun detectsEnglishAddressTemplateWithSgPrefix() {
        val text = """🔥ORDER SARUNG MIRAA🔥

            Tarikh Order: 18/08/2026
            Total: $130 (COD)
            Name: Salamah Mohd
            FB: Salamah Mohd
            Page: Syurga Wanita

            Address:
            Blk 268 #03-254 Bukit Batok East Ave 4 SG 650268

            Whatsapp no.:
            https://wa.me/+6591779029

            Item:
            7 COLOURS
        """.trimIndent()
        assertEquals(listOf("650268"), extractWhatsAppPostalCodes(text))
    }

    @Test fun detectsEnglishMultilineAddressWithTrailingPeriod() {
        val text = """🌸ORDER SHAKIRA🌸

            Total: $ 80 (COD)

            FB: Norlaili Mohd Nor
            Name: Norlaili bte Mohd Nor

            Address:
            Blk 512 # 07 -04
            Wellington Circle Singapore 750512.

            Whatsapp no.:
            https://wa.me/+6584253239

            Tarikh Order: 24/7/2026
        """.trimIndent()
        assertEquals(listOf("750512"), extractWhatsAppPostalCodes(text))
    }

    @Test fun detectsMixedAddressLabelsInOneBulkPaste() {
        val text = """Alamat:
            Singapore 400018
            No.telefon:
            https://wa.me/+6583915054

            Address:
            SG 650268
            Whatsapp no.:
            https://wa.me/+6591779029

            Delivery Address:
            Singapore 750512.
            Phone:
            +6584253239
        """.trimIndent()
        assertEquals(listOf("400018", "650268", "750512"), extractWhatsAppPostalCodes(text))
    }

    @Test fun fallbackFindsPostalCodeWithoutKnownAddressLabelButIgnoresPhoneAndDates() {
        val text = """ORDER TEST
            Tarikh Order: 18/08/2026
            Customer location Singapore 560123
            Whatsapp no.:
            +6591779029
        """.trimIndent()
        assertEquals(listOf("560123"), extractWhatsAppPostalCodes(text))
    }

    @Test fun emptyAndPhoneOnlyTextProduceNoCodes() {
        listOf("", "+6592483000", "https://wa.me/+6596430908", "No.telefon: 123456").forEach { assertTrue(extractWhatsAppPostalCodes(it).isEmpty()) }
    }
}
