package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets.UTF_8
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only authenticated ciphertext; the encryption key never leaves Android Keystore. */
class OneMapTokenStore internal constructor(
    private val preferences: SharedPreferences,
    private val keyProvider: TokenKeyProvider,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        AndroidTokenKeyProvider,
    )

    fun readToken(): String? {
        try {
            val envelope = preferences.getString(TOKEN_ENTRY, null) ?: return null
            val parts = envelope.split(':')
            if (parts.size != 3 || parts[0] != VERSION) throw GeneralSecurityException()
            val iv = Base64.getDecoder().decode(parts[1])
            val ciphertext = Base64.getDecoder().decode(parts[2])
            if (iv.size != IV_BYTES || ciphertext.size <= TAG_BITS / 8) throw GeneralSecurityException()
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.key(createIfMissing = false), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AUTHENTICATED_CONTEXT)
            val plaintext = String(cipher.doFinal(ciphertext), UTF_8)
            // Never return malformed data or fall back to a legacy plaintext preference.
            if (normalizeToken(plaintext) != plaintext) throw GeneralSecurityException()
            return plaintext
        } catch (_: Exception) {
            throw PlannerError("Could not read the saved OneMap token. Update the OneMap token in Settings.")
        }
    }

    fun saveToken(token: String) {
        val normalized = normalizeToken(token)
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            // Android Keystore generates a new random IV for every encryption operation.
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.key(createIfMissing = true))
            cipher.updateAAD(AUTHENTICATED_CONTEXT)
            val ciphertext = cipher.doFinal(normalized.toByteArray(UTF_8))
            val iv = cipher.iv
            if (iv.size != IV_BYTES) throw GeneralSecurityException()
            val encoder = Base64.getEncoder()
            val envelope = "$VERSION:${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
            if (!preferences.edit().putString(TOKEN_ENTRY, envelope).commit()) throw GeneralSecurityException()
        } catch (_: Exception) {
            throw PlannerError("Could not securely save the OneMap token. Please try again in Settings.")
        }
    }

    fun clearToken() {
        try {
            if (!preferences.edit().remove(TOKEN_ENTRY).commit()) throw GeneralSecurityException()
        } catch (_: Exception) {
            throw PlannerError("Could not remove the saved OneMap token. Please try again in Settings.")
        }
    }

    private fun normalizeToken(input: String): String {
        // Reject control characters before trimming so a pasted newline cannot become a header.
        if (input.any { it.code < 0x20 || it.code > 0x7e }) invalidToken()
        var token = input.trim()
        if (token.startsWith("Bearer ", ignoreCase = true)) token = token.substring(7).trim()
        if (!BEARER_TOKEN.matches(token)) invalidToken()
        return token
    }

    private fun invalidToken(): Nothing = throw PlannerError(
        "Enter a valid OneMap API token in Settings. Paste the token only, or include the Bearer prefix.",
    )

    companion object {
        private const val PREFERENCES_NAME = "onemap_credentials"
        private const val TOKEN_ENTRY = "encrypted_token"
        private const val VERSION = "v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private val AUTHENTICATED_CONTEXT = "IZZ Delivery OneMap token v1".toByteArray(UTF_8)
        // RFC 6750 bearer token syntax. Spaces and other header delimiters are not allowed.
        private val BEARER_TOKEN = Regex("[A-Za-z0-9._~+/-]+=*")
    }
}

/** A test seam for AES keys; the public constructor always uses Android Keystore. */
internal fun interface TokenKeyProvider {
    fun key(createIfMissing: Boolean): SecretKey
}

private object AndroidTokenKeyProvider : TokenKeyProvider {
    private const val KEY_ALIAS = "izz_delivery_onemap_token_v1"

    @Synchronized
    override fun key(createIfMissing: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) return existing.secretKey
        if (existing != null || !createIfMissing) throw GeneralSecurityException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
