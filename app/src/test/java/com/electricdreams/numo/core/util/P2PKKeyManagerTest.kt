package com.electricdreams.numo.core.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class P2PKKeyManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        P2PKKeyManager.clearKeys(context)
    }

    @Test
    fun `getOrCreateKeyPair generates new keypair when none exists`() {
        val keyPair = P2PKKeyManager.getOrCreateKeyPair(context)

        assertNotNull(keyPair)
        assertEquals(64, keyPair.privateKeyHex.length)
        assertEquals(64, keyPair.publicKeyHex.length)
    }

    @Test
    fun `getOrCreateKeyPair returns same keypair on subsequent calls`() {
        val keyPair1 = P2PKKeyManager.getOrCreateKeyPair(context)
        val keyPair2 = P2PKKeyManager.getOrCreateKeyPair(context)

        assertEquals(keyPair1.publicKeyHex, keyPair2.publicKeyHex)
        assertEquals(keyPair1.privateKeyHex, keyPair2.privateKeyHex)
    }

    @Test
    fun `getPublicKeyHex returns valid hex string`() {
        val publicKey = P2PKKeyManager.getPublicKeyHex(context)

        assertNotNull(publicKey)
        assertEquals(64, publicKey.length)
        assertTrue(publicKey.matches(Regex("^[a-fA-F0-9]+$")))
    }

    @Test
    fun `getSigningKey returns list with public key`() {
        val signingKeys = P2PKKeyManager.getSigningKey(context)

        assertEquals(1, signingKeys.size)
        assertEquals(P2PKKeyManager.getPublicKeyHex(context), signingKeys[0])
    }

    @Test
    fun `hasKeyPair returns true when key exists`() {
        P2PKKeyManager.getOrCreateKeyPair(context)

        assertTrue(P2PKKeyManager.hasKeyPair(context))
    }
}