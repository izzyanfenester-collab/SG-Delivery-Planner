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

    @Test fun detectsTwentyOnePostalCodesFromEmojiPrefixedBulkWhatsAppPaste() {
        val text = """📍 Alamat:
            Blk 189B Marsiling Road
            #11-960 S'pore 732189
            📱 WhatsApp:
            https://wa.me/+6597983272

            📍 Alamat:
            Block 623, Woodlands Dr 52, #10-02, S 730623
            📱 WhatsApp:
            https://wa.me/+6586838323

            📍 Alamat:
            Block 689E #06-138 Woodlands Dr 75 SINGAPORE 735689
            📱 WhatsApp:
            https://wa.me/+6590377878

            Alamat:
            Blk 372B #06-225 Sun Sails Sembawang Ave S 752372
            No.telefon:
            https://wa.me/+6589781308

            📍 Alamat:
            Blk 462 sembawang drive, 13-231, Singapore 750462
            📱 WhatsApp:
            https://wa.me/+6596431748

            Address:
            Blk 512 # 07 -04
            Wellington Circle Singapore 750512.
            Whatsapp no.:
            https://wa.me/+6584253239

            📍 Alamat:
            Blk 296 # 03-07 Yishun St. 20 S'pore 760296
            📱 WhatsApp:
            https://wa.me/+6598321920

            📍 Alamat:
            Blk 706 #03-184 Yishun Avenue 5 Spore 760706
            📱 WhatsApp:
            https://wa.me/+6581616455

            Alamat:
            Blk 709 #09-66 Yishun Ave 5 S 760709
            No.telefon:
            https://wa.me/+6581003668

            Alamat:
            776 Yishun Ave 2 #12-1601 Singapore 760776
            No.telefon:
            https://wa.me/+6596999130

            📍 Alamat:
            Blk 199 -B Punggol Field #10-421 SE 822199
            📱 WhatsApp:
            https://wa.me/+6591072985

            📍 Alamat:
            Blk 422A Northshore Drive #18-729 S 821422
            📱 WhatsApp:
            https://wa.me/+6588660551

            📍 Alamat:
            233C Sumang lane #02-305
            Matilda Court Singapore 823233
            📱 WhatsApp:
            https://wa.me/+6580143418

            Address:
            Blk 466 tampines street 44 #03-36 S 520466
            Whatsapp no.:
            https://wa.me/+6586127661

            📍 Alamat:
            BLK 863 TAMPINES STREET 83 #06-474 S(520863)
            📱 WhatsApp:
            https://wa.me/+6588962432

            📍 Alamat:
            Blk 704 #03-209 Hougang Ave 2(530704)
            📱 WhatsApp:
            https://wa.me/+6594795775

            Alamat:
            151 Bedok Reservoir Road #04-1745 Singapore 470151
            No.telefon:
            https://wa.me/+6596170731

            📍 Alamat:
            Blk 22 Eunos Crescent
            #03-3001 Singapore 400022
            📱 WhatsApp:
            https://wa.me/+6597543440

            Address:
            Blk 316 #02-371 Ubi ave 1 S 400316
            Whatsapp no.:
            https://wa.me/+6591119759

            📍 Alamat:
            Blk 142 lor 2 Toa payoh #05-166 S 310142
            📱 WhatsApp:
            https://wa.me/+6593894171

            Alamat:
            Blk 513 #04-34 Wellington Circle Singapore 750513
            No.telefon:
            https://wa.me/+6587683467
        """.trimIndent()

        val result = analyzeWhatsAppImport(text)
        assertEquals(
            listOf(
                "732189", "730623", "735689", "752372", "750462", "750512", "760296",
                "760706", "760709", "760776", "822199", "821422", "823233", "520466",
                "520863", "530704", "470151", "400022", "400316", "310142", "750513"
            ),
            result.postalCodes
        )
        assertEquals(21, result.found)
        assertEquals(0, result.duplicate)
        assertEquals(0, result.failed)
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
