// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.os.Build
import android.os.CombinedVibration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.InputDevice
import com.metallic.chiaki.common.Preferences
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Plays the console's rumble on the gamepad that is driving the stream (CloudPad vibration patch).
 *
 * Two sources are merged by taking the stronger value per motor (never added, so they can't
 * double up):
 *  - legacy rumble ([onLegacyRumble]): what a PS4, or a PS5 talking to a "DualShock 4", sends;
 *  - DualSense haptics converted to rumble by native code ([onHapticRumble]), only present when
 *    the optional DualSense mode is on.
 *
 * All vibrator calls run on one background thread so neither the network threads that deliver
 * the events nor the UI thread ever block on the input service.
 */
class RumbleController(
	private val preferences: Preferences,
	/** Device id of the gamepad that last sent input, or -1. */
	private val activeDeviceId: () -> Int
)
{
	companion object
	{
		private const val TAG = "CloudPadRumble"
		/** Length of each one-shot effect; refreshed while the rumble is held. */
		private const val EFFECT_DURATION_MS = 5000L
		private const val REFRESH_INTERVAL_MS = 2500L
		/** A held rumble with no new event for this long is dropped (stalled stream safety net). */
		private const val LEGACY_HOLD_MAX_MS = 30_000L
		/** Native code repeats haptics every 100 ms while active; silence after this = stop. */
		private const val HAPTIC_TIMEOUT_MS = 300L
		/** At most one vibrator call per this interval; later values are coalesced. */
		private const val MIN_SEND_INTERVAL_MS = 25L
		private const val LOG_FIRST_EVENTS = 5
	}

	private val thread = HandlerThread("CloudPadRumble").apply { start() }
	private val handler = Handler(thread.looper)

	// Settings snapshot, refreshed by reloadPreferences() (every connect).
	@Volatile private var enabled = true
	@Volatile private var intensity = 1.0f
	@Volatile private var hapticsEnabled = true
	@Volatile private var hapticsGain = 1.0f

	// State below is only touched on [thread].
	private var legacyLeft = 0
	private var legacyRight = 0
	private var legacyTime = 0L
	private var hapticLeft = 0
	private var hapticRight = 0
	private var hapticTime = 0L

	private var playingLeft = 0
	private var playingRight = 0
	private var lastSendTime = 0L
	private var playingDevice: InputDevice? = null
	private var applyPending = false
	private var released = false
	private var loggedDevice: Int = Int.MIN_VALUE
	// Diagnostics: only the first few events of each kind reach logcat.
	private var legacyLogged = 0
	private var hapticLogged = 0

	init { reloadPreferences() }

	fun reloadPreferences()
	{
		legacyLogged = 0
		hapticLogged = 0
		Log.w(TAG, "Settings: rumble=${preferences.rumbleEnabled} intensity=${preferences.rumbleIntensity} " +
			"haptics=${preferences.hapticsRumbleEnabled} gain=${preferences.hapticsRumbleGain} dualsense=${preferences.dualsenseMode}")
		enabled = preferences.rumbleEnabled
		intensity = preferences.rumbleIntensity / 100f
		hapticsEnabled = preferences.hapticsRumbleEnabled
		hapticsGain = preferences.hapticsRumbleGain / 100f
	}

	fun onLegacyRumble(left: Int, right: Int)
	{
		handler.post {
			if(legacyLogged < LOG_FIRST_EVENTS)
			{
				legacyLogged++
				Log.w(TAG, "Legacy rumble event #$legacyLogged: left=$left right=$right enabled=$enabled intensity=$intensity")
			}
			legacyLeft = left.coerceIn(0, 255)
			legacyRight = right.coerceIn(0, 255)
			legacyTime = SystemClock.uptimeMillis()
			apply()
		}
	}

	fun onHapticRumble(left: Int, right: Int)
	{
		handler.post {
			if(hapticLogged < LOG_FIRST_EVENTS)
			{
				hapticLogged++
				Log.w(TAG, "Haptic rumble event #$hapticLogged: left=$left right=$right enabled=$hapticsEnabled gain=$hapticsGain")
			}
			hapticLeft = left.coerceIn(0, 255)
			hapticRight = right.coerceIn(0, 255)
			hapticTime = SystemClock.uptimeMillis()
			apply()
		}
	}

	/** Stops any vibration and forgets the current values (stream paused, ended or restarted). */
	fun stop()
	{
		handler.post {
			legacyLeft = 0; legacyRight = 0
			hapticLeft = 0; hapticRight = 0
			cancelVibration()
		}
	}

	/** Stops and ends the worker thread; the controller can't be used afterwards. */
	fun release()
	{
		handler.post {
			legacyLeft = 0; legacyRight = 0
			hapticLeft = 0; hapticRight = 0
			cancelVibration()
			released = true
			thread.quitSafely()
		}
	}

	private val applyRunnable = Runnable {
		applyPending = false
		apply()
	}

	private val refreshRunnable = Runnable { apply(forceRefresh = true) }

	private fun apply(forceRefresh: Boolean = false)
	{
		if(released)
			return
		val now = SystemClock.uptimeMillis()

		if(legacyLeft != 0 || legacyRight != 0)
		{
			if(now - legacyTime > LEGACY_HOLD_MAX_MS)
			{
				legacyLeft = 0; legacyRight = 0
			}
		}
		if(hapticLeft != 0 || hapticRight != 0)
		{
			if(now - hapticTime > HAPTIC_TIMEOUT_MS)
			{
				hapticLeft = 0; hapticRight = 0
			}
		}

		var left = 0
		var right = 0
		if(enabled)
		{
			var l = legacyLeft.toFloat()
			var r = legacyRight.toFloat()
			if(hapticsEnabled)
			{
				l = max(l, hapticLeft * hapticsGain)
				r = max(r, hapticRight * hapticsGain)
			}
			left = (l * intensity).roundToInt().coerceIn(0, 255)
			right = (r * intensity).roundToInt().coerceIn(0, 255)
		}

		handler.removeCallbacks(refreshRunnable)

		if(left == 0 && right == 0)
		{
			cancelVibration()
			return
		}

		// Keep re-evaluating while something plays: refreshes the one-shot before it runs out,
		// and lets the haptic timeout / legacy hold limit end it if the stream goes quiet.
		val haptic = hapticsEnabled && (hapticLeft != 0 || hapticRight != 0)
		handler.postDelayed(refreshRunnable, if(haptic) HAPTIC_TIMEOUT_MS else REFRESH_INTERVAL_MS)

		val unchanged = left == playingLeft && right == playingRight
		if(unchanged && !(forceRefresh && now - lastSendTime >= REFRESH_INTERVAL_MS))
			return

		if(!unchanged && now - lastSendTime < MIN_SEND_INTERVAL_MS)
		{
			if(!applyPending)
			{
				applyPending = true
				handler.postDelayed(applyRunnable, MIN_SEND_INTERVAL_MS - (now - lastSendTime))
			}
			return
		}

		if(vibrate(left, right))
		{
			playingLeft = left
			playingRight = right
			lastSendTime = now
		}
	}

	private fun amplitude(value: Int) = value.coerceIn(1, 255)

	private fun oneShot(value: Int): VibrationEffect? =
		if(Build.VERSION.SDK_INT >= 26) VibrationEffect.createOneShot(EFFECT_DURATION_MS, amplitude(value)) else null

	@Suppress("DEPRECATION")
	private fun vibrate(left: Int, right: Int): Boolean
	{
		val device = findDevice()
		if(device == null)
		{
			if(loggedDevice != -1)
			{
				Log.w(TAG, "No connected gamepad with a vibrator")
				loggedDevice = -1
			}
			return false
		}
		if(playingDevice != null && playingDevice?.id != device.id)
			cancelVibration()
		if(loggedDevice != device.id)
		{
			Log.w(TAG, "Rumble on \"${device.name}\" (id ${device.id})")
			loggedDevice = device.id
		}
		try
		{
			if(Build.VERSION.SDK_INT >= 31)
			{
				val manager = device.vibratorManager
				val ids = manager.vibratorIds
				if(ids.isEmpty())
					return false
				if(ids.size >= 2)
				{
					// Motor 0 = strong / low-frequency (left), motor 1 = weak / high-frequency (right).
					// A motor left out of the combination is off.
					val combo = CombinedVibration.startParallel()
					if(left > 0)
						combo.addVibrator(ids[0], oneShot(left)!!)
					if(right > 0)
						combo.addVibrator(ids[1], oneShot(right)!!)
					manager.vibrate(combo.combine())
				}
				else
					manager.getVibrator(ids[0]).vibrate(oneShot(max(left, right))!!)
			}
			else
			{
				val vibrator: Vibrator = device.vibrator
				if(!vibrator.hasVibrator())
					return false
				val effect = oneShot(max(left, right))
				if(effect != null)
					vibrator.vibrate(effect)
				else
					vibrator.vibrate(EFFECT_DURATION_MS)
			}
			playingDevice = device
			return true
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Vibration failed on ${device.name}", e)
			return false
		}
	}

	@Suppress("DEPRECATION")
	private fun cancelVibration()
	{
		handler.removeCallbacks(refreshRunnable)
		handler.removeCallbacks(applyRunnable)
		applyPending = false
		val device = playingDevice ?: return
		playingDevice = null
		playingLeft = 0
		playingRight = 0
		try
		{
			if(Build.VERSION.SDK_INT >= 31)
				device.vibratorManager.cancel()
			else
				device.vibrator.cancel()
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Cancel vibration failed", e)
		}
	}

	@Suppress("DEPRECATION")
	private fun hasVibrator(device: InputDevice): Boolean = try
	{
		if(Build.VERSION.SDK_INT >= 31)
			device.vibratorManager.vibratorIds.isNotEmpty()
		else
			device.vibrator.hasVibrator()
	}
	catch(e: Exception) { false }

	private fun isGamepad(device: InputDevice): Boolean =
		(device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
		(device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

	private fun findDevice(): InputDevice?
	{
		val activeId = activeDeviceId()
		if(activeId >= 0)
		{
			val device = InputDevice.getDevice(activeId)
			if(device != null && hasVibrator(device))
				return device
		}
		playingDevice?.let { current ->
			val device = InputDevice.getDevice(current.id)
			if(device != null && hasVibrator(device))
				return device
		}
		for(id in InputDevice.getDeviceIds())
		{
			val device = InputDevice.getDevice(id) ?: continue
			if(device.isVirtual || !isGamepad(device))
				continue
			if(hasVibrator(device))
				return device
		}
		return null
	}
}
