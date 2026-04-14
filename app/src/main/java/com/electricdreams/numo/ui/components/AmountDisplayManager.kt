package com.electricdreams.numo.ui.components

import android.content.Context
import android.util.Log
import android.view.View
import com.electricdreams.numo.R
import android.widget.Button
import android.widget.TextView
import com.electricdreams.numo.core.cashu.CashuWalletManager
import android.widget.Toast
import com.electricdreams.numo.core.model.Amount
import com.electricdreams.numo.core.prefs.PreferenceStore
import com.electricdreams.numo.core.util.CurrencyManager
import com.electricdreams.numo.core.worker.BitcoinPriceWorker

/**
 * Manages amount display, formatting, and currency animations for the POS interface.
 */
class AmountDisplayManager(
    private val context: Context,
    private val amountDisplay: TextView,
    private val secondaryAmountDisplay: TextView,
    private val switchCurrencyButton: View,
    private val submitButton: Button,
    private val bitcoinPriceWorker: BitcoinPriceWorker?
) {

    var isUsdInputMode: Boolean = false
        private set
    var requestedAmount: Long = 0
        private set
    
    // Original amount in the selected unit (for stablesat: cents, for sat: sats)
    var originalAmount: Long = 0
        private set
    
    // Unit from mint selection (sat, usd, eur) - separate from fiat input mode
    var activeMintUnit: String = "sat"
        private set

    enum class AnimationType { NONE, DIGIT_ENTRY, CURRENCY_SWITCH }

    /** Initialize input mode from preferences */
    fun initializeInputMode() {
        val prefs = PreferenceStore.app(context)
        isUsdInputMode = prefs.getBoolean(KEY_INPUT_MODE, false)
    }

    /** Check if we can switch to USD (need price data) */
    fun canSwitchToUsd(): Boolean {
        if (!isUsdInputMode) {
            val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
            if (price <= 0) {
                return false
            }
        }
        return true
    }
    
    /** Set the active mint unit (sat, usd, eur) from mint selection */
    fun setActiveMintUnit(unit: String) {
        activeMintUnit = unit.lowercase()
        Log.d("AmountDisplayManager", "Set active mint unit: $activeMintUnit")
    }
    
    /** Check if current active unit is a fiat unit (stablesat USD/EUR) */
    fun isStablesatUnit(): Boolean {
        return activeMintUnit == "usd" || activeMintUnit == "eur"
    }

    /** Toggle between USD and Satoshi input modes */
    fun toggleInputMode(satoshiInput: StringBuilder, fiatInput: StringBuilder): Boolean {
        if (!canSwitchToUsd()) return false

        // Convert current input value to the other unit before switching
        val currentInputStr = getCurrentInput(satoshiInput, fiatInput).toString()
        val currencyCode = CurrencyManager.getInstance(context).getCurrentCurrency()
        val currency = Amount.Currency.fromCode(currencyCode)

        if (currentInputStr.isNotEmpty()) {
            try {
                if (isUsdInputMode) {
                    // Currently in fiat mode, converting to satoshi mode
                    val rawInput = currentInputStr.toLong()
                    // JPY/KRW input is already whole units, others are cents
                    val fiatAmount = if (currency.isZeroDecimal()) {
                        rawInput.toDouble()
                    } else {
                        rawInput / 100.0
                    }
                    val satoshis = fiatToSatoshis(fiatAmount)
                    satoshiInput.clear()
                    satoshiInput.append(satoshis.toString())
                } else {
                    // Currently in satoshi mode, converting to fiat mode
                    val satoshis = currentInputStr.toLong()
                    val fiatAmount = bitcoinPriceWorker?.satoshisToFiat(satoshis) ?: 0.0
                    // JPY/KRW stored as whole units, others as cents
                    val storedValue = if (currency.isZeroDecimal()) {
                        fiatAmount.toLong()
                    } else {
                        (fiatAmount * 100).toLong()
                    }
                    fiatInput.clear()
                    fiatInput.append(storedValue.toString())
                }
            } catch (e: NumberFormatException) {
                // If conversion fails, don't change anything
            }
        }

        isUsdInputMode = !isUsdInputMode
        // Persist the input mode preference
        PreferenceStore.app(context).putBoolean(KEY_INPUT_MODE, isUsdInputMode)

        return true
    }

    /** Update the display with current input values and optional animation */
    fun updateDisplay(
        satoshiInput: StringBuilder,
        fiatInput: StringBuilder,
        animationType: AnimationType
    ) {
        val currentInputStr = getCurrentInput(satoshiInput, fiatInput).toString()
        var amountDisplayText: String
        var secondaryDisplayText: String
        var satsValue: Long = 0
        
        // Check if we have Bitcoin price data
        val hasBitcoinPrice = (bitcoinPriceWorker?.getCurrentPrice() ?: 0.0) > 0
        
        // Determine if we should treat input as fiat (either USD input mode OR active mint unit is USD/EUR)
        val isFiatInput = isUsdInputMode || isStablesatUnit()

        if (isFiatInput) {
            // Input mode: fiat (either from toggle or from mint's stablesat unit)
            // Display based on which fiat mode we're in
            if (isUsdInputMode) {
                // Regular fiat input mode (user toggled to fiat)
                val currencyCode = CurrencyManager.getInstance(context).getCurrentCurrency()
                val currency = Amount.Currency.fromCode(currencyCode)
                
                val rawInput = if (currentInputStr.isEmpty()) 0L else currentInputStr.toLong()
                val fiatCents = if (currency.isZeroDecimal()) {
                    rawInput * 100
                } else {
                    rawInput
                }
                
                amountDisplayText = Amount(fiatCents, currency).toString()
                val fiatAmount = fiatCents / 100.0
                satsValue = fiatToSatoshis(fiatAmount)
            } else {
                // Stablesat mode: input is in cents (like regular fiat mode)
                // User types: 1 → $0.01, 100 → $1.00
                val rawInput = if (currentInputStr.isEmpty()) 0L else currentInputStr.toLong()
                val fiatCents = rawInput  // Input is in cents: "1" = 1 cent = $0.01
                val fiatAmount = fiatCents / 100.0  // Convert to dollars for calculation
                satsValue = fiatToSatoshis(fiatAmount)
                
                // Format display as fiat (with 2 decimals)
                amountDisplayText = "$${String.format("%.2f", fiatAmount)}"
            }
            
            // Secondary display always shows sats equivalent
            Log.d("AmountDisplayManager", "isFiatInput=$isFiatInput, isStablesatUnit=${isStablesatUnit()}, satsValue=$satsValue, price=${bitcoinPriceWorker?.getCurrentPrice()}")
            secondaryDisplayText = Amount(satsValue, Amount.Currency.BTC).toString()
        } else {
            // Input mode: satoshi, display sats as primary, fiat as secondary
            satsValue = if (currentInputStr.isEmpty()) 0L else currentInputStr.toLong()
            amountDisplayText = formatAmount(currentInputStr)
            
            if (hasBitcoinPrice) {
                // Show fiat conversion with swap icon
                val fiatValue = bitcoinPriceWorker?.satoshisToFiat(satsValue) ?: 0.0
                secondaryDisplayText = bitcoinPriceWorker?.formatFiatAmount(fiatValue)
                    ?: CurrencyManager.getInstance(context).formatCurrencyAmount(0.0)
                switchCurrencyButton.visibility = View.VISIBLE
            } else {
                // No price data - just show "BTC" without swap icon
                secondaryDisplayText = context.getString(R.string.pos_secondary_amount_btc_label)
                switchCurrencyButton.visibility = View.GONE
            }
        }

        // Update secondary amount display with animation when switching currencies
        if (animationType == AnimationType.CURRENCY_SWITCH) {
            // Animate secondary display in opposite direction of main display
            animateSecondaryCurrencySwitch(secondaryDisplayText, isUsdInputMode)
        } else {
            secondaryAmountDisplay.text = secondaryDisplayText
        }

        // Update main amount display
        if (amountDisplay.text.toString() != amountDisplayText) {
            when (animationType) {
                AnimationType.CURRENCY_SWITCH -> animateCurrencySwitch(amountDisplayText, !isUsdInputMode)
                AnimationType.DIGIT_ENTRY -> animateDigitEntry(amountDisplayText)
                AnimationType.NONE -> amountDisplay.text = amountDisplayText
            }
        }

        // Update submit button
        if (satsValue > 0 || (isStablesatUnit() && currentInputStr.isNotEmpty())) {
            // Always show a simple, localized "Charge" label without the amount
            submitButton.text = context.getString(R.string.pos_charge_button)
            submitButton.isEnabled = true
            
            // For Lightning payment: use the original amount in the selected unit
            // If stablesat (USD/EUR): use cents, if sat: use sats
            if (isStablesatUnit()) {
                // Input is in cents for USD/EUR
                originalAmount = currentInputStr.toLongOrNull() ?: 0L
                // requestedAmount is used for display only (in sats)
                requestedAmount = satsValue
            } else {
                // Regular sat mode
                originalAmount = satsValue
                requestedAmount = satsValue
            }
        } else {
            requestedAmount = 0
            originalAmount = 0
            submitButton.text = context.getString(R.string.pos_charge_button)
            submitButton.isEnabled = false
        }
    }

    /** Reset requested amount */
    fun resetRequestedAmount() {
        requestedAmount = 0
    }

    /** Get the current input StringBuilder based on input mode */
    fun getCurrentInput(satoshiInput: StringBuilder, fiatInput: StringBuilder): StringBuilder {
        return if (isUsdInputMode) fiatInput else satoshiInput
    }

    /** Show shake animation on amount display */
    fun shakeAmountDisplay() {
        val shake = object : android.view.animation.Animation() {
            override fun applyTransformation(interpolatedTime: Float, t: android.view.animation.Transformation) {
                val offset = (Math.sin(interpolatedTime * Math.PI * 8) * 10).toFloat()
                t.matrix.setTranslate(offset, 0f)
            }
        }.apply {
            duration = 400
        }
        amountDisplay.startAnimation(shake)
    }

    /** Convert fiat amount (in currency units, not cents) to satoshis */
    private fun fiatToSatoshis(fiatAmount: Double): Long {
        Log.d("AmountDisplayManager", "fiatToSatoshis: amount=$fiatAmount, isStablesat=${isStablesatUnit()}")
        
        // For stablesat (active mint unit is USD/EUR):
        if (isStablesatUnit()) {
            // Try to get USD price first
            var usdPrice = bitcoinPriceWorker?.getBtcUsdPrice() ?: 0.0
            Log.d("AmountDisplayManager", "getBtcUsdPrice: $usdPrice")
            
            // If no USD price, try current price (might be EUR, GBP, etc)
            if (usdPrice <= 0) {
                usdPrice = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
                Log.d("AmountDisplayManager", "getCurrentPrice: $usdPrice")
            }
            
            // Log the currency being used
            val currency = try {
                val cm = CurrencyManager.getInstance(context)
                cm.getCurrentCurrency()
            } catch (e: Exception) { "unknown" }
            Log.d("AmountDisplayManager", "Current currency from CurrencyManager: $currency")
            
            if (usdPrice > 0) {
                Log.d("AmountDisplayManager", "Converting: $fiatAmount / $usdPrice * 100000000")
                val btcAmount = fiatAmount / usdPrice
                return (btcAmount * 100_000_000).toLong()
            } else {
                Log.d("AmountDisplayManager", "No price - returning 0 (payment uses USD)")
                return 0L
            }
        }
        
        // For regular fiat input mode
        val price = bitcoinPriceWorker?.getCurrentPrice() ?: 0.0
        if (price <= 0) return 0L
        
        val btcAmount = fiatAmount / price
        return (btcAmount * 100_000_000).toLong()
    }

    /** Format amount using Amount class */
    private fun formatAmount(amount: String): String = try {
        val value = if (amount.isEmpty()) 0L else amount.toLong()
        Amount(value, Amount.Currency.BTC).toString()
    } catch (_: NumberFormatException) {
        ""
    }

    /** Animate currency switch transition */
    private fun animateCurrencySwitch(newText: String, isUp: Boolean) {
        amountDisplay.animate().cancel()
        if (amountDisplay.alpha == 0f) {
            amountDisplay.alpha = 1f
            amountDisplay.translationY = 0f
        }
        val exitTranslation = if (isUp) -50f else 50f
        val enterStartTranslation = if (isUp) 50f else -50f
        amountDisplay.animate()
            .alpha(0f)
            .translationY(exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                amountDisplay.text = newText
                amountDisplay.translationY = enterStartTranslation
                amountDisplay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** Animate digit entry */
    private fun animateDigitEntry(newText: String) {
        amountDisplay.animate().cancel()
        amountDisplay.animate()
            .alpha(0.7f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(80)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .withEndAction {
                amountDisplay.text = newText
                amountDisplay.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                    .start()
            }
            .start()
    }

    /** Animate secondary currency switch */
    private fun animateSecondaryCurrencySwitch(newText: String, isUp: Boolean) {
        secondaryAmountDisplay.animate().cancel()
        switchCurrencyButton.animate().cancel()
        
        // Convert 2dp to pixels for the icon offset
        val iconOffsetPx = 2f * context.resources.displayMetrics.density
        
        if (secondaryAmountDisplay.alpha == 0f) {
            secondaryAmountDisplay.alpha = 1f
            secondaryAmountDisplay.translationY = 0f
        }
        if (switchCurrencyButton.alpha == 0f) {
            switchCurrencyButton.alpha = 1f
            switchCurrencyButton.translationY = iconOffsetPx
        }
        
        val exitTranslation = if (isUp) -30f else 30f
        val enterStartTranslation = if (isUp) 30f else -30f
        
        // Get current icon translation (should be iconOffsetPx, but handle case where it's not initialized)
        val currentIconTranslation = switchCurrencyButton.translationY
        val baseIconTranslation = if (currentIconTranslation == 0f) iconOffsetPx else currentIconTranslation
        
        // Animate both text and icon together
        secondaryAmountDisplay.animate()
            .alpha(0f)
            .translationY(exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                secondaryAmountDisplay.text = newText
                secondaryAmountDisplay.translationY = enterStartTranslation
                secondaryAmountDisplay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
            
        // Animate icon with same timing, preserving the 2dp offset
        switchCurrencyButton.animate()
            .alpha(0f)
            .translationY(baseIconTranslation + exitTranslation)
            .setDuration(150)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                switchCurrencyButton.translationY = baseIconTranslation + enterStartTranslation
                switchCurrencyButton.animate()
                    .alpha(1f)
                    .translationY(iconOffsetPx)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    companion object {
        private const val KEY_INPUT_MODE = "inputMode" // true = fiat, false = satoshi
    }
}
