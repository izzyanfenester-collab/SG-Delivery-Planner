package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import java.util.zip.*

data class HistoryBackupResult(val routes:Int, val photos:Int, val missing:Int)
data class HistoryRestoreResult(val restored:Int, val skipped:Int, val photos:Int)

/** A portable, unencrypted ZIP with saved routes and their private proof images. */
class HistoryBackup(private val context:Context, private val dao:PlannerDao) {
    private val gson=Gson()
    private val store=OrderProofStore(context)
    private val imageName=Regex("[A-Za-z0-9_-]+\\.jpg")
    private fun photoIds(plan:Plan)=plan.stops.flatMap { listOfNotNull(it.order?.proofFileId,it.order?.paymentProofFileId) }
        .filter { imageName.matches(it) }.distinct()
    suspend fun export(uri:Uri):HistoryBackupResult=withContext(Dispatchers.IO) {
        val routes=dao.allHistoryForBackup()
        require(routes.size<=2000) { "Too many routes for one backup." }
        val names=routes.flatMap { photoIds(PlanJson.decode(it.json)) }.distinct()
        val available=names.mapNotNull { id -> store.file(id)?.let { id to it } }
        val json=gson.toJson(routes).toByteArray()
        require(json.size<=30*1024*1024) { "History file is too large." }
        val manifest=gson.toJson(mapOf("format" to "sg-delivery-history","version" to 1,
            "routeCount" to routes.size,"exportedAt" to LocalDateTime.now().toString()))
        val stream=context.contentResolver.openOutputStream(uri,"w") ?: error("Cannot write backup.")
        stream.use { target -> ZipOutputStream(target.buffered()).use { zip ->
            fun textEntry(name:String,bytes:ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            textEntry("manifest.json",manifest.toByteArray())
            textEntry("routes.json",json)
            available.forEach { (id,file) ->
                require(file.length()<=20L*1024*1024) { "Proof file too large." }
                zip.putNextEntry(ZipEntry("proofs/"+id))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        } }
        HistoryBackupResult(routes.size,available.size,names.size-available.size)
    }

    private fun hash(file:File):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes=ByteArray(8192)
            while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}
        }
        return digest.digest()
    }
    suspend fun restore(uri:Uri):HistoryRestoreResult=withContext(Dispatchers.IO) {
        val tmp=File(context.cacheDir,"restore_"+UUID.randomUUID()).apply { check(mkdirs()) }
        try {
            val images=linkedMapOf<String,File>()
            val seen=hashSetOf<String>()
            var manifest:ByteArray?=null
            var routesJson:ByteArray?=null
            var total=0L
            val stream=context.contentResolver.openInputStream(uri) ?: error("Cannot read backup.")
            stream.use { source -> ZipInputStream(source.buffered()).use { zip ->
                while(true){
                    val entry=zip.nextEntry ?: break
                    val name=entry.name
                    require(!entry.isDirectory&&seen.add(name)) { "Invalid or duplicate archive entry." }
                    val cap=when {
                        name=="manifest.json" -> 32768L
                        name=="routes.json" -> 30L*1024*1024
                        name.startsWith("proofs/")&&imageName.matches(name.removePrefix("proofs/")) -> 20L*1024*1024
                        else -> error("Unexpected backup file.")
                    }
                    var size=0L
                    val buffer=ByteArray(8192)
                    val text=if(name=="manifest.json"||name=="routes.json")ByteArrayOutputStream() else null
                    val id=name.removePrefix("proofs/")
                    if(text==null) require(images.size<5000) { "Too many images." }
                    val file=if(text==null)File(tmp,id) else null
                    val sink:OutputStream=text?:file!!.outputStream()
                    sink.use { out ->
                        while(true){
                            val n=zip.read(buffer)
                            if(n<0)break
                            size+=n; total+=n
                            require(size<=cap&&total<=350L*1024*1024) { "Backup exceeds safety limits." }
                            out.write(buffer,0,n)
                        }
                    }
                    if(name=="manifest.json")manifest=text!!.toByteArray()
                    else if(name=="routes.json")routesJson=text!!.toByteArray()
                    else images[id]=file!!
                    zip.closeEntry()
                }
            } }
            val header=JsonParser.parseString(requireNotNull(manifest) { "Not an SG Delivery backup." }.toString(Charsets.UTF_8)).asJsonObject
            require(header.get("format")?.asString=="sg-delivery-history"&&header.get("version")?.asInt==1) { "Unsupported backup format." }
            val rows=gson.fromJson(requireNotNull(routesJson) { "History data missing." }.toString(Charsets.UTF_8),
                Array<SavedRoute>::class.java).toList()
            require(rows.size<=2000&&rows.size==header.get("routeCount")?.asInt) { "Invalid history count." }
            require(rows.all { it.id.isNotBlank()&&it.id.length<=200&&it.created.length<=100 }&&
                rows.map { it.id }.distinct().size==rows.size) { "Invalid route identifiers." }
            val expected=rows.flatMap { row ->
                val plan=PlanJson.decode(row.json)
                require(plan.id==row.id) { "Route ID mismatch." }
                photoIds(plan)
            }.toSet()
            require(images.keys.all { it in expected }) { "Unexpected photo in backup." }
            images.forEach { (id,file) ->
                store.file(id)?.let { existing ->
                    require(hash(existing).contentEquals(hash(file))) { "Existing proof photo conflict; no data overwritten." }
                }
            }
            val newFiles=mutableListOf<File>()
            try {
                images.forEach { (id,file) ->
                    if(store.file(id)==null) {
                        val dir=File(context.filesDir,"order_proofs").apply { check(isDirectory||mkdirs()) }
                        val dest=File(dir,id)
                        file.copyTo(dest,overwrite=false)
                        newFiles.add(dest)
                    }
                }
                val restored=dao.insertRestoredRoutes(rows).count { it!=-1L }
                HistoryRestoreResult(restored,rows.size-restored,newFiles.size)
            } catch(e:Exception) {
                newFiles.forEach { it.delete() }
                throw e
            }
        } finally { tmp.deleteRecursively() }
    }
}
