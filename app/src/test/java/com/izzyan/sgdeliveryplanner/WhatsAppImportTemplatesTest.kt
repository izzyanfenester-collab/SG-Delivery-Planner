package com.izzyan.sgdeliveryplanner

import org.junit.Test
import org.junit.Assert.*
import java.math.BigDecimal

internal object WhatsAppImportFixture {
    val english="""
        🌸ORDER SHAKIRA🌸
        Total: $ 80 (COD)
        FB: Norlaili Mohd Nor
        Name: Norlaili bte Mohd Nor
        💕PT.Co💕
        Address:
        Blk 512 # 07 -04
        Wellington Circle Singapore 750512.
        Whatsapp no.:
        https://wa.me/+6584253239
        Tarikh Order: 24/7/2026
        Item:
        Size XL
        1- Light Purple
        2- Maroon
    """.trimIndent()
    val malay="""
        TT.Co 💍 INVOICE: 28/7/26
        Total: $55 (COD)
        Nama: Noraisah Abdul Manan
        FB: Nor Aisah
        Alamat:
        776 Yishun Ave 2 #12-1601 Singapore 760776
        No.telefon:
        https://wa.me/+6596999130
        Order:
        SULAM CROSIA: HH
        SIZE XL
        1. SOFT YELLOW
        2. PEANUT BROWN
    """.trimIndent()
    val emoji="""
        🔥 ORDER TUDUNG LUPI🔥
        📅 Tarikh Order: 28/07/2026
        💰 Total: $60 (COD)
        👤 Nama: Sarah fereny
        📘 FB: Sarah fereny
        🛍️ Page: Syurga Wanita
        📍 Alamat:
        233C Sumang lane #02-305
        Matilda Court Singapore 823233
        📱 WhatsApp:
        https://wa.me/+6580143418
        🛒 Item:
        BLACK
        OAT
    """.trimIndent()
    val mixed="[31/07, 11:21 pm] Eyra Ramanny: $english\n[31/07, 11:22 pm] Eyra Ramanny: ${malay.replace("COD","PayNow")}\n[31/07, 11:23 pm] Eyra Ramanny: $emoji"
    val names=listOf("Norlaili bte Mohd Nor","Noraisah Abdul Manan","Sarah fereny")
    val phones=listOf("+6584253239","+6596999130","+6580143418")
    val postals=listOf("750512","760776","823233")
    val addresses=listOf("Blk 512 # 07 -04\nWellington Circle Singapore 750512.","776 Yishun Ave 2 #12-1601 Singapore 760776","233C Sumang lane #02-305\nMatilda Court Singapore 823233")
}
class WhatsAppImportTemplatesTest {
    @Test fun allThreeRealTemplatesKeepEveryCustomerFieldAndAddressBoundary() {
        val result=parseCustomerOrders(listOf(WhatsAppImportFixture.english,WhatsAppImportFixture.malay,WhatsAppImportFixture.emoji).joinToString("\n\n"))
        assertEquals(0,result.failed);assertEquals(3,result.orders.size)
        assertEquals(WhatsAppImportFixture.names,result.orders.map {it.customerName})
        assertEquals(WhatsAppImportFixture.phones,result.orders.map {it.phoneNumber})
        assertEquals(WhatsAppImportFixture.postals,result.orders.map {it.postalCode})
        assertEquals(WhatsAppImportFixture.addresses,result.orders.map {it.fullAddress})
        assertEquals(listOf("80.00","55.00","60.00").map(::BigDecimal),result.orders.map {it.parcelPrice})
        assertEquals(listOf("COD","COD","COD"),result.orders.map {it.paymentStatus})
    }
    @Test fun timestampedMixedExportsAreIndependentIncludingInlineMessageBodies() {
        val result=parseCustomerOrders(WhatsAppImportFixture.mixed)
        assertEquals(0,result.failed)
        assertEquals(WhatsAppImportFixture.names,result.orders.map {it.customerName})
        assertEquals(WhatsAppImportFixture.phones,result.orders.map {it.phoneNumber})
        assertEquals(WhatsAppImportFixture.addresses,result.orders.map {it.fullAddress})
        assertEquals(listOf("COD","PAYNOW","COD"),result.orders.map {it.paymentStatus})
        assertEquals(3,result.orders.map {it.orderId}.distinct().size)
    }
    @Test fun arbitrarySymbolsAndEveryAliasPermitSpacingPunctuationAndPlainPhones() {
        val phoneLabels=listOf("No.telefon","No telefon","No. telefon","Phone","Phone no.","Telephone","Whatsapp","WhatsApp","Whatsapp no.","WhatsApp no.")
        for(name in listOf("Nama","Name","Customer","Customer Name")) for(phone in phoneLabels) {
            val order=parseCustomerOrders("🔥 • ★ $name :Alice\n🪷 Amount : SGD 55 (paynow)\n🌀 Address : Block 1 # 07 -04 S’pore 732189\n🎉 $phone : +65 8425 3239\n🛒 Item : 123456").orders.single()
            assertEquals("Alice",order.customerName);assertEquals("+6584253239",order.phoneNumber)
            assertEquals("732189",order.postalCode);assertEquals(BigDecimal("55.00"),order.parcelPrice);assertEquals("PAYNOW",order.paymentStatus)
            assertEquals("Block 1 # 07 -04 S’pore 732189",order.fullAddress)
        }
    }
    @Test fun addressStopsAtEveryNextRecognizedFieldAndPostalNeverComesFromOutsideIt() {
        for(end in listOf("No.telefon","No telefon","Phone","Phone no.","Whatsapp","WhatsApp","Whatsapp no.","Order","Item","Tarikh Order","Date","FB","Page")) {
            val result=parseCustomerOrders("Name: Alice\nAddress: Blk 123 #07-04 Singapore 750512\n📱 $end: 123456\nItem: 654321")
            assertEquals("Blk 123 #07-04 Singapore 750512",result.orders.single().fullAddress)
            assertEquals("750512",result.orders.single().postalCode)
            assertEquals(1,parseCustomerOrders("Total: 750512\nName: Alice\nAddress: no postcode\n$end: 123456\nPhone: +6584253239\nDate: 31/07/2026").failed)
        }
    }
    @Test fun hundredMixedOrdersRetainDuplicatePostalsAndReportFailedMessage() {
        val raw=(1..100).joinToString("\n") {"[31/07, 11:21 pm] Runner: ${listOf(WhatsAppImportFixture.english,WhatsAppImportFixture.malay,WhatsAppImportFixture.emoji)[it%3]}"}+
            "\n[31/07, 11:23 pm] Runner: Name: Failed\nAddress: Singapore\nWhatsApp: https://wa.me/6584253239"
        val result=parseCustomerOrders(raw)
        assertEquals(100,result.orders.size);assertEquals(100,result.orders.map {it.orderId}.distinct().size);assertEquals(1,result.failed)
        assertEquals(3,result.orders.map {it.postalCode}.distinct().size)
    }
}
