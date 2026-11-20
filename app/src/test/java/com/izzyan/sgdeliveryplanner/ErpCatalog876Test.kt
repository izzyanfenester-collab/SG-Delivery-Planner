package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ErpCatalog876Test {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun catalog()=ErpStore876(context).load()
    @Before fun clearNewPreference(){context.getSharedPreferences("sg_delivery_navigation_appearance",0).edit().remove("show_erp_gantry_icons_876").commit()}

    @Test fun all106RecordsAnd101SitesLoadFromOfflineSqlite(){val c=catalog();assertEquals(106,c.records.size);assertEquals(101,c.sites.size);assertEquals(106,c.records.map{it.uniqueId}.toSet().size)}
    @Test fun everyWorkbookCellRetainsItsExactOriginalValue(){
        var xml:ByteArray?=null
        context.assets.open("erp_source_876.xlsx").use{input->ZipInputStream(input).use{zip->
            while(true){val e=zip.nextEntry?:break;if(e.name=="xl/worksheets/sheet1.xml")xml=zip.readBytes()}
        }}
        val factory=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=true;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
        val doc=factory.newDocumentBuilder().parse(requireNotNull(xml).inputStream())
        val ns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val actual=catalog().records.associateBy{it.sourceRow}
        val rows=doc.getElementsByTagNameNS(ns,"row");var verified=0
        for(i in 1 until rows.length){
            val row=rows.item(i) as org.w3c.dom.Element;val values=mutableMapOf<String,String>()
            val cells=row.getElementsByTagNameNS(ns,"c")
            for(j in 0 until cells.length){val cell=cells.item(j) as org.w3c.dom.Element
                val tag=if(cell.getAttribute("t")=="inlineStr")"t" else "v"
                val texts=cell.getElementsByTagNameNS(ns,tag)
                values[cell.getAttribute("r").replace(Regex("[0-9]"),"")]=(0 until texts.length).joinToString(""){texts.item(it).textContent}
            }
            val r=requireNotNull(actual[row.getAttribute("r").toInt()])
            assertEquals(values["A"],r.uniqueId);assertEquals(values["B"].orEmpty(),r.gantryNo)
            assertEquals(values["C"],r.latitudeText);assertEquals(values["D"],r.longitudeText);assertEquals(values["E"],r.googleMaps)
            verified++
        }
        assertEquals(106,verified)
        println("B876 EXCEL_SQLITE_EXACT 106/106 rows; raw coordinates, IDs, gantry values and links identical")
    }
    @Test fun allFiveSharedCoordinateGroupsRetainBothIds(){
        val c=catalog();val groups=c.sites.filter{it.records.size>1}.map{it.records.map{r->r.uniqueId}.toSet()}.toSet()
        assertEquals(setOf(setOf("627","838"),setOf("645","839"),setOf("646","840"),setOf("705","717"),setOf("757","331")),groups)
        assertEquals(106,c.sites.sumOf{it.records.size})
    }
    @Test fun providedExample628KeepsAllDigitsAndActualGantry65(){
        val r=catalog().records.single{it.uniqueId=="628"}
        assertEquals("1.3286531600046",r.latitudeText);assertEquals("103.911592577984",r.longitudeText);assertEquals("65",r.gantryLabel)
    }
    @Test fun missingAndUnkGantryValuesRemainUninvented(){
        val rows=catalog().records
        assertEquals(19,rows.count{it.gantryNo.isEmpty()})
        assertEquals(1,rows.count{it.gantryNo.isNotEmpty()&&it.gantryNo.isBlank()})
        assertEquals(20,rows.count{it.gantryNo.isBlank()});assertEquals(6,rows.count{it.gantryNo=="UNK"})
        rows.filter{it.gantryNo.isBlank()||it.gantryNo=="UNK"}.forEach{assertEquals("Not specified",it.gantryLabel)}
    }
    @Test fun everyStoredGoogleMapsLinkUsesTheActualUnroundedCoordinates(){catalog().records.forEach{assertTrue(it.supportsMapsLink);assertTrue(it.googleMaps.endsWith(it.latitudeText+","+it.longitudeText))}}
    @Test fun unsafeUrlCannotBeOpened(){val r=catalog().records.first();assertFalse(r.copy(googleMaps="javascript:alert(1)").supportsMapsLink);assertFalse(r.copy(googleMaps="https://maps.google.com.evil.example/?q=${r.latitudeText},${r.longitudeText}").supportsMapsLink)}
    @Test fun reversedCoordinatesRejectTheWholeDataset(){val c=catalog();val r=c.records.first();try{c.copy(records=listOf(r.copy(latitudeText=r.longitudeText,longitudeText=r.latitudeText))+c.records.drop(1));fail("Swapped coordinates must fail")}catch(_:IllegalArgumentException){}}
    @Test fun missingRecordCannotBeSilentlySkipped(){val c=catalog();try{c.copy(records=c.records.drop(1));fail("Missing record must fail")}catch(_:IllegalArgumentException){}}
    @Test fun duplicateIdCannotOverwriteAnotherRecord(){val c=catalog();try{c.copy(records=listOf(c.records.first().copy(uniqueId=c.records[1].uniqueId))+c.records.drop(1));fail("Duplicate ID must fail")}catch(_:IllegalArgumentException){}}
    @Test fun coldReconstructionAndRestartKeepAllDataWithoutNetwork(){
        val a=catalog();File(context.noBackupFilesDir,"erp-876").deleteRecursively();val b=catalog()
        assertEquals(a,b);assertEquals(106,b.records.size);assertEquals(101,ErpStore876(context).load().sites.size)
    }
    @Test fun preferenceDefaultsOnPersistsAndDoesNotChangeOtherSettings(){
        val original=context.getSharedPreferences("sg_delivery_navigation_appearance",0)
        original.edit().putString("existing-appearance-test","night").commit()
        assertTrue(ErpPreferences876(context).visible());ErpPreferences876(context).setVisible(false)
        assertFalse(ErpPreferences876(context).visible());assertEquals("night",original.getString("existing-appearance-test",null))
        ErpPreferences876(context).setVisible(true);assertTrue(ErpPreferences876(context).visible())
    }
    @Test fun originalWorkbookAndReferenceImageBytesMatchTheManifest(){
        val m=context.assets.open("sg_erp_manifest_876.json").bufferedReader().use{JSONObject(it.readText())}
        assertEquals(m.getString("workbook_sha256"),context.assets.open("erp_source_876.xlsx").use{ErpStore876.sha(it.readBytes())})
        assertEquals(m.getString("reference_sha256"),context.assets.open("erp_reference_876.jpg").use{ErpStore876.sha(it.readBytes())})
    }
    @Test fun markerPreservesOriginalAspectRatioAndTransparentSurroundings(){
        val b=ErpMapIcon876.bitmap(context);val d=context.resources.displayMetrics.density
        assertEquals(46f,b.width/d,1f);assertEquals(21.5f,b.height/d,1f)
        assertEquals(0,Color.alpha(b.getPixel(0,0)));assertEquals(context.resources.displayMetrics.densityDpi,b.density)
    }
}
