package com.izzyan.sgdeliveryplanner

/** Only postal codes leave this parser. Raw customer messages are never persisted. */
internal fun extractWhatsAppPostalCodes(text: String): List<String> {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val addresses = Regex("(?ims)^\\s*alamat\\s*:\\s*(.*?)(?=^\\s*(?:no\\.?\\s*telefon|alamat)\\s*:|\\z)")
        .findAll(normalized).map { it.groupValues[1] }.toList()
    val source = if (addresses.isNotEmpty()) addresses.joinToString("\n") else normalized
    val withoutPhones = source
        .replace(Regex("(?im)^[ \t]*(?:payment|amount|total|price|harga|bayaran|jumlah|customer|name|nama|facebook|order(?:[ \t]+id)?)[ \t]*:[^\\n]*"), "")
        .replace(Regex("(?im)^[ \t]*(?:no\\.?[ \t]*telefon|phone|mobile|telefon)[ \t]*:[ \t]*(?:\\n[ \t]*)?[^\\n]*"), "")
        .replace(Regex("(?i)https?://\\S+"), "")
        .replace(Regex("\\+[0-9][0-9 ()-]{7,}"), "")
    return Regex("(?<!\\d)\\d{6}(?!\\d)").findAll(withoutPhones).map { it.value }.distinct().toList()
}
