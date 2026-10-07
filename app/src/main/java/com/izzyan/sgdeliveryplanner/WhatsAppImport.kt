package com.izzyan.sgdeliveryplanner

internal data class WhatsAppImportAnalysis(
    val postalCodes: List<String>,
    val found: Int,
    val duplicate: Int,
    val failed: Int
)

private val whatsAppPostalRegex = Regex("(?<!\\d)\\d{6}(?!\\d)")

private val addressSectionRegex = Regex(
    "(?ims)^[^\\p{L}\\p{N}\\r\\n]*(?:alamat(?:\\s+penghantaran)?|address|delivery\\s+address|shipping\\s+address|delivery\\s+location|lokasi(?:\\s+penghantaran)?|addr)\\s*:\\s*" +
        "(.*?)" +
        "(?=^[^\\p{L}\\p{N}\\r\\n]*(?:" +
        "whatsapp(?:\\s+(?:no\\.?|number))?|wa(?:\\s+no\\.?)?|no\\.?\\s*telefon|phone(?:\\s+no\\.?)?|mobile|telefon|" +
        "order|item|total|name|nama|fb|facebook|page|tarikh(?:\\s+order)?|date|payment|amount|price|harga|bayaran|jumlah|" +
        "alamat(?:\\s+penghantaran)?|address|delivery\\s+address|shipping\\s+address|delivery\\s+location|lokasi(?:\\s+penghantaran)?|addr" +
        ")\\s*:|\\z)"
)

private fun sanitizeWhatsAppImportSource(source: String): String = source
    // Remove links first so WhatsApp phone URLs can never contribute digits.
    .replace(Regex("(?i)https?://\\S+"), "")
    // Remove common labelled non-address fields.
    .replace(
        Regex(
            "(?im)^[^\\p{L}\\p{N}\\r\\n]*(?:payment|amount|total|price|harga|bayaran|jumlah|customer|name|nama|facebook|fb|page|" +
                "order(?:[ \\t]+id)?|invoice|tarikh(?:[ \\t]+order)?|date)[ \\t]*:[^\\n]*"
        ),
        ""
    )
    // Remove phone/WhatsApp fields, including a number or URL on the next line.
    .replace(
        Regex(
            "(?im)^[^\\p{L}\\p{N}\\r\\n]*(?:whatsapp(?:[ \\t]+(?:no\\.?|number))?|wa(?:[ \\t]+no\\.?)?|" +
                "no\\.?[ \\t]*telefon|phone(?:[ \\t]+no\\.?)?|mobile|telefon)[ \\t]*:[ \\t]*(?:\\n[ \\t]*)?[^\\n]*"
        ),
        ""
    )
    // Remove international/local phone-like digit runs before looking for six-digit postal codes.
    .replace(Regex("(?<!\\d)\\+?65[ \\-]?[3689](?:[ \\-]?\\d){7}(?!\\d)"), "")
    .replace(Regex("(?<!\\d)[3689](?:[ \\-]?\\d){7}(?!\\d)"), "")
    .replace(Regex("(?<!\\d)\\d{7,}(?!\\d)"), "")

/**
 * Analyze a pasted/shared WhatsApp batch without persisting customer text.
 *
 * Supported address labels include:
 * Alamat, Alamat Penghantaran, Address, Delivery Address, Shipping Address,
 * Delivery Location, Lokasi, Lokasi Penghantaran and Addr.
 *
 * If no known address label exists, a safe whole-message fallback is used after
 * phone numbers, URLs, dates, totals and other common non-address fields are removed.
 *
 * found = valid postal-code occurrences detected in the batch (including repeats)
 * duplicate = repeated postal-code occurrences inside this one import batch
 * failed = labelled address sections where no valid six-digit postal code was detected
 */
internal fun analyzeWhatsAppImport(text: String): WhatsAppImportAnalysis {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val addressSections = addressSectionRegex.findAll(normalized).map { it.groupValues[1] }.toList()

    val occurrencesBySection = if (addressSections.isNotEmpty()) {
        addressSections.map { section ->
            whatsAppPostalRegex.findAll(sanitizeWhatsAppImportSource(section)).map { it.value }.toList()
        }
    } else {
        listOf(
            whatsAppPostalRegex.findAll(sanitizeWhatsAppImportSource(normalized)).map { it.value }.toList()
        )
    }

    val occurrences = occurrencesBySection.flatten()
    val distinct = occurrences.distinct()

    return WhatsAppImportAnalysis(
        postalCodes = distinct,
        found = occurrences.size,
        duplicate = occurrences.size - distinct.size,
        failed = if (addressSections.isNotEmpty()) occurrencesBySection.count { it.isEmpty() } else 0
    )
}

/** Only postal codes leave this parser. Raw customer messages are never persisted. */
internal fun extractWhatsAppPostalCodes(text: String): List<String> = analyzeWhatsAppImport(text).postalCodes
