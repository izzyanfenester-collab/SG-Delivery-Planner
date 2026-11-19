package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class NavigationDestination873Test {
    private fun place(postal:String="460011",block:String="11",address:String="11 Bedok South Road")=
        Place(postal,1.33,103.93,block,"Bedok",address)
    @Test fun allRequestedAddressExamplesUseActualUnits() {
        val rows=listOf(
            Triple(place(),"Blk 11, #05-123, Singapore 460011","Block 11, #05-123 (460011)"),
            Triple(place("650203","203"),"Block 203, #12-456, Singapore 650203","Block 203, #12-456 (650203)"),
            Triple(place("560088","88"),"Blk 88, Singapore 560088","Block 88 (560088)"),
            Triple(place("730512","512"),"Block 512, #01-02, Singapore 730512","Block 512, #01-02 (730512)"))
        for((p,address,wanted) in rows)assertEquals(wanted,destinationHeading873(p,address).full)
    }
    @Test fun unitOnlyAndPostalOnlyNeverInventABlock() {
        val p=place(block="",address="11 Ordinary Road")
        assertEquals("#05-123 (460011)",destinationHeading873(p,"11 Ordinary Road #05-123").full)
        assertEquals("460011",destinationHeading873(p,"11 Ordinary Road").full)
        assertEquals("Block 11, #05-123 (460011)",
            destinationHeading873(p,"Blk 11 #05-123 Singapore 460011").full)
    }
    @Test fun standardUnitsAndAlphanumericSuffixArePreservedExactly() {
        for(unit in listOf("#01-01","#05-123","#12-456","#18-02","#01-02A","#01-02a","#B1-07")) {
            assertEquals(unit,deliveryUnitNumber873("Blk 11, $unit, Singapore 460011"))
            assertEquals("Block 11, $unit (460011)",destinationHeading873(place(),"Blk 11, $unit").full)
        }
    }
    @Test fun roadFloorParcelCountAndPostalAreNotUnits() {
        for(text in listOf("11 Road, 5th Floor, 2 parcels, Singapore 460011",
            "Blk 11 #05 Singapore 460011","Block 11 Unit unknown Singapore 460011",
            "Blk 11 parcel #01-02 Singapore 460011","Block 11 Qty: #01-02 Singapore 460011",
            "11 Road 05-123, Singapore 460011","11 Road #05-460011 Singapore 460011",
            "Blk 11 Level: #05-06 Singapore 460011","Blk 11 https://example.org/#05-123 Singapore 460011")) {
            assertNull(text,deliveryUnitNumber873(text))
        }
    }
    @Test fun ambiguousMultipleUnitsAreNotGuessed() {
        assertNull(deliveryUnitNumber873("Blk 11 #05-123 / #08-456 Singapore 460011"))
        assertEquals("#05-123",deliveryUnitNumber873("Blk 11 #05-123 (unit #05-123)"))
    }
    @Test fun leadingZerosAndBlankActualAddressAreHandledWithoutPostalLookup() {
        assertEquals("Block 11, #01-02 (010011)",
            destinationHeading873(place("010011"),"Blk 11 #01-02").full)
        assertNull(destinationHeading873(place(address="Blk 11 #09-999"),"").unit)
        assertEquals("Block 11 (460011)",destinationHeading873(place(),"").full)
    }
    @Test fun customEndAndCheckpointKeepTheirOriginalHeadingAndNeverExtractUnitFromName() {
        for(p in listOf(place("END","","Home #05-123"),
            place("","","Woodlands Checkpoint"),place("519001","","Custom End #05-123"))) {
            assertEquals(destinationHeading870(p).full,destinationHeading873(p,null).full)
            assertNull(destinationHeading873(p,null).unit)
        }
    }
    @Test fun columnFitIncludesScaledTextMeasurementsAndNeverRequiresShrinking() {
        assertTrue(destinationColumnsFit873(320,120,188,12))
        assertFalse(destinationColumnsFit873(319,120,188,12))
        assertFalse(destinationColumnsFit873(280,240,220,12))
        assertTrue(destinationColumnsFit873(411,140,160,12))
    }
}
