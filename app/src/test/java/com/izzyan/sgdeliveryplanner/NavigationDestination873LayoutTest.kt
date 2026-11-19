package com.izzyan.sgdeliveryplanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Actual Compose panel rendering in simulated Android native graphics. No map/GPS engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class NavigationDestination873LayoutTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    // PixelCopy requires a real device compositor. Draw the actual Android view
    // onto a native Canvas instead when running this rendering test in Robolectric.
    private fun savePanel(file:File) {
        val bounds=compose.onNodeWithTag("gps-bottom-panel").fetchSemanticsNode().boundsInRoot
        file.parentFile?.mkdirs()
        compose.runOnIdle {
            val view=compose.activity.findViewById<View>(android.R.id.content)
            val full=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            val panel=Bitmap.createBitmap(full,bounds.left.toInt(),bounds.top.toInt(),
                bounds.width.toInt(),bounds.height.toInt())
            file.outputStream().use{panel.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    private fun textLayout(tag:String):TextLayoutResult {
        val results=mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag,useUnmergedTree=true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(results)}
        assertEquals(1,results.size)
        return results.single()
    }
    private fun assertReadableDestination() {
        for(tag in listOf("gps-destination-primary","gps-destination-postal")) {
            compose.onNodeWithTag(tag,useUnmergedTree=true).assertIsDisplayed()
            val layout=textLayout(tag)
            assertFalse("$tag clipped",layout.hasVisualOverflow)
            assertEquals(26.sp,layout.layoutInput.style.fontSize)
            assertEquals(FontWeight.ExtraBold,layout.layoutInput.style.fontWeight)
        }
        val arrival=compose.onNodeWithTag("gps-arrival").fetchSemanticsNode().boundsInRoot
        val destination=compose.onNodeWithTag("gps-destination").fetchSemanticsNode().boundsInRoot
        assertFalse("Arrival overlaps destination",arrival.overlaps(destination))
        val header=compose.onNodeWithTag("gps-destination-header").fetchSemanticsNode().boundsInRoot
        assertEquals(header.right,destination.right,1.5f)
        compose.onNodeWithText("12:30 PM").assertIsDisplayed()
        compose.onNodeWithTag("gps-remaining-summary").assertIsDisplayed()
        val summary=textLayout("gps-remaining-summary")
        assertFalse("Remaining summary clipped: size=${summary.size} lines=${summary.lineCount} " +
            "overflowWidth=${summary.didOverflowWidth} overflowHeight=${summary.didOverflowHeight} " +
            "constraints=${summary.layoutInput.constraints}",summary.hasVisualOverflow)
        compose.onNodeWithTag("gps-delivery-button").assertIsDisplayed()
    }
    @Test fun narrowWidthsAndLargeFontScalesWrapWithoutClippingOrOverlap() {
        var width by mutableIntStateOf(360)
        var fontScale by mutableFloatStateOf(1f)
        val heading=destinationHeading873(Place("460011",1.33,103.93,"11","Bedok","Bedok Road"),
            "Blk 11 #05-123 Singapore 460011")
        var clicked=0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,fontScale)) {
                IzzDeliveryTheme(true) {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) {
                        Surface(Modifier.width(width.dp),color=PremiumNavy) {
                            NavigationDestinationPanel873(heading,"12:30 PM","5.5 km  ·  10 min",null,{clicked++})
                        }
                    }
                }
            }
        }
        for((w,scale) in listOf(320 to 1f,360 to 1f,411 to 1f,320 to 1.3f,320 to 1.5f,280 to 2f)) {
            compose.runOnIdle{width=w;fontScale=scale}
            compose.waitForIdle()
            println("B873 checking width=$w fontScale=$scale")
            val summary=textLayout("gps-remaining-summary")
            println("B873 summary size=${summary.size} lines=${summary.lineCount} " +
                "overflowWidth=${summary.didOverflowWidth} overflowHeight=${summary.didOverflowHeight} " +
                "constraints=${summary.layoutInput.constraints}")
            val diagnostic=File("build/reports/b873-ui/layout-${w}-${scale}.png")
            savePanel(diagnostic)
            assertReadableDestination()
            println("B873 NATIVE_COMPOSE width=$w fontScale=$scale destination=${heading.full} clipped=false overlap=false font=26sp ExtraBold")
            if(w==360 && scale==1f) {
                val file=File("build/reports/b873-ui/SG-Delivery-B873-Bottom-Panel.png")
                savePanel(file)
                assertTrue(file.length()>1000)
            }
        }
        compose.onNodeWithTag("gps-delivery-button").performClick()
        compose.runOnIdle{assertEquals(1,clicked)}
    }
    @Test fun switchingDuplicatePostalOrdersUpdatesUnitAndThenRemovesItWhenUnavailable() {
        val place=Place("460011",1.33,103.93,"11","Bedok","Bedok Road")
        var address by mutableStateOf("Blk 11 #05-123 Singapore 460011")
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,1f)) {
                IzzDeliveryTheme(true) {
                    Surface(Modifier.width(360.dp),color=PremiumNavy) {
                        NavigationDestinationPanel873(destinationHeading873(place,address),
                            "12:30 PM","5.5 km  ·  10 min",null,{})
                    }
                }
            }
        }
        compose.onNodeWithText("Block 11, #05-123").assertIsDisplayed()
        compose.runOnIdle{address="Blk 11 #08-456 Singapore 460011"}
        compose.onNodeWithText("Block 11, #08-456").assertIsDisplayed()
        compose.onNodeWithText("Block 11, #05-123").assertDoesNotExist()
        compose.runOnIdle{address="Blk 11 Singapore 460011"}
        compose.onNodeWithText("Block 11").assertIsDisplayed()
        compose.onNodeWithText("Block 11, #08-456").assertDoesNotExist()
        compose.onNodeWithText("(460011)").assertIsDisplayed()
    }
    @Test fun longBlockAndAlphanumericUnitWrapAndKeepAllNumbersVisible() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,1.5f)) {
                IzzDeliveryTheme(true) {
                    Surface(Modifier.width(280.dp),color=PremiumNavy) {
                        NavigationDestinationPanel873(DestinationHeading873("123456A","#123-12345A","650203",null),
                            "12:30 PM","15.5 km  ·  45 min","Local traffic estimate: Slow",{})
                    }
                }
            }
        }
        assertReadableDestination()
        assertTrue(textLayout("gps-destination-primary").lineCount>=2)
        compose.onNodeWithText("Block 123456A, #123-12345A").assertIsDisplayed()
        compose.onNodeWithText("(650203)").assertIsDisplayed()
    }
}
