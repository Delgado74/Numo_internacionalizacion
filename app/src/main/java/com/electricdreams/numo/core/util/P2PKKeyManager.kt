package com.electricdreams.numo.core.util

import android.content.Context
import android.util.Log
import com.electricdreams.numo.nostr.NostrKeyPair
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger

object P2PKKeyManager {
    private const val TAG = "P2PKKeyManager"

    private const val PREFS_NAME = "p2pk_prefs"
    private const val KEY_PUBLIC = "p2pk_public_key"
    private const val KEY_PRIVATE = "p2pk_private_key"

    private var cachedKeyPair: P2PKKeyPair? = null

    data class P2PKKeyPair(
        val publicKeyHex: String,
        val privateKeyHex: String
    )

    fun getOrCreateKeyPair(context: Context): P2PKKeyPair {
        cachedKeyPair?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPublic = prefs.getString(KEY_PUBLIC, null)
        val storedPrivate = prefs.getString(KEY_PRIVATE, null)

        if (storedPublic != null && storedPrivate != null) {
            val keyPair = P2PKKeyPair(storedPublic, storedPrivate)
            cachedKeyPair = keyPair
            return keyPair
        }

        val newKeyPair = generateNewKeyPair(context)
        cachedKeyPair = newKeyPair
        return newKeyPair
    }

    private fun generateNewKeyPair(context: Context): P2PKKeyPair {
        val nostrKeyPair = NostrKeyPair.generate()
        val publicKeyHex = nostrKeyPair.getHexPub()
        val privateKeyHex = nostrKeyPair.getHexSec()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PUBLIC, publicKeyHex)
            .putString(KEY_PRIVATE, privateKeyHex)
            .apply()

        Log.d(TAG, "Generated new P2PK keypair: $publicKeyHex")

        return P2PKKeyPair(publicKeyHex, privateKeyHex)
    }

    fun getPublicKeyHex(context: Context): String {
        return getOrCreateKeyPair(context).publicKeyHex
    }

    fun getSigningKey(context: Context): List<String> {
        return listOf(getPublicKeyHex(context))
    }

    fun hasKeyPair(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PUBLIC, null) != null
    }

    fun clearKeys(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        cachedKeyPair = null
        Log.d(TAG, "Cleared P2PK keys")
    }
}