package com.izzyan.sgdeliveryplanner

internal data class WhatsAppImportAnalysis(
    val postalCodes: List<String>,
    val found: Int,
    val duplicate: Int,
    val failed: Int
)

private val whatsAppPostalRegex = Regex("(?<!\\d)\\d{6}(?!\\d)")

private fun sanitizeWhatsAppImportSource(source: String): String = source
    .replace(Regex("(?im)^[ \\t]*(?:payment|amount|total|price|harga|bayaran|jumlah|customer|name|nama|facebook|order(?:[ \\t]+id)?)[ \\t]*:[^\\n]*"), "")
    .replace(Regex("(?im)^[ \\t]*(?:no\\.?[ \\t]*telefon|phone|mobile|telefon)[ \\t]*:[ \\t]*(?:\\n[ \\t]*)?[^\\n]*"), "")
    .replace(Regex("(?i)https?://\\S+"), "")
    .replace(Regex("\\+[0-9][0-9 ()-]{7,}"), "")

/**
 * Analyze a pasted/shared WhatsApp batch without persisting customer text.
 *
 * found = valid postal-code occurrences detected in address sections (including repeats)
 * duplicate = repeated postal-code occurrences inside this import batch
 * failed = Alamat sections where no valid six-digit postal code was detected
 */
internal fun analyzeWhatsAppImport(text: String): WhatsAppImportAnalysis {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val addresses = Regex("(?ims)^\\s*alamat\\s*:\\s*(.*?)(?=^\\s*(?:no\\.?\\s*telefon|alamat)\\s*:|\\z)")
        .findAll(normalized).map { it.groupValues[1] }.toList()
    val sections = if (addresses.isNotEmpty()) addresses else listOf(normalized)
    val occurrencesBySection = sections.map { section ->
        whatsAppPostalRegex.findAll(sanitizeWhatsAppImportSource(section)).map { it.value }.toList()
    }
    val occurrences = occurrencesBySection.flatten()
    val distinct = occurrences.distinct()
    return WhatsAppImportAnalysis(
        postalCodes = distinct,
        found = occurrences.size,
        duplicate = occurrences.size - distinct.size,
        failed = if (addresses.isNotEmpty()) occurrencesBySection.count { it.isEmpty() } else 0
    )
}

/** Only postal codes leave this parser. Raw customer messages are never persisted. */
internal fun extractWhatsAppPostalCodes(text: String): List<String> = analyzeWhatsAppImport(text).postalCodes
