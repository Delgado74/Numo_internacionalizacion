package com.electricdreams.numo.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.electricdreams.numo.ndef.CashuPaymentHelper
import com.electricdreams.numo.core.cashu.CashuWalletManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object OfflineTokenQueue {
    private const val TAG = "OfflineTokenQueue"
    private const val PREFS_NAME = "offline_token_queue"
    private const val KEY_PENDING_TOKENS = "pending_tokens"

    private var pendingTokens = mutableListOf<PendingToken>()

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    data class PendingToken(
        val tokenString: String,
        val mintUrl: String,
        val amount: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addToken(context: Context, tokenString: String, mintUrl: String, amount: Long) {
        val pendingToken = PendingToken(tokenString, mintUrl, amount)
        pendingTokens.add(pendingToken)
        saveToPrefs(context)
        Log.d(TAG, "Added token to offline queue. Queue size: ${pendingTokens.size}")
    }

    fun getPendingCount(): Int = pendingTokens.size

    fun getAllPending(): List<PendingToken> = pendingTokens.toList()

    fun processQueue(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        if (pendingTokens.isEmpty()) {
            Log.d(TAG, "No pending tokens to process")
            return
        }

        if (!isNetworkAvailable(context)) {
            Log.d(TAG, "No network available, skipping process")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val total = pendingTokens.size
            val successList = mutableListOf<PendingToken>()
            val failList = mutableListOf<PendingToken>()

            pendingTokens.forEachIndexed { index, token ->
                try {
                    onProgress(index + 1, total)
                    CashuWalletManager.getWallet()?.let { wallet ->
                        val result = CashuPaymentHelper.redeemToken(token.tokenString)
                        if (result != null) {
                            successList.add(token)
                            Log.d(TAG, "Redeemed token: ${token.amount} sats")
                        } else {
                            failList.add(token)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to redeem token: ${e.message}")
                    failList.add(token)
                }
            }

            pendingTokens.clear()
            pendingTokens.addAll(failList)
            saveToPrefs(context)

            Log.d(TAG, "Processed $total tokens: ${successList.size} success, ${failList.size} failed")
        }
    }

    fun clearQueue(context: Context) {
        pendingTokens.clear()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared offline token queue")
    }

    private fun saveToPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tokensJson = pendingTokens.map { "${it.tokenString}|${it.mintUrl}|${it.amount}|${it.timestamp}" }
        prefs.edit().putStringSet(KEY_PENDING_TOKENS, tokensJson.toSet()).apply()
    }

    fun loadFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tokensSet = prefs.getStringSet(KEY_PENDING_TOKENS, emptySet()) ?: emptySet()

        pendingTokens.clear()
        tokensSet.forEach { entry ->
            try {
                val parts = entry.split("|")
                if (parts.size >= 4) {
                    pendingTokens.add(PendingToken(
                        tokenString = parts[0],
                        mintUrl = parts[1],
                        amount = parts[2].toLong(),
                        timestamp = parts[3].toLong()
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse token entry: $entry")
            }
        }
        Log.d(TAG, "Loaded ${pendingTokens.size} pending tokens from prefs")
    }
}