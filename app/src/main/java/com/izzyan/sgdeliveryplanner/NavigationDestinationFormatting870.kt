package com.izzyan.sgdeliveryplanner

/**
 * Build #870: one current-navigation-destination snapshot, never inferred from
 * the first digits of a street address or from an unrelated planned stop.
 */
internal data class NavigationDestinationLabel870(
    val blockNumber:String?,
    val postalCode:String?,
    val fallbackName:String?
) {
    val blockLine:String? get()=blockNumber?.let { "Block $it" }
    val singleLine:String get()=when {
        blockLine!=null && postalCode!=null -> "$blockLine ($postalCode)"
        blockLine!=null -> requireNotNull(blockLine)
        postalCode!=null -> postalCode
        else -> fallbackName.orEmpty()
    }
    val primaryLine:String get()=blockLine ?: postalCode ?: fallbackName.orEmpty()
    val secondaryLine:String? get()=if(blockLine!=null && postalCode!=null) "($postalCode)" else null
}

internal object NavigationDestinationFormatting870 {
    private val confirmedBlock = Regex("[0-9]{1,5}[A-Za-z]?(?:[/-][0-9]{1,5}[A-Za-z]?)?")
    private val blockPrefix = Regex("(?i)^(?:block|blk\\.?)\\s*#?\\s*")
    private val explicitlyNamedBlock =
        Regex("(?i)\\b(?:block|blk\\.?)\\s*#?\\s*([0-9]{1,5}[a-z]?(?:[/-][0-9]{1,5}[a-z]?)?)\\b")

    fun validBlockNumber(raw:String?):String? {
        val trimmed=raw.orEmpty().trim()
        if(trimmed.isEmpty())return null
        val number=trimmed.replace(blockPrefix,"").trim()
        return number.takeIf { confirmedBlock.matches(it) }
    }

    fun fromDestination(place:Place):NavigationDestinationLabel870 {
        val postcode=place.postal.trim().takeIf { Regex("[0-9]{6}").matches(it) }
        // The explicit structured block field wins. An explicitly labelled
        // "Blk 11" in the address is reliable; an ordinary "11 Foo Road" isn't.
        val block=validBlockNumber(place.block)
            ?: explicitlyNamedBlock.find(place.address)
                ?.groupValues?.getOrNull(1)?.let(::validBlockNumber)
        val fallback=if(block==null && postcode==null)
            place.block.trim().ifBlank { place.address.trim() }.takeIf { it.isNotEmpty() }
        else null
        return NavigationDestinationLabel870(block,postcode,fallback)
    }
}
