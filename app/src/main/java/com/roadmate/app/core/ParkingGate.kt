package com.roadmate.app.core

/** Demo input may explain a scenario, but can never authorize vehicle actuators. */
class ParkingGate {
    var actualParked: Boolean? = null
        private set
    var demoParked: Boolean? = null
        private set

    val effectiveParked: Boolean get() = demoParked ?: actualParked ?: true
    val parkedConfirmed: Boolean get() = actualParked == true && effectiveParked

    fun updateActual(isPark: Boolean?) { actualParked = isPark }
    fun simulate(isPark: Boolean) { demoParked = isPark }
    fun useVehicleSignal() { demoParked = null }
}
