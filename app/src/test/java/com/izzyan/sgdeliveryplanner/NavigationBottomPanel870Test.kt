package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #870 bottom-panel information fidelity and responsive typography source contract.
 * Real Android device and screenshot inspections are still required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class NavigationBottomPanel870Test {
    private fun place(
        postal:String="460011",block:String="11",
        address:String="11 Bedok North Road"
    )=Place(postal,1.327,103.923,block,"Bedok",address)

    @Test fun structuredBlockAndPostalAreShownTogetherWithoutGuessing() {
        val display=NavigationDestinationFormatting870.fromDestination(place())
        assertEquals("Block 11 (460011)",display.singleLine)
        assertEquals("Block 11",display.primaryLine)
        assertEquals("(460011)",display.secondaryLine)
    }

    @Test fun prelabelledBlockNumbersAreNeverDoubled() {
        val labels=listOf("11","Blk 11","BLK. 11","Block 11","block 11")
        for(block in labels) {
            assertEquals("Block 11 (460011)",
                NavigationDestinationFormatting870.fromDestination(place(block=block)).singleLine)
        }
        assertEquals("Block 123A (460011)",
            NavigationDestinationFormatting870.fromDestination(place(block="Blk 123A")).singleLine)
    }

    @Test fun preservesEntirePostalIncludingLeadingZeros() {
        val display=NavigationDestinationFormatting870.fromDestination(
            place(postal="000011",block="11A"))
        assertEquals("Block 11A (000011)",display.singleLine)
        assertEquals("(000011)",display.secondaryLine)
    }

    @Test fun neverTreatsStreetNumberAsBlockWhenFieldIsMissing() {
        val display=NavigationDestinationFormatting870.fromDestination(
            place(block="",address="11 Bedok North Road"))
        assertEquals("460011",display.singleLine)
        assertNull(display.blockNumber)
        assertNull(display.secondaryLine)
    }

    @Test fun explicitlyNamedAddressBlockIsTrustedWhenStructuredFieldMissing() {
        val display=NavigationDestinationFormatting870.fromDestination(
            place(block="",address="No. 19, BLK 66A, Hougang Avenue 1"))
        assertEquals("Block 66A (460011)",display.singleLine)
    }

    @Test fun invalidBlockOrPostalFallsBackToActualAvailableDestinationOnly() {
        val unknownBlock=NavigationDestinationFormatting870.fromDestination(
            place(postal="460011",block="Woodlands Checkpoint"))
        assertEquals("460011",unknownBlock.singleLine)
        assertNull(unknownBlock.blockNumber)
        val absentPostal=NavigationDestinationFormatting870.fromDestination(
            place(postal="",block="",address="Woodlands Checkpoint"))
        assertEquals("Woodlands Checkpoint",absentPostal.singleLine)
    }

    @Test fun newDeliveryStopChangesShownBlockAndPostal() {
        val first=NavigationDestinationFormatting870.fromDestination(place())
        val second=NavigationDestinationFormatting870.fromDestination(
            place(postal="650202",block="202"))
        assertEquals("Block 11 (460011)",first.singleLine)
        assertEquals("Block 202 (650202)",second.singleLine)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val intent=builtInNavigationIntent(context,place(postal="650202",block="202"))
        assertEquals("202",intent.getStringExtra("block"))
        assertEquals("650202",intent.getStringExtra("postal"))
    }

    @Test fun panelKeepsFullSizeTwentyFiveSpAndNeverUsesEllipsisForPostal() {
        val source=File("src/main/java/com/izzyan/sgdeliveryplanner/NavigationBottomPanel870.kt")
        assertTrue(source.exists())
        val text=source.readText()
        assertTrue(text.contains("fontSize=25.sp"))
        assertTrue(text.contains("FontWeight.ExtraBold"))
        assertTrue(text.contains("label.secondaryLine"))
        assertFalse(text.contains("TextOverflow.Ellipsis"))
        assertTrue(text.contains("heightIn(min=48.dp)"))
        assertTrue(text.contains("onClick=onDeliveryClick"))
    }
}
