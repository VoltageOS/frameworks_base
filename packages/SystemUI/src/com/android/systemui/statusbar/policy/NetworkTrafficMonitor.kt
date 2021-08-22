/*
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.TrafficStats
import android.net.Uri
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.provider.Settings.System.NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX
import android.provider.Settings.System.NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX
import android.provider.Settings.System.NETWORK_TRAFFIC_ENABLED
import android.provider.Settings.System.NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR
import android.provider.Settings.System.NETWORK_TRAFFIC_UNIT_TEXT_SIZE
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.DataUnit
import android.util.Log
import android.util.TypedValue

import androidx.annotation.GuardedBy

import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SystemSettings

import java.text.DecimalFormat

import javax.inject.Inject

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SysUISingleton
class NetworkTrafficMonitor @Inject constructor(
    private val wakefulnessLifecycle: WakefulnessLifecycle,
    private val systemSettings: SystemSettings,
    context: Context,
    userTracker: UserTracker
) : UserTracker.Callback {

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    private val stateMutex = Mutex()

    @GuardedBy("stateMutex")
    private val state = NetworkTrafficState()
    private val callbacks = mutableSetOf<Callback>()

    private val defaultTextSize = context.resources.getDimension(
        R.dimen.network_traffic_unit_text_default_size
    ).toInt()
    private val defaultScaleFactor: Float

    private var trafficUpdateJob: Job? = null

    // Threshold value in KiB/S
    @GuardedBy("stateMutex")
    private var txThreshold: Long = 0
    @GuardedBy("stateMutex")
    private var rxThreshold: Long = 0

    // Whether traffic monitor is enabled
    @GuardedBy("stateMutex")
    private var enabled = false

    // RelativeSizeSpan for network traffic rate text
    @GuardedBy("stateMutex")
    private var rsp: RelativeSizeSpan

    // Whether external callbacks and observers are registered
    private var registered = false

    // Whether there is an active internet connection
    private var networkAvailable = false

    // Whether device is dozing, should not run the monitor
    // in this state
    private var isDozing = false

    private val settingsObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            logD("settings changed for $uri")
            coroutineScope.launch {
                stateMutex.withLock {
                    when (uri.lastPathSegment) {
                        NETWORK_TRAFFIC_ENABLED -> {
                            updateEnabledStateLocked()
                            notifyCallbacksLocked()
                        }
                        NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX -> updateTxAutoHideThresholdLocked()
                        NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX -> updateRxAutoHideThresholdLocked()
                        NETWORK_TRAFFIC_UNIT_TEXT_SIZE -> {
                            updateUnitTextSizeLocked()
                            notifyCallbacksLocked()
                        }
                        NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR -> {
                            updateRateTextScaleLocked()
                            notifyCallbacksLocked()
                        }
                    }
                }
            }
        }
    }

    // To schedule / unschedule task based on connectivity
    private val networkCallback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            logD("onAvailable")
            networkAvailable = true
            scheduleJob()
        }

        override fun onLost(network: Network) {
            logD("onLost")
            networkAvailable = false
            cancelScheduledJob()
        }
    }

    // To kill / start the timer thread if device is going to sleep / waking up
    private val wakefulnessObserver = object : WakefulnessLifecycle.Observer {
        override fun onStartedGoingToSleep() {
            logD("onStartedGoingToSleep")
            isDozing = true
            cancelScheduledJob()
        }

        override fun onStartedWakingUp() {
            logD("onStartedWakingUp")
            isDozing = false
            scheduleJob()
        }
    }

    init {
        val typedValue = TypedValue()
        context.resources.getValue(
            R.dimen.network_traffic_rate_text_default_scale_factor,
            typedValue,
            true
        )
        defaultScaleFactor = typedValue.float
        rsp = RelativeSizeSpan(defaultScaleFactor)
        loadSettings()
        register(
            NETWORK_TRAFFIC_ENABLED,
            NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX,
            NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX,
            NETWORK_TRAFFIC_UNIT_TEXT_SIZE,
            NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR
        )
        userTracker.addCallback(this) {
            it.run()
        }
    }

    private fun register(vararg keys: String) {
        keys.forEach {
            systemSettings.registerContentObserverForUser(
                it,
                settingsObserver,
                UserHandle.USER_ALL
            )
        }
    }

    override fun onUserChanged(newUser: Int, userContext: Context) {
        loadSettings()
    }

    private fun loadSettings() {
        coroutineScope.launch {
            stateMutex.withLock {
                updateEnabledStateLocked()
                updateTxAutoHideThresholdLocked()
                updateRxAutoHideThresholdLocked()
                updateUnitTextSizeLocked()
                updateRateTextScaleLocked()
            }
        }
    }

    @GuardedBy("stateMutex")
    private suspend fun updateEnabledStateLocked() {
        enabled = withContext(Dispatchers.IO) {
            systemSettings.getIntForUser(NETWORK_TRAFFIC_ENABLED, 0, UserHandle.USER_CURRENT) == 1
        }
        logD("enabled = $enabled")
        if (enabled) {
            register()
        } else {
            unregister()
            cancelScheduledJob()
        }
    }

    @GuardedBy("stateMutex")
    private suspend fun updateTxAutoHideThresholdLocked() {
        txThreshold = withContext(Dispatchers.IO) {
            DataUnit.KIBIBYTES.toBytes(
                systemSettings.getLongForUser(
                    NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_TX,
                    0,
                    UserHandle.USER_CURRENT
                )
            )
        }
        logD("txThreshold = $txThreshold")
    }

    @GuardedBy("stateMutex")
    private suspend fun updateRxAutoHideThresholdLocked() {
        rxThreshold = withContext(Dispatchers.IO) {
            DataUnit.KIBIBYTES.toBytes(
                systemSettings.getLongForUser(
                    NETWORK_TRAFFIC_AUTO_HIDE_THRESHOLD_RX,
                    0,
                    UserHandle.USER_CURRENT
                )
            )
        }
        logD("rxThreshold = $rxThreshold")
    }

    @GuardedBy("stateMutex")
    private suspend fun updateUnitTextSizeLocked() {
        state.size = withContext(Dispatchers.IO) {
            systemSettings.getIntForUser(
                NETWORK_TRAFFIC_UNIT_TEXT_SIZE,
                defaultTextSize,
                UserHandle.USER_CURRENT
            )
        }
        logD("defaultTextSize = $defaultTextSize, size = ${state.size}")
        notifyCallbacksLocked()
    }

    @GuardedBy("stateMutex")
    private suspend fun updateRateTextScaleLocked() {
        val scaleFactor = withContext(Dispatchers.IO) {
            systemSettings.getIntForUser(
                NETWORK_TRAFFIC_RATE_TEXT_SCALE_FACTOR,
                (defaultScaleFactor * 10).toInt(),
                UserHandle.USER_CURRENT
            ) / 10f
        }
        logD("scaleFactor = $scaleFactor")
        rsp = RelativeSizeSpan(scaleFactor)
    }

    /**
     * Register a [Callback] to listen to updates on
     * [NetworkTrafficState].
     *
     * @param callback the callback to register.
     */
    fun addCallback(callback: Callback) {
        logD("adding callback")
        callbacks.add(callback)
    }

    /**
     * Unregister an already registered callback.
     *
     * @param callback the callback to unregister.
     */
    fun removeCallback(callback: Callback) {
        logD("removing callback")
        callbacks.remove(callback)
    }

    private suspend fun notifyCallbacks() {
        stateMutex.withLock {
            notifyCallbacksLocked()
        }
    }

    @GuardedBy("stateMutex")
    private suspend fun notifyCallbacksLocked() {
        logD("notifying callbacks about new state = $state")
        withContext(Dispatchers.Main) {
            callbacks.forEach { it.onTrafficUpdate(state) }
        }
    }

    private fun register() {
        if (registered) return
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        wakefulnessLifecycle.addObserver(wakefulnessObserver)
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        connectivityManager.unregisterNetworkCallback(networkCallback)
        wakefulnessLifecycle.removeObserver(wakefulnessObserver)
        registered = false
    }

    private fun scheduleJob() {
        if (trafficUpdateJob != null) {
            logD("Job is already scheduled, returning")
            return
        }
        if (!networkAvailable) {
            logD("No active connection, returning")
            return
        }
        if (isDozing) {
            logD("Device is dozing, returning")
            return
        }
        logD("scheduling job")
        trafficUpdateJob = createNewJob().also {
            it.invokeOnCompletion {
                coroutineScope.launch {
                    stateMutex.withLock {
                        state.visible = false
                        notifyCallbacksLocked()
                    }
                }
            }
        }
    }

    private fun cancelScheduledJob() {
        if (trafficUpdateJob == null) {
            logD("Job is already cancelled, returning")
            return
        }
        logD("Cancelling job")
        trafficUpdateJob?.cancel()
        trafficUpdateJob = null
    }

    private fun createNewJob(): Job {
        return coroutineScope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                state.visible = true
            }
            var currentRxBytes = TrafficStats.getTotalRxBytes()
            var currentTxBytes = TrafficStats.getTotalTxBytes()
            var lastUpdateTime = SystemClock.uptimeNanos()
            var updateRx = true
            do {
                logD("updateRx = $updateRx")
                val rxBytes = TrafficStats.getTotalRxBytes()
                val txBytes = TrafficStats.getTotalTxBytes()
                val duration = SystemClock.uptimeNanos() - lastUpdateTime
                val rxTrans = ((rxBytes - currentRxBytes) * NANO_SEC) / duration
                val txTrans = ((txBytes - currentTxBytes) * NANO_SEC) / duration
                logD("rxBytes = $rxBytes, currentRxBytes = $currentRxBytes" +
                        ", rxTrans = $rxTrans, rxThreshold = $rxThreshold")
                logD("txBytes = $txBytes, currentTxBytes = $currentTxBytes" +
                        ", txTrans = $txTrans, txThreshold = $txThreshold")
                currentRxBytes = rxBytes
                currentTxBytes = txBytes
                stateMutex.withLock {
                    val thresholdMet = rxTrans >= rxThreshold && txTrans >= txThreshold // Show iff both thresholds are met
                    logD("thresholdMet = $thresholdMet")
                    state.rateVisible = thresholdMet
                    if (thresholdMet) {
                        state.rate = getRateFormatted(if (updateRx) rxTrans else txTrans)
                    }
                    notifyCallbacksLocked()
                }
                lastUpdateTime = SystemClock.uptimeNanos()
                updateRx = !updateRx
                delay(1000)
            } while (isActive)
        }
    }

    @GuardedBy("stateMutex")
    private fun getRateFormatted(bytes: Long): SpannableString {
        var unit: String
        var rateString: String
        var rate: Float = bytes / KiB.toFloat()
        var i = 0
        while (true) {
            rate /= KiB
            if (rate >= 0.9f && rate < 1) {
                unit = Units[i + 1]
                break
            } else if (rate < 0.9) {
                rate *= KiB
                unit = Units[i]
                break
            }
            i++
        }
        rateString = getFormattedString(rate)
        logD("bytes = $bytes, rate = $rate, rateString = $rateString, unit = $unit")
        return SpannableString(rateString + LINE_SEPARATOR + unit).also {
            it.setSpan(rsp, 0, rateString.length, 0)
        }
    }

    /**
     * Callback interface that clients can implement
     * and register with resgisterListener method to
     * listen to updates on [NetworkTrafficState].
     */
    interface Callback {
        /**
         * Called to notify clients about possible state changes.
         *
         * @param state new updated state.
         */
        fun onTrafficUpdate(state: NetworkTrafficState)
    }

    companion object {
        private const val TAG = "NetworkTrafficMonitor"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private const val LINE_SEPARATOR = "\n"

        private const val NANO_SEC = 1000 * 1000 * 1000

        private val Units = arrayOf("KiB/s", "MiB/s", "GiB/s")
        private val SingleDecimalFmt = DecimalFormat("00.0")
        private val DoubleDecimalFmt = DecimalFormat("0.00")
        private val KiB: Long = DataUnit.KIBIBYTES.toBytes(1)

        private fun getFormattedString(rate: Float) =
            when {
                rate < 10 -> DoubleDecimalFmt.format(rate)
                rate < 100 -> SingleDecimalFmt.format(rate)
                rate < 1000 -> rate.toInt().toString()
                else -> rate.toString()
            }

        private fun logD(msg: String?) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }
}

/**
 * Class holding relevant information for the view in
 * StatusBar to update or instantiate from.
 */
data class NetworkTrafficState(
    var rate: Spanned? = null,
    var size: Int = 0,
    @JvmField var visible: Boolean = false,
    var rateVisible: Boolean = true,
)
