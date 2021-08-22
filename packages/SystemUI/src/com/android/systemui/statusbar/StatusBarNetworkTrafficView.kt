/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue.COMPLEX_UNIT_PX
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView

import com.android.systemui.R
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.statusbar.StatusBarIconView.STATE_DOT
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.policy.NetworkTrafficState

/**
 * Layout class for statusbar network traffic indicator
 */
class StatusBarNetworkTrafficView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : FrameLayout(
        context,
        attrs,
        defStyleAttr,
        defStyleRes,
    ), StatusIconDisplayable {

    private var dotView: StatusBarIconView? = null
    private var trafficGroup: FrameLayout? = null
    private var trafficRate: TextView? = null
    private var state: NetworkTrafficState? = null
    private var slot: String? = null
    private var visibleState = -1

    override fun getSlot() = slot

    fun setSlot(newSlot: String?) {
        slot = newSlot
    }

    override fun setStaticDrawableColor(color: Int) {
        trafficRate?.setTextColor(color)
        dotView?.setDecorColor(color)
    }

    override fun setDecorColor(color: Int) {
        dotView?.setDecorColor(color)
    }

    override fun isIconVisible() = state?.visible == true

    override fun setVisibleState(newVisibleState: Int, animate: Boolean) {
        logD("setVisibleState, newVisibleState = $newVisibleState")
        if (newVisibleState == visibleState) {
            return
        }
        visibleState = newVisibleState
        trafficGroup?.visibility = if (visibleState == STATE_ICON) VISIBLE else GONE
        dotView?.visibility = if (visibleState == STATE_DOT) VISIBLE else GONE
    }

    override fun getVisibleState() = visibleState

    override fun getDrawingRect(outRect: Rect) {
        super.getDrawingRect(outRect)
        outRect.left += translationX.toInt()
        outRect.right += translationX.toInt()
        outRect.top += translationY.toInt()
        outRect.bottom += translationY.toInt()
    }

    override fun onDarkChanged(areas: ArrayList<Rect>, darkIntensity: Float, tint: Int) {
        val areaTint: Int = DarkIconDispatcher.getTint(areas, this, tint)
        trafficRate?.setTextColor(areaTint)
        dotView?.setDecorColor(areaTint)
        dotView?.setIconColor(areaTint, false)
    }

    fun applyNetworkTrafficState(newState: NetworkTrafficState) {
        logD("applyNetworkTrafficState, state = $state, newState = $newState")
        if (state == null) {
            state = newState.copy()
            initViewState()
            requestLayout()
        } else if (state != newState) {
            updateState(newState.copy())
        }
    }

    private fun setWidgets() {
        trafficGroup = findViewById(R.id.traffic_group)
        trafficRate = findViewById(R.id.traffic_rate)
        dotView = findViewById<StatusBarIconView>(R.id.dot_view)?.also {
            it.visibleState = STATE_DOT
        }
    }

    private fun updateState(newState: NetworkTrafficState) {
        logD("updateState, newState = $newState")
        if (state?.size != newState.size) {
            logD("setTextSize")
            trafficRate?.setTextSize(COMPLEX_UNIT_PX, newState.size.toFloat())
        }
        if (state?.rate != newState.rate) {
            logD("setText")
            trafficRate?.setText(newState.rate)
        }
        if (state?.rateVisible != newState.rateVisible) {
            logD("setRateVisibility")
            trafficRate?.visibility = if (newState.rateVisible) VISIBLE else GONE
        }
        if (state?.visible != newState.visible) {
            logD("setVisibility")
            visibility = if (newState.visible) VISIBLE else GONE
        }
        state = newState
    }

    private fun initViewState() {
        logD("initViewState")
        state?.let {
            trafficRate?.let { tr ->
                tr.setTextSize(COMPLEX_UNIT_PX, it.size.toFloat())
                tr.text = it.rate?.toString()
                tr.visibility = if (it.rateVisible) VISIBLE else GONE
            }
            visibility = if (it.visible) VISIBLE else GONE
        }
    }

    override fun toString() = "StatusBarNetworkTrafficView(slot = $slot, state = $state)"

    companion object {
        private const val TAG = "StatusBarNetworkTrafficView"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private const val DEFAULT_TEXT_SIZE = 40f

        @JvmStatic
        fun fromContext(context: Context, slot: String): StatusBarNetworkTrafficView {
            val layoutInflater = LayoutInflater.from(context)
            val layout = layoutInflater.inflate(R.layout.status_bar_network_traffic_view, null)
            return (layout as StatusBarNetworkTrafficView).apply {
                this.slot = slot
                setWidgets()
                visibleState = STATE_ICON
            }
        }

        private fun logD(msg: String) {
            if (DEBUG) Log.d(TAG, msg)
        }
    }
}
