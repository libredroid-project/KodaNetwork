/*
 * Copyright (c) 2026 KodaHosting
 *
 * This file is part of KodaHosting (KodaNetwork).
 * KodaHosting is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3 of the License.
 *
 * KodaHosting is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY, without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * KodaHosting. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-FileCopyrightText: 2026 KodaHosting
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.kodanetwork.mchost.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import eu.kodanetwork.mchost.R

class TiltEffectHelper(
    context: Context, 
    private val viewToTilt: View,
    private val enableGlare: Boolean = false
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var glareView: ImageView? = null

    init {
        if (enableGlare && viewToTilt is ViewGroup) {
            glareView = ImageView(context)
            glareView?.setBackgroundResource(R.drawable.bg_glass_glare_layer)
            glareView?.alpha = 0f
            
            // twice the size so the layer can move around without showing its edges
            val params = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            glareView?.scaleX = 2f
            glareView?.scaleY = 2f

            viewToTilt.addView(glareView, params)
        }
    }

    fun register() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun unregister() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        val orientationVals = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientationVals)

        // orientationVals[1] is pitch, the phone tipping forward or back
        // orientationVals[2] is roll, the phone tipping left or right
        val pitch = Math.toDegrees(orientationVals[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientationVals[2].toDouble()).toFloat()

        // parallax offset from where the view sits on the screen
        val location = IntArray(2)
        viewToTilt.getLocationOnScreen(location)
        val screenHeight = android.content.res.Resources.getSystem().displayMetrics.heightPixels
        val viewY = location[1]
        // yOffset runs from ~ -0.5 at the top of the screen to +0.5 at the bottom
        val yOffset = (viewY - screenHeight / 2f) / screenHeight.toFloat()
        
        // mix the screen position into the pitch so cards tilt organically
        val parallaxPitch = pitch + (yOffset * 15f)

        // clamp it so nothing flips over, cards only turn a little or the corners clip
        val maxRotation = 6f
        val clampedPitch = parallaxPitch.coerceIn(-maxRotation, maxRotation)
        val clampedRoll = roll.coerceIn(-maxRotation, maxRotation)

        // write the rotation straight in, it stays smooth at 60fps
        viewToTilt.rotationX = -clampedPitch * 0.3f
        viewToTilt.rotationY = clampedRoll * 0.3f

        glareView?.let {
            if (it.alpha == 0f) {
                it.animate().alpha(1f).setDuration(500).start()
            }
            // glare moves against the tilt, plus the offset from the screen position
            it.translationX = roll * 25f
            it.translationY = (parallaxPitch * 25f) + (yOffset * 150f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // nothing to do here
    }
}
