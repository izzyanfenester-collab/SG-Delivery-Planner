package com.izzyan.sgdeliveryplanner

/**
 * Bottom GPS panel: only show a block when explicitly supplied by the selected
 * destination. Never guess a block from the six-digit Singapore postal code.
 */
internal object NavigationDestinationLabel870 {
    private val validPostal=Regex("^\\d{6}$")
    private val blockNumber=Regex("^(?:block|blk\\.?|bl)?\\s*([0-9]{1,5}[A-Za-z]?)$",RegexOption.IGNORE_CASE)
    private val addressBlock=Regex("^(?:block|blk\\.?|bl)\\s*([0-9]{1,5}[A-Za-z]?)\\b",RegexOption.IGNORE_CASE)

    fun format(stop:Place):String {
        val postal=stop.postal.trim().takeIf { validPostal.matches(it) }
        val explicit=stop.block.trim()
        val block=blockNumber.matchEntire(explicit)?.groupValues?.get(1)
            ?: addressBlock.find(stop.address.trim())?.groupValues?.get(1)
        return when {
            block!=null && postal!=null -> "Block $block ($postal)"
            block!=null -> "Block $block"
            postal!=null -> postal
            explicit.isNotEmpty() -> explicit
            stop.address.isNotBlank() -> stop.address.trim()
            else -> "Destination"
        }
    }
}
