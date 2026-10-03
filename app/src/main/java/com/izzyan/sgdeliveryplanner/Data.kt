package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query as HttpQuery
import retrofit2.http.Url
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime

private inline fun checkPlanning(condition: Boolean, message: () -> String) {
 if (!condition) throw PlannerError(message())
}

@Entity(tableName="places") data class CachedPlace(@PrimaryKey val postal: String, val json: String, val saved: Long)
@Entity(tableName="routes") data class SavedRoute(@PrimaryKey val id: String, val json: String, val created: String)
@Entity(tableName="legs") data class CachedLeg(@PrimaryKey val key: String, val json: String, val saved: Long)
@Dao interface PlannerDao {
 @Query("SELECT * FROM places WHERE postal=:postal") suspend fun place(postal:String): CachedPlace?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun place(p:CachedPlace)
 @Query("SELECT * FROM legs WHERE `key`=:key") suspend fun leg(key:String): CachedLeg?
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun leg(l:CachedLeg)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(r:SavedRoute)
 @Query("SELECT * FROM routes ORDER BY created DESC") fun history(): Flow<List<SavedRoute>>
 @Query("SELECT * FROM routes WHERE id=:id") suspend fun route(id:String): SavedRoute?
}
@Database(entities=[CachedPlace::class,SavedRoute::class,CachedLeg::class],version=1,exportSchema=false)
abstract class PlannerDb: RoomDatabase() { abstract fun dao(): PlannerDao }
interface Api {
 @GET("api/common/elastic/search") suspend fun search(@HttpQuery("searchVal") postal:String,@HttpQuery("returnGeom") geom:String="Y",@HttpQuery("getAddrDetails") details:String="Y"): JsonObject
 @GET suspend fun get(@Url url:String): JsonObject
}
class Repository(context:Context) {
 private val gson=Gson()
 val dao=Room.databaseBuilder(context,PlannerDb::class.java,"planner.db").build().dao()
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS).build()
 private val api=Retrofit.Builder().baseUrl("https://www.onemap.gov.sg/").client(client).addConverterFactory(GsonConverterFactory.create()).build().create(Api::class.java)
 suspend fun save(plan:Plan) = dao.save(SavedRoute(plan.id,PlanJson.encode(plan),plan.created))
 fun decode(r:SavedRoute): Plan = PlanJson.decode(r.json)
 suspend fun resolve(postal:String):Place {
  dao.place(postal)?.takeIf { System.currentTimeMillis()-it.saved < 180L*86400000 }?.let { return gson.fromJson(it.json,Place::class.java) }
  val matches=api.search(postal).getAsJsonArray("results")
  val row=matches?.firstOrNull { it.asJsonObject.get("POSTAL")?.asString == postal }?.asJsonObject ?: throw PlannerError("Postal code $postal was not found. Check the code and try again.")
  fun field(name:String)=row.get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
  val p=Place(postal,field("LATITUDE").toDouble(),field("LONGITUDE").toDouble(),field("BLK_NO"),field("ROAD_NAME"),field("ADDRESS"))
  checkPlanning(p.lat in 1.1..1.6 && p.lon in 103.5..104.1) { "Postal code $postal returned a location outside Singapore. Check the code and try again." }
  dao.place(CachedPlace(postal,gson.toJson(p),System.currentTimeMillis())); return p
 }
 suspend fun plan(codes:List<String>,start:LocalDateTime,service:Int,mode:String,endpoint:String,progress:(String)->Unit):Plan = coroutineScope {
  checkPlanning(codes.size in 1..50) { "Plan 1–50 unique stops per route. Split larger lists into separate routes." }
  checkPlanning(endpoint.startsWith("https://")) { "The routing server address must start with https://. Update it in Settings." }
  val gate=Semaphore(4)
  val resolved=codes.map { code -> async { gate.withPermit { try { Result.success(resolve(code)) } catch(e:CancellationException){throw e} catch(e:Exception){ Result.failure<Place>(PlannerError("$code: ${englishError(e, "Could not find the address. Check the postal code and try again.")}", e)) } } } }.awaitAll()
  val failures=resolved.filter { it.isFailure }.map { englishError(it.exceptionOrNull()!!, "Could not check this postal code. Please try again.") }
  checkPlanning(failures.isEmpty()) { "Could not create a route because these postal codes could not be checked:\n${failures.joinToString("\n")}\nCheck your internet connection and postal codes, then try again. Your entries have been kept." }
  progress("Finding road distances and driving times…")
  val places=listOf(depot)+resolved.map { it.getOrThrow() }
  val coordinates=places.joinToString(";") { "${it.lon},${it.lat}" }
  val root=endpoint.trimEnd('/')
  val n=places.size
  val cached=Array(n) { arrayOfNulls<Leg>(n) }
  var complete=true
  for(i in 0 until n) for(j in 0 until n) {
   if(i==j) { cached[i][j]=Leg(0.0,0.0,0.0); continue }
   val record=dao.leg("$root|${places[i].postal}|${places[j].postal}")
   if(record!=null && System.currentTimeMillis()-record.saved<7L*86400000) cached[i][j]=gson.fromJson(record.json,Leg::class.java) else complete=false
  }
  val meters:Array<DoubleArray>
  val seconds:Array<DoubleArray>
  if(complete) {
   meters=Array(n) { i -> DoubleArray(n) { j -> cached[i][j]!!.km*1000 } }
   seconds=Array(n) { i -> DoubleArray(n) { j -> cached[i][j]!!.baseSeconds } }
  } else {
   val table=api.get("$root/table/v1/driving/$coordinates?annotations=distance,duration")
   checkPlanning(table.get("code")?.asString == "Ok") { "The routing service could not calculate driving times. Try again or change the routing server in Settings." }
   fun matrix(name:String):Array<DoubleArray> = Array(n) { i -> DoubleArray(n) { j ->
    val cell=table.getAsJsonArray(name)[i].asJsonArray[j]
    checkPlanning(!cell.isJsonNull) { "No driving route was found between postal codes ${places[i].postal} and ${places[j].postal}. Check the codes or change the routing server in Settings." }
    cell.asDouble.also { checkPlanning(it.isFinite() && it>=0) { "The routing service returned invalid travel information. Please try again." } }
   } }
   meters=matrix("distances"); seconds=matrix("durations")
   for(i in 0 until n) for(j in 0 until n) if(i!=j) dao.leg(CachedLeg("$root|${places[i].postal}|${places[j].postal}",gson.toJson(Leg(meters[i][j]/1000,seconds[i][j],0.0)),System.currentTimeMillis()))
  }
  val costs=Array(n) { i -> DoubleArray(n) { j -> seconds[i][j]+trafficBuffer(meters[i][j]/1000,seconds[i][j],mode)+meters[i][j]/1000*15 } }
  progress("Improving the delivery order…")
  val order=withContext(Dispatchers.Default) { Optimizer.optimize(costs) }
  val path=listOf(0)+order+0
  val legs=path.zipWithNext().map { (a,b) ->
   val key="$root|${places[a].postal}|${places[b].postal}"
   val leg=Leg(meters[a][b]/1000,seconds[a][b],trafficBuffer(meters[a][b]/1000,seconds[a][b],mode))
   dao.leg(CachedLeg(key,gson.toJson(leg),System.currentTimeMillis())); leg
  }
  progress("Loading the route map…")
  val route=api.get("$root/route/v1/driving/${path.joinToString(";") { "${places[it].lon},${places[it].lat}" }}?overview=full&geometries=geojson&steps=false")
  checkPlanning(route.get("code")?.asString == "Ok") { "The route map is unavailable. Please try planning the route again." }
  val geometry=route.getAsJsonArray("routes")[0].asJsonObject.getAsJsonObject("geometry").getAsJsonArray("coordinates").map { listOf(it.asJsonArray[0].asDouble,it.asJsonArray[1].asDouble) }
  schedule(order.map { places[it] },legs,start,service,mode,geometry).also { save(it) }
 }
}
