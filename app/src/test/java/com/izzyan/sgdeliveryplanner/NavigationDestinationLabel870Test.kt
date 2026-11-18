package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class NavigationDestinationLabel870Test {
    private fun stop(postal:String="460011",block:String="",address:String="")=
        Place(postal,1.31,103.8,block,"",address)

    @Test fun explicitBlockAndPostalUseLargeDestinationLabel() {
        assertEquals("Block 11 (460011)",NavigationDestinationLabel870.format(stop(block="11")))
        assertEquals("Block 11 (460011)",NavigationDestinationLabel870.format(stop(block="Blk 11")))
        assertEquals("Block 12345A (460011)",NavigationDestinationLabel870.format(stop(block="12345A")))
    }
    @Test fun knownAddressBlockIsAllowedButPostalNeverInventsBlock() {
        assertEquals("Block 11 (460011)",NavigationDestinationLabel870.format(stop(address="Blk 11 Bedok Road")))
        assertEquals("460011",NavigationDestinationLabel870.format(stop()))
        assertEquals("460011",NavigationDestinationLabel870.format(stop(block="Woodlands Checkpoint")))
    }
    @Test fun unknownPostalShowsRealDestinationNameWithoutInventingNumber() {
        assertEquals("Woodlands Checkpoint",NavigationDestinationLabel870.format(stop("","Woodlands Checkpoint")))
        assertEquals("Destination",NavigationDestinationLabel870.format(stop("","")))
    }
    @Test fun newStopUsesItsOwnBlockAndPostalNotCachedPreviousStop() {
        assertEquals("Block 11 (460011)",NavigationDestinationLabel870.format(stop(block="11")))
        assertEquals("Block 77 (560077)",NavigationDestinationLabel870.format(stop("560077","77")))
    }
}
