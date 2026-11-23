package com.roadmate.app.car

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper

/** Read the platform UX policy; never change or override it. Unknown state stays locked. */
class UxRestrictionMonitor(
    private val context: Context,
    private val onRestrictions: (CarUxRestrictions?) -> Unit
) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var released = false

    fun start() {
        onRestrictions(null)
        try {
            car = Car.createCar(context, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (released) return@createCar
                if (!ready) {
                    manager = null
                    onRestrictions(null)
                } else {
                    try {
                        manager?.unregisterListener()
                        val current = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        manager = current
                        current.registerListener { restrictions -> if (!released) onRestrictions(restrictions) }
                        onRestrictions(current.currentCarUxRestrictions)
                    } catch (_: Exception) {
                        onRestrictions(null)
                    }
                }
            }
        } catch (_: Exception) {
            onRestrictions(null)
        }
    }

    fun release() {
        released = true
        try { manager?.unregisterListener() } catch (_: Exception) { }
        manager = null
        try { car?.disconnect() } catch (_: Exception) { }
        car = null
    }
}
