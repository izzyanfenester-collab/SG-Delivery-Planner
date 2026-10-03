package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OneMapTokenStoreTest {
    private lateinit var preferences: SharedPreferences
    private val key: SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val keys = TokenKeyProvider { key }

    @Before
    fun resetStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("token-store-test", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun tokenIsEncryptedAndCanBeReopenedUpdatedAndCleared() {
        val token = "test.token_VALUE-123"
        val store = OneMapTokenStore(preferences, keys)
        assertNull(store.readToken())

        store.saveToken(token)

        assertEquals(token, OneMapTokenStore(preferences, keys).readToken())
        val stored = preferences.all.values.single() as String
        assertTrue(stored.startsWith("v1:"))
        assertFalse(stored.contains(token))
        assertNotEquals(token, stored)
        val parts = stored.split(':')
        assertEquals(12, Base64.getDecoder().decode(parts[1]).size)
        assertFalse(Base64.getDecoder().decode(parts[2]).contentEquals(token.toByteArray()))

        store.saveToken("updated.token_VALUE-456")
        assertEquals("updated.token_VALUE-456", OneMapTokenStore(preferences, keys).readToken())
        assertNotEquals(stored, preferences.all.values.single())

        store.clearToken()
        assertNull(OneMapTokenStore(preferences, keys).readToken())
        assertTrue(preferences.all.isEmpty())
    }

    @Test
    fun everySaveUsesANewRandomIvEvenForTheSameToken() {
        val store = OneMapTokenStore(preferences, keys)
        store.saveToken("test.token_VALUE-123")
        val first = (preferences.all.values.single() as String).split(':')
        store.saveToken("test.token_VALUE-123")
        val second = (preferences.all.values.single() as String).split(':')

        assertNotEquals(first[1], second[1])
        assertNotEquals(first[2], second[2])
        assertEquals("test.token_VALUE-123", store.readToken())
    }

    @Test
    fun optionalBearerPrefixAndOuterSpacesAreNormalized() {
        val store = OneMapTokenStore(preferences, keys)
        store.saveToken("  bEaReR  test.token_VALUE-123==  ")

        assertEquals("test.token_VALUE-123==", store.readToken())
    }

    @Test
    fun malformedHeaderInputIsRejectedBeforeExistingCredentialsChange() {
        val store = OneMapTokenStore(preferences, keys)
        store.saveToken("existing.test_token")
        val original = preferences.all.values.single()
        val malformed = listOf("", "   ", "Bearer ", "two tokens", "test\n", "test\r\nHeader: value", "test\t", "test\u0000", "test\u00a0", "test:token", "test=token")

        malformed.forEach { input ->
            val error = plannerError { store.saveToken(input) }
            assertTrue(error.englishMessage.contains("valid OneMap API token in Settings"))
            assertEquals(original, preferences.all.values.single())
        }
        assertEquals("existing.test_token", store.readToken())
    }

    @Test
    fun tamperedCiphertextFailsWithoutReturningPlaintextOrLegacyCredentials() {
        val store = OneMapTokenStore(preferences, keys)
        store.saveToken("synthetic.test_token")
        val entry = preferences.all.entries.single()
        val parts = (entry.value as String).split(':')
        val damaged = Base64.getDecoder().decode(parts[2]).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        val tampered = "${parts[0]}:${parts[1]}:${Base64.getEncoder().encodeToString(damaged)}"
        assertTrue(preferences.edit().putString(entry.key, tampered).putString("token", "legacy.plaintext_token").commit())

        val error = plannerError { store.readToken() }

        assertEquals("Could not read the saved OneMap token. Update the OneMap token in Settings.", error.englishMessage)
        assertFalse(error.englishMessage.contains("synthetic.test_token"))
        assertFalse(error.englishMessage.contains("legacy.plaintext_token"))
    }

    @Test
    fun unsupportedEnvelopeAndUnavailableKeyRequireUpdatingSettings() {
        val store = OneMapTokenStore(preferences, keys)
        store.saveToken("synthetic.test_token")
        val entry = preferences.all.entries.single()
        val original = entry.value as String
        assertTrue(preferences.edit().putString(entry.key, original.replaceFirst("v1:", "v2:")).commit())
        assertTrue(plannerError { store.readToken() }.englishMessage.contains("Update the OneMap token in Settings"))

        assertTrue(preferences.edit().putString(entry.key, original).commit())
        var attemptedKeyCreation = false
        val missingKeys = TokenKeyProvider { createIfMissing ->
            attemptedKeyCreation = createIfMissing
            throw GeneralSecurityException()
        }
        assertTrue(plannerError { OneMapTokenStore(preferences, missingKeys).readToken() }.englishMessage.contains("Update the OneMap token in Settings"))
        assertFalse(attemptedKeyCreation)
    }

    @Test
    fun legacyPlaintextIsNeverRead() {
        assertTrue(preferences.edit().putString("token", "legacy.plaintext_token").commit())

        assertNull(OneMapTokenStore(preferences, keys).readToken())
    }

    private fun plannerError(action: () -> Any?): PlannerError {
        try {
            action()
            fail("Expected a controlled English error.")
        } catch (error: PlannerError) {
            return error
        }
        throw AssertionError("Expected a controlled English error.")
    }
}
