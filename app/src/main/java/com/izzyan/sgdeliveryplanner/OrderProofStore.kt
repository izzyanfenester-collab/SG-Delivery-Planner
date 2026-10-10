package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import java.io.File
import java.util.UUID

enum class ProofKind { PAYMENT, DELIVERY }
data class ProofRequest(val routeId: String, val orderId: String, val kind: ProofKind)

class OrderProofStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "order_proofs").apply { mkdirs() }
    fun file(id: String?): File? = id?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+\\.jpg")) }?.let { File(directory,it).takeIf(File::isFile) }
    fun import(uri: Uri, kind: ProofKind): String {
        val tmp = File(directory,"pending_${UUID.randomUUID()}")
        val id = "${kind.name.lowercase()}_${UUID.randomUUID()}.jpg"
        val output = File(directory, id)
        try {
            context.contentResolver.openInputStream(uri)?.use { source -> tmp.outputStream().use { target ->
                val buffer = ByteArray(8192); var count = 0L
                while (true) { val n = source.read(buffer); if (n < 0) break; count += n; require(count <= 20L*1024*1024) { "Image exceeds 20 MB." }; target.write(buffer,0,n) }
            } } ?: error("Image could not be opened")
            val bitmap = decode(tmp, 2048) ?: error("Choose a valid image")
            try { output.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) } }
            finally { bitmap.recycle() }
            return id
        } catch (e: Exception) { output.delete(); throw e }
        finally { tmp.delete() }
    }
    fun delete(id: String?) { file(id)?.delete() }
    fun decode(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path,bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth/sample > maxEdge || bounds.outHeight/sample > maxEdge) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = runCatching { android.media.ExifInterface(file.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
        val matrix = Matrix()
        when (orientation) { 2 -> matrix.setScale(-1f,1f); 3 -> matrix.setRotate(180f); 4 -> matrix.setScale(1f,-1f); 5 -> { matrix.setRotate(90f); matrix.postScale(-1f,1f) }; 6 -> matrix.setRotate(90f); 7 -> { matrix.setRotate(270f); matrix.postScale(-1f,1f) }; 8 -> matrix.setRotate(270f) }
        if (matrix.isIdentity) return bitmap
        val rotated = Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
fun updateOrderProof(plan: Plan, orderId: String, kind: ProofKind, fileId: String, uploadedAt: String): Plan {
    require(plan.stops.count { it.orderId == orderId } == 1)
    return plan.copy(stops = plan.stops.map { stop ->
        if (stop.orderId != orderId) stop else {
            val order = stop.order ?: CustomerOrder(orderId = stop.orderId, postalCode = stop.place.postal)
            stop.copy(order = if (kind == ProofKind.PAYMENT) order.copy(paymentProofFileId = fileId,paymentProofUploadedAt = uploadedAt)
                else order.copy(proofFileId = fileId,proofUploadedAt = uploadedAt))
        }
    })
}
