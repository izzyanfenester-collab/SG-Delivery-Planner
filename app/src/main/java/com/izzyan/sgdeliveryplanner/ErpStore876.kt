package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Immutable bundled ERP database, completely separate from all user databases. */
internal class ErpStore876(context:Context) {
    private val app=context.applicationContext
    fun load():ErpCatalog876 = synchronized(lock) {
        val manifest=app.assets.open("sg_erp_manifest_876.json").bufferedReader().use{JSONObject(it.readText())}
        require(manifest.getInt("schema")==1 && manifest.getInt("record_count")==106 && manifest.getInt("location_count")==101)
        val version=manifest.getString("version");require(version.matches(Regex("xlsx-[a-f0-9]{12}")))
        val folder=File(app.noBackupFilesDir,"erp-876").apply{mkdirs()}
        val target=File(folder,"$version.db")
        val expected=manifest.getString("database_sha256")
        if(!target.isFile || sha(target.readBytes())!=expected){
            val raw=app.assets.open("sg_erp_locations_876.db").use{it.readBytes()}
            require(raw.size in 4096..1_048_576 && sha(raw)==expected){"ERP database hash mismatch"}
            val atomic=AtomicFile(target);val out=atomic.startWrite()
            try{out.write(raw);atomic.finishWrite(out)}catch(t:Throwable){atomic.failWrite(out);throw t}
        }
        SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use{db->
            fun scalar(sql:String)=db.rawQuery(sql,null).use{require(it.moveToFirst());it.getLong(0)}
            fun meta(key:String)=db.rawQuery("SELECT value FROM metadata WHERE key=?",arrayOf(key)).use{require(it.moveToFirst());it.getString(0)}
            require(scalar("PRAGMA application_id")==0x45525036L && scalar("PRAGMA user_version")==1L)
            require(db.rawQuery("PRAGMA quick_check",null).use{it.moveToFirst()&&it.getString(0)=="ok"})
            require(!db.rawQuery("PRAGMA foreign_key_check",null).use{it.moveToFirst()})
            require(meta("version")==version && meta("workbook_sha256")==manifest.getString("workbook_sha256"))
            require(scalar("SELECT COUNT(*) FROM erp_record")==106L && scalar("SELECT COUNT(*) FROM erp_site")==101L &&
                scalar("SELECT COUNT(*) FROM erp_site_record")==106L)
            val records=db.rawQuery("SELECT unique_id,gantry_no,latitude_text,longitude_text,google_maps,source_row,latitude,longitude FROM erp_record ORDER BY source_row",null).use{c->
                buildList {while(c.moveToNext()){
                    val r=ErpRecord876(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getInt(5))
                    require(r.point.lat==c.getDouble(6) && r.point.lon==c.getDouble(7));add(r)
                }}
            }
            ErpCatalog876(version,manifest.getString("workbook_sha256"),records).also{catalog->
                val linked=db.rawQuery("SELECT site_key,unique_id FROM erp_site_record",null).use{c->buildMap{while(c.moveToNext())put(c.getString(1),c.getString(0))}}
                require(catalog.records.all{linked[it.uniqueId]==it.coordinateKey})
            }
        }
    }
    companion object {
        private val lock=Any()
        internal fun sha(raw:ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it.toInt()and 255)}
    }
}
internal class ErpPreferences876(context:Context) {
    // An additive key in the existing GPS appearance preference file.
    private val prefs=context.applicationContext.getSharedPreferences("sg_delivery_navigation_appearance",Context.MODE_PRIVATE)
    fun visible()=prefs.getBoolean("show_erp_gantry_icons_876",true)
    fun setVisible(value:Boolean){prefs.edit().putBoolean("show_erp_gantry_icons_876",value).apply()}
}
