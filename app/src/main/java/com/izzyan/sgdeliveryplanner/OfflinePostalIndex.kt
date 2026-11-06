package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local Singapore address index. No OneMap API call, account, or token is needed. */
internal class OfflinePostalIndex(context: Context) {
    private val app = context.applicationContext
    private val databaseFile = app.getDatabasePath("sg_postals.db")

    companion object {
        private val mutex = Mutex()
        private const val SEED_NAME = "sg_postal_seed.db"
        private const val MIN_VALID_POSTALS = 100_000

        private fun parseCsvRow(line: String): List<String>? {
            val fields = mutableListOf<String>()
            val word = StringBuilder()
            var quoted = false
            var index = 0
            while (index < line.length) {
                val char = line[index]
                when {
                    char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                        word.append('"')
                        index++
                    }
                    char == '"' -> quoted = !quoted
                    char == ',' && !quoted -> {
                        fields.add(word.toString())
                        word.setLength(0)
                    }
                    else -> word.append(char)
                }
                index++
            }
            if (quoted) return null
            fields.add(word.toString())
            return fields.takeIf { it.size == 6 }
        }

        private fun insideSingapore(lat: Double, lon: Double) =
            lat in 1.1..1.6 && lon in 103.5..104.1
    }

    private fun ensureSeed() {
        if (databaseFile.isFile && databaseFile.length() > 1_000_000L) return
        databaseFile.parentFile?.mkdirs()
        val temp = File(databaseFile.parentFile, "sg_postals_seed.tmp")
        try {
            app.assets.open(SEED_NAME).use { source -> temp.outputStream().use { source.copyTo(it) } }
            Files.move(temp.toPath(), databaseFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }

    private inline fun <T> readDatabase(block: (SQLiteDatabase) -> T): T {
        ensureSeed()
        val db = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        return try { block(db) } finally { db.close() }
    }

    private fun readPostal(db: SQLiteDatabase, code: String): Place? =
        db.rawQuery("SELECT block,street,name,lat,lon FROM postals WHERE postal=? LIMIT 1", arrayOf(code)).use { cur ->
            if (!cur.moveToFirst()) return@use null
            val block = cur.getString(0).orEmpty()
            val street = cur.getString(1).orEmpty()
            val lat = cur.getDouble(3)
            val lon = cur.getDouble(4)
            if (!insideSingapore(lat, lon)) return@use null
            val address = listOf(block, street).filter { it.isNotBlank() }.joinToString(" ") + ", Singapore $code"
            Place(code, lat, lon, block, street, address)
        }

    suspend fun find(code: String): Place? = withContext(Dispatchers.IO) {
        mutex.withLock { readDatabase { db -> readPostal(db, code) } }
    }

    suspend fun version(): Int = withContext(Dispatchers.IO) {
        mutex.withLock { readDatabase { db ->
            db.rawQuery("SELECT version FROM metadata LIMIT 1", null).use { row ->
                if (row.moveToFirst()) row.getInt(0) else 0
            }
        } }
    }

    /** Search used by the unchanged Start/End location picker. */
    suspend fun search(term: String): JsonObject = withContext(Dispatchers.IO) {
        mutex.withLock {
            readDatabase { db ->
                val value = term.trim().take(90)
                val postal = Regex("\\b[0-9]{6}\\b").find(value)?.value
                val sql: String
                val args: Array<String>
                if (postal != null) {
                    sql = "SELECT postal,block,street,name,lat,lon FROM postals WHERE postal=? LIMIT 6"
                    args = arrayOf(postal)
                } else {
                    sql = """SELECT postal,block,street,name,lat,lon FROM postals
                        WHERE street LIKE ? OR name LIKE ? OR (block || ' ' || street) LIKE ?
                        ORDER BY postal LIMIT 6"""
                    val pattern = "%" + value.replace("%", "").replace("_", "") + "%"
                    args = arrayOf(pattern, pattern, pattern)
                }
                val rows = JsonArray()
                db.rawQuery(sql, args).use { cursor ->
                    while (cursor.moveToNext()) {
                        val code = cursor.getString(0)
                        val block = cursor.getString(1).orEmpty()
                        val street = cursor.getString(2).orEmpty()
                        val name = cursor.getString(3).orEmpty()
                        val lat = cursor.getDouble(4)
                        val lon = cursor.getDouble(5)
                        if (!insideSingapore(lat, lon)) continue
                        val address = listOf(block, street).filter { it.isNotBlank() }.joinToString(" ") + ", Singapore $code"
                        rows.add(JsonObject().apply {
                            addProperty("POSTAL", code)
                            addProperty("BLK_NO", block)
                            addProperty("ROAD_NAME", street)
                            addProperty("BUILDING", name)
                            addProperty("LATITUDE", lat.toString())
                            addProperty("LONGITUDE", lon.toString())
                            addProperty("ADDRESS", address)
                            addProperty("SEARCHVAL", address)
                        })
                    }
                }
                JsonObject().apply { addProperty("found", rows.size()); add("results", rows) }
            }
        }
    }

    /** Validate the entire new dataset before replacing the current usable database. */
    suspend fun installCsv(csvFile: File, newVersion: Int): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureSeed()
            val previous = readDatabase { db ->
                db.rawQuery("SELECT version FROM metadata LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
            if (newVersion <= previous) return@withLock 0
            val temp = File(databaseFile.parentFile, "sg_postals_update.tmp")
            temp.delete()
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openOrCreateDatabase(temp, null)
                db.execSQL("""CREATE TABLE postals (
                    postal TEXT PRIMARY KEY NOT NULL, block TEXT NOT NULL, street TEXT NOT NULL,
                    name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL)""")
                db.execSQL("CREATE TABLE metadata (version INTEGER NOT NULL, postal_count INTEGER NOT NULL)")
                db.beginTransaction()
                try {
                    val statement = db.compileStatement("INSERT OR IGNORE INTO postals VALUES (?,?,?,?,?,?)")
                    csvFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.drop(1).forEach { line ->
                            val row = parseCsvRow(line) ?: return@forEach
                            val code = row[2].trim().padStart(6, '0')
                            val lat = row[4].toDoubleOrNull() ?: return@forEach
                            val lon = row[5].toDoubleOrNull() ?: return@forEach
                            if (!code.matches(Regex("[0-9]{6}")) || !insideSingapore(lat, lon)) return@forEach
                            statement.clearBindings()
                            statement.bindString(1, code)
                            statement.bindString(2, row[0].trim())
                            statement.bindString(3, row[1].trim())
                            statement.bindString(4, row[3].trim())
                            statement.bindDouble(5, lat)
                            statement.bindDouble(6, lon)
                            statement.executeInsert()
                        }
                    }
                    statement.close()
                    val count = db.rawQuery("SELECT COUNT(*) FROM postals", null).use { c ->
                        c.moveToFirst(); c.getInt(0)
                    }
                    if (count < MIN_VALID_POSTALS) throw IllegalStateException("Postal source is incomplete")
                    db.execSQL("INSERT INTO metadata VALUES (?,?)", arrayOf(newVersion, count))
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.close()
                db = null
                Files.move(temp.toPath(), databaseFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                newVersion
            } finally {
                db?.close()
                temp.delete()
            }
        }
    }
}

internal class OfflinePostalSearchApi(context: Context) : OneMapApi {
    private val index = OfflinePostalIndex(context)
    override suspend fun search(postal: String, geom: String, details: String): JsonObject = index.search(postal)
}
