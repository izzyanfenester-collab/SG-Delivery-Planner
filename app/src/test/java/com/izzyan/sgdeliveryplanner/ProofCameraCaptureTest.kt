package com.izzyan.sgdeliveryplanner

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProofCameraCaptureTest {
    @org.junit.Before fun resetProviderRoots() {
        // Robolectric assigns a new app cache directory per test, unlike an Android process.
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply {isAccessible=true}
        (cache.get(null) as MutableMap<*,*>).clear()
    }
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val models=ViewModelStore()
    @Before fun setup() {Dispatchers.setMain(UnconfinedTestDispatcher())}
    @After fun cleanup() {models.clear();Dispatchers.resetMain()}
    @Test fun cameraUsesUniqueContentUriWithExplicitTemporaryReadWriteGrants() {
        val capture=ProofCameraCapture(app)
        val file=capture.create();val second=capture.create()
        val uri=capture.uri(file)
        val intent=ProofTakePicture().createIntent(app,uri)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE,intent.action)
        assertEquals(uri,intent.getParcelableExtra<android.net.Uri>(MediaStore.EXTRA_OUTPUT))
        assertEquals("content",uri.scheme);assertEquals("${app.packageName}.excel-files",uri.authority)
        assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertEquals(file,capture.file(file.name));assertNotEquals(file,second)
        assertNull(capture.file("../outside.jpg"));assertNull(capture.file("/tmp/outside.jpg"))
        assertFalse(ProofTakePicture().parseResult(android.app.Activity.RESULT_CANCELED,null))
        assertTrue(ProofTakePicture().parseResult(android.app.Activity.RESULT_OK,null))
        file.delete();second.delete()
    }
    @Test fun capturedPaymentAndDeliveryPersistByOrderAndReplacementKeepsOtherProof() = runBlocking {
        val vm=PlannerViewModel(app).also {models.put("camera",it)}
        val base=schedule(listOf(Place("650417",1.3,103.8,"","","Address")),List(2){Leg(0.0,0.0,0.0)},LocalDateTime.of(2026,10,11,10,0),8,"Normal",emptyList())
        val orders=listOf(CustomerOrder(postalCode="650417",customerName="First"),CustomerOrder(postalCode="650417",customerName="Second"))
        vm.route=expandCustomerOrders(base,orders)
        val target=vm.route!!.stops[0].orderId
        val capture=ProofCameraCapture(app)
        suspend fun upload(kind: ProofKind,color: Int): String {
            val file=capture.create()
            val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply {eraseColor(color)}
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle()
            vm.saveProof(ProofRequest(base.id,target,kind),capture.uri(file),file)
            kotlinx.coroutines.withTimeout(10000) {while(vm.busy) kotlinx.coroutines.delay(10)}
            assertFalse("Temporary capture was not removed",file.exists())
            val order=vm.route!!.stops[0].order!!
            return requireNotNull(if(kind==ProofKind.PAYMENT) order.paymentProofFileId else order.proofFileId)
        }
        val payment=upload(ProofKind.PAYMENT,Color.RED)
        val delivery=upload(ProofKind.DELIVERY,Color.BLUE)
        val replacement=upload(ProofKind.PAYMENT,Color.GREEN)
        assertNotEquals(payment,replacement)
        assertEquals(delivery,vm.route!!.stops[0].order!!.proofFileId)
        assertNull(vm.route!!.stops[1].order!!.paymentProofFileId)
        assertNull(vm.route!!.stops[1].order!!.proofFileId)
        assertEquals("PENDING",vm.route!!.stops[0].status)
        val bad=capture.create().apply {writeText("invalid image")}
        vm.saveProof(ProofRequest(base.id,target,ProofKind.PAYMENT),capture.uri(bad),bad)
        kotlinx.coroutines.withTimeout(10000) {while(vm.busy) kotlinx.coroutines.delay(10)}
        assertFalse(bad.exists());assertEquals(replacement,vm.route!!.stops[0].order!!.paymentProofFileId)
        val repo=Repository(app)
        val reloaded=repo.decode(repo.dao.route(base.id)!!)
        assertEquals(vm.route!!.stops[0].order,reloaded.stops[0].order)
        assertNotNull(OrderProofStore(app).file(reloaded.stops[0].order!!.paymentProofFileId))
        assertNotNull(OrderProofStore(app).file(reloaded.stops[0].order!!.proofFileId))
    }
}
