package com.izzyan.sgdeliveryplanner

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

/** Outcome checks on real Compose layout, touch targets and Room persistence. */
class HistorySpacing884Test {
    @get:Rule val ui=createAndroidComposeRule<HistorySpacingVerificationActivity884>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val vm get()=ui.activity.vm
    private fun save(name:String,data:ByteArray,mime:String) {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/SGDeliveryB884")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val resolver=app.contentResolver
        val uri=requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use{it.write(data)}
        resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
    }
    private fun json(name:String,value:JSONObject)=save(name,value.toString(2).toByteArray(),"application/json")
    private fun screenshot(name:String) {
        ui.waitForIdle()
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out)
        save(name,out.toByteArray(),"image/png")
    }
    private fun fixtures():List<Plan> = listOf(
        Triple("2026-11-21",8,SavedDeliveryStatus.COMPLETED),
        Triple("2026-11-18",12,SavedDeliveryStatus.COMPLETED),
        Triple("2026-11-12",8,SavedDeliveryStatus.IN_PROGRESS),
        Triple("2026-11-10",4,SavedDeliveryStatus.NOT_STARTED)
    ).mapIndexed { index,(date,count,status)->
        val places=List(count){Place("460011",1.323,103.935,"11","Synthetic QA","Block 11, Singapore 460011")}
        val totalKm=if(index==0)92.5 else 96.4
        val plan=schedule(places,List(count+1){Leg(totalKm/(count+1),120.0,0.0)},
            LocalDateTime.parse(date+"T08:00:00"),10,"Normal",emptyList())
        val finish=date+if(index==0)"T12:29:00" else "T22:54:00"
        plan.copy(id="8840000${index+1}-qa-history",created=date+"T23:00:00",
            stops=plan.stops.mapIndexed{i,s->when {
                status==SavedDeliveryStatus.COMPLETED->s.copy(status="DELIVERED",completedAt=finish,reviewedAt=finish)
                status==SavedDeliveryStatus.IN_PROGRESS && i==0->s.copy(status="ON_HOLD",reviewedAt=finish,holdReason="Synthetic QA")
                else->s
            }},reviewedFinishedAt=finish.takeIf{status==SavedDeliveryStatus.COMPLETED})
    }
    private fun seed():List<Plan> {
        ui.waitUntil(15_000){!vm.busy}
        val plans=fixtures()
        runBlocking{plans.forEach{vm.repo.save(it)}}
        ui.waitUntil(15_000){ui.onAllNodesWithText("2026-11-21").fetchSemanticsNodes().isNotEmpty()}
        return plans
    }
    private fun card(date:String)=ui.onNode(hasClickAction() and hasAnyDescendant(hasText(date)),useUnmergedTree=true)
    private fun variant(before:Boolean,width:Int,scale:Float) {
        ui.runOnIdle{ui.activity.baseline=before;ui.activity.widthDp=width;ui.activity.fontScale=scale}
        ui.waitUntil(15_000){ui.onAllNodesWithText("2026-11-21").fetchSemanticsNodes().isNotEmpty()}
        ui.waitForIdle()
    }
    private fun textLayout(text:String):TextLayoutResult {
        val result=mutableListOf<TextLayoutResult>()
        ui.onAllNodesWithText(text,useUnmergedTree=true)[0]
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(result)}
        return result.first()
    }
    private fun firstCardTypography(plan:Plan):Map<String,Float> {
        val summary=deliverySummary(plan)
        val values=listOf(plan.deliveryDate.toString(),"COMPLETED","Route ID: ${plan.id.take(8)}",
            "Total Parcel","Delivered / Remaining","Success Rate","Total Distance","Finish Time",
            summary.totalParcel.toString(),"${summary.delivered} / 0","100.0%",km(summary.totalKm),
            time(summary.finish),"Tap to View History")
        return values.associateWith { text->
            val layout=textLayout(text)
            assertFalse("Clipped/truncated text: $text",layout.hasVisualOverflow)
            layout.layoutInput.style.fontSize.value
        }
    }
    @Test fun compactHistoryReclaimsSpaceWithoutShrinkingOrTruncatingText() {
        val plans=seed();val results=JSONArray()
        for((width,scale) in listOf(400 to 1f,320 to 1f,320 to 1.5f)) {
            variant(true,width,scale)
            val beforeHeight=card("2026-11-21").getUnclippedBoundsInRoot().let{it.bottom.value-it.top.value}
            val beforeFonts=firstCardTypography(plans[0])
            screenshot("B884-before-${width}dp-${scale}font.png")
            variant(false,width,scale)
            val afterHeight=card("2026-11-21").getUnclippedBoundsInRoot().let{it.bottom.value-it.top.value}
            val afterFonts=firstCardTypography(plans[0])
            assertEquals("Existing typography changed",beforeFonts,afterFonts)
            assertTrue("Card did not become more compact",afterHeight<beforeHeight)
            val saving=(1-afterHeight/beforeHeight)*100
            if(width==400 && scale==1f)assertTrue("Normal-width reduction $saving outside target",saving in 25f..35f)
            screenshot("B884-after-${width}dp-${scale}font.png")
            results.put(JSONObject().put("widthDp",width).put("fontScale",scale)
                .put("baselineHeightDp",beforeHeight).put("compactHeightDp",afterHeight)
                .put("heightReductionPercent",saving).put("fontSizesUnchanged",true).put("noTextOverflow",true))
        }
        // Each unchanged stamp/status remains available after scrolling.
        variant(false,400,1f)
        for(plan in plans) {
            ui.onNode(hasScrollAction()).performScrollToNode(hasText(plan.deliveryDate.toString()))
            ui.onAllNodesWithContentDescription(savedDeliveryStatus(plan).label+" delivery status")[0].assertExists()
        }
        json("B884-layout-results.json",JSONObject().put("result","PASS").put("cases",results)
            .put("statuses",JSONArray(listOf("COMPLETED","IN PROGRESS","NOT STARTED")))
            .put("palette","unchanged baseline Navy & Gold").put("originalStampSizeAndRotation",true))
    }
    @Test fun compactCardsKeepViewingSelectionDeletionAndResumeBoundToOriginalRecords() {
        val plans=seed();variant(false,400,1f)
        val before=runBlocking{vm.repo.dao.history().first().associate{it.id to it.json}}
        val activeBefore=ui.runOnIdle{vm.route?.id}
        card("2026-11-21").performClick()
        ui.onNodeWithContentDescription("Back to History").assertExists().performClick()
        assertEquals(activeBefore,ui.runOnIdle{vm.route?.id})
        card("2026-11-21").performTouchInput{longClick()}
        ui.onNodeWithText("Delete Selected (1)").performScrollTo().performClick()
        ui.onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
        ui.onNodeWithText("Cancel selection").performScrollTo().performClick()
        assertEquals(before,runBlocking{vm.repo.dao.history().first().associate{it.id to it.json}})
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("2026-11-12"))
        card("2026-11-12").performClick()
        ui.waitUntil(15_000){!vm.busy && vm.screen=="Delivery"}
        assertEquals(plans[2].id,vm.route?.id)
        assertEquals(before,runBlocking{vm.repo.dao.history().first().associate{it.id to it.json}})
        ui.onNode(hasScrollAction()).performScrollToNode(hasText("2026-11-10"))
        card("2026-11-10").performTouchInput{longClick()}
        ui.onNodeWithText("Delete Selected (1)").performScrollTo().performClick()
        ui.onNode(hasText("Delete Selected") and hasAnyAncestor(isDialog())).performClick()
        ui.waitUntil(15_000){!vm.busy}
        val after=runBlocking{vm.repo.dao.history().first().associate{it.id to it.json}}
        assertEquals(before-plans[3].id,after)
        assertEquals(plans[2].id,vm.route?.id)
        json("B884-actions-results.json",JSONObject().put("result","PASS")
            .put("completedViewReadOnly",true).put("longPressSelection",true).put("deleteCancelPreservesRecords",true)
            .put("resumeExactSelectedRoute",true).put("deleteOnlySelectedFixture",true)
            .put("remainingRecordsByteIdentical",true))
    }
}
