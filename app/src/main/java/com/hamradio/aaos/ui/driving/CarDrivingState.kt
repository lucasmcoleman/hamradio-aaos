package com.hamradio.aaos.ui.driving

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CarDrivingState"

/**
 * Whether the head unit currently requires distraction-optimized UI (vehicle moving, as decided
 * by the car's UX restrictions service). MainActivity is marked distractionOptimized, so while
 * this is true the app must show only the driving screen.
 *
 * Off-car (phone, tablet, emulator without the car service) it stays false.
 */
class CarDrivingState(private val context: Context) {

    private val _restricted = MutableStateFlow(false)
    val requiresDistractionOptimization: StateFlow<Boolean> = _restricted.asStateFlow()

    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null

    fun start() {
        if (car != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!ready) {
                    uxManager = null
                    return@createCar
                }
                val mgr = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                    ?: return@createCar
                uxManager = mgr
                _restricted.value = mgr.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
                mgr.registerListener { r -> _restricted.value = r.isRequiresDistractionOptimization }
            }
        } catch (e: Throwable) {
            // android.car is an optional library; without it, behave as a parked/non-car device.
            Log.w(TAG, "car service unavailable", e)
            car = null
        }
    }

    fun stop() {
        try { uxManager?.unregisterListener() } catch (e: Exception) { Log.w(TAG, "unregister failed", e) }
        uxManager = null
        try { car?.disconnect() } catch (e: Exception) { Log.w(TAG, "disconnect failed", e) }
        car = null
    }
}
