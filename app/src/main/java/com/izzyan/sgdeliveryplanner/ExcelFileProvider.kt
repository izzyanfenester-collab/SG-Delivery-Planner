package com.izzyan.sgdeliveryplanner

import android.net.Uri
import androidx.core.content.FileProvider

/** Excel recipients can query a consistent MIME type on every supported Android version. */
class ExcelFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? {
        // Resolve through FileProvider first, retaining its configured-root validation.
        val detected = super.getType(uri)
        return if (uri.lastPathSegment?.endsWith(".xlsx", ignoreCase = true) == true)
            EXCEL_MIME_TYPE else detected
    }
}
