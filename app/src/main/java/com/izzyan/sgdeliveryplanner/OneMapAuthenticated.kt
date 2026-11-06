package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never appear in the APK, routing requests, logs or ordinary preferences. */
internal class OneMapCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("onemap_credentials", Context.MODE_PRIVATE)

    fun configured(): Boolean = preferences.contains("encrypted_login")

    fun save(email: String, password: String) {
        require(email.isNotBlank() && password.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = (email.trim() + "\n" + password).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.iv + cipher.doFinal(payload)
        preferences.edit().putString("encrypted_login", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putLong("generation", generation() + 1).apply()
    }

    fun credentials(): Pair<String, String>? {
        val encoded = preferences.getString("encrypted_login", null) ?: return null
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
                .split("\n", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        } catch (_: Exception) {
            null // A replaced or invalidated device keystore requires signing in again.
        }
    }

    fun clear() { preferences.edit().remove("encrypted_login").putLong("generation", generation() + 1).apply() }

    fun generation(): Long = preferences.getLong("generation", 0L)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object { const val KEY_ALIAS = "sg_delivery_onemap_login_v1" }
}

internal interface OneMapTokenApi {
    @POST("api/auth/post/getToken")
    suspend fun generateToken(@Body credentials: OneMapLoginRequest): JsonObject
}

internal data class OneMapLoginRequest(val email: String, val password: String)

internal interface OneMapProtectedSearchApi {
    @GET("api/common/elastic/search")
    suspend fun search(
        @Header("Authorization") token: String,
        @Query("searchVal") value: String,
        @Query("returnGeom") geom: String,
        @Query("getAddrDetails") details: String
    ): JsonObject
}

/** OneMap Search requires a free account token, renewable every three days. */
internal class AuthenticatedOneMapApi(
    private val login: () -> Pair<String, String>?,
    private val tokens: OneMapTokenApi,
    private val searchService: OneMapProtectedSearchApi,
    private val credentialGeneration: () -> Long = { 0L },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : OneMapApi {
    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAt: Long = 0L
    private var generation: Long = Long.MIN_VALUE

    override suspend fun search(postal: String, geom: String, details: String): JsonObject {
        val current = accessToken()
        val response = searchService.search(current, postal, geom, details)
        val error = response.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (error.contains("expired", ignoreCase = true) || error.contains("invalid authentication", ignoreCase = true)) {
            mutex.withLock { token = null; expiresAt = 0L }
            val retried = searchService.search(accessToken(), postal, geom, details)
            return checkResponse(retried)
        }
        return checkResponse(response)
    }

    private fun checkResponse(result: JsonObject): JsonObject {
        val error = result.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (error.isNotBlank()) throw PlannerError("OneMap search failed: $error. Check the free OneMap account in Settings.")
        return result
    }

    private suspend fun accessToken(): String = mutex.withLock {
        val currentGeneration = credentialGeneration()
        if (currentGeneration != generation) {
            generation = currentGeneration
            token = null
            expiresAt = 0L
        }
        token?.takeIf { clock() < expiresAt - 10L * 60_000 }?.let { return@withLock it }
        val (email, password) = login() ?: throw PlannerError(
            "OneMap account is not configured. Open Settings > Map & Postal Data to add your free OneMap account."
        )
        val result = tokens.generateToken(OneMapLoginRequest(email, password))
        val error = result.get("error")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        if (error.isNotEmpty()) throw PlannerError("OneMap sign-in failed. Verify your email and password in Settings.")
        val newToken = result.get("access_token")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val expiry = result.get("expiry_timestamp")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
        if (newToken.isBlank() || expiry == null || expiry * 1000L <= clock()) {
            throw PlannerError("OneMap did not return a valid access token. Please retry or check Settings.")
        }
        token = newToken
        expiresAt = expiry * 1000L
        newToken
    }
}

/** Used by the app; the unauthenticated test-only factory remains available for legacy tests. */
fun createOneMapApi(context: Context, baseUrl: String = "https://www.onemap.gov.sg/"): OneMapApi {
    val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).build()
    val retrofit = Retrofit.Builder().baseUrl(baseUrl).client(client)
        .addConverterFactory(GsonConverterFactory.create()).build()
    val credentials = OneMapCredentialStore(context)
    return AuthenticatedOneMapApi(
        login = { credentials.credentials() },
        tokens = retrofit.create(OneMapTokenApi::class.java),
        searchService = retrofit.create(OneMapProtectedSearchApi::class.java),
        credentialGeneration = { credentials.generation() }
    )
}
