package com.roadmate.app.core

import org.junit.Assert.*
import org.junit.Test

class ParkingGateTest {
    @Test fun unknownVehicleCannotAuthorizeNap() {
        val gate = ParkingGate()
        gate.simulate(true)
        assertFalse(gate.parkedConfirmed)
    }
    @Test fun demoParkCannotOverrideActualDrive() {
        val gate = ParkingGate()
        gate.updateActual(false)
        gate.simulate(true)
        assertTrue(gate.effectiveParked)
        assertFalse(gate.parkedConfirmed)
    }
    @Test fun actualParkAllowsControlAndDepartureRevokesIt() {
        val gate = ParkingGate()
        gate.updateActual(true)
        assertTrue(gate.parkedConfirmed)
        gate.updateActual(false)
        assertFalse(gate.parkedConfirmed)
    }
    @Test fun lostVehicleSignalRevokesPreviouslyConfirmedPark() {
        val gate = ParkingGate()
        gate.updateActual(true)
        gate.updateActual(null)
        assertFalse(gate.parkedConfirmed)
    }
    @Test fun demoDriveBlocksParkedControlsUntilVehicleModeResumes() {
        val gate = ParkingGate()
        gate.updateActual(true)
        gate.simulate(false)
        assertFalse(gate.parkedConfirmed)
        gate.useVehicleSignal()
        assertTrue(gate.parkedConfirmed)
    }
}
