package com.izzyan.sgdeliveryplanner

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** A narrow cache FileProvider grants only this capture, never private saved proofs. */
class ProofCameraCapture(private val context: Context) {
    private val directory get() = File(context.cacheDir,"proof_camera").apply { check(mkdirs() || isDirectory) }
    fun create(): File {
        directory.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }?.forEach(File::delete)
        return File.createTempFile("capture_${UUID.randomUUID()}_", ".jpg", directory)
    }
    fun file(name: String?): File? = name?.takeIf { it.matches(Regex("capture_[a-zA-Z0-9_-]+\\.jpg")) }?.let { File(directory,it).takeIf(File::isFile) }
    fun uri(file: File): Uri = FileProvider.getUriForFile(context,"${context.packageName}.excel-files",file)
}
class ProofTakePicture : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context,input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = ClipData.newRawUri("Proof photo",input)
    }
}
