package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** B870 presentation-only regression checks; real small-screen screenshots remain device QA. */
class NavigationDestinationHeading870Test {
    private fun place(postal:String="460011",block:String="11",
        address:String="11 Bedok South Road",area:String="Bedok")=
        Place(postal,1.33,103.93,block,area,address)

    @Test fun showActualSavedBlockAndSixDigitPostalWithExpectedFormatting(){
        val heading=destinationHeading870(place())
        assertEquals("Block 11 (460011)",heading.full)
        assertEquals("Block 11",heading.blockLine)
        assertEquals("(460011)",heading.postalLine)
    }
    @Test fun maintainAlphanumericHdbBlocksWithoutInventingNumbers(){
        assertEquals("Block 123A (460011)",destinationHeading870(place(block="123a")).full)
        assertEquals("Block 11 (460011)",destinationHeading870(place(block="Blk 11")).full)
        assertEquals("Block 11 (460011)",destinationHeading870(place(block="Block 11")).full)
    }
    @Test fun fallbackToPostalWithoutGuessingHouseNumberFromAddress(){
        val heading=destinationHeading870(place(block="",address="11 Bedok South Road"))
        assertNull(heading.block)
        assertEquals("460011",heading.full)
        assertEquals("460011",destinationHeading870(place(block="Woodlands",address="11 Road")).full)
    }
    @Test fun explicitBlockInAddressCanBeUsedWithoutInventing(){
        assertEquals("Block 11 (460011)",
            destinationHeading870(place(block="",address="Blk 11, Bedok Road")).full)
    }
    @Test fun noSixDigitPostalFallsBackToReliableSavedDestinationName(){
        val heading=destinationHeading870(place(postal="",block="",address="Woodlands Checkpoint"))
        assertEquals("Woodlands Checkpoint",heading.full)
        assertNull(heading.postal)
        assertNull(heading.block)
    }
    @Test fun neverMisclassifyArbitraryPostalOrStreetTextAsHdbBlock(){
        val heading=destinationHeading870(place(postal="invalid",block="Tampines",
            address="45A Tampines Avenue 4"))
        assertEquals("45A Tampines Avenue 4",heading.full)
        assertNull(heading.block)
        assertNull(heading.postal)
    }
    @Test fun exactWidthOrMoreFitsAndNarrowDisplayWraps(){
        assertTrue(destinationHeadingFitsRow870(320,60,240,20))
        assertFalse(destinationHeadingFitsRow870(319,60,240,20))
        assertFalse(destinationHeadingFitsRow870(160,60,240,20))
        assertFalse(destinationHeadingFitsRow870(400,60,340,20))
        assertTrue(destinationHeadingFitsRow870(450,60,340,20))
    }
    @Test fun currentStopUpdatesWhenNavigationIsLaunchedForNextDelivery(){
        val first=destinationHeading870(place(postal="460011",block="11"))
        val next=destinationHeading870(place(postal="460012",block="12"))
        assertEquals("Block 11 (460011)",first.full)
        assertEquals("Block 12 (460012)",next.full)
        assertNotEquals(first.full,next.full)
    }
}
