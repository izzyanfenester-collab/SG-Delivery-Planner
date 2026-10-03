package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Exercise Android document writes, the real manifest provider, and pending-picker restoration. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScheduleExportAndroidTest {
    private lateinit var app: Application
    private lateinit var scheduler: TestCoroutineScheduler
    private val viewModels = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.cacheDir, "exports").deleteRecursively()
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun cleanUp() {
        viewModels.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
        File(app.cacheDir, "exports").deleteRecursively()
    }

    @Test
    fun shareUsesContentUriWithCorrectMimeFilenameClipDataAndReadOnlyGrant() {
        val file = createExcelSnapshot(app, route())
        val intent = excelShareIntent(app, file)
        val uri = requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(EXCEL_MIME_TYPE, intent.type)
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.excel-files", uri.authority)
        assertEquals("IZZ_Delivery_2026-10-03.xlsx", uri.lastPathSegment)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(file.name, intent.clipData?.description?.label)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        val info = requireNotNull(app.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA))
        assertFalse("Export files must not be exposed by an exported provider", info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(EXCEL_MIME_TYPE, app.contentResolver.getType(uri))
    }

    @Test
    fun fileProviderCannotExposeTokensOtherCacheFilesOrExternalPaths() {
        val authority = "${app.packageName}.excel-files"
        val privateFiles = listOf(
            File(app.filesDir, "onemap-token-test.xml"),
            File(app.cacheDir, "private-cache-test.xlsx"),
            File(app.cacheDir, "exports-other/private-cache-test.xlsx")
        )
        privateFiles.forEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("private")
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    FileProvider.getUriForFile(app, authority, file)
                }
            } finally {
                file.delete()
            }
        }
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse(requested.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(requested.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertFalse(requested.contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
    }

    @Test
    fun documentSaveCopiesTheExactWorkbookAndClosesTheDestinationBeforeReturning() {
        val file = createExcelSnapshot(app, route())
        val expected = file.readBytes()
        val uri = Uri.parse("content://documents.test/schedule.xlsx")
        val destination = TrackingOutputStream()
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, destination)

        writeExcelSnapshotToDocument(app, file.absolutePath, uri)

        assertTrue(destination.closed)
        assertArrayEquals(expected, destination.toByteArray())
        assertEquals('P'.code.toByte(), destination.toByteArray()[0])
        assertEquals('K'.code.toByte(), destination.toByteArray()[1])
    }

    @Test
    fun documentSavePropagatesProviderCloseFailuresInsteadOfClaimingSuccess() {
        val file = createExcelSnapshot(app, route())
        val uri = Uri.parse("content://documents.test/close-failure.xlsx")
        val destination = TrackingOutputStream(failOnClose = true)
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, destination)

        assertThrows(IOException::class.java) {
            writeExcelSnapshotToDocument(app, file.absolutePath, uri)
        }
        assertTrue(destination.closed)
        assertTrue("The provider can fail after accepting all workbook bytes", destination.size() > 0)
    }

    @Test
    fun documentSaveRejectsPathsOutsideTheExportCacheBeforeOpeningADestination() {
        val privateFile = File(app.filesDir, "private-workbook-test.xlsx").apply { writeText("private") }
        val uri = Uri.parse("content://documents.test/private.xlsx")
        val destination = TrackingOutputStream()
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, destination)
        try {
            assertNull(existingExcelSnapshot(app, privateFile.absolutePath))
            assertThrows(IOException::class.java) {
                writeExcelSnapshotToDocument(app, privateFile.absolutePath, uri)
            }
            assertEquals(0, destination.size())
            assertFalse(destination.closed)
        } finally {
            privateFile.delete()
        }
    }

    @Test
    fun restoredPickerSavesTheOriginalSnapshotEvenWhenTheActiveRouteChanges() {
        val state = SavedStateHandle()
        val initial = model(state)
        initial.prepare(route("005003"), share = false)
        await("Prepare the original workbook") { initial.saveRequest != null }
        val original = requireNotNull(initial.saveRequest)
        val expected = original.readBytes()
        initial.savePickerOpened()

        // These values are the small serializable state retained while Android owns the picker.
        val restoredState = SavedStateHandle(mapOf(
            "pending_excel_path" to state.get<String>("pending_excel_path"),
            "pending_excel_picker_opened" to state.get<Boolean>("pending_excel_picker_opened")
        ))
        val restored = model(restoredState)
        assertTrue(restored.busy)
        assertNull("An already-open picker must not open a second time", restored.saveRequest)
        restored.prepare(route("730099"), share = false)

        val uri = Uri.parse("content://documents.test/restored-snapshot.xlsx")
        val destination = TrackingOutputStream(onClose = {
            assertNotEquals("Excel file saved successfully", restored.message)
        })
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, destination)
        restored.saveResult(uri)
        await("Save the restored pending workbook") { !restored.busy }

        assertTrue(destination.closed)
        assertArrayEquals(expected, destination.toByteArray())
        assertEquals("Excel file saved successfully", restored.message)
        assertFalse(restored.isError)
        assertEquals("Excel file saved successfully", restored.notice)
        assertNull(restoredState.get<String>("pending_excel_path"))
    }

    @Test
    fun failedDocumentWriteClearsPendingStateAndShowsAnErrorWithoutSuccessNotice() {
        val state = SavedStateHandle()
        val export = model(state)
        export.prepare(route(), share = false)
        await("Prepare the workbook for a failed save") { export.saveRequest != null }
        export.savePickerOpened()
        val uri = Uri.parse("content://documents.test/provider-failed.xlsx")
        val destination = TrackingOutputStream(failOnClose = true)
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, destination)

        export.saveResult(uri)
        await("Handle the document-provider failure") { !export.busy }

        assertTrue(export.isError)
        assertEquals("The Excel file could not be saved. Choose another location and try again.", export.message)
        assertNotEquals("Excel file saved successfully", export.notice)
        assertNull(state.get<String>("pending_excel_path"))
        assertFalse(state.get<Boolean>("pending_excel_picker_opened") == true)
    }

    @Test
    fun pickerCancellationReleasesThePendingExportWithoutShowingSuccess() {
        val state = SavedStateHandle()
        val export = model(state)
        export.prepare(route(), share = false)
        await("Prepare the workbook for cancellation") { export.saveRequest != null }
        val file = requireNotNull(export.saveRequest)
        export.savePickerOpened()

        export.saveResult(null)
        await("Remove the cancelled snapshot") { !file.exists() }

        assertFalse(export.busy)
        assertEquals("", export.message)
        assertNull(export.notice)
        assertNull(state.get<String>("pending_excel_path"))
    }

    @Test
    fun notYetOpenedPickerIsRestoredAndRecentSharesNeverOverwriteEachOther() {
        val original = createExcelSnapshot(app, route("005003"))
        val originalBytes = original.readBytes()
        val replacement = createExcelSnapshot(app, route("730099"))
        assertNotEquals(original.absolutePath, replacement.absolutePath)
        assertArrayEquals(originalBytes, original.readBytes())

        val restored = model(SavedStateHandle(mapOf(
            "pending_excel_path" to original.absolutePath,
            "pending_excel_picker_opened" to false
        )))
        assertEquals(original, restored.saveRequest)
        assertTrue(restored.busy)
    }

    @Test
    fun processDeathDuringDocumentWriteResumesTheImmutableWorkbookAndTruncatesPartialOutput() {
        val source = createExcelSnapshot(app, route("005003"))
        val expected = source.readBytes()
        val destinationFile = File(app.cacheDir, "exports/restored-document.xlsx").apply {
            // A killed writer can leave arbitrary partial bytes or a previously longer document.
            writeBytes(ByteArray(expected.size * 3) { 0x37 })
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.excel-files", destinationFile)
        val state = SavedStateHandle(mapOf(
            "pending_excel_path" to source.absolutePath,
            "pending_excel_picker_opened" to true,
            "pending_excel_destination_uri" to uri.toString(),
            "pending_excel_permission_flags" to 0
        ))

        val restored = model(state)
        assertTrue(restored.busy)
        assertNull("A write in progress resumes without reopening the picker", restored.saveRequest)
        restored.prepare(route("730099"), share = false)
        restored.saveResult(uri) // A restored ActivityResult callback cannot start a second writer.
        await("Resume a document write after process recreation") { !restored.busy }

        assertArrayEquals(expected, destinationFile.readBytes())
        assertEquals("Excel file saved successfully", restored.message)
        assertFalse(restored.isError)
        assertNull(state.get<String>("pending_excel_path"))
        assertNull(state.get<String>("pending_excel_destination_uri"))
    }

    @Test
    fun lostDocumentPermissionDuringRestorationClearsBusyStateAndNeverReportsSuccess() {
        val source = createExcelSnapshot(app, route())
        val uri = Uri.parse("content://documents.test/revoked-permission.xlsx")
        var destinationClosed = false
        val revokedDestination = object : OutputStream() {
            override fun write(value: Int) {
                throw SecurityException("Simulated revoked document write grant")
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                throw SecurityException("Simulated revoked document write grant")
            }
            override fun close() { destinationClosed = true }
        }
        Shadows.shadowOf(app.contentResolver).registerOutputStream(uri, revokedDestination)
        val state = SavedStateHandle(mapOf(
            "pending_excel_path" to source.absolutePath,
            "pending_excel_picker_opened" to true,
            "pending_excel_destination_uri" to uri.toString(),
            "pending_excel_permission_flags" to 0
        ))

        val restored = model(state)
        await("Handle a revoked document grant during restore") { !restored.busy }

        assertTrue(destinationClosed)
        assertTrue(restored.isError)
        assertEquals(
            "Access to the selected save location is no longer available. Export the schedule again and choose a location.",
            restored.message
        )
        assertNotEquals("Excel file saved successfully", restored.notice)
        assertNull(state.get<String>("pending_excel_path"))
        assertNull(state.get<String>("pending_excel_destination_uri"))
        assertFalse(state.get<Boolean>("pending_excel_picker_opened") == true)
    }

    @Test
    fun documentPickerKeepsReturnedUriGrantsAndCancelReturnsNoDestination() {
        val contract = CreateExcelDocument()
        val intent = contract.createIntent(app, "IZZ_Delivery_2026-10-03.xlsx")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(EXCEL_MIME_TYPE, intent.type)
        assertEquals("IZZ_Delivery_2026-10-03.xlsx", intent.getStringExtra(Intent.EXTRA_TITLE))
        val expectedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertEquals(expectedFlags, intent.flags and expectedFlags)
        val uri = Uri.parse("content://documents.test/persisted.xlsx")
        val result = requireNotNull(contract.parseResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(expectedFlags)))
        assertEquals(uri, result.uri)
        assertEquals(expectedFlags, result.permissionFlags)
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent()))
    }

    private fun model(state: SavedStateHandle): ScheduleExportViewModel =
        ScheduleExportViewModel(app, state).also { model ->
            viewModels += ViewModelStore().apply { put("export", model) }
        }

    /** IO uses real files; pump the controlled main dispatcher until its result arrives. */
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out: $description")
    }

    private fun route(postal: String = "005003"): Plan = schedule(
        listOf(depot.copy(postal = postal, block = "8", area = "Test area")),
        listOf(Leg(4.2, 600.0, 60.0), Leg(4.8, 660.0, 80.0)),
        LocalDateTime.of(2026, 10, 3, 10, 0),
        8,
        "Normal Traffic",
        emptyList()
    )

    private class TrackingOutputStream(
        private val failOnClose: Boolean = false,
        private val onClose: () -> Unit = {}
    ) : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            onClose()
            closed = true
            super.close()
            if (failOnClose) throw IOException("Simulated document-provider flush failure")
        }
    }
}
